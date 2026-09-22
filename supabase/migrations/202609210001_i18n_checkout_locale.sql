-- Store the locale chosen before a checkout is created so idempotent retries keep
-- the same Stripe language even if the browser language changes.
alter table public.website_orders
  add column locale text not null default 'en'
  check (locale in ('en', 'zh-Hans', 'zh-Hant', 'ja', 'ko', 'es', 'fr', 'de', 'pt-BR'));

drop function public.beyoureyes_reserve_website_order(uuid, uuid, text, integer, text);

create function public.beyoureyes_reserve_website_order(
  p_id uuid,
  p_account_id uuid,
  p_price_id text,
  p_amount integer,
  p_currency text,
  p_locale text default 'en'
) returns public.website_orders language plpgsql security definer
set search_path = pg_catalog, public as $$
declare v_order public.website_orders;
begin
  if p_locale not in ('en', 'zh-Hans', 'zh-Hant', 'ja', 'ko', 'es', 'fr', 'de', 'pt-BR') then
    raise exception 'invalid_locale';
  end if;
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
  insert into public.website_orders(id, account_id, price_id, amount, currency, locale)
    values (p_id, p_account_id, p_price_id, p_amount, p_currency, p_locale)
    returning * into v_order;
  return v_order;
end $$;

revoke all on function public.beyoureyes_reserve_website_order(uuid, uuid, text, integer, text, text)
  from public, anon, authenticated;
grant execute on function public.beyoureyes_reserve_website_order(uuid, uuid, text, integer, text, text)
  to service_role;
