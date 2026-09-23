/**
 * Supabase Edge Function configuration.
 *
 * Secrets (SERVICE_ACCOUNT_JSON, FCM_PROJECT_ID, SERVICE_ROLE_KEY,
 * WEBHOOK_SECRET) are set with:  supabase secrets set NAME=value
 * The WEBHOOK_SECRET value must also be pasted into the Database Webhook's
 * custom header (see README "Wiring the webhook").
 */
export const CONFIG = {
  /** Custom header the Database Webhook must present; enforced by process-reading. */
  webhookHeader: "x-webhook-secret",

  fcmOAuthAudience: "https://oauth2.googleapis.com/token",
  fcmScope: "https://www.googleapis.com/auth/firebase.messaging",
  fcmSendEndpoint: (projectId: string) =>
    `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,

  /** Firestore bridge (optional path for devices using the Firebase Arduino lib). */
  firestoreScope: "https://www.googleapis.com/auth/datastore",
  firestoreCollection: "device_readings",
  bridgeBatchLimit: 300,
  /** Re-pull window behind the cursor; duplicates are absorbed by the
   *  readings.source_ref unique index, so overlap only costs nothing. */
  bridgeOverlapMs: 30_000,

  /** Max recent readings fetched for windowed classification. */
  recentWindowLimit: 2000,

  offlineAlertNote:
    "No readings received — device may be powered off or out of range",
};
