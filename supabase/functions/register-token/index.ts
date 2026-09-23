import { serviceClient } from "../_shared/db.ts";

/**
 * register-token — FCM token registration from the app. The anon key can't
 * UPDATE fcm_tokens (RLS is insert-only), so re-registration upserts via the
 * service role instead of fighting a 409 on conflict.
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const body = await req.json();
    const token = typeof body?.token === "string" ? body.token.trim() : "";
    if (!token) return new Response("token is required", { status: 400 });

    const { error } = await serviceClient()
      .from("fcm_tokens")
      .upsert({ token, updated_at: new Date().toISOString() });
    if (error) throw error;
    return new Response(JSON.stringify({ ok: true }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("register-token failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
