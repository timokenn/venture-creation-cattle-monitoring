-- ============================================================================
-- Cow profile photos.
--
-- cows.image_url holds a public Storage URL; the bucket 'cow-photos' is
-- public-read (avatars need to render without signed URLs) while uploads are
-- limited to authenticated users. Keep in sync with schema.sql.
-- ============================================================================
alter table public.cows add column if not exists image_url text;

insert into storage.buckets (id, name, public)
values ('cow-photos', 'cow-photos', true)
on conflict (id) do nothing;

-- Storage evaluates INSERT + UPDATE + SELECT together on upsert paths, so all
-- four policies are needed. Writes require a signed-in user; reads are public.
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
