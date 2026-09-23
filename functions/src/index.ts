import { getApps, initializeApp } from "firebase-admin/app";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { setGlobalOptions } from "firebase-functions/v2";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  EXPECTED_SEND_INTERVAL_SECONDS,
  OFFLINE_CHECK_INTERVAL_MINUTES,
  OFFLINE_FACTOR,
  RECENT_WINDOW_HOURS,
} from "./thresholds";
import { decide } from "./pipeline";
import { alertPushPayload, sendAlertPush } from "./fcm";
import { AlertType, CowStatus, Reading } from "./types";

if (getApps().length === 0) {
  initializeApp();
}

setGlobalOptions({ region: "us-central1" });

const db = () => getFirestore();

/** Fetch this cow's recent valid readings for windowed classification. */
async function fetchRecentReadings(
  cowId: string,
): Promise<Reading[]> {
  const cutoff = Timestamp.fromMillis(
    Date.now() - RECENT_WINDOW_HOURS * 60 * 60 * 1000,
  );
  const snap = await db()
    .collection(`cows/${cowId}/readings`)
    .where("timestamp", ">", cutoff)
    .orderBy("timestamp", "asc")
    .limit(2000)
    .get();
  return snap.docs.map((d) => {
    const data = d.data();
    return {
      id: d.id,
      timestamp: (data.timestamp as Timestamp).toDate(),
      temperature: data.temperature as number,
      accel: data.accel as { x: number; y: number; z: number },
      gyro: data.gyro as { x: number; y: number; z: number },
      activity_index: data.activity_index as number | undefined,
      data_quality: data.data_quality as Reading["data_quality"],
      valid: data.valid as boolean | undefined,
    } satisfies Reading;
  });
}

async function fetchUnresolved(cowId: string) {
  const snap = await db()
    .collection(`cows/${cowId}/alerts`)
    .where("resolved", "==", false)
    .get();
  return snap.docs.map((d) => ({
    id: d.id,
    type: d.data().type as AlertType,
  }));
}

export const onReadingCreated = onDocumentCreated(
  "cows/{cowId}/readings/{readingId}",
  async (event) => {
    const cowId = event.params.cowId;
    const snapshot = event.data;
    if (!snapshot) return;

    const data = snapshot.data();
    const sample = {
      temperature: data.temperature as number,
      accel: data.accel as { x: number; y: number; z: number },
      gyro: data.gyro as { x: number; y: number; z: number },
    };
    const now = (data.timestamp as Timestamp | undefined)?.toDate() ?? new Date();

    const [cowSnap, recent, unresolved] = await Promise.all([
      db().collection("cows").doc(cowId).get(),
      fetchRecentReadings(cowId),
      fetchUnresolved(cowId),
    ]);
    if (!cowSnap.exists) {
      // Reading for an unregistered device — nothing to update.
      return;
    }
    const cow = cowSnap.data()!;
    const cowName = (cow.name as string) ?? cowId;

    const decision = decide(
      sample,
      {
        baseline_temp: (cow.baseline_temp as number | null) ?? null,
        baseline_activity: (cow.baseline_activity as number | null) ?? null,
        baseline_samples: (cow.baseline_samples as number | null) ?? 0,
      },
      recent,
      unresolved,
      { now },
    );

    const batch = db().batch();

    // Enrich the raw reading with computed fields.
    batch.update(snapshot.ref, {
      activity_index: decision.activityIndex,
      data_quality: decision.reading.data_quality,
      valid: decision.reading.valid,
    });

    // Cow doc: rolling baselines, status, latest values, last_seen (reading
    // time, not write time). Glitch temps never overwrite the shown value.
    batch.update(db().collection("cows").doc(cowId), {
      baseline_temp: decision.baseline_temp,
      baseline_activity: decision.baseline_activity,
      baseline_samples: decision.baseline_samples,
      current_status: decision.statusAfter,
      ...(decision.reading.valid ? { latest_temp: sample.temperature } : {}),
      latest_activity: decision.activityIndex,
      last_seen: Timestamp.fromDate(now),
    });

    // Alert creates / resolves from the plan.
    for (const op of decision.ops) {
      if (op.op === "create") {
        batch.create(db().collection(`cows/${cowId}/alerts`).doc(), {
          type: op.type,
          timestamp: Timestamp.fromDate(op.timestamp),
          resolved: false,
          note: op.note,
        });
      } else {
        batch.update(db().collection(`cows/${cowId}/alerts`).doc(op.id), {
          resolved: true,
        });
      }
    }

    await batch.commit();

    // Push only for newly created alerts.
    for (const created of decision.createdAlerts) {
      await sendAlertPush(
        alertPushPayload(cowId, cowName, {
          type: created.type,
          timestamp: created.timestamp,
          resolved: false,
          note: created.note,
        }),
        db().collection("app_tokens"),
      );
    }
  },
);

