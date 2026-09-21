-- Persisted Event payloads are a closed contract, not an arbitrary JSON upload surface.
-- Keep this validation in PostgreSQL because authenticated clients are not trusted.

create or replace function public.beyoureyes_jsonb_has_exact_keys(
  p_value jsonb,
  p_keys text[]
)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog
as $$
  select coalesce(
    jsonb_typeof(p_value) = 'object'
     and not exists (
       select 1 from jsonb_object_keys(p_value) as actual(key)
       where not (actual.key = any(p_keys))
     )
     and not exists (
       select 1 from unnest(p_keys) as required(key)
       where not (p_value ? required.key)
     ),
    false
  )
$$;

revoke all on function public.beyoureyes_jsonb_has_exact_keys(jsonb, text[])
  from public, anon, authenticated;
grant execute on function public.beyoureyes_jsonb_has_exact_keys(jsonb, text[])
  to service_role;

create or replace function public.beyoureyes_numeric_reading_is_valid(p_reading jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_confidence numeric;
begin
  if not public.beyoureyes_jsonb_has_exact_keys(
    p_reading,
    array['type', 'status', 'value_decimal', 'unit', 'confidence', 'source_kind', 'observed_at']
  ) then
    return false;
  end if;
  if jsonb_typeof(p_reading->'type') <> 'string'
     or p_reading->>'type' <> 'numeric_reading'
     or jsonb_typeof(p_reading->'status') <> 'string'
     or p_reading->>'status' not in ('candidate', 'stable')
     or jsonb_typeof(p_reading->'value_decimal') <> 'string'
     or (p_reading->>'value_decimal') !~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
     or jsonb_typeof(p_reading->'source_kind') <> 'string'
     or p_reading->>'source_kind' not in ('digital_display', 'counter', 'analog_dial')
     or jsonb_typeof(p_reading->'observed_at') <> 'string'
     or (p_reading->>'observed_at') !~
       '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
  then
    return false;
  end if;
  if jsonb_typeof(p_reading->'unit') not in ('string', 'null')
     or (
       jsonb_typeof(p_reading->'unit') = 'string'
       and char_length(p_reading->>'unit') > 32
     )
  then
    return false;
  end if;
  if jsonb_typeof(p_reading->'confidence') <> 'number' then
    return false;
  end if;
  v_confidence := (p_reading->>'confidence')::numeric;
  return v_confidence between 0 and 1;
end
$$;

revoke all on function public.beyoureyes_numeric_reading_is_valid(jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_numeric_reading_is_valid(jsonb)
  to service_role;

create or replace function public.beyoureyes_event_payload_is_valid(p_payload jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_duration numeric;
  v_count numeric;
begin
  if jsonb_typeof(p_payload) <> 'object'
     or jsonb_typeof(p_payload->'type') <> 'string'
  then
    return false;
  end if;

  if p_payload->>'type' = 'object_episode' then
    if not public.beyoureyes_jsonb_has_exact_keys(
      p_payload,
      array['type', 'target_id', 'condition', 'duration_ms', 'count']
    )
       or jsonb_typeof(p_payload->'target_id') <> 'string'
       or char_length(p_payload->>'target_id') < 1
       or jsonb_typeof(p_payload->'condition') <> 'string'
       or p_payload->>'condition' not in ('appeared', 'disappeared', 'count_matched')
       or jsonb_typeof(p_payload->'duration_ms') <> 'number'
       or jsonb_typeof(p_payload->'count') <> 'number'
    then
      return false;
    end if;
    v_duration := (p_payload->>'duration_ms')::numeric;
    v_count := (p_payload->>'count')::numeric;
    return v_duration >= 0 and v_duration = trunc(v_duration)
       and v_count >= 0 and v_count = trunc(v_count);
  end if;

  if p_payload->>'type' = 'reading_threshold_crossed' then
    if jsonb_typeof(p_payload->'operator') <> 'string'
       or not public.beyoureyes_numeric_reading_is_valid(p_payload->'reading')
    then
      return false;
    end if;
    if p_payload->>'operator' = 'outside' then
      return public.beyoureyes_jsonb_has_exact_keys(
          p_payload,
          array['type', 'reading', 'operator', 'lower_threshold_decimal', 'upper_threshold_decimal']
        )
        and jsonb_typeof(p_payload->'lower_threshold_decimal') = 'string'
        and (p_payload->>'lower_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and jsonb_typeof(p_payload->'upper_threshold_decimal') = 'string'
        and (p_payload->>'upper_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$';
    end if;
    return public.beyoureyes_jsonb_has_exact_keys(
        p_payload,
        array['type', 'reading', 'operator', 'threshold_decimal']
      )
      and p_payload->>'operator' in ('gt', 'gte', 'lt', 'lte', 'eq')
      and jsonb_typeof(p_payload->'threshold_decimal') = 'string'
      and (p_payload->>'threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$';
  end if;

  if p_payload->>'type' = 'state_transition' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_payload,
        array['type', 'from_state', 'to_state']
      )
      and jsonb_typeof(p_payload->'from_state') = 'string'
      and char_length(p_payload->>'from_state') >= 1
      and jsonb_typeof(p_payload->'to_state') = 'string'
      and char_length(p_payload->>'to_state') >= 1;
  end if;

  return false;
end
$$;

revoke all on function public.beyoureyes_event_payload_is_valid(jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_event_payload_is_valid(jsonb)
  to service_role;

alter table public.events
  drop constraint if exists events_payload_contract_valid;
alter table public.events
  add constraint events_payload_contract_valid
  check (public.beyoureyes_event_payload_is_valid(payload));

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
  if not public.beyoureyes_event_payload_is_valid(new.payload) then
    raise exception 'event_payload_invalid' using errcode = 'P0001';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_validate_event()
  from public, anon, authenticated;

-- Hosted readiness must stay unavailable when this privacy migration was skipped.
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
     and to_regclass('public.device_push_tokens') is not null
     and to_regclass('public.push_outbox') is not null
     and to_regprocedure('public.beyoureyes_client_insert_event_batch(jsonb)') is not null
     and to_regprocedure('public.beyoureyes_client_add_event_receipt(uuid,uuid,text,timestamp with time zone)') is not null
     and to_regprocedure('public.beyoureyes_client_register_push_token(uuid,text)') is not null
     and to_regprocedure('public.beyoureyes_claim_push_batch(integer,uuid,timestamp with time zone)') is not null
     and to_regprocedure('public.beyoureyes_event_payload_is_valid(jsonb)') is not null
     and exists (
       select 1
       from pg_constraint
       where conrelid = 'public.events'::regclass
         and conname = 'events_payload_contract_valid'
         and contype = 'c'
     )
     and has_column_privilege('authenticated', 'public.devices', 'display_name', 'INSERT')
     and has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT')
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
$$;

revoke all on function public.beyoureyes_data_ready()
  from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
