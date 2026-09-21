-- Durable API repository primitives. Supabase Auth remains the account authority.
-- Server RPCs are service-role only; authenticated clients retain same-account RLS reads.

alter table public.devices
  add column if not exists schema_version text not null default '3.0'
    check (schema_version = '3.0'),
  add column if not exists device_profile text not null default 'android_arm64_8gb_launch_v1'
    check (device_profile = 'android_arm64_8gb_launch_v1'),
  add column if not exists android_api integer not null default 26
    check (android_api >= 26),
  add column if not exists abi text not null default 'arm64-v8a'
    check (abi = 'arm64-v8a'),
  add column if not exists memory_mb integer not null default 8192
    check (memory_mb >= 8192),
  add column if not exists gms_available boolean not null default true,
  add column if not exists app_version text not null default '0.1.0'
    check (app_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$');

alter table public.devices drop constraint if exists devices_display_name_check;
alter table public.devices add constraint devices_display_name_check
  check (char_length(display_name) between 1 and 100);

alter table public.events
  add column if not exists schema_version text not null default '3.0'
    check (schema_version = '3.0');

alter table public.events
  add column if not exists event_sequence bigint generated always as identity;

create unique index if not exists events_sequence_idx on public.events(event_sequence);
create index if not exists events_account_sequence_idx on public.events(account_id, event_sequence);

create table if not exists public.sync_changes (
  sequence bigint generated always as identity primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  resource_type text not null check (resource_type in ('device', 'task', 'event')),
  operation text not null check (operation in ('upsert', 'delete')),
  resource_id uuid not null,
  value jsonb,
  created_at timestamptz not null default now(),
  check ((operation = 'delete' and value is null) or (operation = 'upsert' and value is not null))
);

create index if not exists sync_changes_account_sequence_idx
  on public.sync_changes(account_id, sequence);

alter table public.sync_changes enable row level security;
revoke all on public.sync_changes from anon;
grant select on public.sync_changes to authenticated;

drop policy if exists sync_changes_same_account on public.sync_changes;
create policy sync_changes_same_account on public.sync_changes
  for select to authenticated
  using ((select auth.uid()) = account_id);

create or replace function public.beyoureyes_jsonb_contains_media_key(p_value jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog
as $$
  select case jsonb_typeof(p_value)
    when 'object' then exists (
      select 1
      from jsonb_each(p_value) as item(key, value)
      where item.key in ('image', 'frame', 'photo', 'video', 'audio', 'blob', 'data_url', 'media_url')
         or public.beyoureyes_jsonb_contains_media_key(item.value)
    )
    when 'array' then exists (
      select 1
      from jsonb_array_elements(p_value) as item(value)
      where public.beyoureyes_jsonb_contains_media_key(item.value)
    )
    else false
  end
$$;

revoke all on function public.beyoureyes_jsonb_contains_media_key(jsonb) from public;
grant execute on function public.beyoureyes_jsonb_contains_media_key(jsonb) to authenticated, service_role;

alter table public.events
  drop constraint if exists events_payload_recursive_media_free;
alter table public.events
  add constraint events_payload_recursive_media_free
  check (not public.beyoureyes_jsonb_contains_media_key(payload));

create or replace function public.beyoureyes_assert_active_monitoring_device()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if not exists (
    select 1 from public.devices d
    where d.account_id = new.account_id
      and d.device_id = new.monitoring_device_id
      and d.revoked_at is null
  ) then
    raise exception 'invalid_monitoring_device' using errcode = 'P0001';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_assert_active_monitoring_device() from public, anon, authenticated;

drop trigger if exists tasks_assert_active_monitoring_device on public.tasks;
create trigger tasks_assert_active_monitoring_device
before insert or update on public.tasks
for each row execute function public.beyoureyes_assert_active_monitoring_device();

create or replace function public.beyoureyes_validate_event()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_task_revision bigint;
begin
  select t.revision into v_task_revision
  from public.tasks t
  where t.account_id = new.account_id and t.task_id = new.task_id;
  if not found then
    raise exception 'task_not_found' using errcode = 'P0001';
  end if;
  if new.task_revision < 1 or new.task_revision > v_task_revision then
    raise exception 'invalid_task_revision' using errcode = 'P0001';
  end if;
  if public.beyoureyes_jsonb_contains_media_key(new.payload) then
    raise exception 'event_payload_contains_media' using errcode = 'P0001';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_validate_event() from public, anon, authenticated;

drop trigger if exists events_validate_before_write on public.events;
create trigger events_validate_before_write
before insert or update on public.events
for each row execute function public.beyoureyes_validate_event();

create or replace function public.beyoureyes_record_device_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_row public.devices%rowtype;
begin
  v_row := case when tg_op = 'DELETE' then old else new end;
  insert into public.sync_changes(account_id, resource_type, operation, resource_id, value)
  values (
    v_row.account_id,
    'device',
    case when tg_op = 'DELETE' then 'delete' else 'upsert' end,
    v_row.device_id,
    case when tg_op = 'DELETE' then null else jsonb_build_object(
      'schema_version', v_row.schema_version,
      'device_id', v_row.device_id,
      'device_profile', v_row.device_profile,
      'display_name', v_row.display_name,
      'android_api', v_row.android_api,
      'abi', v_row.abi,
      'memory_mb', v_row.memory_mb,
      'gms_available', v_row.gms_available,
      'event_notifications_enabled', v_row.notifications_enabled,
      'app_version', v_row.app_version,
      'revoked_at', v_row.revoked_at,
      'updated_at', v_row.updated_at
    ) end
  );
  return case when tg_op = 'DELETE' then old else new end;
end
$$;

revoke all on function public.beyoureyes_record_device_change() from public, anon, authenticated;

drop trigger if exists devices_record_sync_change on public.devices;
create trigger devices_record_sync_change
after insert or update or delete on public.devices
for each row execute function public.beyoureyes_record_device_change();

create or replace function public.beyoureyes_record_task_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_row public.tasks%rowtype;
begin
  v_row := case when tg_op = 'DELETE' then old else new end;
  insert into public.sync_changes(account_id, resource_type, operation, resource_id, value)
  values (
    v_row.account_id,
    'task',
    case when tg_op = 'DELETE' then 'delete' else 'upsert' end,
    v_row.task_id,
    case when tg_op = 'DELETE' then null else jsonb_build_object(
      'schema_version', '3.0',
      'task_id', v_row.task_id,
      'revision', v_row.revision,
      'catalog_version', v_row.catalog_version,
      'capability_id', v_row.capability_id,
      'target_id', v_row.target_id,
      'target_definition', v_row.config->'target_definition',
      'monitoring_device_id', v_row.monitoring_device_id,
      'roi', v_row.config->'roi',
      'sampling_policy', v_row.config->'sampling_policy',
      'rule', v_row.config->'rule',
      'schedule', v_row.config->'schedule'
    ) end
  );
  return case when tg_op = 'DELETE' then old else new end;
end
$$;

revoke all on function public.beyoureyes_record_task_change() from public, anon, authenticated;

drop trigger if exists tasks_record_sync_change on public.tasks;
create trigger tasks_record_sync_change
after insert or update or delete on public.tasks
for each row execute function public.beyoureyes_record_task_change();

create or replace function public.beyoureyes_record_event_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_row public.events%rowtype;
begin
  v_row := case when tg_op = 'DELETE' then old else new end;
  insert into public.sync_changes(account_id, resource_type, operation, resource_id, value)
  values (
    v_row.account_id,
    'event',
    case when tg_op = 'DELETE' then 'delete' else 'upsert' end,
    v_row.event_id,
    case when tg_op = 'DELETE' then null else jsonb_build_object(
      'schema_version', v_row.schema_version,
      'event_id', v_row.event_id,
      'task_id', v_row.task_id,
      'task_revision', v_row.task_revision,
      'episode_id', v_row.episode_id,
      'source_sequence', v_row.source_sequence,
      'occurred_at', v_row.occurred_at,
      'payload', v_row.payload
    ) end
  );
  return case when tg_op = 'DELETE' then old else new end;
end
$$;

revoke all on function public.beyoureyes_record_event_change() from public, anon, authenticated;

drop trigger if exists events_record_sync_change on public.events;
create trigger events_record_sync_change
after insert or update or delete on public.events
for each row execute function public.beyoureyes_record_event_change();

create or replace function public.beyoureyes_insert_event_batch(p_account_id uuid, p_events jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_event jsonb;
  v_existing public.events%rowtype;
  v_inserted integer;
  v_task_revision bigint;
  v_accepted jsonb := '[]'::jsonb;
  v_duplicates jsonb := '[]'::jsonb;
begin
  if jsonb_typeof(p_events) <> 'array' or jsonb_array_length(p_events) not between 1 and 100 then
    raise exception 'invalid_event_batch' using errcode = '22023';
  end if;
  if exists (
    select 1
    from jsonb_array_elements(p_events) e
    group by e->>'event_id'
    having count(*) > 1
  ) then
    raise exception 'duplicate_batch_event_id' using errcode = '22023';
  end if;

  for v_event in select value from jsonb_array_elements(p_events)
  loop
    select revision into v_task_revision
    from public.tasks
    where account_id = p_account_id and task_id = (v_event->>'task_id')::uuid;
    if not found then
      raise exception 'task_not_found' using errcode = 'P0001';
    end if;
    if (v_event->>'task_revision')::bigint not between 1 and v_task_revision then
      raise exception 'invalid_task_revision' using errcode = 'P0001';
    end if;

    insert into public.events(
      schema_version, account_id, event_id, task_id, task_revision, episode_id,
      source_sequence, occurred_at, payload
    ) values (
      v_event->>'schema_version',
      p_account_id,
      (v_event->>'event_id')::uuid,
      (v_event->>'task_id')::uuid,
      (v_event->>'task_revision')::bigint,
      (v_event->>'episode_id')::uuid,
      (v_event->>'source_sequence')::bigint,
      (v_event->>'occurred_at')::timestamptz,
      v_event->'payload'
    ) on conflict (account_id, event_id) do nothing;
    get diagnostics v_inserted = row_count;

    if v_inserted = 1 then
      v_accepted := v_accepted || jsonb_build_array(v_event->>'event_id');
    else
      select * into v_existing
      from public.events
      where account_id = p_account_id and event_id = (v_event->>'event_id')::uuid;
      if not found
        or v_existing.schema_version <> v_event->>'schema_version'
        or v_existing.task_id <> (v_event->>'task_id')::uuid
        or v_existing.task_revision <> (v_event->>'task_revision')::bigint
        or v_existing.episode_id <> (v_event->>'episode_id')::uuid
        or v_existing.source_sequence <> (v_event->>'source_sequence')::bigint
        or v_existing.occurred_at <> (v_event->>'occurred_at')::timestamptz
        or v_existing.payload <> v_event->'payload'
      then
        raise exception 'event_id_conflict' using errcode = 'P0001';
      end if;
      v_duplicates := v_duplicates || jsonb_build_array(v_event->>'event_id');
    end if;
  end loop;

  return jsonb_build_object('accepted_ids', v_accepted, 'duplicate_ids', v_duplicates);
end
$$;

revoke all on function public.beyoureyes_insert_event_batch(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.beyoureyes_insert_event_batch(uuid, jsonb) to service_role;

create or replace function public.beyoureyes_add_event_receipt(
  p_account_id uuid,
  p_event_id uuid,
  p_device_id uuid,
  p_receipt_type text,
  p_received_at timestamptz
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_receipt public.event_receipts%rowtype;
begin
  if p_receipt_type not in ('fetched', 'displayed') then
    raise exception 'invalid_receipt_type' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.events
    where account_id = p_account_id and event_id = p_event_id
  ) then
    raise exception 'event_not_found' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.devices
    where account_id = p_account_id and device_id = p_device_id
  ) then
    raise exception 'device_not_found' using errcode = 'P0001';
  end if;
  if exists (
    select 1 from public.devices
    where account_id = p_account_id and device_id = p_device_id and revoked_at is not null
  ) then
    raise exception 'device_revoked' using errcode = 'P0001';
  end if;

  insert into public.event_receipts(account_id, event_id, device_id, receipt_type, received_at)
  values (p_account_id, p_event_id, p_device_id, p_receipt_type, p_received_at)
  on conflict (account_id, event_id, device_id, receipt_type) do nothing
  returning * into v_receipt;

  if not found then
    select * into v_receipt
    from public.event_receipts
    where account_id = p_account_id
      and event_id = p_event_id
      and device_id = p_device_id
      and receipt_type = p_receipt_type;
  end if;

  return jsonb_build_object(
    'event_id', v_receipt.event_id,
    'device_id', v_receipt.device_id,
    'receipt_type', v_receipt.receipt_type,
    'received_at', v_receipt.received_at
  );
end
$$;

revoke all on function public.beyoureyes_add_event_receipt(uuid, uuid, uuid, text, timestamptz)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_add_event_receipt(uuid, uuid, uuid, text, timestamptz)
  to service_role;

create or replace function public.beyoureyes_purge_account_data(p_account_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  delete from public.event_receipts where account_id = p_account_id;
  delete from public.events where account_id = p_account_id;
  delete from public.tasks where account_id = p_account_id;
  delete from public.devices where account_id = p_account_id;
  delete from public.sync_changes where account_id = p_account_id;
end
$$;

revoke all on function public.beyoureyes_purge_account_data(uuid) from public, anon, authenticated;
grant execute on function public.beyoureyes_purge_account_data(uuid) to service_role;

grant select, insert, update, delete on
  public.devices, public.tasks, public.events, public.event_receipts, public.sync_changes
  to service_role;
grant usage, select on all sequences in schema public to service_role;

create or replace function public.beyoureyes_data_ready()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  select to_regclass('public.devices') is not null
     and to_regclass('public.tasks') is not null
     and to_regclass('public.events') is not null
     and to_regclass('public.event_receipts') is not null
     and to_regclass('public.sync_changes') is not null
$$;

revoke all on function public.beyoureyes_data_ready() from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
