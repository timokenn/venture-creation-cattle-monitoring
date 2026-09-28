-- ============================================================================
-- Per-user cows (multi-tenant) — run via `supabase db push`.
--
-- Adds cows.owner_id (the auth.users id of the account that registered the
-- cow) and locks SELECT down to the owner. Before this migration anyone with
-- the public anon key could read every cow; afterwards the app must hold an
-- authenticated session, which it now does (all reads go through the logged-in
-- Supabase client in CattleRepository).
--
-- Existing cow rows (Bella) are claimed by the first user in auth.users so
-- nothing disappears after the RLS change.
-- ============================================================================
alter table public.cows add column if not exists owner_id uuid references auth.users(id) on delete cascade;
create index if not exists cows_owner_idx on public.cows (owner_id);

-- Claim pre-existing rows: assign them to the most recently created account
-- (the one actually being used/tested on this project).
update public.cows set owner_id = (select id from auth.users order by created_at desc limit 1)
where owner_id is null;

-- The device boot lookup used to be an anon GET against cows (allowed by the
-- old open SELECT policy). Under per-owner RLS that returns zero rows, so the
-- firmware resolves its UUID through this SECURITY DEFINER RPC instead.
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

-- ---------------------------------------------------------------------------
-- RLS: per-owner isolation.
-- ---------------------------------------------------------------------------
drop policy if exists "cows_select" on public.cows;
create policy "cows_select" on public.cows for select
  to authenticated
  using (owner_id = auth.uid());

drop policy if exists "cows_insert" on public.cows;
create policy "cows_insert" on public.cows for insert
  to authenticated
  with check (owner_id = auth.uid());

drop policy if exists "readings_select" on public.readings;
create policy "readings_select" on public.readings for select
  to authenticated
  using (exists (
    select 1 from public.cows c
    where c.id = readings.cow_id and c.owner_id = auth.uid()
  ));

drop policy if exists "alerts_select" on public.alerts;
create policy "alerts_select" on public.alerts for select
  to authenticated
  using (exists (
    select 1 from public.cows c
    where c.id = alerts.cow_id and c.owner_id = auth.uid()
  ));

-- readings_insert stays open to anon on purpose: the collar has no user
-- account, it inserts straight into readings with the publishable key. The
-- webhook (service role) bypasses RLS entirely, so cow state is untouched.
