-- Optional same-account FCM delivery. Android remains local-first: this queue is
-- populated only after a canonical Event reaches Supabase and only for active
-- devices whose per-device notification switch is enabled.

create table if not exists public.device_push_tokens (
  account_id uuid not null references auth.users(id) on delete cascade,
  device_id uuid not null,
  token text not null check (char_length(token) between 20 and 4096),
  updated_at timestamptz not null default now(),
  disabled_at timestamptz,
  primary key (account_id, device_id),
  unique (token),
  foreign key (account_id, device_id)
    references public.devices(account_id, device_id) on delete cascade
);

create table if not exists public.push_outbox (
  outbox_id bigint generated always as identity primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  event_id uuid not null,
  device_id uuid not null,
  cursor_hint text not null check (char_length(cursor_hint) between 1 and 128),
  status text not null default 'pending'
    check (status in ('pending', 'leased', 'sent', 'dead')),
  attempts integer not null default 0 check (attempts between 0 and 5),
  available_at timestamptz not null default now(),
  lease_id uuid,
  leased_until timestamptz,
  last_error text check (last_error is null or char_length(last_error) <= 240),
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  unique (account_id, event_id, device_id),
  foreign key (account_id, event_id)
    references public.events(account_id, event_id) on delete cascade,
  foreign key (account_id, device_id)
    references public.devices(account_id, device_id) on delete cascade,
  check (
    (status = 'pending' and lease_id is null and leased_until is null and sent_at is null)
    or (status = 'leased' and lease_id is not null and leased_until is not null and sent_at is null)
    or (status = 'sent' and lease_id is null and leased_until is null and sent_at is not null)
    or (status = 'dead' and lease_id is null and leased_until is null and sent_at is null)
  )
);

create index if not exists push_outbox_claim_idx
  on public.push_outbox(status, available_at, leased_until, outbox_id);

alter table public.device_push_tokens enable row level security;
alter table public.push_outbox enable row level security;
revoke all on public.device_push_tokens, public.push_outbox from public, anon, authenticated;

-- Tokens are deliberately write-only to clients. A phone can register or
-- remove only its own account's non-revoked GMS device token through RPC.
create or replace function public.beyoureyes_client_register_push_token(
  p_device_id uuid,
  p_token text
)
returns void
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
  if char_length(p_token) not between 20 and 4096 then
    raise exception 'invalid_push_token' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.devices d
    where d.account_id = v_account_id
      and d.device_id = p_device_id
      and d.revoked_at is null
      and d.gms_available
  ) then
    raise exception 'invalid_push_device' using errcode = 'P0001';
  end if;

  -- A refreshed FCM token may move between reinstall identities. Keep exactly
  -- one active owner and never expose the raw token through a SELECT grant.
  delete from public.device_push_tokens where token = p_token;
  insert into public.device_push_tokens(account_id, device_id, token, updated_at, disabled_at)
  values (v_account_id, p_device_id, p_token, clock_timestamp(), null)
  on conflict (account_id, device_id) do update
    set token = excluded.token,
        updated_at = excluded.updated_at,
        disabled_at = null;
end
$$;

create or replace function public.beyoureyes_client_unregister_push_token(
  p_device_id uuid
)
returns void
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
  delete from public.device_push_tokens
  where account_id = v_account_id and device_id = p_device_id;
end
$$;

revoke all on function public.beyoureyes_client_register_push_token(uuid, text)
  from public, anon, service_role;
revoke all on function public.beyoureyes_client_unregister_push_token(uuid)
  from public, anon, service_role;
grant execute on function public.beyoureyes_client_register_push_token(uuid, text)
  to authenticated;
grant execute on function public.beyoureyes_client_unregister_push_token(uuid)
  to authenticated;

create or replace function public.beyoureyes_enqueue_event_pushes()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
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
  return new;
end
$$;

revoke all on function public.beyoureyes_enqueue_event_pushes()
  from public, anon, authenticated;

drop trigger if exists events_enqueue_pushes on public.events;
create trigger events_enqueue_pushes
after insert on public.events
for each row execute function public.beyoureyes_enqueue_event_pushes();

