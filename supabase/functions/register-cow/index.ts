import { serviceClient } from "../_shared/db.ts";

/**
 * register-cow — replaces the old addCow callable. Service-role insert;
 * device_id uniqueness is enforced by the DB and a 23505 violation is
 * translated into a clean 409 (no racy pre-checks).
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const body = await req.json();
    const name = typeof body?.name === "string" ? body.name.trim() : "";
    const deviceId = typeof body?.device_id === "string" ? body.device_id.trim() : "";
    if (!name || !deviceId) {
      return new Response("name and device_id are required", { status: 400 });
    }

    const { data, error } = await serviceClient()
      .from("cows")
      .insert({ name, device_id: deviceId })
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
