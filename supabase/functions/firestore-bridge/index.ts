import { serviceClient } from "../_shared/db.ts";
import { getAccessToken, googleProjectId } from "../_shared/googleAuth.ts";
import { CONFIG } from "../_shared/config.ts";

/**
 * firestore-bridge — OPTIONAL ingestion path for devices that send readings
 * to Firestore via the Firebase Arduino library (Firebase-ESP-Client).
 *
 * A pg_cron job invokes this function every minute; it:
 *   1. pulls up to CONFIG.bridgeBatchLimit docs from a flat Firestore
 *      collection (no filter, no cursor — device clocks are NOT trusted,
 *      collars have no RTC and NTP may fail),
 *   2. inserts them into `readings` (duplicates impossible: the
 *      readings_source_ref unique index + on_conflict do nothing),
 *   3. deletes the successfully-ingested Firestore docs, so the collection
 *      only ever holds unprocessed backlog.
 *
 * The device payload is exactly what the pipeline needs — nothing else:
 *   collection: device_readings
 *   { device_id: "esp32-bella", timestamp: "2026-09-23T10:00:00Z",
 *     temperature: 38.2, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z }
 *
 * If the bridge is down for a while, the backlog is chewed through in
 * batches each minute; catch-up rate (~18k docs/h) far exceeds a full
 * herd's production rate. Overlapping cron runs are safe (dedup + deletes).
 */

interface BridgeDoc {
  name: string; // projects/{p}/databases/(default)/documents/device_readings/{docId}
  fields?: Record<string, { stringValue?: string; doubleValue?: number; integerValue?: number }>;
}

type FieldValue = NonNullable<BridgeDoc["fields"]>[string];

function fieldString(f: Record<string, FieldValue> | undefined, key: string): string | null {
  const v = f?.[key];
  if (!v) return null;
  return v.stringValue ?? (v.integerValue !== undefined ? String(v.integerValue) : null);
}

function fieldNumber(f: Record<string, FieldValue> | undefined, key: string): number | null {
  const v = f?.[key];
  if (!v) return null;
  if (v.doubleValue !== undefined) return v.doubleValue;
  if (v.integerValue !== undefined) return Number(v.integerValue);
  return null;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const supabase = serviceClient();
    const token = await getAccessToken(CONFIG.firestoreScope);
    const projectId = googleProjectId();
    const base = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)`;

    // ---- pull a batch (unfiltered; the collection holds only backlog) ------
    const runQueryRes = await fetch(`${base}/documents:runQuery`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-type": "application/json" },
      body: JSON.stringify({
        structuredQuery: {
          from: [{ collectionId: CONFIG.firestoreCollection }],
          limit: CONFIG.bridgeBatchLimit,
        },
      }),
    });
    if (!runQueryRes.ok) {
      throw new Error(`Firestore runQuery failed: ${runQueryRes.status} ${await runQueryRes.text()}`);
    }
    const entries = (await runQueryRes.json()) as Array<{ document?: BridgeDoc }>;
    const docs = entries.map((e) => e.document).filter((d): d is BridgeDoc => !!d);

    // ---- map + insert into readings ----------------------------------------
    let inserted = 0;
    let skipped = 0;
    const processedDocNames: string[] = [];
    const cowCache = new Map<string, string | null>();

    for (const doc of docs) {
      const f = doc.fields ?? {};
      const deviceId = fieldString(f, "device_id");
      const temperature = fieldNumber(f, "temperature");
      if (!deviceId || temperature === null) {
        skipped++;
        processedDocNames.push(doc.name); // malformed: delete so it can't jam the queue
        continue;
      }

      if (!cowCache.has(deviceId)) {
        const cowRes = await supabase
          .from("cows")
          .select("id")
          .eq("device_id", deviceId)
          .maybeSingle();
        if (cowRes.error) throw cowRes.error;
        cowCache.set(deviceId, cowRes.data?.id ?? null);
      }
      const cowId = cowCache.get(deviceId) ?? null;
      if (!cowId) {
        skipped++; // unknown device — delete so it can't accumulate forever
        processedDocNames.push(doc.name);
        continue;
      }

      const insRes = await supabase
        .from("readings")
        .upsert(
          {
            cow_id: cowId,
            timestamp: fieldString(f, "timestamp") ?? new Date().toISOString(),
            temperature,
            accel_x: fieldNumber(f, "accel_x") ?? 0,
            accel_y: fieldNumber(f, "accel_y") ?? 0,
            accel_z: fieldNumber(f, "accel_z") ?? 1,
            gyro_x: fieldNumber(f, "gyro_x") ?? 0,
            gyro_y: fieldNumber(f, "gyro_y") ?? 0,
            gyro_z: fieldNumber(f, "gyro_z") ?? 0,
            source_ref: `fs:${doc.name.split("/").pop()}`,
          },
          { onConflict: "source_ref", ignoreDuplicates: true },
        )
        .select("id");
      if (insRes.error) throw insRes.error;
      inserted += insRes.data?.length ?? 0;
      processedDocNames.push(doc.name);
    }

    // ---- delete ingested docs from Firestore (batched, ≤300 writes) --------
    let deleted = 0;
    if (processedDocNames.length > 0) {
      const batchRes = await fetch(`${base}/documents:batchWrite`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-type": "application/json" },
        body: JSON.stringify({
          writes: processedDocNames.map((name) => ({ delete: name })),
        }),
      });
      if (!batchRes.ok) {
        // Deletes failing is safe: next run re-pulls, dedup absorbs re-inserts.
        console.error(`Firestore batchWrite (delete) failed: ${await batchRes.text()}`);
      } else {
        const writeResults = (await batchRes.json()) as Array<{
          status?: { code?: number };
        }>;
        deleted = writeResults.filter((w) => !w.status || w.status.code === 0).length;
      }
    }

    // ---- observability ------------------------------------------------------
    await supabase.from("bridge_state").upsert({
      id: "firestore",
      cursor: new Date().toISOString(),
      last_doc_id: `${docs.length} docs, ${deleted} deleted`,
      updated_at: new Date().toISOString(),
    });

    return new Response(
      JSON.stringify({ ok: true, pulled: docs.length, inserted, skipped, deleted }),
      { headers: { "Content-type": "application/json" } },
    );
  } catch (err) {
    console.error("firestore-bridge failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
