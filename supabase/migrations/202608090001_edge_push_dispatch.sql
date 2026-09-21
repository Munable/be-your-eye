-- Dispatch optional same-account push delivery from a bounded Supabase Edge Function.
-- Runtime credentials are created separately in Vault and Function Secrets; no secret is stored
-- in migration history.

create extension if not exists pg_net with schema extensions;
create extension if not exists pg_cron with schema extensions;
create extension if not exists supabase_vault with schema vault;

create or replace function public.beyoureyes_request_push_dispatch()
returns bigint
language plpgsql
security definer
set search_path = pg_catalog, public, extensions, vault
as $$
declare
  v_project_url text;
  v_secret text;
  v_request_id bigint;
begin
  select decrypted_secret into v_project_url
  from vault.decrypted_secrets
  where name = 'beyoureyes_push_dispatch_project_url';

  select decrypted_secret into v_secret
  from vault.decrypted_secrets
  where name = 'beyoureyes_push_dispatch_secret';

  if v_project_url is null or v_secret is null then
    return null;
  end if;

  select net.http_post(
    url := rtrim(v_project_url, '/') || '/functions/v1/push-dispatch',
    headers := jsonb_build_object(
      'content-type', 'application/json',
      'x-beyoureyes-push-secret', v_secret
    ),
    body := '{"source":"database"}'::jsonb,
    timeout_milliseconds := 15000
  ) into v_request_id;
  return v_request_id;
end
$$;

revoke all on function public.beyoureyes_request_push_dispatch()
  from public, anon, authenticated, service_role;

create or replace function public.beyoureyes_enqueue_event_pushes()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_enqueued integer;
begin
  insert into public.push_outbox(account_id, event_id, device_id, cursor_hint)
  select
    new.account_id,
    new.event_id,
    d.device_id,
    new.event_sequence::text
  from public.devices d
  join public.device_push_tokens p
    on p.account_id = d.account_id and p.device_id = d.device_id
  where d.account_id = new.account_id
    and d.notifications_enabled
    and d.revoked_at is null
    and d.gms_available
    and p.disabled_at is null
  on conflict (account_id, event_id, device_id) do nothing;

  get diagnostics v_enqueued = row_count;
  if v_enqueued > 0 then
    perform public.beyoureyes_request_push_dispatch();
  end if;
  return new;
end
$$;

do $$
declare
  v_job record;
begin
  for v_job in select jobid from cron.job where jobname = 'beyoureyes-push-dispatch-every-minute'
  loop
    perform cron.unschedule(v_job.jobid);
  end loop;
end
$$;

select cron.schedule(
  'beyoureyes-push-dispatch-every-minute',
  '* * * * *',
  'select public.beyoureyes_request_push_dispatch()'
);

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
     and to_regprocedure('public.beyoureyes_request_push_dispatch()') is not null
     and has_column_privilege('authenticated', 'public.devices', 'display_name', 'INSERT')
     and not has_column_privilege('authenticated', 'public.tasks', 'config', 'UPDATE')
     and not has_table_privilege('authenticated', 'public.events', 'INSERT')
     and not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT')
$$;

revoke all on function public.beyoureyes_data_ready() from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