export const scheduledOfflineCheck = onSchedule(
  {
    schedule: `every ${OFFLINE_CHECK_INTERVAL_MINUTES} minutes`,
    timeoutSeconds: 300,
  },
  async () => {
    const cows = await db().collection("cows").get();
    const offlineAfterMs =
      OFFLINE_FACTOR * EXPECTED_SEND_INTERVAL_SECONDS * 1000;

    for (const cow of cows.docs) {
      const lastSeen = cow.data().last_seen as Timestamp | null;
      const status = (cow.data().current_status as CowStatus) ?? "normal";
      const isStale =
        !lastSeen || Date.now() - lastSeen.toMillis() > offlineAfterMs;
      if (isStale === (status === "offline")) continue; // already correct

      const alerts = db().collection(`cows/${cow.id}/alerts`);

      if (isStale) {
        const open = await alerts
          .where("type", "==", "device_offline")
          .where("resolved", "==", false)
          .limit(1)
          .get();
        const batch = db().batch();
        batch.update(cow.ref, { current_status: "offline" });
        if (open.empty) {
          batch.create(alerts.doc(), {
            type: "device_offline",
            timestamp: Timestamp.now(),
            resolved: false,
            note: "No readings received — device may be powered off or out of range",
          });
        }
        await batch.commit();
      } else {
        const open = await alerts
          .where("type", "==", "device_offline")
          .where("resolved", "==", false)
          .get();
        const batch = db().batch();
        batch.update(cow.ref, { current_status: "normal" });
        open.docs.forEach((d) => batch.update(d.ref, { resolved: true }));
        await batch.commit();
      }
    }
  },
);

/** Register a cow with its ESP32 device id (used by the app's Add Device screen). */
export const addCow = onCall(async (request) => {
  const name = (request.data?.name as string | undefined)?.trim();
  const deviceId = (request.data?.device_id as string | undefined)?.trim();
  if (!name || !deviceId) {
    throw new HttpsError("invalid-argument", "name and device_id are required");
  }
  const dupe = await db()
    .collection("cows")
    .where("device_id", "==", deviceId)
    .limit(1)
    .get();
  if (!dupe.empty) {
    throw new HttpsError("already-exists", "device_id already registered");
  }
  const ref = db().collection("cows").doc();
  await ref.set({
    name,
    device_id: deviceId,
    baseline_temp: null,
    baseline_activity: null,
    baseline_samples: 0,
    current_status: "normal",
    last_seen: null,
  });
  return { id: ref.id };
});

/** Update cow name / device_id. */
export const updateCow = onCall(async (request) => {
  const id = request.data?.id as string | undefined;
  if (!id) throw new HttpsError("invalid-argument", "id is required");
  const patch: Record<string, unknown> = {};
  if (typeof request.data?.name === "string" && request.data.name.trim()) {
    patch.name = request.data.name.trim();
  }
  if (typeof request.data?.device_id === "string" && request.data.device_id.trim()) {
    patch.device_id = request.data.device_id.trim();
  }
  if (Object.keys(patch).length === 0) {
    throw new HttpsError("invalid-argument", "nothing to update");
  }
  await db().collection("cows").doc(id).update(patch);
  return { ok: true };
});

/** Register/sync an FCM token for this install. */
export const registerToken = onCall(async (request) => {
  const token = request.data?.token as string | undefined;
  if (!token) throw new HttpsError("invalid-argument", "token is required");
  await db().collection("app_tokens").doc(token).set(
    { updated_at: FieldValue.serverTimestamp() },
    { merge: true },
  );
  return { ok: true };
});

/** Remove a cow and its subcollections (management UI convenience). */
export const deleteCow = onCall(async (request) => {
  const id = request.data?.id as string | undefined;
  if (!id) throw new HttpsError("invalid-argument", "id is required");
  const cowRef = db().collection("cows").doc(id);
  for (const sub of ["readings", "alerts"]) {
    let cursor: FirebaseFirestore.QuerySnapshot | null = null;
    do {
      cursor = await cowRef.collection(sub).limit(300).get();
      const batch = db().batch();
      cursor.docs.forEach((d) => batch.delete(d.ref));
      await batch.commit();
    } while (!cursor.empty);
  }
  await cowRef.delete();
  return { ok: true };
});
