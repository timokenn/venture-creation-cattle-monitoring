-- Plan change: the collar sends readings straight to Supabase (Path A, plain
-- PostgREST insert), so the Firestore inbox path is no longer used. Stop the
-- per-minute firestore-bridge cron to save free-tier invocations; the
-- deployed function stays for reference. Idempotent (guarded on job name).
do $$
begin
  if exists (select 1 from cron.job where jobname = 'firestore-bridge') then
    perform cron.unschedule('firestore-bridge');
  end if;
end $$;
