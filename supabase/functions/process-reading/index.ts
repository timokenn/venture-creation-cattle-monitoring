import { decide } from "../_shared/pipeline.ts";
import {
  BASELINE_ACTIVITY_EMA_ALPHA,
  BASELINE_MIN_SAMPLES,
  BASELINE_TEMP_EMA_ALPHA,
  RECENT_WINDOW_HOURS,
} from "../_shared/thresholds.ts";
import { serviceClient, webhookSecret } from "../_shared/db.ts";
import { CONFIG } from "../_shared/config.ts";
import { alertPushPayload, sendPush } from "../_shared/fcmAdmin.ts";
import { Reading } from "../_shared/types.ts";
import { UnresolvedAlertRow } from "../_shared/pipeline.ts";
import { Vector3 } from "../_shared/activity.ts";

/**
 * process-reading — the health-logic entry point.
 *
 * Wired to a Database Webhook on INSERT into public.readings, with a custom
 * x-webhook-secret header configured in the dashboard; this function rejects
 * anything that doesn't present it (anyone who finds the function URL can
 * otherwise feed us readings directly).
 *
 * Runs the pure decide() pipeline, then persists everything in ONE
 * transaction via the apply_reading_decision RPC (schema.sql): the reading's
 * computed fields, the cow's baselines/status/latest values, and alert
 * creates/resolves. Duplicate webhook deliveries no-op inside the RPC (CAS on
 * processed_at). FCM pushes happen only after the RPC commits and each push
 * is individually try/caught — a recorded-but-unpushed alert is recoverable.
 */

interface ReadingRow {
  id: string;
  cow_id: string;
  timestamp: string | null;
  temperature: number;
  accel_x: number;
  accel_y: number;
  accel_z: number;
  gyro_x: number;
  gyro_y: number;
  gyro_z: number;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  if (req.headers.get(CONFIG.webhookHeader) !== webhookSecret()) {
    return new Response("unauthorized", { status: 401 });
  }

  try {
    const body = await req.json();
    const row: ReadingRow = body.record ?? body;
    if (!row?.id || !row?.cow_id) {
      return new Response("bad payload", { status: 400 });
    }

    const supabase = serviceClient();

    const [cowRes, recentRes, unresolvedRes] = await Promise.all([
      supabase.from("cows").select("*").eq("id", row.cow_id).maybeSingle(),
      supabase
        .from("readings")
        .select(
          "id,timestamp,temperature,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,activity_index,data_quality",
        )
        .eq("cow_id", row.cow_id)
        .gte(
          "timestamp",
          new Date(
            Date.now() - RECENT_WINDOW_HOURS * 3600 * 1000,
          ).toISOString(),
        )
        .order("timestamp", { ascending: true })
        .limit(CONFIG.recentWindowLimit),
      supabase
        .from("alerts")
        .select("id,type")
        .eq("cow_id", row.cow_id)
        .eq("resolved", false),
    ]);
    if (cowRes.error) throw cowRes.error;
    if (recentRes.error) throw recentRes.error;
    if (unresolvedRes.error) throw unresolvedRes.error;

    const cow = cowRes.data;
    if (!cow) return new Response("unknown cow; nothing to do", { status: 200 });

    const now = row.timestamp ? new Date(row.timestamp) : new Date();
    const sample = {
      temperature: Number(row.temperature),
      accel: {
        x: Number(row.accel_x),
        y: Number(row.accel_y),
        z: Number(row.accel_z),
      } as Vector3,
      gyro: {
        x: Number(row.gyro_x),
        y: Number(row.gyro_y),
        z: Number(row.gyro_z),
      } as Vector3,
    };

    // Recent PRIOR readings for windowed classification (exclude this row —
    // decide() appends the current sample itself).
    const recent: Reading[] = (recentRes.data ?? [])
      .filter((r) => r.id !== row.id)
      .map((r) => ({
        timestamp: new Date(r.timestamp),
        temperature: Number(r.temperature),
        accel: { x: r.accel_x, y: r.accel_y, z: r.accel_z },
        gyro: { x: r.gyro_x, y: r.gyro_y, z: r.gyro_z },
        activity_index: r.activity_index === null ? undefined : Number(r.activity_index),
        data_quality: r.data_quality ?? undefined,
      }));

    const unresolved: UnresolvedAlertRow[] = (unresolvedRes.data ?? []).map(
      (a) => ({ id: a.id, type: a.type }),
    );

    const decision = decide(
      sample,
      {
        baseline_temp: cow.baseline_temp === null ? null : Number(cow.baseline_temp),
        baseline_activity:
          cow.baseline_activity === null ? null : Number(cow.baseline_activity),
        baseline_samples: Number(cow.baseline_samples ?? 0),
      },
      recent,
      unresolved,
      { now },
    );

    const { error: rpcError } = await supabase.rpc("apply_reading_decision", {
      p_reading_id: row.id,
      p_cow_id: row.cow_id,
      p_reading_ts: now.toISOString(),
      p_temperature: sample.temperature,
      p_activity_index: decision.activityIndex,
      p_valid: decision.reading.valid ?? false,
      p_data_quality: decision.reading.data_quality ?? "ok",
      p_status_after: decision.statusAfter,
      p_alpha_temp: BASELINE_TEMP_EMA_ALPHA,
      p_alpha_activity: BASELINE_ACTIVITY_EMA_ALPHA,
      p_min_samples: BASELINE_MIN_SAMPLES,
      p_creates: JSON.stringify(
        decision.ops
          .filter((o) => o.op === "create")
          .map((o) => ({ type: o.type, note: o.note, timestamp: o.timestamp.toISOString() })),
      ),
      p_resolves: JSON.stringify(
        decision.ops.filter((o) => o.op === "resolve").map((o) => o.id),
      ),
    });
    if (rpcError) throw rpcError;

    // Pushes only for NEWLY created alerts, after the commit. Never let a
    // push failure discard the recorded alert.
    const created = decision.createdAlerts;
    if (created.length > 0) {
      try {
        const tokenRes = await supabase.from("fcm_tokens").select("token");
        const tokens = (tokenRes.data ?? []).map((t) => t.token);
        for (const alert of created) {
          const message = alertPushPayload(row.cow_id, cow.name, alert);
          await sendPush(message, tokens, async (token) => {
            await supabase.from("fcm_tokens").delete().eq("token", token);
          });
        }
      } catch (pushErr) {
        console.error("push failed (alert kept):", pushErr);
      }
    }

    return new Response(
      JSON.stringify({
        ok: true,
        classification: decision.classification,
        created: created.map((a) => a.type),
      }),
      { headers: { "Content-type": "application/json" } },
    );
  } catch (err) {
    console.error("process-reading failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
