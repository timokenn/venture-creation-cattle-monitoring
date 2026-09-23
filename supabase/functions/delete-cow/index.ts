import { serviceClient } from "../_shared/db.ts";

/**
 * delete-cow — removes the cow row; readings and alerts cascade via FK.
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const body = await req.json();
    const id = typeof body?.id === "string" ? body.id : "";
    if (!id) return new Response("id is required", { status: 400 });

    const { data, error } = await serviceClient()
      .from("cows")
      .delete()
      .eq("id", id)
      .select("id");
    if (error) throw error;
    if (!data || data.length === 0) {
      return new Response("cow not found", { status: 404 });
    }
    return new Response(JSON.stringify({ ok: true }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("delete-cow failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
