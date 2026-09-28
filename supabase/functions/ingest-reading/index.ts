import { serviceClient } from "../_shared/db.ts";

/**
 * ingest-reading — hardened device ingest (replaces the open anon INSERT on
 * public.readings, which is revoked by migration 20260928150000).
 *
 * Contract for a collar:
 *   POST /functions/v1/ingest-reading
 *   headers: x-collar-key: <COLLAR_INGEST_KEY>   (shared secret, in firmware)
 *   body: { device_id, temperature, accel:{x,y,z}, gyro:{x,y,z} }
 *
 * The device_id is stamped server-side from the body (never trusted from the
 * client for ownership — the key only proves "this is one of our collars",
 * the body says which one). A per-device token bucket absorbs runaway /
 * malicious floods (600-second refill) before anything touches the DB.
 *
 * Secrets: set once with
 *   supabase secrets set COLLAR_INGEST_KEY=...
 */
Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }
  try {
    // --- 1. Shared collar key -------------------------------------------------
    const expected = Deno.env.get("COLLAR_INGEST_KEY");
    if (!expected || req.headers.get("x-collar-key") !== expected) {
      return new Response("unauthorized", { status: 401 });
    }

    const body = await req.json();
    const deviceId = typeof body?.device_id === "string" ? body.device_id.trim() : "";
    const temperature = typeof body?.temperature === "number" ? body.temperature : NaN;

    if (!deviceId || !Number.isFinite(temperature)) {
      return new Response("device_id and temperature are required", { status: 400 });
    }

    // --- 2. Per-device rate limit (token bucket, 600s refill) ----------------
    // Cache ~10k devices; free-tier single-instance functions only.
    const cache = (globalThis as unknown as {
      __collarBuckets?: Map<string, { tokens: number; last: number }>;
    });
    cache.__collarBuckets ??= new Map();
    const buckets = cache.__collarBuckets;
    const now = Date.now();
    const REFILL_PER_SEC = 1 / 600;
    const CAPACITY = 5;
    let bucket = buckets.get(deviceId);
    if (!bucket) {
      bucket = { tokens: CAPACITY, last: now };
      buckets.set(deviceId, bucket);
      if (buckets.size > 10_000) buckets.clear();
    }
    const elapsed = (now - bucket.last) / 1000;
    bucket.tokens = Math.min(CAPACITY, bucket.tokens + elapsed * REFILL_PER_SEC);
    bucket.last = now;
    if (bucket.tokens < 1) {
      return new Response("rate limited", { status: 429 });
    }
    bucket.tokens -= 1;

    // --- 3. Resolve the cow (device_id -> owner-checked row, service role) ---
    const supabase = serviceClient();
    const { data: cow, error: cowErr } = await supabase
      .from("cows")
      .select("id")
      .eq("device_id", deviceId)
      .single();
    if (cowErr || !cow) {
      return new Response("unknown device_id", { status: 404 });
    }

    // --- 4. Insert the reading (service role; webhook fires process-reading) --
    const accel = body?.accel ?? {};
    const gyro = body?.gyro ?? {};
    const { error: insertErr } = await supabase.from("readings").insert({
      cow_id: cow.id,
      temperature,
      accel_x: Number(accel.x) || 0,
      accel_y: Number(accel.y) || 0,
      accel_z: Number(accel.z) || 0,
      gyro_x: Number(gyro.x) || 0,
      gyro_y: Number(gyro.y) || 0,
      gyro_z: Number(gyro.z) || 0,
      ...(typeof body?.battery_level === "number"
        ? { battery_level: Math.min(100, Math.max(0, body.battery_level)) }
        : {}),
    });
    if (insertErr) throw insertErr;

    return new Response(JSON.stringify({ ok: true }), {
      headers: { "Content-type": "application/json" },
    });
  } catch (err) {
    console.error("ingest-reading failed:", err);
    return new Response(String(err?.message ?? err), { status: 500 });
  }
});
