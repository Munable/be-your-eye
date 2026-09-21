-- Catalog 4.0 has one account-level product entitlement. A Task stores only the exact signed
-- package identity needed to replay its runtime binding; package-level access metadata is gone.

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
        'package_id', 'package_version', 'manifest_sha256',
        'artifact_identity_sha256'
      ]
    )
      and jsonb_typeof(p_package->'package_id') = 'string'
      and (p_package->>'package_id') ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'
      and jsonb_typeof(p_package->'package_version') = 'string'
      and (p_package->>'package_version') ~ '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
      and jsonb_typeof(p_package->'manifest_sha256') = 'string'
      and (p_package->>'manifest_sha256') ~ '^[0-9a-f]{64}$'
      and jsonb_typeof(p_package->'artifact_identity_sha256') = 'string'
      and (p_package->>'artifact_identity_sha256') ~ '^[0-9a-f]{64}$',
    false
  )
$$;

revoke all on function public.beyoureyes_task_package_binding_is_valid(jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.beyoureyes_task_package_binding_is_valid(jsonb)
  to service_role;

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
      'schema_version', '4.0',
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

revoke all on function public.beyoureyes_record_task_change()
  from public, anon, authenticated;

-- Extend cumulative readiness without rewriting an applied migration. The behavioral checks keep
-- an accidentally restored package-level access field from reopening the hosted data plane.
alter function public.beyoureyes_data_ready()
  rename to beyoureyes_data_ready_pre_task_config_v4;
revoke all on function public.beyoureyes_data_ready_pre_task_config_v4()
  from public, anon, authenticated, service_role;

create function public.beyoureyes_data_ready()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, realtime
as $$
  select public.beyoureyes_data_ready_pre_task_config_v4()
     and public.beyoureyes_task_package_binding_is_valid(
       jsonb_build_object(
         'package_id', 'efficientdet_lite2_object_v1',
         'package_version', '1.0.0',
         'manifest_sha256', repeat('0', 64),
         'artifact_identity_sha256', repeat('1', 64)
       )
     )
     and not public.beyoureyes_task_package_binding_is_valid(
       jsonb_build_object(
         'package_id', 'efficientdet_lite2_object_v1',
         'package_version', '1.0.0',
         'manifest_sha256', repeat('0', 64),
         'artifact_identity_sha256', repeat('1', 64),
         'access_tier', 'subscription'
       )
     )
     and position(
       '''schema_version'', ''4.0'''
       in pg_get_functiondef('public.beyoureyes_record_task_change()'::regprocedure)
     ) > 0
$$;

revoke all on function public.beyoureyes_data_ready()
  from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready()
  to service_role;
