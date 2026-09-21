-- Provider state and provider expiry are the durable entitlement authority. Device leases are
-- derived at request time and therefore cannot outlive expiry or become a second persisted clock.
drop index if exists public.account_entitlements_access_until_idx;

alter table public.account_entitlements
  drop column if exists access_until;

comment on table public.account_entitlements is
  'Service-only Google Play provider state; device leases are derived dynamically and purchase tokens are stored only as hashes.';
