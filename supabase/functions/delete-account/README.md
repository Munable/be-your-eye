# delete-account

Authenticated account self-deletion for the Android client. The gateway and
function both require the caller's Supabase bearer token. The function verifies
that token with Supabase Auth, deletes only the resulting `auth.users` identity
with the runtime-provided service role, and returns no account data.

Deploy from the repository root with:

```bash
supabase functions deploy delete-account --project-ref PROJECT_REF
```

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are Supabase-hosted runtime
secrets. They must never be added to Android configuration or committed files.
