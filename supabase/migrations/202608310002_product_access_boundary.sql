-- A Google Play entitlement is the single product-access boundary. Account management and
-- device revocation remain available while product data, Realtime media relay, and FCM fail closed.

create or replace function public.beyoureyes_account_has_active_entitlement(
  p_account_id uuid,
  p_at timestamptz default statement_timestamp()
)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  select p_account_id is not null
     and exists (
       select 1
       from public.account_entitlements e
       where e.account_id = p_account_id
         and e.subscription_state in (
           'SUBSCRIPTION_STATE_ACTIVE',
           'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
           'SUBSCRIPTION_STATE_CANCELED'
         )
         and e.expires_at is not null
         and e.expires_at > p_at
     )
$$;

create or replace function public.beyoureyes_has_active_entitlement()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
  select public.beyoureyes_account_has_active_entitlement(auth.uid(), statement_timestamp())
$$;

revoke all on function public.beyoureyes_account_has_active_entitlement(uuid, timestamptz)
  from public, anon, authenticated;
revoke all on function public.beyoureyes_has_active_entitlement()
  from public, anon, service_role;
grant execute on function public.beyoureyes_has_active_entitlement()
  to authenticated;

-- Device discovery and revocation are account-management surfaces. New registration and device
-- updates are product activity and therefore require an active entitlement.
drop policy if exists devices_same_account on public.devices;
drop policy if exists devices_entitled_insert on public.devices;
drop policy if exists devices_entitled_update on public.devices;

create policy devices_same_account on public.devices
  for select to authenticated
  using ((select auth.uid()) = account_id);

create policy devices_entitled_insert on public.devices
  for insert to authenticated
  with check (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  );

create policy devices_entitled_update on public.devices
  for update to authenticated
  using (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  )
  with check (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  );

drop policy if exists tasks_same_account on public.tasks;
create policy tasks_same_account on public.tasks
  for all to authenticated
  using (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  )
  with check (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  );

drop policy if exists events_same_account on public.events;
create policy events_same_account on public.events
  for all to authenticated
  using (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  )
  with check (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  );

drop policy if exists event_receipts_same_account on public.event_receipts;
create policy event_receipts_same_account on public.event_receipts
  for all to authenticated
  using (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  )
  with check (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  );

drop policy if exists sync_changes_same_account on public.sync_changes;
create policy sync_changes_same_account on public.sync_changes
  for select to authenticated
  using (
    (select auth.uid()) = account_id
    and (select public.beyoureyes_has_active_entitlement())
  );

-- Supabase Realtime evaluates these policies for private Broadcast authorization. Entitlement
-- expiry therefore tears down both snapshot request and response paths without storing media.
drop policy if exists beyoureyes_snapshot_broadcast_receive on realtime.messages;
create policy beyoureyes_snapshot_broadcast_receive
on realtime.messages
for select
to authenticated
using (
  extension = 'broadcast'
  and (select realtime.topic()) =
    'beyoureyes:snapshots:' || (select auth.uid())::text
  and (select public.beyoureyes_has_active_entitlement())
);

drop policy if exists beyoureyes_snapshot_broadcast_send on realtime.messages;
create policy beyoureyes_snapshot_broadcast_send
on realtime.messages
for insert
to authenticated
with check (
  extension = 'broadcast'
  and (select realtime.topic()) =
    'beyoureyes:snapshots:' || (select auth.uid())::text
  and (select public.beyoureyes_has_active_entitlement())
);

-- Keep the already-audited data-plane implementations intact, but expose them only through
-- entitlement-checking entry points. The implementation functions are owner-only.
alter function public.beyoureyes_client_upsert_task_batch(jsonb)
  rename to beyoureyes_client_upsert_task_batch_entitled_impl;
revoke all on function public.beyoureyes_client_upsert_task_batch_entitled_impl(jsonb)
  from public, anon, authenticated, service_role;

create function public.beyoureyes_client_upsert_task_batch(p_tasks jsonb)
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
  if not public.beyoureyes_account_has_active_entitlement(v_account_id) then
    raise exception 'subscription_required' using errcode = 'P0001';
  end if;
  return public.beyoureyes_client_upsert_task_batch_entitled_impl(p_tasks);
end
$$;

alter function public.beyoureyes_client_insert_event_batch(jsonb)
  rename to beyoureyes_client_insert_event_batch_entitled_impl;
revoke all on function public.beyoureyes_client_insert_event_batch_entitled_impl(jsonb)
  from public, anon, authenticated, service_role;

create function public.beyoureyes_client_insert_event_batch(p_events jsonb)
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
  if not public.beyoureyes_account_has_active_entitlement(v_account_id) then
    raise exception 'subscription_required' using errcode = 'P0001';
  end if;
  return public.beyoureyes_client_insert_event_batch_entitled_impl(p_events);
end
$$;

alter function public.beyoureyes_client_add_event_receipt(uuid, uuid, text, timestamptz)
  rename to beyoureyes_client_add_event_receipt_entitled_impl;
revoke all on function public.beyoureyes_client_add_event_receipt_entitled_impl(
  uuid, uuid, text, timestamptz
) from public, anon, authenticated, service_role;

