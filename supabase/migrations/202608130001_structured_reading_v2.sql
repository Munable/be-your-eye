-- Structured reading v2 is intentionally incompatible with the former numeric-only shape.
-- Internal candidates are installed with cleared app data; no legacy payload is rewritten.

create or replace function public.beyoureyes_confirmed_reading_format_is_valid(p_format jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = pg_catalog, public
as $$
declare
  v_fractional numeric;
  v_segments numeric;
begin
  if not public.beyoureyes_jsonb_has_exact_keys(
    p_format,
    array['profile_id', 'kind', 'fractional_digits', 'time_segments', 'unit']
  )
     or jsonb_typeof(p_format->'profile_id') <> 'string'
     or p_format->>'profile_id' <> 'confirmed_reading_format_v2'
     or jsonb_typeof(p_format->'kind') <> 'string'
     or p_format->>'kind' not in ('decimal', 'percent', 'scientific', 'time')
     or jsonb_typeof(p_format->'fractional_digits') <> 'number'
     or jsonb_typeof(p_format->'unit') not in ('string', 'null')
  then
    return false;
  end if;
  v_fractional := (p_format->>'fractional_digits')::numeric;
  if v_fractional <> trunc(v_fractional) or v_fractional not between 0 and 12 then
    return false;
  end if;
  if jsonb_typeof(p_format->'unit') = 'string'
     and char_length(p_format->>'unit') not between 1 and 8
  then
    return false;
  end if;
  if p_format->>'kind' = 'time' then
    if jsonb_typeof(p_format->'time_segments') <> 'number'
       or jsonb_typeof(p_format->'unit') <> 'null'
       or v_fractional <> 0
    then
      return false;
    end if;
    v_segments := (p_format->>'time_segments')::numeric;
    return v_segments = trunc(v_segments) and v_segments between 2 and 3;
  end if;
  if p_format->>'kind' = 'percent' and p_format->>'unit' is distinct from '%' then
    return false;
  end if;
  return jsonb_typeof(p_format->'time_segments') = 'null';
exception when others then
  return false;
end
$$;

revoke all on function public.beyoureyes_confirmed_reading_format_is_valid(jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_confirmed_reading_format_is_valid(jsonb)
  to service_role;

create or replace function public.beyoureyes_structured_reading_is_valid(p_reading jsonb)
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
    array[
      'type', 'status', 'display_text', 'value_decimal', 'format', 'unit',
      'confidence', 'source_kind', 'observed_at'
    ]
  )
     or jsonb_typeof(p_reading->'type') <> 'string'
     or p_reading->>'type' <> 'structured_reading'
     or jsonb_typeof(p_reading->'status') <> 'string'
     or p_reading->>'status' not in ('candidate', 'stable')
     or jsonb_typeof(p_reading->'display_text') <> 'string'
     or char_length(p_reading->>'display_text') not between 1 and 96
     or jsonb_typeof(p_reading->'value_decimal') <> 'string'
     or (p_reading->>'value_decimal') !~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
     or not public.beyoureyes_confirmed_reading_format_is_valid(p_reading->'format')
     or jsonb_typeof(p_reading->'unit') not in ('string', 'null')
     or p_reading->'unit' <> p_reading->'format'->'unit'
     or jsonb_typeof(p_reading->'source_kind') <> 'string'
     or p_reading->>'source_kind' not in ('digital_display', 'counter', 'analog_dial')
     or jsonb_typeof(p_reading->'observed_at') <> 'string'
     or (p_reading->>'observed_at') !~
       '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
     or jsonb_typeof(p_reading->'confidence') <> 'number'
  then
    return false;
  end if;
  v_confidence := (p_reading->>'confidence')::numeric;
  return v_confidence between 0 and 1;
exception when others then
  return false;
end
$$;

revoke all on function public.beyoureyes_structured_reading_is_valid(jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_structured_reading_is_valid(jsonb)
  to service_role;

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
            'hysteresis_decimal', 'cooldown_ms'
          ]
        )
        and jsonb_typeof(p_rule->'lower_threshold_decimal') = 'string'
        and (p_rule->>'lower_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and jsonb_typeof(p_rule->'upper_threshold_decimal') = 'string'
        and (p_rule->>'upper_threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and (p_rule->>'lower_threshold_decimal')::numeric
          < (p_rule->>'upper_threshold_decimal')::numeric
        and jsonb_typeof(p_rule->'hysteresis_decimal') = 'string'
        and (p_rule->>'hysteresis_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
        and public.beyoureyes_jsonb_integer_between(p_rule->'cooldown_ms', 0);
    end if;
    return public.beyoureyes_jsonb_has_exact_keys(
        p_rule,
        array[
          'type', 'operator', 'threshold_decimal',
          'hysteresis_decimal', 'cooldown_ms'
        ]
      )
      and v_operator in ('gt', 'gte', 'lt', 'lte', 'eq')
      and jsonb_typeof(p_rule->'threshold_decimal') = 'string'
      and (p_rule->>'threshold_decimal') ~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$'
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

revoke all on function public.beyoureyes_task_rule_is_valid(text, jsonb)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_task_rule_is_valid(text, jsonb)
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

alter table public.events
  drop constraint if exists events_payload_contract_valid;
alter table public.events
  add constraint events_payload_contract_valid
  check (public.beyoureyes_event_payload_is_valid(payload));

drop function if exists public.beyoureyes_numeric_reading_is_valid(jsonb);
