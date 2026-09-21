-- Authenticated device revocation stays entirely in Supabase.
-- A revoked device may never reactivate itself by replaying the normal device upsert.

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
    new.revoked_at := null;
    new.created_at := clock_timestamp();
    new.updated_at := new.created_at;
  else
    new.account_id := old.account_id;
    new.device_id := old.device_id;
    new.revision := old.revision + 1;
    new.created_at := old.created_at;
    new.updated_at := clock_timestamp();
    if old.revoked_at is not null then
      new.revoked_at := old.revoked_at;
    elsif new.revoked_at is not null then
      new.revoked_at := new.updated_at;
    end if;
  end if;
  if new.account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_prepare_device_write() from public, anon, authenticated;

create or replace function public.beyoureyes_client_revoke_device(p_device_id uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_account_id uuid := auth.uid();
  v_changed boolean;
begin
  if v_account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;

  update public.devices
  set revoked_at = clock_timestamp()
  where account_id = v_account_id
    and device_id = p_device_id
    and revoked_at is null;
  v_changed := found;

  if v_changed then
    delete from public.device_push_tokens
    where account_id = v_account_id and device_id = p_device_id;

    delete from public.push_outbox
    where account_id = v_account_id and device_id = p_device_id;
  end if;

  return v_changed;
end
$$;

revoke all on function public.beyoureyes_client_revoke_device(uuid)
  from public, anon, service_role;
grant execute on function public.beyoureyes_client_revoke_device(uuid)
  to authenticated;

-- Hosted readiness proves that the schema includes the revocation contract.
create or replace function public.beyoureyes_data_ready()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, cron
as $$
  select to_regclass('public.devices') is not null
     and to_regclass('public.tasks') is not null
     and to_regclass('public.events') is not null
     and to_regclass('public.event_receipts') is not null
     and to_regclass('public.sync_changes') is not null
     and to_regclass('public.device_push_tokens') is not null
     and to_regclass('public.push_outbox') is not null
     and to_regclass('cron.job') is not null
     and to_regprocedure('public.beyoureyes_client_upsert_task_batch(jsonb)') is not null
     and to_regprocedure('public.beyoureyes_client_revoke_device(uuid)') is not null
     and to_regprocedure('public.beyoureyes_client_insert_event_batch(jsonb)') is not null
     and to_regprocedure('public.beyoureyes_client_add_event_receipt(uuid,uuid,text,timestamp with time zone)') is not null
     and to_regprocedure('public.beyoureyes_client_register_push_token(uuid,text)') is not null
     and to_regprocedure('public.beyoureyes_claim_push_batch(integer,uuid,timestamp with time zone)') is not null
     and to_regprocedure('public.beyoureyes_event_payload_is_valid(jsonb)') is not null
     and to_regprocedure(
       'public.beyoureyes_purge_expired_events(uuid,timestamp with time zone,integer)'
     ) is not null
     and exists (
       select 1
       from pg_constraint
       where conrelid = 'public.events'::regclass
         and conname = 'events_payload_contract_valid'
         and contype = 'c'
     )
     and exists (
       select 1
       from cron.job
       where jobname = 'beyoureyes-event-retention-hourly-v1'
         and schedule = '17 * * * *'
         and command = 'select public.beyoureyes_purge_expired_events();'
         and active
     )
     and has_column_privilege('authenticated', 'public.devices', 'display_name', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'task_id', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT')
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
     and not has_function_privilege(
       'authenticated',
       'public.beyoureyes_purge_expired_events(uuid,timestamp with time zone,integer)',
       'EXECUTE'
     )
     and has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_register_push_token(uuid,text)',
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
$$;

revoke all on function public.beyoureyes_data_ready()
  from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
