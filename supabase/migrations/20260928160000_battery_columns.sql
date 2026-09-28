-- ============================================================================
-- Battery level support (from the decow-app design drop). The app renders a
-- battery indicator per cow card once the collar reports a value; until then
-- it stays hidden. Keep in sync with schema.sql.
-- ============================================================================
alter table public.cows     add column if not exists battery_level numeric;
alter table public.readings add column if not exists battery_level numeric
  check (battery_level is null or battery_level between 0 and 100);
