-- Keep financial writes fail-closed even if an internal caller sends null values.
create or replace function public.beyoureyes_apply_website_payment(
  p_order_id uuid, p_session_id text, p_payment_intent_id text,
  p_amount integer, p_currency text, p_revoked boolean
) returns void language plpgsql security definer
set search_path = pg_catalog, public as $$
declare
  v_order public.website_orders;
  v_future public.website_orders;
  v_start timestamptz;
begin
  if p_order_id is null or p_session_id is null or p_payment_intent_id is null
     or p_amount is null or p_currency is null or p_revoked is null then
    raise exception 'payment_mismatch';
  end if;
  select * into v_order from public.website_orders where id = p_order_id;
  if not found then raise exception 'order_missing'; end if;
  perform pg_advisory_xact_lock(hashtextextended(coalesce(v_order.account_id::text, v_order.id::text), 0));
  select * into v_order from public.website_orders where id = p_order_id for update;
  if (v_order.checkout_session_id is not null and v_order.checkout_session_id <> p_session_id)
     or (v_order.payment_intent_id is not null and v_order.payment_intent_id <> p_payment_intent_id)
     or v_order.amount <> p_amount or v_order.currency <> p_currency
     or p_session_id not like 'cs\_%' escape '\' or p_payment_intent_id not like 'pi\_%' escape '\' then
    raise exception 'payment_mismatch';
  end if;
  if v_order.state = 'revoked' then return; end if;
  if p_revoked then
    update public.website_orders set state = 'revoked', checkout_session_id = p_session_id,
      payment_intent_id = p_payment_intent_id, updated_at = now() where id = p_order_id;
    -- Removing an earlier term must not leave later paid terms stranded in the future.
    select greatest(now(), coalesce(max(expires_at), now())) into v_start
      from public.website_orders where account_id = v_order.account_id and state = 'paid'
      and starts_at <= now();
    for v_future in select * from public.website_orders
      where account_id = v_order.account_id and state = 'paid' and starts_at > now()
      order by starts_at, id for update
    loop
      update public.website_orders set starts_at = v_start,
        expires_at = v_start + (v_future.expires_at - v_future.starts_at), updated_at = now()
        where id = v_future.id;
      v_start := v_start + (v_future.expires_at - v_future.starts_at);
    end loop;
    return;
  end if;
  if v_order.state = 'paid' then return; end if;
  select greatest(now(), coalesce(max(expires_at), now())) into v_start
    from public.website_orders where account_id = v_order.account_id and state = 'paid';
  update public.website_orders set state = 'paid', checkout_session_id = p_session_id,
    payment_intent_id = p_payment_intent_id, starts_at = v_start,
    expires_at = v_start + interval '30 days', updated_at = now() where id = p_order_id;
end $$;

-- Adjacent paid periods form one continuous entitlement. Show its full end date,
-- while never granting access across a gap in time.
create or replace function public.beyoureyes_product_entitlement(p_account_id uuid)
returns jsonb language sql stable security definer set search_path = pg_catalog, public as $$
  with paid_ranges as (
    select unnest(range_agg(tstzrange(starts_at, expires_at, '[)'))) as period
      from public.website_orders where account_id = p_account_id and state = 'paid'
  )
  select coalesce((select jsonb_build_object('state', state, 'expires_at', expiry)
    from (
      select subscription_state as state, expires_at as expiry from public.account_entitlements
        where account_id = p_account_id and subscription_state in
        ('SUBSCRIPTION_STATE_ACTIVE','SUBSCRIPTION_STATE_IN_GRACE_PERIOD','SUBSCRIPTION_STATE_CANCELED')
        and expires_at > now()
      union all
      select 'WEBSITE_PASS_ACTIVE', upper(period) from paid_ranges where period @> now()
      union all
      select 'WEBSITE_TRIAL_ACTIVE', expires_at from public.website_trials
        where account_id = p_account_id and starts_at <= now() and expires_at > now()
    ) grants order by expiry desc limit 1), jsonb_build_object('state','none','expires_at',null))
$$;
