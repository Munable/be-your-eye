-- A Task revision is created locally and is part of the immutable Event evidence contract.
-- Authenticated clients therefore upload Tasks through one atomic, account-derived batch RPC.
-- The cloud row reaches the supplied local revision on first upload/advance and never regresses.

-- Direct authenticated Task writes cannot safely express an offline revision jump. Keep DELETE
-- for local deletion reconciliation, but require the batch RPC for INSERT/UPDATE.
revoke insert, update on public.tasks from authenticated;

-- The legacy service-role repository still uses direct SQL writes. Preserve its normal +1
-- behavior while allowing the authenticated batch RPC to insert/advance to a larger supplied
-- revision. Direct authenticated writes remain revoked above.
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
    new.revision := greatest(new.revision, 1);
    new.created_at := clock_timestamp();
    new.updated_at := new.created_at;
  else
    new.account_id := old.account_id;
    new.task_id := old.task_id;
    new.revision := greatest(old.revision + 1, new.revision);
    new.created_at := old.created_at;
    new.updated_at := clock_timestamp();
  end if;
  if new.account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return new;
end
$$;

revoke all on function public.beyoureyes_prepare_task_write()
  from public, anon, authenticated;

create or replace function public.beyoureyes_uuid_v7_is_valid(p_value jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog
as $$
  select coalesce(
    jsonb_typeof(p_value) = 'string'
      and (p_value #>> '{}') ~
        '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    false
  )
$$;

create or replace function public.beyoureyes_jsonb_integer_between(
  p_value jsonb,
  p_min numeric,
  p_max numeric default null
)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog
as $$
declare
  v_value numeric;
begin
  if jsonb_typeof(p_value) <> 'number' then
    return false;
  end if;
  v_value := (p_value #>> '{}')::numeric;
  return v_value = trunc(v_value)
     and v_value >= p_min
     and (p_max is null or v_value <= p_max);
exception when others then
  return false;
end
$$;

create or replace function public.beyoureyes_task_target_is_valid(
  p_capability_id text,
  p_target jsonb
)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_mode text;
begin
  if jsonb_typeof(p_target) <> 'object'
     or jsonb_typeof(p_target->'mode') <> 'string'
  then
    return false;
  end if;
  v_mode := p_target->>'mode';
  if v_mode = 'text' then
    return p_capability_id <> 'structured_reading'
       and public.beyoureyes_jsonb_has_exact_keys(p_target, array['mode', 'text'])
       and jsonb_typeof(p_target->'text') = 'string'
       and char_length(p_target->>'text') between 1 and 200
       and (p_target->>'text') ~ '\S';
  elsif v_mode = 'reference_images' then
    return p_capability_id <> 'structured_reading'
       and public.beyoureyes_jsonb_has_exact_keys(p_target, array['mode']);
  elsif v_mode = 'none' then
    return p_capability_id = 'structured_reading'
       and public.beyoureyes_jsonb_has_exact_keys(p_target, array['mode']);
  end if;
  return false;
end
$$;

create or replace function public.beyoureyes_task_roi_is_valid(p_roi jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_left numeric;
  v_top numeric;
  v_right numeric;
  v_bottom numeric;
begin
  if not public.beyoureyes_jsonb_has_exact_keys(
    p_roi,
    array['left', 'top', 'right', 'bottom']
  )
     or jsonb_typeof(p_roi->'left') <> 'number'
     or jsonb_typeof(p_roi->'top') <> 'number'
     or jsonb_typeof(p_roi->'right') <> 'number'
     or jsonb_typeof(p_roi->'bottom') <> 'number'
  then
    return false;
  end if;
  v_left := (p_roi->>'left')::numeric;
  v_top := (p_roi->>'top')::numeric;
  v_right := (p_roi->>'right')::numeric;
  v_bottom := (p_roi->>'bottom')::numeric;
  return v_left between 0 and 1
     and v_top between 0 and 1
     and v_right between 0 and 1
     and v_bottom between 0 and 1
     and v_left < v_right
     and v_top < v_bottom;
exception when others then
  return false;
end
$$;

create or replace function public.beyoureyes_task_sampling_is_valid(p_sampling jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_mode text;
begin
  if jsonb_typeof(p_sampling) <> 'object'
     or jsonb_typeof(p_sampling->'mode') <> 'string'
  then
    return false;
  end if;
  v_mode := p_sampling->>'mode';
  if v_mode = 'package_default' then
    return public.beyoureyes_jsonb_has_exact_keys(p_sampling, array['mode']);
  elsif v_mode = 'fixed_interval' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_sampling,
        array['mode', 'interval_ms']
      )
      and public.beyoureyes_jsonb_integer_between(p_sampling->'interval_ms', 50, 10000);
  elsif v_mode = 'adaptive' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_sampling,
        array['mode', 'preferred_interval_ms']
      )
      and public.beyoureyes_jsonb_integer_between(
        p_sampling->'preferred_interval_ms',
        50,
        10000
      );
  end if;
  return false;
end
$$;

create or replace function public.beyoureyes_task_rule_is_valid(
  p_capability_id text,
  p_rule jsonb
)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_type text;
  v_operator text;
begin
  if jsonb_typeof(p_rule) <> 'object'
     or jsonb_typeof(p_rule->'type') <> 'string'
  then
    return false;
  end if;
  v_type := p_rule->>'type';
  if p_capability_id = 'structured_reading' and v_type <> 'reading_threshold' then
    return false;
  end if;

  if v_type = 'presence_duration' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array[
          'type', 'duration_ms', 'min_positive_count',
          'max_positive_gap_ms', 'rearm_absence_ms'
        ]
      )
      and public.beyoureyes_jsonb_integer_between(p_rule->'duration_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'min_positive_count', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'max_positive_gap_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'rearm_absence_ms', 1);
  elsif v_type = 'absence_duration' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array[
          'type', 'duration_ms', 'min_negative_count',
          'max_observation_gap_ms', 'rearm_presence_ms'
        ]
      )
      and public.beyoureyes_jsonb_integer_between(p_rule->'duration_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'min_negative_count', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'max_observation_gap_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'rearm_presence_ms', 1);
  elsif v_type = 'object_count' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array['type', 'operator', 'count', 'duration_ms', 'cooldown_ms']
      )
      and jsonb_typeof(p_rule->'operator') = 'string'
      and p_rule->>'operator' in ('eq', 'gte', 'lte')
      and public.beyoureyes_jsonb_integer_between(p_rule->'count', 0)
      and public.beyoureyes_jsonb_integer_between(p_rule->'duration_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'cooldown_ms', 0);
  elsif v_type = 'reading_threshold' then
    if jsonb_typeof(p_rule->'operator') <> 'string' then
      return false;
    end if;
    v_operator := p_rule->>'operator';
    if v_operator = 'outside' then
      return public.beyoureyes_jsonb_has_exact_keys(
          p_rule,
          array[
            'type', 'operator', 'lower_threshold_decimal', 'upper_threshold_decimal',
            'stable_frames', 'hysteresis_decimal', 'cooldown_ms'
          ]
        )
        and jsonb_typeof(p_rule->'lower_threshold_decimal') = 'string'
        and (p_rule->>'lower_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and jsonb_typeof(p_rule->'upper_threshold_decimal') = 'string'
        and (p_rule->>'upper_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and public.beyoureyes_jsonb_integer_between(p_rule->'stable_frames', 1)
        and jsonb_typeof(p_rule->'hysteresis_decimal') = 'string'
        and (p_rule->>'hysteresis_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and public.beyoureyes_jsonb_integer_between(p_rule->'cooldown_ms', 0);
    end if;
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array[
          'type', 'operator', 'threshold_decimal', 'stable_frames',
          'hysteresis_decimal', 'cooldown_ms'
        ]
      )
      and v_operator in ('gt', 'gte', 'lt', 'lte', 'eq')
      and jsonb_typeof(p_rule->'threshold_decimal') = 'string'
      and (p_rule->>'threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
      and public.beyoureyes_jsonb_integer_between(p_rule->'stable_frames', 1)
      and jsonb_typeof(p_rule->'hysteresis_decimal') = 'string'
      and (p_rule->>'hysteresis_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
      and public.beyoureyes_jsonb_integer_between(p_rule->'cooldown_ms', 0);
  elsif v_type = 'state_transition' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array['type', 'from_state', 'to_state', 'stable_frames', 'cooldown_ms']
      )
      and jsonb_typeof(p_rule->'from_state') = 'string'
      and char_length(p_rule->>'from_state') >= 1
      and jsonb_typeof(p_rule->'to_state') = 'string'
      and char_length(p_rule->>'to_state') >= 1
      and public.beyoureyes_jsonb_integer_between(p_rule->'stable_frames', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'cooldown_ms', 0);
  end if;
  return false;
end
$$;

create or replace function public.beyoureyes_task_schedule_is_valid(p_schedule jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_period jsonb;
begin
  if not public.beyoureyes_jsonb_has_exact_keys(
    p_schedule,
    array['time_zone', 'active_periods']
  )
     or jsonb_typeof(p_schedule->'time_zone') <> 'string'
     or char_length(p_schedule->>'time_zone') < 1
     or jsonb_typeof(p_schedule->'active_periods') <> 'array'
  then
    return false;
  end if;

  for v_period in select value from jsonb_array_elements(p_schedule->'active_periods')
  loop
    if not public.beyoureyes_jsonb_has_exact_keys(
      v_period,
      array['days', 'start_local', 'end_local']
    )
       or jsonb_typeof(v_period->'days') <> 'array'
       or jsonb_array_length(v_period->'days') < 1
       or jsonb_typeof(v_period->'start_local') <> 'string'
       or (v_period->>'start_local') !~ '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$'
       or jsonb_typeof(v_period->'end_local') <> 'string'
       or (v_period->>'end_local') !~ '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$'
       or exists (
         select 1 from jsonb_array_elements(v_period->'days') as day(value)
         where not public.beyoureyes_jsonb_integer_between(day.value, 1, 7)
       )
       or exists (
         select 1
         from jsonb_array_elements(v_period->'days') as day(value)
         group by day.value
         having count(*) > 1
       )
    then
      return false;
    end if;
  end loop;
  return true;
end
$$;

create or replace function public.beyoureyes_task_config_is_valid(
  p_capability_id text,
  p_config jsonb
)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
  select coalesce(
    public.beyoureyes_jsonb_has_exact_keys(
      p_config,
      array['target_definition', 'roi', 'sampling_policy', 'rule', 'schedule']
    )
      and public.beyoureyes_task_target_is_valid(
        p_capability_id,
        p_config->'target_definition'
      )
      and public.beyoureyes_task_roi_is_valid(p_config->'roi')
      and public.beyoureyes_task_sampling_is_valid(p_config->'sampling_policy')
      and public.beyoureyes_task_rule_is_valid(p_capability_id, p_config->'rule')
      and public.beyoureyes_task_schedule_is_valid(p_config->'schedule'),
    false
  )
$$;

create or replace function public.beyoureyes_task_write_is_valid(p_task jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
  select coalesce(
    public.beyoureyes_jsonb_has_exact_keys(
      p_task,
      array[
        'task_id', 'revision', 'catalog_version', 'capability_id', 'target_id',
        'monitoring_device_id', 'config'
      ]
    )
      and public.beyoureyes_uuid_v7_is_valid(p_task->'task_id')
      and public.beyoureyes_jsonb_integer_between(
        p_task->'revision',
        1,
        9223372036854775807
      )
      and jsonb_typeof(p_task->'catalog_version') = 'string'
      and char_length(p_task->>'catalog_version') >= 1
      and jsonb_typeof(p_task->'capability_id') = 'string'
      and p_task->>'capability_id' in (
        'visual_target', 'visible_state', 'structured_reading'
      )
      and jsonb_typeof(p_task->'target_id') = 'string'
      and char_length(p_task->>'target_id') between 1 and 100
      and public.beyoureyes_uuid_v7_is_valid(p_task->'monitoring_device_id')
      and public.beyoureyes_task_config_is_valid(
        p_task->>'capability_id',
        p_task->'config'
      ),
    false
  )
$$;

revoke all on function public.beyoureyes_uuid_v7_is_valid(jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_jsonb_integer_between(jsonb, numeric, numeric)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_target_is_valid(text, jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_roi_is_valid(jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_sampling_is_valid(jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_rule_is_valid(text, jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_schedule_is_valid(jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_config_is_valid(text, jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_write_is_valid(jsonb)
  from public, anon, authenticated;

create or replace function public.beyoureyes_client_upsert_task_batch(p_tasks jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_account_id uuid := auth.uid();
  v_task jsonb;
  v_existing public.tasks%rowtype;
  v_written integer;
  v_current_revision bigint;
  v_upserted jsonb := '[]'::jsonb;
  v_unchanged jsonb := '[]'::jsonb;
  v_revisions jsonb := '[]'::jsonb;
begin
  if v_account_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  if jsonb_typeof(p_tasks) <> 'array'
     or jsonb_array_length(p_tasks) not between 1 and 100
  then
    raise exception 'invalid_task_batch' using errcode = '22023';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_tasks) as task(value)
    where not public.beyoureyes_task_write_is_valid(task.value)
  ) then
    raise exception 'invalid_task_contract' using errcode = '22023';
  end if;
  if exists (
    select 1
    from jsonb_array_elements(p_tasks) as task(value)
    group by task.value->>'task_id'
    having count(*) > 1
  ) then
    raise exception 'duplicate_batch_task_id' using errcode = '22023';
  end if;

  for v_task in select value from jsonb_array_elements(p_tasks)
  loop
    if not exists (
      select 1 from public.devices d
      where d.account_id = v_account_id
        and d.device_id = (v_task->>'monitoring_device_id')::uuid
        and d.revoked_at is null
    ) then
      raise exception 'invalid_monitoring_device' using errcode = 'P0001';
    end if;

    insert into public.tasks(
      task_id,
      account_id,
      revision,
      catalog_version,
      capability_id,
      target_id,
      monitoring_device_id,
      config
    ) values (
      (v_task->>'task_id')::uuid,
      v_account_id,
      (v_task->>'revision')::bigint,
      v_task->>'catalog_version',
      v_task->>'capability_id',
      v_task->>'target_id',
      (v_task->>'monitoring_device_id')::uuid,
      v_task->'config'
    )
    on conflict (task_id) do update set
      revision = excluded.revision,
      catalog_version = excluded.catalog_version,
      capability_id = excluded.capability_id,
      target_id = excluded.target_id,
      monitoring_device_id = excluded.monitoring_device_id,
      config = excluded.config
    where public.tasks.account_id = v_account_id
      and excluded.revision > public.tasks.revision
    returning revision into v_current_revision;
    get diagnostics v_written = row_count;

    if v_written = 1 then
      v_upserted := v_upserted || jsonb_build_array(v_task->>'task_id');
    else
      select * into v_existing
      from public.tasks
      where task_id = (v_task->>'task_id')::uuid
      for update;
      if not found or v_existing.account_id <> v_account_id then
        raise exception 'task_id_conflict' using errcode = 'P0001';
      end if;
      v_current_revision := v_existing.revision;
      if v_existing.revision = (v_task->>'revision')::bigint
         and (
           v_existing.catalog_version <> v_task->>'catalog_version'
           or v_existing.capability_id <> v_task->>'capability_id'
           or v_existing.target_id <> v_task->>'target_id'
           or v_existing.monitoring_device_id <> (v_task->>'monitoring_device_id')::uuid
           or v_existing.config <> v_task->'config'
         )
      then
        raise exception 'task_revision_conflict' using errcode = 'P0001';
      end if;
      v_unchanged := v_unchanged || jsonb_build_array(v_task->>'task_id');
    end if;
    v_revisions := v_revisions || jsonb_build_array(jsonb_build_object(
      'task_id', v_task->>'task_id',
      'revision', v_current_revision
    ));
  end loop;

  return jsonb_build_object(
    'upserted_ids', v_upserted,
    'unchanged_ids', v_unchanged,
    'task_revisions', v_revisions
  );
end
$$;

revoke all on function public.beyoureyes_client_upsert_task_batch(jsonb)
  from public, anon, service_role;
grant execute on function public.beyoureyes_client_upsert_task_batch(jsonb)
  to authenticated;

-- Hosted readiness is fail-closed when an older direct-Task-write schema is deployed.
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
