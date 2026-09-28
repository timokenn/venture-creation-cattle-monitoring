-- Behavior change: out-of-cow-range temperatures (e.g. room-temp bench data)
-- are now STORED with valid=true plus data_quality='temp_out_of_range' so the
-- app can display them annotated as suspicious, while baselines/sample-count
-- remain gated on the quality flag (implausible data must never teach the
-- health logic). Mirrors _shared/pipeline.ts (agreement-tested). Also drops
-- the readings temperature CHECK: the pipeline owns plausibility now, and the
-- dashboard-injected rows already bypass table CHECKs (service role).
alter table public.readings drop constraint if exists readings_temperature_check;
create or replace function public.apply_reading_decision(
  p_reading_id        uuid,
create or replace function public.apply_reading_decision(
  p_reading_id        uuid,
  p_cow_id            uuid,
  p_reading_ts        timestamptz,
  p_temperature       double precision,
  p_activity_index    double precision,
  p_valid             boolean,
  p_data_quality      text,
  p_status_after      text,
  p_alpha_temp        double precision,
  p_alpha_activity    double precision,
  p_min_samples       integer,
  p_creates           jsonb,   -- [{type, note, timestamp}]
  p_resolves          jsonb    -- [alert_id, ...]
) returns void
language plpgsql
as $$
declare
  v_claimed boolean;
  v_bt numeric;
  v_ba numeric;
  v_samples integer;
begin
  -- Idempotency guard: claim the reading with ONE statement. Postgres row
  -- locking during this UPDATE serializes concurrent duplicate webhook
  -- deliveries; if no row comes back, someone else already processed it.
  -- Do NOT "simplify" into SELECT-then-UPDATE — that reintroduces the race.
  update public.readings
     set processed_at = now(),
         activity_index = p_activity_index,
         valid = p_valid,
         data_quality = p_data_quality
   where id = p_reading_id
     and processed_at is null
  returning true into v_claimed;

  if v_claimed is null then
    return; -- already processed (duplicate webhook delivery) — no-op
  end if;

  select baseline_temp, baseline_activity, baseline_samples
    into v_bt, v_ba, v_samples
    from public.cows where id = p_cow_id
  for update; -- lock the cow row: serializes concurrent readings for one cow

  -- Baseline advance, in-SQL (single UPDATE = no lost-update race), mirroring
  -- nextBaseline() in _shared/baseline.ts. The vitest agreement test proves
  -- both formulas stay identical — change one, change both, or break the test.
  -- Sensor-suspicious readings (temp_out_of_range) carry valid=true so the app
  -- can display them flagged, but they still must not teach the baselines —
  -- the gate is the quality flag, not p_valid.
  if p_data_quality is distinct from 'temp_out_of_range' then
    if v_bt is null or v_samples <= 0 then
      v_bt := p_temperature;
    elsif v_samples < p_min_samples then
      v_bt := v_bt + (p_temperature - v_bt) / (v_samples + 1);
    else
      v_bt := v_bt + p_alpha_temp * (p_temperature - v_bt);
    end if;
  end if;

  if p_data_quality is distinct from 'activity_stale' then
    if v_ba is null or v_samples <= 0 then
      v_ba := p_activity_index;
    elsif v_samples < p_min_samples then
      v_ba := v_ba + (p_activity_index - v_ba) / (v_samples + 1);
    else
      v_ba := v_ba + p_alpha_activity * (p_activity_index - v_ba);
    end if;
  end if;

  if p_data_quality is distinct from 'temp_out_of_range' then
    v_samples := v_samples + 1;
  end if;

  update public.cows set
    baseline_temp     = v_bt,
    baseline_activity = v_ba,
    baseline_samples  = v_samples,
    current_status    = p_status_after,
    last_seen         = p_reading_ts,
    latest_temp       = p_temperature,
    latest_activity   = p_activity_index
  where id = p_cow_id;

  -- Alert creates with dedup, resolves — mirroring planAlertOps().
  insert into public.alerts (cow_id, type, timestamp, resolved, note)
  select p_cow_id,
         (a->>'type'),
         (a->>'timestamp')::timestamptz,
         false,
         coalesce(a->>'note', '')
  from jsonb_array_elements(p_creates) as a
  where not exists (
    select 1 from public.alerts open
     where open.cow_id = p_cow_id
       and open.type = (a->>'type')
       and open.resolved = false
  );

  update public.alerts set resolved = true
   where cow_id = p_cow_id
     and resolved = false
     and id::text = any (select value::text from jsonb_array_elements_text(p_resolves));
end;
$$;
