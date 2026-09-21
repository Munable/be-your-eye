-- Minimal same-account data boundary. Supabase Auth owns credentials in auth.users.
create table if not exists public.devices (
  device_id uuid primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 80),
  notifications_enabled boolean not null default false,
  revision bigint not null default 1 check (revision >= 1),
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, device_id)
);

create table if not exists public.tasks (
  task_id uuid primary key,
  account_id uuid not null references auth.users(id) on delete cascade,
  revision bigint not null default 1 check (revision >= 1),
  catalog_version text not null,
  capability_id text not null,
  target_id text not null,
  monitoring_device_id uuid not null,
  config jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, task_id),
  foreign key (account_id, monitoring_device_id)
    references public.devices(account_id, device_id)
);

create table if not exists public.events (
  account_id uuid not null references auth.users(id) on delete cascade,
  event_id uuid not null,
  task_id uuid not null,
  task_revision bigint not null check (task_revision >= 1),
  episode_id uuid not null,
  source_sequence bigint not null check (source_sequence >= 0),
  occurred_at timestamptz not null,
  payload jsonb not null check (
    not (payload ?| array['image', 'frame', 'photo', 'video', 'audio', 'blob', 'data_url', 'media_url'])
  ),
  received_at timestamptz not null default now(),
  primary key (account_id, event_id),
  foreign key (account_id, task_id)
    references public.tasks(account_id, task_id) on delete cascade
);

create table if not exists public.event_receipts (
  account_id uuid not null references auth.users(id) on delete cascade,
  event_id uuid not null,
  device_id uuid not null,
  receipt_type text not null check (receipt_type in ('fetched', 'displayed')),
  received_at timestamptz not null,
  primary key (account_id, event_id, device_id, receipt_type),
  foreign key (account_id, event_id)
    references public.events(account_id, event_id) on delete cascade,
  foreign key (account_id, device_id)
    references public.devices(account_id, device_id) on delete cascade
);

create index if not exists devices_account_updated_idx on public.devices(account_id, updated_at);
create index if not exists tasks_account_updated_idx on public.tasks(account_id, updated_at);
create index if not exists events_account_received_idx on public.events(account_id, received_at, event_id);

alter table public.devices enable row level security;
alter table public.tasks enable row level security;
alter table public.events enable row level security;
alter table public.event_receipts enable row level security;

revoke all on public.devices, public.tasks, public.events, public.event_receipts from anon;
revoke insert, update, delete on public.devices, public.tasks, public.events, public.event_receipts from authenticated;
grant select on public.devices, public.tasks, public.events, public.event_receipts to authenticated;

create policy devices_same_account on public.devices
  for all to authenticated
  using ((select auth.uid()) = account_id)
  with check ((select auth.uid()) = account_id);

create policy tasks_same_account on public.tasks
  for all to authenticated
  using ((select auth.uid()) = account_id)
  with check ((select auth.uid()) = account_id);

create policy events_same_account on public.events
  for all to authenticated
  using ((select auth.uid()) = account_id)
  with check ((select auth.uid()) = account_id);

create policy event_receipts_same_account on public.event_receipts
  for all to authenticated
  using ((select auth.uid()) = account_id)
  with check ((select auth.uid()) = account_id);