create function public.beyoureyes_client_add_event_receipt(
  p_event_id uuid,
  p_device_id uuid,
  p_receipt_type text,
  p_received_at timestamptz
)
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
  if not public.beyoureyes_account_has_active_entitlement(v_account_id) then
    raise exception 'subscription_required' using errcode = 'P0001';
  end if;
  return public.beyoureyes_client_add_event_receipt_entitled_impl(
    p_event_id,
    p_device_id,
    p_receipt_type,
    p_received_at
  );
end
$$;

alter function public.beyoureyes_client_register_push_token(uuid, text)
  rename to beyoureyes_client_register_push_token_entitled_impl;
revoke all on function public.beyoureyes_client_register_push_token_entitled_impl(uuid, text)
  from public, anon, authenticated, service_role;

create function public.beyoureyes_client_register_push_token(
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
  if not public.beyoureyes_account_has_active_entitlement(v_account_id) then
    raise exception 'subscription_required' using errcode = 'P0001';
  end if;
  perform public.beyoureyes_client_register_push_token_entitled_impl(p_device_id, p_token);
end
$$;

revoke all on function public.beyoureyes_client_upsert_task_batch(jsonb)
  from public, anon, service_role;
revoke all on function public.beyoureyes_client_insert_event_batch(jsonb)
  from public, anon, service_role;
revoke all on function public.beyoureyes_client_add_event_receipt(uuid, uuid, text, timestamptz)
  from public, anon, service_role;
revoke all on function public.beyoureyes_client_register_push_token(uuid, text)
  from public, anon, service_role;
grant execute on function public.beyoureyes_client_upsert_task_batch(jsonb),
  public.beyoureyes_client_insert_event_batch(jsonb),
  public.beyoureyes_client_add_event_receipt(uuid, uuid, text, timestamptz),
  public.beyoureyes_client_register_push_token(uuid, text)
  to authenticated;

-- A queued notification cannot escape after the provider entitlement expires. Inactive rows are
-- terminal rather than delayed for a future resubscription, so old events never notify later.
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
      last_error = case
        when not public.beyoureyes_account_has_active_entitlement(o.account_id, p_now)
          then 'entitlement_inactive'
        else 'delivery_disabled'
      end,
      sent_at = null
  where o.status in ('pending', 'leased')
    and (o.status = 'pending' or o.leased_until <= p_now)
    and (
      o.attempts >= 5
      or not public.beyoureyes_account_has_active_entitlement(o.account_id, p_now)
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
      and public.beyoureyes_account_has_active_entitlement(o.account_id, p_now)
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

revoke all on function public.beyoureyes_claim_push_batch(integer, uuid, timestamptz)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_claim_push_batch(integer, uuid, timestamptz)
  to service_role;

-- Extend the existing cumulative hosted-readiness function instead of copying its historical
-- checks. Any missing entitlement helper, policy, or client guard keeps hosted data unavailable.
alter function public.beyoureyes_data_ready()
  rename to beyoureyes_data_ready_pre_product_access;
revoke all on function public.beyoureyes_data_ready_pre_product_access()
  from public, anon, authenticated, service_role;

create function public.beyoureyes_data_ready()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, realtime
as $$
  select public.beyoureyes_data_ready_pre_product_access()
     and to_regprocedure(
       'public.beyoureyes_account_has_active_entitlement(uuid,timestamp with time zone)'
     ) is not null
     and to_regprocedure('public.beyoureyes_has_active_entitlement()') is not null
     and not has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_upsert_task_batch_entitled_impl(jsonb)',
       'EXECUTE'
     )
     and not has_function_privilege(
       'authenticated',
       'public.beyoureyes_client_insert_event_batch_entitled_impl(jsonb)',
       'EXECUTE'
     )
     and exists (
       select 1 from pg_policies
       where schemaname = 'public'
         and tablename = 'tasks'
         and policyname = 'tasks_same_account'
         and coalesce(qual, '') like '%beyoureyes_has_active_entitlement%'
     )
     and exists (
       select 1 from pg_policies
       where schemaname = 'public'
         and tablename = 'events'
         and policyname = 'events_same_account'
         and coalesce(qual, '') like '%beyoureyes_has_active_entitlement%'
     )
     and exists (
       select 1 from pg_policies
       where schemaname = 'public'
         and tablename = 'sync_changes'
         and policyname = 'sync_changes_same_account'
         and coalesce(qual, '') like '%beyoureyes_has_active_entitlement%'
     )
     and exists (
       select 1 from pg_policies
       where schemaname = 'realtime'
         and tablename = 'messages'
         and policyname = 'beyoureyes_snapshot_broadcast_receive'
         and coalesce(qual, '') like '%beyoureyes_has_active_entitlement%'
     )
     and exists (
       select 1 from pg_policies
       where schemaname = 'realtime'
         and tablename = 'messages'
         and policyname = 'beyoureyes_snapshot_broadcast_send'
         and coalesce(with_check, '') like '%beyoureyes_has_active_entitlement%'
     )
$$;

revoke all on function public.beyoureyes_data_ready()
  from public, anon, authenticated;
grant execute on function public.beyoureyes_data_ready()
  to service_role;
