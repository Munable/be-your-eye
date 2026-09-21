-- Supabase Realtime may refresh its table grants independently of project migrations.
-- Keep the snapshot relay account-authenticated and least-privilege at the table boundary.
revoke all privileges on table realtime.messages from anon, authenticated;
grant select, insert on table realtime.messages to authenticated;
