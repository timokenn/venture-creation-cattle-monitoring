import { serviceClient } from "../_shared/db.ts";
import { requireOwner } from "../_shared/owner.ts";

/**
 * delete-cow — removes the cow row; readings and alerts cascade via FK.
 * Ownership enforced: only the cow's owner may delete it (401/404 otherwise).
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    const ownerId = await requireOwner(req);
    if (!ownerId) return new Response("sign in required", { status: 401 });

    const body = await req.json();
    const id = typeof body?.id === "string" ? body.id : "";
    if (!id) return new Response("id is required", { status: 400 });

    const { data, error } = await serviceClient()
      .from("cows")
      .delete()
      .eq("id", id)
      .eq("owner_id", ownerId)
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
