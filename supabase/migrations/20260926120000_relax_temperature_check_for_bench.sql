-- TEMPORARY (bench testing): the collar's cow-range guard (30–45°C) is
-- disabled in firmware so room-temperature DS18B20 readings (~24°C) can flow
-- during hardware bring-up. Widen the DB CHECK to the DS18B20's physical
-- range so they're accepted. RESTORE the cow-range CHECK after bench testing
-- (see 20260926130000_restore_temperature_check.sql when written).
alter table public.readings drop constraint readings_temperature_check;
alter table public.readings add constraint readings_temperature_check
  check (temperature between -55 and 150);
