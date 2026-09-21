-- Google Play purchase tokens never enter client-readable tables. Edge Functions write a
-- short-lived, server-verified entitlement cache; deleting auth.users removes it.
create table public.account_entitlements (
  account_id uuid primary key references auth.users(id) on delete cascade,
  tier text not null check (tier = 'pro'),
  source text not null check (source = 'google_play'),
  product_id text not null check (product_id = 'be_your_eye_pro'),
  obfuscated_account_id text not null unique
    check (obfuscated_account_id ~ '^[0-9a-f]{64}$'),
  purchase_token_sha256 text not null unique
    check (purchase_token_sha256 ~ '^[0-9a-f]{64}$'),
  subscription_state text not null check (subscription_state in (
    'SUBSCRIPTION_STATE_PENDING',
    'SUBSCRIPTION_STATE_ACTIVE',
    'SUBSCRIPTION_STATE_PAUSED',
    'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
    'SUBSCRIPTION_STATE_ON_HOLD',
    'SUBSCRIPTION_STATE_CANCELED',
    'SUBSCRIPTION_STATE_EXPIRED',
    'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED'
  )),
  expires_at timestamptz,
  access_until timestamptz not null,
  verified_at timestamptz not null,
  updated_at timestamptz not null default now()
);

alter table public.account_entitlements enable row level security;
revoke all on table public.account_entitlements from public, anon, authenticated;
grant select, insert, update, delete on table public.account_entitlements to service_role;

create index account_entitlements_access_until_idx
  on public.account_entitlements(access_until);

comment on table public.account_entitlements is
  'Service-only Google Play entitlement cache; contains account and token hashes, never the purchase token.';
