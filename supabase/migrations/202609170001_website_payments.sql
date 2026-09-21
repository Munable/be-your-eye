-- Website purchases have their own ledger; they never impersonate Google Play tokens.
create table public.website_orders (
  id uuid primary key,
  account_id uuid references auth.users(id) on delete set null,
  price_id text not null,
  amount integer not null check (amount > 0),
  currency text not null check (currency ~ '^[a-z]{3}$'),
  checkout_session_id text unique,
  payment_intent_id text unique,
  state text not null default 'pending' check (state in ('pending', 'paid', 'revoked')),
  starts_at timestamptz,
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((starts_at is null and expires_at is null) or expires_at > starts_at)
);
create index website_orders_account_idx on public.website_orders(account_id);
create table public.website_trials (
  account_id uuid primary key references auth.users(id) on delete cascade,
  starts_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '3 days'),
  check (expires_at = starts_at + interval '3 days')
);
alter table public.website_orders enable row level security;
alter table public.website_trials enable row level security;
revoke all on public.website_orders, public.website_trials from public, anon, authenticated;
grant select, insert, update on public.website_orders to service_role;
grant select, insert on public.website_trials to service_role;

-- Serialize checkout creation per account and cap abandoned checkout attempts.
create function public.beyoureyes_reserve_website_order(
  p_id uuid, p_account_id uuid, p_price_id text, p_amount integer, p_currency text
) returns public.website_orders language plpgsql security definer
set search_path = pg_catalog, public as $$
declare v_order public.website_orders;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_account_id::text, 0));
  select * into v_order from public.website_orders where id = p_id;
  if found then
    if v_order.account_id is distinct from p_account_id then
      raise exception 'order_account_mismatch';
    end if;
    return v_order;
  end if;
  if (select count(*) from public.website_orders where account_id = p_account_id
      and created_at > now() - interval '1 hour') >= 5 then
    raise exception 'checkout_rate_limited';
  end if;
  insert into public.website_orders(id, account_id, price_id, amount, currency)
    values (p_id, p_account_id, p_price_id, p_amount, p_currency) returning * into v_order;
  return v_order;
end $$;

-- Signature verification and authoritative Stripe retrieval happen in the Edge Function.
-- This transaction grants exactly once and makes revocation terminal, including when a
-- refund/dispute webhook wins the race against the original payment webhook.
create function public.beyoureyes_apply_website_payment(
  p_order_id uuid, p_session_id text, p_payment_intent_id text,
  p_amount integer, p_currency text, p_revoked boolean
) returns void language plpgsql security definer
set search_path = pg_catalog, public as $$
declare v_order public.website_orders; v_start timestamptz;
begin
  select * into v_order from public.website_orders where id = p_order_id;
  if not found then raise exception 'order_missing'; end if;
  perform pg_advisory_xact_lock(hashtextextended(coalesce(v_order.account_id::text, v_order.id::text), 0));
  select * into v_order from public.website_orders where id = p_order_id for update;
  if (v_order.checkout_session_id is not null and v_order.checkout_session_id <> p_session_id)
     or (v_order.payment_intent_id is not null and v_order.payment_intent_id <> p_payment_intent_id)
     or v_order.amount <> p_amount or v_order.currency <> p_currency
     or p_session_id not like 'cs_%' or p_payment_intent_id not like 'pi_%' then
    raise exception 'payment_mismatch';
  end if;
  if v_order.state = 'revoked' then return; end if;
  if p_revoked then
    update public.website_orders set state = 'revoked', checkout_session_id = p_session_id,
      payment_intent_id = p_payment_intent_id, updated_at = now() where id = p_order_id;
    return;
  end if;
  if v_order.state = 'paid' then return; end if;
  select greatest(now(), coalesce(max(expires_at), now())) into v_start
    from public.website_orders where account_id = v_order.account_id and state = 'paid';
  update public.website_orders set state = 'paid', checkout_session_id = p_session_id,
    payment_intent_id = p_payment_intent_id, starts_at = v_start,
    expires_at = v_start + interval '30 days', updated_at = now() where id = p_order_id;
end $$;

create function public.beyoureyes_product_entitlement(p_account_id uuid)
returns jsonb language sql stable security definer set search_path = pg_catalog, public as $$
  select coalesce((select jsonb_build_object('state', state, 'expires_at', expiry)
    from (
      select subscription_state as state, expires_at as expiry from public.account_entitlements
        where account_id = p_account_id and subscription_state in
        ('SUBSCRIPTION_STATE_ACTIVE','SUBSCRIPTION_STATE_IN_GRACE_PERIOD','SUBSCRIPTION_STATE_CANCELED')
        and expires_at > now()
      union all
      select 'WEBSITE_PASS_ACTIVE', expires_at from public.website_orders
        where account_id = p_account_id and state = 'paid' and starts_at <= now() and expires_at > now()
      union all
      select 'WEBSITE_TRIAL_ACTIVE', expires_at from public.website_trials
        where account_id = p_account_id and expires_at > now()
    ) grants order by expiry desc limit 1), jsonb_build_object('state','none','expires_at',null))
$$;

create or replace function public.beyoureyes_account_has_active_entitlement(
  p_account_id uuid, p_at timestamptz default statement_timestamp()
) returns boolean language sql stable security definer set search_path = pg_catalog, public as $$
  select p_account_id is not null and (
    exists (select 1 from public.account_entitlements where account_id = p_account_id
      and subscription_state in ('SUBSCRIPTION_STATE_ACTIVE','SUBSCRIPTION_STATE_IN_GRACE_PERIOD','SUBSCRIPTION_STATE_CANCELED')
      and expires_at > p_at)
    or exists (select 1 from public.website_orders where account_id = p_account_id
      and state = 'paid' and starts_at <= p_at and expires_at > p_at)
    or exists (select 1 from public.website_trials where account_id = p_account_id and starts_at <= p_at and expires_at > p_at)
  )
$$;
revoke all on function public.beyoureyes_reserve_website_order(uuid,uuid,text,integer,text),
  public.beyoureyes_apply_website_payment(uuid,text,text,integer,text,boolean),
  public.beyoureyes_product_entitlement(uuid),
  public.beyoureyes_account_has_active_entitlement(uuid,timestamptz) from public, anon, authenticated;
grant execute on function public.beyoureyes_reserve_website_order(uuid,uuid,text,integer,text),
  public.beyoureyes_apply_website_payment(uuid,text,text,integer,text,boolean),
  public.beyoureyes_product_entitlement(uuid),
  public.beyoureyes_account_has_active_entitlement(uuid,timestamptz) to service_role;
