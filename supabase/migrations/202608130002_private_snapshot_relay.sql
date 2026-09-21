-- Same-account private Broadcast relay. Image plaintext never enters a database or Storage.
revoke select, insert on table realtime.messages from anon;
grant select, insert on table realtime.messages to authenticated;

drop policy if exists beyoureyes_snapshot_broadcast_receive on realtime.messages;
create policy beyoureyes_snapshot_broadcast_receive
on realtime.messages
for select
to authenticated
using (
  extension = 'broadcast'
  and (select realtime.topic()) =
    'beyoureyes:snapshots:' || (select auth.uid())::text
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
);

-- Event-only push fetches need the source device identity before the matching Task cursor arrives.
create or replace function public.beyoureyes_record_event_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_row public.events%rowtype;
  v_monitoring_device_id uuid;
begin
  v_row := case when tg_op = 'DELETE' then old else new end;
  if tg_op = 'DELETE' and not exists (select 1 from auth.users where id = v_row.account_id) then
    return old;
  end if;
  if tg_op <> 'DELETE' then
    select monitoring_device_id into strict v_monitoring_device_id
    from public.tasks
    where account_id = v_row.account_id and task_id = v_row.task_id;
  end if;
  insert into public.sync_changes(account_id, resource_type, operation, resource_id, value)
  values (
    v_row.account_id,
    'event',
    case when tg_op = 'DELETE' then 'delete' else 'upsert' end,
    v_row.event_id,
    case when tg_op = 'DELETE' then null else jsonb_build_object(
      'schema_version', v_row.schema_version,
      'event_id', v_row.event_id,
      'task_id', v_row.task_id,
      'task_revision', v_row.task_revision,
      'episode_id', v_row.episode_id,
      'source_sequence', v_row.source_sequence,
      'occurred_at', v_row.occurred_at,
      'monitoring_device_id', v_monitoring_device_id,
      'payload', v_row.payload
    ) end
  );
  return case when tg_op = 'DELETE' then old else new end;
end
$$;

revoke all on function public.beyoureyes_record_event_change() from public, anon, authenticated;
