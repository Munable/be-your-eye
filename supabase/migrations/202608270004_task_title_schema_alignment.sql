-- Align the hosted Task table with the current title-based cloud contract.
-- Historical migrations remain unchanged; this is the single forward schema transition.
alter table public.tasks rename column target_id to title;
alter table public.tasks
  add constraint tasks_title_check check (char_length(title) between 1 and 100);

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
      'title', v_row.title,
      'target_definition', v_row.config->'target_definition',
      'monitoring_device_id', v_row.monitoring_device_id,
      'roi', v_row.config->'roi',
      'sampling_policy', v_row.config->'sampling_policy',
      'route_binding', v_row.config->'route_binding',
      'package_binding', v_row.config->'package_binding',
      'rule', v_row.config->'rule'
    ) end
  );
  return case when tg_op = 'DELETE' then old else new end;
end
$$;

revoke all on function public.beyoureyes_record_task_change() from public, anon, authenticated;

create or replace function public.beyoureyes_data_ready()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, cron, realtime
as $$
  select to_regclass('public.devices') is not null
     and to_regclass('public.tasks') is not null
     and exists (
       select 1 from information_schema.columns
       where table_schema = 'public' and table_name = 'tasks'
         and column_name = 'title' and is_nullable = 'NO'
     )
     and not exists (
       select 1 from information_schema.columns
       where table_schema = 'public' and table_name = 'tasks'
         and column_name = 'target_id'
     )
     and to_regclass('public.events') is not null
     and to_regclass('public.event_receipts') is not null
     and to_regclass('public.sync_changes') is not null
     and to_regclass('public.device_push_tokens') is not null
     and to_regclass('public.push_outbox') is not null
     and to_regclass('cron.job') is not null
     and to_regclass('realtime.messages') is not null
     and to_regprocedure('public.beyoureyes_client_upsert_task_batch(jsonb)') is not null
     and to_regprocedure('public.beyoureyes_client_revoke_device(uuid)') is not null
     and to_regprocedure('public.beyoureyes_client_insert_event_batch(jsonb)') is not null
     and to_regprocedure(
       'public.beyoureyes_client_add_event_receipt(uuid,uuid,text,timestamp with time zone)'
     ) is not null
     and to_regprocedure('public.beyoureyes_client_register_push_token(uuid,text)') is not null
     and to_regprocedure(
       'public.beyoureyes_claim_push_batch(integer,uuid,timestamp with time zone)'
     ) is not null
     and to_regprocedure('public.beyoureyes_event_payload_is_valid(jsonb)') is not null
     and to_regprocedure('public.beyoureyes_jsonb_contains_media_key(jsonb)') is not null
     and to_regprocedure(
       'public.beyoureyes_purge_expired_events(uuid,timestamp with time zone,integer)'
     ) is not null
     and to_regprocedure('public.beyoureyes_request_push_dispatch()') is not null
     and exists (
       select 1
       from pg_constraint
       where conrelid = 'public.events'::regclass
         and conname = 'events_payload_contract_valid'
         and contype = 'c'
     )
     and exists (
       select 1
       from pg_constraint
       where conrelid = 'public.events'::regclass
         and conname = 'events_payload_recursive_media_free'
         and contype = 'c'
     )
     and exists (
       select 1
       from pg_trigger t
       join pg_proc p on p.oid = t.tgfoid
       join pg_namespace n on n.oid = p.pronamespace
       where t.tgrelid = 'public.tasks'::regclass
         and t.tgname = 'tasks_record_sync_change'
         and t.tgenabled <> 'D'
         and n.nspname = 'public'
         and p.proname = 'beyoureyes_record_task_change'
         and p.prosrc like '%''title'', v_row.title%'
         and p.prosrc like '%''route_binding'', v_row.config->''route_binding''%'
         and p.prosrc like '%''package_binding'', v_row.config->''package_binding''%'
     )
     and exists (
       select 1
       from pg_trigger t
       join pg_proc p on p.oid = t.tgfoid
       join pg_namespace n on n.oid = p.pronamespace
       where t.tgrelid = 'public.events'::regclass
         and t.tgname = 'events_record_sync_change'
         and t.tgenabled <> 'D'
         and n.nspname = 'public'
         and p.proname = 'beyoureyes_record_event_change'
         and p.prosrc like '%delete from public.sync_changes c%'
         and p.prosrc like '%c.operation = ''upsert''%'
         and p.prosrc like '%''monitoring_device_id'', v_monitoring_device_id%'
     )
     and exists (
       select 1
       from cron.job
       where jobname = 'beyoureyes-event-retention-hourly-v1'
         and schedule = '17 * * * *'
         and command = 'select public.beyoureyes_purge_expired_events();'
         and active
     )
     and exists (
       select 1
       from cron.job
       where jobname = 'beyoureyes-push-dispatch-every-minute'
         and schedule = '* * * * *'
         and command = 'select public.beyoureyes_request_push_dispatch()'
         and active
     )
     and exists (
       select 1
       from pg_class c
       join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'realtime'
         and c.relname = 'messages'
         and c.relrowsecurity
     )
     and exists (
       select 1
       from pg_policies
       where schemaname = 'realtime'
         and tablename = 'messages'
         and policyname = 'beyoureyes_snapshot_broadcast_receive'
         and cmd = 'SELECT'
         and roles = array['authenticated']::name[]
     )
     and exists (
       select 1
       from pg_policies
       where schemaname = 'realtime'
         and tablename = 'messages'
         and policyname = 'beyoureyes_snapshot_broadcast_send'
         and cmd = 'INSERT'
         and roles = array['authenticated']::name[]
     )
     and not exists (
       select 1
       from pg_policies
       where schemaname = 'realtime'
         and tablename = 'messages'
         and ('anon' = any(roles) or 'public' = any(roles))
     )
     and has_column_privilege('authenticated', 'public.devices', 'display_name', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'task_id', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT')
     and has_table_privilege('authenticated', 'realtime.messages', 'SELECT')
     and has_table_privilege('authenticated', 'realtime.messages', 'INSERT')
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_upsert_task_batch(jsonb)',
       'EXECUTE'
     )
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_revoke_device(uuid)',
       'EXECUTE'
     )
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_insert_event_batch(jsonb)',
       'EXECUTE'
     )
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_add_event_receipt(uuid,uuid,text,timestamp with time zone)',
       'EXECUTE'
     )
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_register_push_token(uuid,text)',
       'EXECUTE'
     )
     and not has_function_privilege(
       'authenticated',
       'public.beyoureyes_record_event_change()',
       'EXECUTE'
     )
     and not has_function_privilege(
       'authenticated',
       'public.beyoureyes_purge_expired_events(uuid,timestamp with time zone,integer)',
       'EXECUTE'
     )
     and has_function_privilege(
       'service_role',
       'public.beyoureyes_claim_push_batch(integer,uuid,timestamp with time zone)',
       'EXECUTE'
     )
     and has_function_privilege(
       'service_role',
       'public.beyoureyes_purge_expired_events(uuid,timestamp with time zone,integer)',
       'EXECUTE'
     )
     and not has_function_privilege(
       'service_role',
       'public.beyoureyes_request_push_dispatch()',
       'EXECUTE'
     )
$$;

revoke all on function public.beyoureyes_data_ready()
  from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
