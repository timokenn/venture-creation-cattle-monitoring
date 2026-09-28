-- ============================================================================
-- Cattle Health Monitor — Supabase schema
-- Run once in the Supabase dashboard SQL Editor (or `supabase db push`).
-- Design notes live in ../README.md; all tunable constants in
-- ../supabase/functions/_shared/thresholds.ts.
-- ============================================================================

-- ---------------------------------------------------------------- tables ----
-- cows.owner_id references auth.users (Supabase Auth). Vanilla Postgres (the
-- PGlite test harness) has no auth schema, so create a minimal stand-in — on
-- hosted Supabase both statements are no-ops (the real one already exists).
create schema if not exists auth;
create table if not exists auth.users (id uuid primary key);

-- auth.uid() stub for vanilla Postgres: mirrors Supabase's implementation
-- (reads the JWT sub claim GUC). Guarded — on hosted Supabase the real
-- function already exists and this block is a no-op.
do $$
begin
  if not exists (
    select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'auth' and p.proname = 'uid'
  ) then
    execute $fn$
      create function auth.uid() returns uuid language sql stable as
      'select nullif(current_setting(''request.jwt.claim.sub'', true), '''')::uuid'
    $fn$;
  end if;
end $$;

create table if not exists public.cows (
  id                uuid primary key default gen_random_uuid(),
  name              text not null,
  device_id         text not null unique,
  baseline_temp     numeric,
  baseline_activity numeric,
  baseline_samples  integer not null default 0,
  current_status    text not null default 'normal'
                    check (current_status in ('normal','warning','alert','offline')),
  last_seen         timestamptz,
  latest_temp       numeric,
  battery_level     numeric, -- 0-100, latest known collar battery %; null until firmware reports it
  latest_activity   numeric,
  -- Owning auth.users account (multi-tenant: a user sees only their cows).
  -- Fresh installs: keep in sync with supabase/migrations/.
  owner_id          uuid references auth.users(id) on delete cascade,
  -- Nominal seconds between collar readings; the app derives instant
  -- OFFLINE locally as last_seen older than 3x this (matches the cron).
  send_interval_seconds integer not null default 20,
  -- Profile photo: public Storage URL in the 'cow-photos' bucket.
  image_url text
);

-- Cow photos: public read (avatars render without signed URLs), uploads by
-- authenticated users only. Keep in sync with migrations/20260928170000.
insert into storage.buckets (id, name, public)
values ('cow-photos', 'cow-photos', true)
on conflict (id) do nothing;

drop policy if exists "cow_photos_read" on storage.objects;
create policy "cow_photos_read" on storage.objects for select
  to anon, authenticated using (bucket_id = 'cow-photos');

drop policy if exists "cow_photos_upload" on storage.objects;
create policy "cow_photos_upload" on storage.objects for insert
  to authenticated
  with check (bucket_id = 'cow-photos');

drop policy if exists "cow_photos_update" on storage.objects;
create policy "cow_photos_update" on storage.objects for update
  to authenticated
  using (bucket_id = 'cow-photos') with check (bucket_id = 'cow-photos');

drop policy if exists "cow_photos_delete" on storage.objects;
create policy "cow_photos_delete" on storage.objects for delete
  to authenticated
  using (bucket_id = 'cow-photos');

create table if not exists public.readings (
  id              uuid primary key default gen_random_uuid(),
  cow_id          uuid not null references public.cows(id) on delete cascade,
  timestamp       timestamptz not null default now(),
  temperature     double precision not null check (temperature between 30 and 45),
  accel_x         double precision not null,
  accel_y         double precision not null,
  accel_z         double precision not null,
  gyro_x          double precision not null,
  gyro_y          double precision not null,
  gyro_z          double precision not null,
  activity_index  double precision check (activity_index >= 0),
  battery_level   numeric check (battery_level is null or battery_level between 0 and 100),
  valid           boolean,
  data_quality    text,
  -- Idempotency guard for Database Webhook retries: set by the RPC below.
  processed_at    timestamptz
);

create table if not exists public.alerts (
  id          uuid primary key default gen_random_uuid(),
  cow_id      uuid not null references public.cows(id) on delete cascade,
  type        text not null check (type in (
                'fever','low_activity','possible_estrus',
                'possible_distress','device_offline','sensor_issue')),
  timestamp   timestamptz not null default now(),
  resolved    boolean not null default false,
  note        text not null default ''
);

create table if not exists public.fcm_tokens (
  token       text primary key,
  updated_at  timestamptz not null default now()
);

create index if not exists cows_owner_idx on public.cows (owner_id);
create index if not exists readings_cow_ts_idx on public.readings (cow_id, timestamp desc);
create index if not exists alerts_cow_resolved_ts_idx on public.alerts (cow_id, resolved, timestamp desc);

-- ------------------------------------------------------------------- RLS ----
-- App + devices: read-only on data, INSERT only where noted. All writes to
-- status/alerts/baselines happen through the service role (Edge Functions).
--
-- Supabase ships the anon/authenticated/service_role roles; vanilla Postgres
-- (the PGlite test harness) doesn't — create them if missing so schema.sql
-- runs unchanged in both places.
do $$
declare r text;
begin
  foreach r in array array['anon', 'authenticated', 'service_role'] loop
    if not exists (select 1 from pg_roles where rolname = r) then
      execute format('create role %I nologin', r);
    end if;
  end loop;
end $$;

alter table public.cows       enable row level security;
alter table public.readings   enable row level security;
alter table public.alerts     enable row level security;
alter table public.fcm_tokens enable row level security;

-- Per-user cows: SELECT/INSERT locked to the row owner (cows.owner_id);
-- readings/alerts inherit visibility through the cow. Fresh installs: keep
-- in sync with supabase/migrations/20260928100000_per_user_cows.sql.
drop policy if exists "cows_select" on public.cows;
create policy "cows_select" on public.cows for select
  to authenticated
  using (owner_id = auth.uid());

drop policy if exists "cows_insert" on public.cows;
create policy "cows_insert" on public.cows for insert
  to authenticated
  with check (owner_id = auth.uid());

-- Device boot lookup (collar firmware): SECURITY DEFINER so the collar can
-- resolve device_id -> cow UUID with the publishable key despite per-owner
-- SELECT RLS. Lookup-only: takes a device id, returns just the UUID.
create or replace function public.lookup_cow_id(p_device_id text)
returns uuid
language sql
security definer
set search_path = public
as $$
  select id from public.cows where device_id = p_device_id limit 1;
$$;

revoke all on function public.lookup_cow_id(text) from public;
grant execute on function public.lookup_cow_id(text) to anon, authenticated;

drop policy if exists "readings_select" on public.readings;
create policy "readings_select" on public.readings for select
  to authenticated
  using (exists (
    select 1 from public.cows c
    where c.id = readings.cow_id and c.owner_id = auth.uid()
  ));

-- NOTE (hardened): the old open anon INSERT on readings is gone. Devices
-- ingest through the ingest-reading Edge Function (x-collar-key header +
-- per-device rate limit, service role inside) — see supabase/functions/
-- ingest-reading and migration 20260928150000. Keep in sync with migrations/.

drop policy if exists "alerts_select" on public.alerts;
create policy "alerts_select" on public.alerts for select
  to authenticated
  using (exists (
    select 1 from public.cows c
    where c.id = alerts.cow_id and c.owner_id = auth.uid()
  ));

drop policy if exists "fcm_tokens_insert" on public.fcm_tokens;
create policy "fcm_tokens_insert" on public.fcm_tokens for insert
  to anon, authenticated with check (true);

drop policy if exists "fcm_tokens_update" on public.fcm_tokens;
create policy "fcm_tokens_update" on public.fcm_tokens for update
  to anon, authenticated using (true) with check (true);

-- NOTE (accepted risk): the readings INSERT policy is open to anyone holding
-- the public anon key. Fine for a student demo; harden later with per-device
-- Supabase Auth identities if this ever leaves the classroom.

-- -------------------------------------------------- realtime publication ----
-- Without this, postgres_changes channels connect but receive NOTHING
-- (silent failure) — see README troubleshooting. Guarded so the file also
-- runs on vanilla Postgres (tests use PGlite) and is safe to re-run.
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
end $$;

do $$
begin
  alter publication supabase_realtime add table public.cows;
exception when duplicate_object then null; end $$;
do $$
begin
  alter publication supabase_realtime add table public.readings;
exception when duplicate_object then null; end $$;
do $$
begin
  alter publication supabase_realtime add table public.alerts;
exception when duplicate_object then null; end $$;

-- ------------------------------------------------------ atomic write RPC ----
-- apply_reading_decision: the ONLY writer of computed reading fields, cow
-- status/baselines and alert rows. One transaction per reading; safe against
-- concurrent duplicate deliveries (see the CAS guard below) and against
-- concurrent readings for the same cow (single UPDATE serializes per row).
-- DROP first: if the signature ever grows (it did: p_battery_level), a
-- bare CREATE OR REPLACE would leave two overloads and PostgREST would
-- then fail calls with "Could not choose the best candidate function".
drop function if exists public.apply_reading_decision(uuid, uuid, timestamptz, double precision, double precision, boolean, text, text, double precision, double precision, integer, jsonb, jsonb);
drop function if exists public.apply_reading_decision(uuid, uuid, timestamptz, double precision, double precision, boolean, text, text, double precision, double precision, integer, jsonb, jsonb, double precision);

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
  p_resolves          jsonb,   -- [alert_id, ...]
  p_battery_level     double precision default null -- 0-100, optional until firmware reports it
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
    latest_activity   = p_activity_index,
    battery_level     = coalesce(p_battery_level, battery_level)
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

-- apply_offline_transition: purpose-built RPC for the 15-min offline checker.
-- Idempotent per transition: repeated firings with the same is_stale no-op.
create or replace function public.apply_offline_transition(
  p_cow_id   uuid,
  p_is_stale boolean,
  p_now      timestamptz
) returns void
language plpgsql
as $$
declare
  v_open uuid;
begin
  if p_is_stale then
    select id into v_open
      from public.alerts
     where cow_id = p_cow_id and type = 'device_offline' and resolved = false
     limit 1;

    if v_open is null then
      insert into public.alerts (cow_id, type, timestamp, resolved, note)
      values (p_cow_id, 'device_offline', p_now, false,
              -- keep in sync with _shared/config.ts offlineAlertNote
              'No readings received — device may be powered off or out of range');
    end if;

    update public.cows set current_status = 'offline' where id = p_cow_id;
  else
    update public.alerts set resolved = true
     where cow_id = p_cow_id and type = 'device_offline' and resolved = false;

    update public.cows
       set current_status = 'normal'
     where id = p_cow_id
       and current_status = 'offline';
  end if;
end;
$$;

-- service_role exists on hosted Supabase but not on vanilla Postgres
-- (PGlite tests) — guard so schema.sql runs in both.
do $$
begin
  grant execute on function public.apply_reading_decision(uuid, uuid, timestamptz, double precision, double precision, boolean, text, text, double precision, double precision, integer, jsonb, jsonb, double precision) to service_role;
  grant execute on function public.apply_offline_transition(uuid, boolean, timestamptz) to service_role;
exception when undefined_object then null; end $$;

-- ------------------------------------------- optional Firestore bridge -------
-- Only used if devices send readings to Firestore via the Firebase Arduino
-- library; see the firestore-bridge Edge Function and README. Harmless if
-- unused: source_ref stays NULL for direct inserts.
alter table public.readings add column if not exists source_ref text;
-- Unique (not partial): PostgREST's ON CONFLICT (source_ref) must infer it.
-- Multiple NULLs coexist fine in a unique index.
create unique index if not exists readings_source_ref_key
  on public.readings (source_ref);

create table if not exists public.bridge_state (
  id           text primary key,
  -- For the firestore-bridge this is a heartbeat (last run time + summary),
  -- not a pull cursor: the bridge is pull-all-and-delete, with no device-
  -- clock assumptions. Columns kept generic so future bridges can reuse.
  cursor       timestamptz,
  last_doc_id  text,
  updated_at   timestamptz
);

-- Internal observability table: RLS on, NO policies → invisible to
-- anon/authenticated clients; the service-role bridge bypasses RLS.
alter table public.bridge_state enable row level security;
