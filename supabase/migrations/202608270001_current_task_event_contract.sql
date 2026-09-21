-- Deploy the current Task and Event wire contract as a new forward migration.
-- 202608200001 was already applied before these definitions existed; applied migrations stay immutable.
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
  if v_mode = 'object_detection' then
    return p_capability_id = 'visual_target'
       and public.beyoureyes_jsonb_has_exact_keys(
         p_target,
         array['mode', 'target_id', 'label_zh_cn', 'label_en']
       )
       and jsonb_typeof(p_target->'target_id') = 'string'
       and (p_target->>'target_id') ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'
       and jsonb_typeof(p_target->'label_zh_cn') = 'string'
       and char_length(p_target->>'label_zh_cn') between 1 and 40
       and jsonb_typeof(p_target->'label_en') = 'string'
       and char_length(p_target->>'label_en') between 1 and 40;
  elsif v_mode = 'reference_images' then
    return p_capability_id <> 'structured_reading'
       and public.beyoureyes_jsonb_has_exact_keys(p_target, array['mode']);
  elsif v_mode = 'none' then
    return p_capability_id = 'structured_reading'
       and public.beyoureyes_jsonb_has_exact_keys(
         p_target,
         array['mode', 'confirmed_format']
       )
       and public.beyoureyes_confirmed_reading_format_is_valid(
         p_target->'confirmed_format'
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
          'type', 'condition', 'duration_ms', 'min_positive_count',
          'max_positive_gap_ms', 'rearm_absence_ms'
        ]
      )
      and jsonb_typeof(p_rule->'condition') = 'string'
      and p_rule->>'condition' in ('appears', 'remains')
      and public.beyoureyes_jsonb_integer_between(p_rule->'duration_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'min_positive_count', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'max_positive_gap_ms', 1)
      and public.beyoureyes_jsonb_integer_between(p_rule->'rearm_absence_ms', 1);
  elsif v_type = 'absence_duration' then
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array[
          'type', 'condition', 'duration_ms', 'min_negative_count',
          'max_observation_gap_ms', 'rearm_presence_ms'
        ]
      )
      and jsonb_typeof(p_rule->'condition') = 'string'
      and p_rule->>'condition' = 'disappears'
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
            'duration_ms', 'hysteresis_decimal', 'cooldown_ms'
          ]
        )
        and jsonb_typeof(p_rule->'lower_threshold_decimal') = 'string'
        and (p_rule->>'lower_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and jsonb_typeof(p_rule->'upper_threshold_decimal') = 'string'
        and (p_rule->>'upper_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and (p_rule->>'lower_threshold_decimal')::numeric
          < (p_rule->>'upper_threshold_decimal')::numeric
        and public.beyoureyes_jsonb_integer_between(p_rule->'duration_ms', 1)
        and p_rule->>'duration_ms' in ('1000', '3000', '5000', '10000', '30000', '60000')
        and jsonb_typeof(p_rule->'hysteresis_decimal') = 'string'
        and (p_rule->>'hysteresis_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and public.beyoureyes_jsonb_integer_between(p_rule->'cooldown_ms', 0);
    end if;
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array[
          'type', 'operator', 'threshold_decimal',
          'duration_ms', 'hysteresis_decimal', 'cooldown_ms'
        ]
      )
      and v_operator in ('gt', 'gte', 'lt', 'lte', 'eq')
      and jsonb_typeof(p_rule->'threshold_decimal') = 'string'
      and (p_rule->>'threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
      and public.beyoureyes_jsonb_integer_between(p_rule->'duration_ms', 1)
      and p_rule->>'duration_ms' in ('1000', '3000', '5000', '10000', '30000', '60000')
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
exception when others then
  return false;
end
$$;

create or replace function public.beyoureyes_task_route_binding_is_valid(p_route jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
  select coalesce(
    public.beyoureyes_jsonb_has_exact_keys(
      p_route,
      array['model_profile_key', 'recipe_id', 'intent_key']
    )
      and jsonb_typeof(p_route->'model_profile_key') = 'string'
      and (p_route->>'model_profile_key') ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'
      and jsonb_typeof(p_route->'recipe_id') = 'string'
      and (p_route->>'recipe_id') ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'
      and jsonb_typeof(p_route->'intent_key') = 'string'
      and (p_route->>'intent_key') ~ '^[a-z0-9][a-z0-9_-]*(\.[a-z0-9][a-z0-9_-]*)*$',
    false
  )
$$;

create or replace function public.beyoureyes_task_package_binding_is_valid(p_package jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
  select coalesce(
    public.beyoureyes_jsonb_has_exact_keys(
      p_package,
      array[
        'package_id', 'package_version', 'manifest_sha256', 'access_tier',
        'artifact_identity_sha256'
      ]
    )
      and jsonb_typeof(p_package->'package_id') = 'string'
      and (p_package->>'package_id') ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'
      and jsonb_typeof(p_package->'package_version') = 'string'
      and (p_package->>'package_version') ~ '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
      and jsonb_typeof(p_package->'manifest_sha256') = 'string'
      and (p_package->>'manifest_sha256') ~ '^[0-9a-f]{64}$'
      and jsonb_typeof(p_package->'access_tier') = 'string'
      and p_package->>'access_tier' in ('free', 'subscription')
      and jsonb_typeof(p_package->'artifact_identity_sha256') = 'string'
      and (p_package->>'artifact_identity_sha256') ~ '^[0-9a-f]{64}$',
    false
  )
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
      array[
        'target_definition', 'roi', 'sampling_policy', 'rule',
        'route_binding', 'package_binding'
      ]
    )
      and public.beyoureyes_task_target_is_valid(
        p_capability_id,
        p_config->'target_definition'
      )
      and public.beyoureyes_task_roi_is_valid(p_config->'roi')
      and public.beyoureyes_task_sampling_is_valid(p_config->'sampling_policy')
      and public.beyoureyes_task_rule_is_valid(p_capability_id, p_config->'rule')
      and public.beyoureyes_task_route_binding_is_valid(p_config->'route_binding')
      and public.beyoureyes_task_package_binding_is_valid(p_config->'package_binding'),
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
        'task_id', 'revision', 'catalog_version', 'capability_id', 'title',
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
      and jsonb_typeof(p_task->'title') = 'string'
      and char_length(p_task->>'title') between 1 and 100
      and public.beyoureyes_uuid_v7_is_valid(p_task->'monitoring_device_id')
      and public.beyoureyes_task_config_is_valid(
        p_task->>'capability_id',
        p_task->'config'
      ),
    false
  )
$$;

revoke all on function public.beyoureyes_task_rule_is_valid(text, jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_target_is_valid(text, jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_task_rule_is_valid(text, jsonb)
  to service_role;
grant execute on function public.beyoureyes_task_target_is_valid(text, jsonb)
  to service_role;
revoke all on function public.beyoureyes_task_route_binding_is_valid(jsonb)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_task_package_binding_is_valid(jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_task_route_binding_is_valid(jsonb),
  public.beyoureyes_task_package_binding_is_valid(jsonb) to service_role;
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
      title,
      monitoring_device_id,
      config
    ) values (
      (v_task->>'task_id')::uuid,
      v_account_id,
      (v_task->>'revision')::bigint,
      v_task->>'catalog_version',
      v_task->>'capability_id',
      v_task->>'title',
      (v_task->>'monitoring_device_id')::uuid,
      v_task->'config'
    )
    on conflict (task_id) do update set
      revision = excluded.revision,
      catalog_version = excluded.catalog_version,
      capability_id = excluded.capability_id,
      title = excluded.title,
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
           or v_existing.title <> v_task->>'title'
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

  if p_payload->>'type' = 'visual_condition_met' then
    if not public.beyoureyes_jsonb_has_exact_keys(
      p_payload,
      array['type', 'target_id', 'condition', 'duration_ms']
    )
       or jsonb_typeof(p_payload->'target_id') <> 'string'
       or char_length(p_payload->>'target_id') < 1
       or jsonb_typeof(p_payload->'condition') <> 'string'
       or p_payload->>'condition' not in ('present_for_duration', 'absent_for_duration')
       or jsonb_typeof(p_payload->'duration_ms') <> 'number'
    then
      return false;
    end if;
    v_duration := (p_payload->>'duration_ms')::numeric;
    return v_duration > 0 and v_duration = trunc(v_duration);
  end if;

  if p_payload->>'type' = 'reading_threshold_crossed' then
    if jsonb_typeof(p_payload->'operator') <> 'string'
       or not public.beyoureyes_structured_reading_is_valid(p_payload->'reading')
       or p_payload->'reading'->>'status' <> 'stable'
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
        and (p_payload->>'upper_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and (p_payload->>'lower_threshold_decimal')::numeric
          < (p_payload->>'upper_threshold_decimal')::numeric;
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

revoke all on function public.beyoureyes_client_insert_event_batch(jsonb)
  from public, anon, service_role;
grant execute on function public.beyoureyes_client_insert_event_batch(jsonb)
  to authenticated;

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
