import { serviceClient } from "../_shared/db.ts";
import { sendPush } from "../_shared/fcmAdmin.ts";

/**
 * fcm-test — manual smoke test for the push pipeline (Phase 2 requirement:
 * verify token minting + HTTP v1 send in isolation). Invoke once after
 * setting the SERVICE_ACCOUNT_JSON / FCM_PROJECT_ID secrets, with the app
 * installed on a phone (so fcm_tokens has at least one token):
 *
 *   curl -X POST "$SUPABASE_URL/functions/v1/fcm-test" \
 *     -H "Authorization: Bearer $SUPABASE_ANON_KEY"
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const supabase = serviceClient();
    const { data, error } = await supabase.from("fcm_tokens").select("token");
    if (error) throw error;
    const tokens = (data ?? []).map((t) => t.token);
    if (tokens.length === 0) {
      return new Response("no tokens registered — install the app first", { status: 200 });
    }
    const result = await sendPush(
      {
        notification: { title: "Cattle Monitor", body: "FCM smoke test" },
        data: { cowId: "test", type: "sensor_issue" },
      },
      tokens,
      async (token) => {
        await supabase.from("fcm_tokens").delete().eq("token", token);
      },
    );
    return new Response(JSON.stringify({ ok: true, tokens: tokens.length, ...result }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("fcm-test failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
