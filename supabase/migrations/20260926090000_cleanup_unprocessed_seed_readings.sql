-- One-off cleanup: 2,880 simulator-seed readings were inserted before the
-- process-reading Edge Function existed, so their webhooks 404'd and the rows
-- were never processed (processed_at is null, activity_index null). The
-- pipeline deliberately has no replay (CAS on processed_at), and these rows
-- would pollute the app's charts with null-activity points. Delete them;
-- Bella's live baseline comes from her 362 processed readings instead.
--
-- Idempotent: re-running deletes 0 rows (all live readings are processed).
delete from public.readings
where processed_at is null;
