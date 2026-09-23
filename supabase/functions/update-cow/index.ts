import { serviceClient } from "../_shared/db.ts";

/**
 * update-cow — name / device_id edits from the Manage Device screen.
 * device_id conflicts surface as 409 (SQLSTATE 23505); unknown ids as 404.
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const body = await req.json();
    const id = typeof body?.id === "string" ? body.id : "";
    if (!id) return new Response("id is required", { status: 400 });

    const patch: Record<string, string> = {};
    if (typeof body?.name === "string" && body.name.trim()) patch.name = body.name.trim();
    if (typeof body?.device_id === "string" && body.device_id.trim()) {
      patch.device_id = body.device_id.trim();
    }
    if (Object.keys(patch).length === 0) {
      return new Response("nothing to update", { status: 400 });
    }

    const { data, error } = await serviceClient()
      .from("cows")
      .update(patch)
      .eq("id", id)
      .select("id")
      .single();

    if (error) {
      if (error.code === "23505") {
        return new Response("device_id already registered", { status: 409 });
      }
      if (error.code === "PGRST116") {
        return new Response("cow not found", { status: 404 });
      }
      throw error;
    }
    return new Response(JSON.stringify({ ok: true, id: data.id }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("update-cow failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
