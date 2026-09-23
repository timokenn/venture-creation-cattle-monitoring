import { Alert } from "./types";

export interface FcmResult {
  sent: number;
  pruned: number;
}

/**
 * Send an FCM push for a new alert to every registered app token.
 * Unregistered/invalid tokens are pruned from app_tokens. Kept as a thin
 * wrapper so tests can stub it and the trigger stays focused on logic.
 */
export interface PushMessage {
  notification?: { title: string; body: string };
  data?: Record<string, string>;
}

export async function sendAlertPush(
  message: PushMessage,
  tokenRefs: FirebaseFirestore.CollectionReference,
): Promise<FcmResult> {
  const { getMessaging } = await import("firebase-admin/messaging");
  const messaging = getMessaging();
  const snap = await tokenRefs.listDocuments();
  const results = await Promise.allSettled(
    snap.map((doc) => messaging.send({ ...message, token: doc.id })),
  );
  let sent = 0;
  let pruned = 0;
  const batch = tokenRefs.firestore.batch();
  for (let i = 0; i < results.length; i++) {
    const r = results[i];
    if (r.status === "fulfilled") {
      sent++;
    } else {
      const err = r.reason as { code?: string };
      if (
        err?.code?.startsWith("messaging/registration-token-not-registered") ||
        err?.code?.startsWith("messaging/invalid-")
      ) {
        batch.delete(tokenRefs.doc(snap[i].id));
        pruned++;
      }
    }
  }
  if (pruned > 0) await batch.commit();
  return { sent, pruned };
}

/** Build the user-facing push payload for an alert. */
export function alertPushPayload(
  cowId: string,
  cowName: string,
  alert: Alert,
): { notification: { title: string; body: string }; data: Record<string, string> } {
  const titles: Record<string, string> = {
    fever: `Fever suspected: ${cowName}`,
    possible_estrus: `Possible estrus: ${cowName}`,
    low_activity: `Low activity: ${cowName}`,
    possible_distress: `Distress: ${cowName}`,
    device_offline: `Device offline: ${cowName}`,
    sensor_issue: `Sensor issue: ${cowName}`,
  };
  return {
    notification: { title: titles[alert.type] ?? cowName, body: alert.note },
    data: { cowId, type: alert.type },
  };
}
