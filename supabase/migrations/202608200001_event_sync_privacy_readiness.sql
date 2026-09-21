-- Preserve the source device in Event upserts without regressing retention-time payload removal.
create or replace function public.beyoureyes_record_event_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_row public.events%rowtype;
  v_monitoring_device_id uuid;
begin
  v_row := case when tg_op = 'DELETE' then old else new end;

  -- auth.users deletion owns the complete account cascade, including sync_changes.
  if tg_op = 'DELETE' and not exists (
    select 1 from auth.users where id = v_row.account_id
  ) then
    return old;
  end if;

  if tg_op = 'DELETE' then
    -- A delete must remove every historical payload-bearing copy before publishing its tombstone.
    delete from public.sync_changes c
    where c.account_id = v_row.account_id
      and c.resource_type = 'event'
      and c.resource_id = v_row.event_id
      and c.operation = 'upsert'
      and c.value is not null;

    insert into public.sync_changes(
      account_id, resource_type, operation, resource_id, value
    ) values (
      v_row.account_id, 'event', 'delete', v_row.event_id, null
    );
    return old;
  end if;

  select monitoring_device_id into strict v_monitoring_device_id
  from public.tasks
  where account_id = v_row.account_id and task_id = v_row.task_id;

  insert into public.sync_changes(account_id, resource_type, operation, resource_id, value)
  values (
    v_row.account_id,
    'event',
    'upsert',
    v_row.event_id,
    jsonb_build_object(
      'schema_version', v_row.schema_version,
      'event_id', v_row.event_id,
      'task_id', v_row.task_id,
      'task_revision', v_row.task_revision,
      'episode_id', v_row.episode_id,
      'source_sequence', v_row.source_sequence,
      'occurred_at', v_row.occurred_at,
      'monitoring_device_id', v_monitoring_device_id,
      'payload', v_row.payload
    )
  );
  return new;
end
$$;

revoke all on function public.beyoureyes_record_event_change()
  from public, anon, authenticated;

-- The final readiness contract is cumulative. Later infrastructure migrations must not erase
-- privacy, retention, account-scope, or client-write checks established by earlier migrations.
create or replace function public.beyoureyes_data_ready()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, cron, realtime
as $$
  select to_regclass('public.devices') is not null
     and to_regclass('public.tasks') is not null
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
     )
     and exists (
       select 1
       from pg_policies
       where schemaname = 'realtime'
         and tablename = 'messages'
         and policyname = 'beyoureyes_snapshot_broadcast_send'
         and cmd = 'INSERT'
     )
     and has_column_privilege('authenticated', 'public.devices', 'display_name', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'task_id', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT')
     and has_table_privilege('authenticated', 'realtime.messages', 'SELECT')
     and has_table_privilege('authenticated', 'realtime.messages', 'INSERT')
     and not has_table_privilege('anon', 'realtime.messages', 'SELECT')
     and not has_table_privilege('anon', 'realtime.messages', 'INSERT')
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
