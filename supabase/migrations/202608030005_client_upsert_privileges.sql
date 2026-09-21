-- PostgREST implements an upsert as INSERT ... ON CONFLICT DO UPDATE and therefore
-- requires UPDATE privilege on the conflict-key column included in the JSON payload.
-- The prepare triggers below keep both identifiers immutable on UPDATE, so granting
-- these two column privileges does not let a client move a row or cross account scope.

grant update (device_id) on public.devices to authenticated;
grant update (task_id) on public.tasks to authenticated;
