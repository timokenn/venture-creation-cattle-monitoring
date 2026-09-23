-- ============================================================================
-- Cattle Health Monitor — pg_cron jobs
-- Run once in the Supabase SQL Editor AFTER schema.sql, and after replacing
-- the two placeholder values in section 1 below. Requires the pg_cron and
-- pg_net extensions (Dashboard → Database → Extensions).
-- ============================================================================

-- 1) Store the project URL + publishable key in Supabase Vault.
--    >>> REPLACE BOTH PLACEHOLDERS with your project's URL and publishable
--    >>> key (dashboard → Connect panel) before running. The guard below
--    >>> refuses to schedule jobs against unfilled placeholders.
do $$
declare
  v_url text := 'https://YOUR-PROJECT-REF.supabase.co';
  v_key text := 'YOUR_PUBLISHABLE_KEY';
begin
  if v_url like '%YOUR-PROJECT-REF%' or v_key like '%YOUR_PUBLISHABLE%' then
    raise exception 'Edit cron.sql first: replace YOUR-PROJECT-REF / YOUR_PUBLISHABLE_KEY in section 1.';
  end if;
  -- Re-runnable: replace stored values each run (jobs read them by name).
  delete from vault.secrets where name in ('project_url', 'publishable_key');
  perform vault.create_secret(v_url, 'project_url');
  perform vault.create_secret(v_key, 'publishable_key');
end $$;

-- 2) Offline check: invoke the offline-check Edge Function every 15 minutes.
--    (SQL alone can't mint FCM tokens, so this goes through the function,
--    which does the staleness scan, deduped alerts and pushes.)
select cron.schedule(
  'offline-check',
  '*/15 * * * *',
  $$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url')
           || '/functions/v1/offline-check',
    headers := jsonb_build_object(
      'Content-type', 'application/json',
      'Authorization', 'Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name = 'publishable_key')
    ),
    body := concat('{"time": "', now(), '"}')::jsonb
  ) as request_id;
  $$
);

-- 3) Retention: drop readings older than 90 days (hourly). Keeps the 500 MB
--    free-tier database comfortably under its cap. Baselines live on the cow
--    row (never recomputed from history), so old readings are safe to delete.
select cron.schedule(
  'reading-retention',
  '17 * * * *',
  $$ delete from public.readings where timestamp < now() - interval '90 days'; $$
);

-- 4) OPTIONAL — Firestore bridge (only if devices use the Firebase Arduino
--    library and write to the `device_readings` collection). Pulls new docs
--    into `readings` every minute; duplicates are absorbed by the
--    readings_source_ref unique index. If you DON'T use that path, remove
--    this job:  select cron.unschedule('firestore-bridge');
select cron.schedule(
  'firestore-bridge',
  '* * * * *',
  $$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url')
           || '/functions/v1/firestore-bridge',
    headers := jsonb_build_object(
      'Content-type', 'application/json',
      'Authorization', 'Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name = 'publishable_key')
    ),
    body := concat('{"time": "', now(), '"}')::jsonb
  ) as request_id;
  $$
);
