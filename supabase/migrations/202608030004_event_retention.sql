-- Cloud Events retain their payload for 30 days from server receipt. Local Room data is not
-- affected. Payload-free delete tombstones remain so a phone that was offline for longer than
-- the retention window can advance its append-only cursor without replaying expired content.

create index if not exists events_retention_received_idx
  on public.events(received_at, event_sequence);

create index if not exists sync_changes_event_resource_idx
  on public.sync_changes(account_id, resource_id, sequence)
  where resource_type = 'event';

-- Event deletion must redact every historical sync upsert before appending the tombstone. This
-- applies to retention, task cascades and explicit service-role cleanup alike.
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

  -- auth.users deletion owns a full account cascade, including sync_changes. Appending a child
  -- tombstone after that parent disappeared would violate the account foreign key.
  if tg_op = 'DELETE' and not exists (
    select 1 from auth.users where id = v_row.account_id
  ) then
    return old;
  end if;

  if tg_op = 'DELETE' then
    delete from public.sync_changes c
    where c.account_id = v_row.account_id
      and c.resource_type = 'event'
      and c.resource_id = v_row.event_id
      and c.value is not null;

    insert into public.sync_changes(
      account_id, resource_type, operation, resource_id, value
    ) values (
      v_row.account_id, 'event', 'delete', v_row.event_id, null
    );
    return old;
  end if;

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
      'payload', v_row.payload
    )
  );
  return new;
end
$$;

revoke all on function public.beyoureyes_record_event_change()
  from public, anon, authenticated;

-- Remove payload copies left by Event rows that were deleted before this migration existed.
delete from public.sync_changes c
where c.resource_type = 'event'
  and c.value is not null
  and not exists (
    select 1
    from public.events e
    where e.account_id = c.account_id and e.event_id = c.resource_id
  );

-- An expired Event ID is consumed permanently for the lifetime of the account. A delayed retry
-- is reported as a duplicate and cannot resurrect payload after the retention job deleted it.
create or replace function public.beyoureyes_insert_event_batch(p_account_id uuid, p_events jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_event jsonb;
  v_event_id uuid;
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
    v_event_id := (v_event->>'event_id')::uuid;

    if exists (
      select 1
      from public.sync_changes c
      where c.account_id = p_account_id
        and c.resource_type = 'event'
        and c.operation = 'delete'
        and c.resource_id = v_event_id
        and c.value is null
    ) then
      v_duplicates := v_duplicates || jsonb_build_array(v_event->>'event_id');
      continue;
    end if;

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
      v_event_id,
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
      where account_id = p_account_id and event_id = v_event_id;
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

revoke all on function public.beyoureyes_insert_event_batch(uuid, jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_insert_event_batch(uuid, jsonb)
  to service_role;

-- p_account_id and p_now are service-only test seams. The scheduled call uses their safe
-- defaults and deletes at most 1000 rows per hour so one Cron run remains bounded.
create or replace function public.beyoureyes_purge_expired_events(
  p_account_id uuid default null,
  p_now timestamptz default clock_timestamp(),
  p_limit integer default 1000
)
returns integer
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_deleted integer;
begin
  if p_now is null or p_limit not between 1 and 10000 then
    raise exception 'invalid_event_retention_request' using errcode = '22023';
  end if;

  with expired as materialized (
    select e.account_id, e.event_id
    from public.events e
    where e.received_at < p_now - interval '30 days'
      and (p_account_id is null or e.account_id = p_account_id)
    order by e.received_at, e.event_sequence
    limit p_limit
    for update skip locked
  ), deleted as (
    delete from public.events e
    using expired x
    where e.account_id = x.account_id and e.event_id = x.event_id
    returning 1
  )
  select count(*)::integer into v_deleted from deleted;

  return v_deleted;
end
$$;

revoke all on function public.beyoureyes_purge_expired_events(uuid, timestamptz, integer)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_purge_expired_events(uuid, timestamptz, integer)
  to service_role;

-- Supabase Cron is backed by pg_cron. Tests may install a narrow cron.schedule fixture first;
-- hosted projects enable the actual extension here. Scheduling the same case-sensitive name is
-- an upsert, so restoring/reapplying infrastructure does not create duplicate jobs.
do $migration$
begin
  if to_regprocedure('cron.schedule(text,text,text)') is null then
    execute 'create extension if not exists pg_cron';
  end if;
  if to_regprocedure('cron.schedule(text,text,text)') is null then
    raise exception 'pg_cron_schedule_unavailable';
  end if;
end
$migration$;

select cron.schedule(
  'beyoureyes-event-retention-hourly-v1',
  '17 * * * *',
  'select public.beyoureyes_purge_expired_events();'
);

-- Readiness is fail-closed when the policy function or active scheduler is absent.
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
     and has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT')
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
