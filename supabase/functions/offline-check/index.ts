import { serviceClient } from "../_shared/db.ts";
import {
  EXPECTED_SEND_INTERVAL_SECONDS,
  OFFLINE_FACTOR,
} from "../_shared/thresholds.ts";
import { sendPush } from "../_shared/fcmAdmin.ts";
import { CONFIG } from "../_shared/config.ts";

/**
 * offline-check — invoked every 15 minutes by pg_cron (see cron.sql).
 * Flags cows whose last_seen is older than OFFLINE_FACTOR × the expected
 * send interval, and repairs the flag when they report again. The actual
 * writes go through the idempotent apply_offline_transition RPC.
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }

  try {
    const supabase = serviceClient();
    const cutoffMs = OFFLINE_FACTOR * EXPECTED_SEND_INTERVAL_SECONDS * 1000;

    const { data: cows, error } = await supabase
      .from("cows")
      .select("id,name,last_seen,current_status");
    if (error) throw error;

    let flagged = 0;
    let repaired = 0;

    for (const cow of cows ?? []) {
      const lastSeen = cow.last_seen ? new Date(cow.last_seen).getTime() : null;
      const isStale = lastSeen === null || Date.now() - lastSeen > cutoffMs;
      const currentlyOffline = cow.current_status === "offline";
      if (isStale === currentlyOffline) continue; // already correct

      // Will this transition create a NEW alert? (needed to know whether to push)
      let willCreate = false;
      if (isStale) {
        const open = await supabase
          .from("alerts")
          .select("id")
          .eq("cow_id", cow.id)
          .eq("type", "device_offline")
          .eq("resolved", false)
          .limit(1);
        willCreate = (open.data ?? []).length === 0;
      }

      const { error: rpcError } = await supabase.rpc("apply_offline_transition", {
        p_cow_id: cow.id,
        p_is_stale: isStale,
        p_now: new Date().toISOString(),
      });
      if (rpcError) throw rpcError;

      if (isStale) flagged++;
      else repaired++;

      if (isStale && willCreate) {
        try {
          const tokenRes = await supabase.from("fcm_tokens").select("token");
          const tokens = (tokenRes.data ?? []).map((t) => t.token);
          await sendPush(
            {
              notification: {
                title: `Device offline: ${cow.name}`,
                body: CONFIG.offlineAlertNote,
              },
              data: { cowId: cow.id, type: "device_offline" },
            },
            tokens,
            async (token) => {
              await supabase.from("fcm_tokens").delete().eq("token", token);
            },
          );
        } catch (pushErr) {
          console.error("offline push failed (alert kept):", pushErr);
        }
      }
    }

    return new Response(JSON.stringify({ ok: true, flagged, repaired }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("offline-check failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