-- Push dispatchers use a short lease. Expired leases are claimable again;
-- repeated client uploads cannot duplicate rows because Event and outbox IDs
-- are independently idempotent.
create or replace function public.beyoureyes_claim_push_batch(
  p_limit integer,
  p_lease_id uuid,
  p_now timestamptz default clock_timestamp()
)
returns table (
  outbox_id bigint,
  event_id uuid,
  device_id uuid,
  token text,
  cursor_hint text
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if p_limit not between 1 and 100 or p_lease_id is null then
    raise exception 'invalid_push_claim' using errcode = '22023';
  end if;

  update public.push_outbox o
  set status = 'dead',
      lease_id = null,
      leased_until = null,
      last_error = 'delivery_disabled',
      sent_at = null
  where o.status in ('pending', 'leased')
    and (o.status = 'pending' or o.leased_until <= p_now)
    and (
      o.attempts >= 5
      or not exists (
        select 1
        from public.device_push_tokens p
        join public.devices d
          on d.account_id = p.account_id and d.device_id = p.device_id
        where p.account_id = o.account_id
          and p.device_id = o.device_id
          and p.disabled_at is null
          and d.notifications_enabled
          and d.revoked_at is null
          and d.gms_available
      )
    );

  return query
  with candidates as (
    select o.outbox_id
    from public.push_outbox o
    join public.device_push_tokens p
      on p.account_id = o.account_id and p.device_id = o.device_id
    join public.devices d
      on d.account_id = o.account_id and d.device_id = o.device_id
    where o.attempts < 5
      and o.available_at <= p_now
      and (o.status = 'pending' or (o.status = 'leased' and o.leased_until <= p_now))
      and p.disabled_at is null
      and d.notifications_enabled
      and d.revoked_at is null
      and d.gms_available
    order by o.outbox_id
    for update of o skip locked
    limit p_limit
  ), claimed as (
    update public.push_outbox o
    set status = 'leased',
        attempts = o.attempts + 1,
        lease_id = p_lease_id,
        leased_until = p_now + interval '2 minutes',
        last_error = null
    from candidates c
    where o.outbox_id = c.outbox_id
    returning o.outbox_id, o.account_id, o.event_id, o.device_id, o.cursor_hint
  )
  select c.outbox_id, c.event_id, c.device_id, p.token, c.cursor_hint
  from claimed c
  join public.device_push_tokens p
    on p.account_id = c.account_id and p.device_id = c.device_id
  order by c.outbox_id;
end
$$;

create or replace function public.beyoureyes_complete_push(
  p_outbox_id bigint,
  p_lease_id uuid,
  p_success boolean,
  p_permanent_failure boolean,
  p_error text default null,
  p_now timestamptz default clock_timestamp()
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_row public.push_outbox%rowtype;
begin
  select * into v_row
  from public.push_outbox
  where outbox_id = p_outbox_id
    and status = 'leased'
    and lease_id = p_lease_id
  for update;
  if not found then
    return false;
  end if;

  if p_success then
    update public.push_outbox
    set status = 'sent', lease_id = null, leased_until = null,
        last_error = null, sent_at = p_now
    where outbox_id = p_outbox_id;
  elsif p_permanent_failure or v_row.attempts >= 5 then
    update public.push_outbox
    set status = 'dead', lease_id = null, leased_until = null,
        last_error = left(coalesce(p_error, 'push_failed'), 240), sent_at = null
    where outbox_id = p_outbox_id;
    if p_permanent_failure then
      update public.device_push_tokens
      set disabled_at = p_now, updated_at = p_now
      where account_id = v_row.account_id and device_id = v_row.device_id;
    end if;
  else
    update public.push_outbox
    set status = 'pending', lease_id = null, leased_until = null,
        available_at = p_now + make_interval(
          secs => least(3600, (60 * power(2, greatest(v_row.attempts - 1, 0)))::integer)
        ),
        last_error = left(coalesce(p_error, 'push_failed'), 240), sent_at = null
    where outbox_id = p_outbox_id;
  end if;
  return true;
end
$$;

revoke all on function public.beyoureyes_claim_push_batch(integer, uuid, timestamptz)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_complete_push(bigint, uuid, boolean, boolean, text, timestamptz)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_claim_push_batch(integer, uuid, timestamptz)
  to service_role;
grant execute on function public.beyoureyes_complete_push(bigint, uuid, boolean, boolean, text, timestamptz)
  to service_role;

-- Include optional push delivery in hosted readiness. A deployment that omits
-- this migration must stay unavailable rather than silently dropping pushes.
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

revoke all on function public.beyoureyes_data_ready() from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready() to service_role;
