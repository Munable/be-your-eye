-- Android owns the public account data plane through Supabase PostgREST/RPC.
-- No remote proxy owns this CRUD. Every client write derives account scope
-- from auth.uid(); service-role functions remain internal to account deletion
-- and the optional push worker.

alter table public.devices alter column account_id set default auth.uid();
alter table public.tasks alter column account_id set default auth.uid();

-- Do not grant account_id, revision, identity, or server timestamp columns.
grant insert (
  device_id, schema_version, device_profile, display_name, android_api, abi,
  memory_mb, gms_available, notifications_enabled, app_version, revoked_at
) on public.devices to authenticated;
grant update (
  schema_version, device_profile, display_name, android_api, abi, memory_mb,
  gms_available, notifications_enabled, app_version, revoked_at
) on public.devices to authenticated;
grant delete on public.devices to authenticated;

grant insert (
  task_id, catalog_version, capability_id, target_id, monitoring_device_id, config
) on public.tasks to authenticated;
grant update (
  catalog_version, capability_id, target_id, monitoring_device_id, config
) on public.tasks to authenticated;
grant delete on public.tasks to authenticated;

-- Events and receipts stay write-protected as tables. Their authenticated RPCs
-- below provide transactional idempotency and derive account_id from auth.uid().
revoke insert, update, delete on public.events, public.event_receipts, public.sync_changes from authenticated;

create or replace function public.beyoureyes_prepare_device_write()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_account_id uuid := auth.uid();
begin
  if tg_op = 'INSERT' then
    if v_account_id is not null then
      new.account_id := v_account_id;
    end if;
    new.revision := 1;
    new.created_at := clock_timestamp();
    new.updated_at := new.created_at;
  else
    new.account_id := old.account_id;
    new.device_id := old.device_id;
    new.revision := old.revision + 1;
    new.created_at := old.created_at;
    new.updated_at := clock_timestamp();
  end if;
  if new.account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_prepare_device_write() from public, anon, authenticated;

drop trigger if exists devices_prepare_client_write on public.devices;
create trigger devices_prepare_client_write
before insert or update on public.devices
for each row execute function public.beyoureyes_prepare_device_write();

create or replace function public.beyoureyes_prepare_task_write()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_account_id uuid := auth.uid();
begin
  if tg_op = 'INSERT' then
    if v_account_id is not null then
      new.account_id := v_account_id;
    end if;
    new.revision := 1;
    new.created_at := clock_timestamp();
    new.updated_at := new.created_at;
  else
    new.account_id := old.account_id;
    new.task_id := old.task_id;
    new.revision := old.revision + 1;
    new.created_at := old.created_at;
    new.updated_at := clock_timestamp();
  end if;
  if new.account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_prepare_task_write() from public, anon, authenticated;

drop trigger if exists tasks_prepare_client_write on public.tasks;
create trigger tasks_prepare_client_write
before insert or update on public.tasks
for each row execute function public.beyoureyes_prepare_task_write();

create or replace function public.beyoureyes_client_insert_event_batch(p_events jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_account_id uuid := auth.uid();
begin
  if v_account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return public.beyoureyes_insert_event_batch(v_account_id, p_events);
end
$$;

revoke all on function public.beyoureyes_client_insert_event_batch(jsonb) from public, anon, service_role;
grant execute on function public.beyoureyes_client_insert_event_batch(jsonb) to authenticated;

create or replace function public.beyoureyes_client_add_event_receipt(
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
  v_account_id uuid := auth.uid();
begin
  if v_account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return public.beyoureyes_add_event_receipt(
    v_account_id,
    p_event_id,
    p_device_id,
    p_receipt_type,
    p_received_at
  );
end
$$;

revoke all on function public.beyoureyes_client_add_event_receipt(uuid, uuid, text, timestamptz)
  from public, anon, service_role;
grant execute on function public.beyoureyes_client_add_event_receipt(uuid, uuid, text, timestamptz)
  to authenticated;

-- Deleting auth.users cascades account rows. Do not append a sync tombstone
-- after the parent account has already disappeared, otherwise the sync_changes
-- foreign key would make Supabase admin account deletion fail.
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
  if tg_op = 'DELETE' and not exists (select 1 from auth.users where id = v_row.account_id) then
    return old;
  end if;
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
  if tg_op = 'DELETE' and not exists (select 1 from auth.users where id = v_row.account_id) then
    return old;
  end if;
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
  if tg_op = 'DELETE' and not exists (select 1 from auth.users where id = v_row.account_id) then
    return old;
  end if;
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

revoke all on function public.beyoureyes_record_device_change() from public, anon, authenticated;
revoke all on function public.beyoureyes_record_task_change() from public, anon, authenticated;
revoke all on function public.beyoureyes_record_event_change() from public, anon, authenticated;

-- Hosted readiness must not pass against an older incomplete schema.
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
     and to_regprocedure('public.beyoureyes_client_insert_event_batch(jsonb)') is not null
     and to_regprocedure('public.beyoureyes_client_add_event_receipt(uuid,uuid,text,timestamp with time zone)') is not null
     and has_column_privilege('authenticated', 'public.devices', 'display_name', 'INSERT')
     and has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_insert_event_batch(jsonb)',
       'EXECUTE'
     )
$$;

revoke all on function public.beyoureyes_data_ready() from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
