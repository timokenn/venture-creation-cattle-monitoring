-- One-off operational reset: put the hardware-test cow (esp32-demo-normal,
-- "Bella") back to a clean healthy state after the fever demo —
-- resolve any open alerts and restore status normal, while KEEPING her
-- learned baselines and reading history. Idempotent: re-running is a no-op.
update public.cows
   set current_status = 'normal'
 where device_id = 'esp32-demo-normal'
   and current_status <> 'normal';

update public.alerts
   set resolved = true
 where resolved = false
   and cow_id in (select id from public.cows where device_id = 'esp32-demo-normal');
