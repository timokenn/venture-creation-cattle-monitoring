import { Alert, AlertType } from "./types.ts";
import { CONFIG } from "./config.ts";
import { getAccessToken, googleProjectId } from "./googleAuth.ts";

/**
 * FCM HTTP v1 without any SDK: OAuth token from the shared Google auth
 * module, messages POSTed to the FCM endpoint. See googleAuth.ts for the
 * required secrets (SERVICE_ACCOUNT_JSON; FCM_PROJECT_ID optional).
 */

export interface PushMessage {
  notification?: { title: string; body: string };
  data?: Record<string, string>;
}

/**
 * Send one message per token. UNREGISTERED/invalid tokens are deleted from
 * fcm_tokens (pruning the dead ones as we go). Throws only on auth failure —
 * per-token errors are absorbed so one bad token can't block the rest.
 */
export async function sendPush(
  message: PushMessage,
  tokens: string[],
  deleteToken: (token: string) => Promise<void>,
): Promise<{ sent: number; pruned: number }> {
  let sent = 0;
  let pruned = 0;
  if (tokens.length === 0) return { sent, pruned };
  const accessToken = await getAccessToken(CONFIG.fcmScope);

  await Promise.allSettled(
    tokens.map(async (token) => {
      const res = await fetch(CONFIG.fcmSendEndpoint(googleProjectId()), {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-type": "application/json",
        },
        body: JSON.stringify({ message: { ...message, token } }),
      });
      if (res.ok) {
        sent++;
        return;
      }
      const err = (await res.json().catch(() => null)) as
        | { error?: { message?: string } }
        | null;
      const msg = err?.error?.message ?? "";
      if (
        res.status === 404 ||
        /registration-token-not-registered|UNREGISTERED|invalid/i.test(msg)
      ) {
        await deleteToken(token);
        pruned++;
      }
    }),
  );
  return { sent, pruned };
}

/** Build the user-facing push payload for an alert. */
export function alertPushPayload(
  cowId: string,
  cowName: string,
  alert: { type: AlertType; timestamp: Date; resolved: boolean; note: string } & Partial<Alert>,
): PushMessage {
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
