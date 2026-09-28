-- ============================================================================
-- Hardening + downsampling.
--
-- 1. Revoke the open anon INSERT on readings. Devices now ingest through the
--    ingest-reading Edge Function (x-collar-key + per-device rate limit) using
--    the service role, so the public write hole is closed. Keep in sync with
--    schema.sql.
-- 2. readings_downsample(): time-bucket aggregation for the 7-day chart. At a
--    20s cadence a week is ~30k rows; the app caps raw fetches at 5,000, so
--    the 7d view silently truncated. SECURITY INVOKER: RLS (per-owner) still
--    applies to the caller.
-- ============================================================================

drop policy if exists "readings_insert" on public.readings;

create or replace function public.readings_downsample(
  p_cow_id uuid,
  p_since timestamptz,
  p_bucket_seconds integer default 600
)
returns table (
  bucket_start      timestamptz,
  temp_avg          double precision,
  temp_min          double precision,
  temp_max          double precision,
  activity_avg      double precision,
  suspicious_share  double precision,
  samples           bigint
)
language sql
stable
security invoker
set search_path = public
as $$
  select
    to_timestamp(floor(extract(epoch from r.timestamp) / p_bucket_seconds) * p_bucket_seconds),
    avg(r.temperature),
    min(r.temperature),
    max(r.temperature),
    avg(r.activity_index),
    avg(case when r.data_quality = 'temp_out_of_range' then 1.0 else 0.0 end),
    count(*)
  from public.readings r
  where r.cow_id = p_cow_id
    and r.timestamp >= p_since
  group by 1
  order by 1;
$$;

revoke all on function public.readings_downsample(uuid, timestamptz, integer) from public, anon;
grant execute on function public.readings_downsample(uuid, timestamptz, integer) to authenticated;
