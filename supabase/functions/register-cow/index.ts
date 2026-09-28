import { serviceClient } from "../_shared/db.ts";

/**
 * register-cow — replaces the old addCow callable. Service-role insert;
 * device_id uniqueness is enforced by the DB and a 23505 violation is
 * translated into a clean 409 (no racy pre-checks).
 *
 * The cow is owned by the caller: owner_id is read from the request's JWT
 * (auth.uid()). The app always sends its Supabase Auth session, so every cow
 * is tied to exactly one user account (multi-tenant). Requests without a
 * valid session are rejected with 401.
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();

    let ownerId: string | null = null;
    if (token) {
      const { data, error } = await serviceClient().auth.getUser(token);
      if (!error) ownerId = data.user?.id ?? null;
    }
    if (!ownerId) {
      return new Response("sign in to register a cow", { status: 401 });
    }

    const body = await req.json();
    const name = typeof body?.name === "string" ? body.name.trim() : "";
    const deviceId = typeof body?.device_id === "string" ? body.device_id.trim() : "";
    if (!name || !deviceId) {
      return new Response("name and device_id are required", { status: 400 });
    }

    const { data, error } = await serviceClient()
      .from("cows")
      .insert({ name, device_id: deviceId, owner_id: ownerId })
      .select("id")
      .single();

    if (error) {
      if (error.code === "23505") {
        return new Response("device_id already registered", { status: 409 });
      }
      throw error;
    }
    return new Response(JSON.stringify({ id: data.id }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("register-cow failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
