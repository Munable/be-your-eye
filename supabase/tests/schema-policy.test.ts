import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const coreMigrationUrl = new URL("../migrations/202608020001_core.sql", import.meta.url);
const durableMigrationUrl = new URL("../migrations/202608020002_durable_store.sql", import.meta.url);
const clientDataPlaneMigrationUrl = new URL(
  "../migrations/202608030001_client_data_plane.sql",
  import.meta.url,
);
const pushDeliveryMigrationUrl = new URL(
  "../migrations/202608030002_push_delivery.sql",
  import.meta.url,
);
const eventPayloadContractMigrationUrl = new URL(
  "../migrations/202608030003_event_payload_contract.sql",
  import.meta.url,
);
const eventRetentionMigrationUrl = new URL(
  "../migrations/202608030004_event_retention.sql",
  import.meta.url,
);
const clientUpsertPrivilegesMigrationUrl = new URL(
  "../migrations/202608030005_client_upsert_privileges.sql",
  import.meta.url,
);
const taskRevisionBatchRpcMigrationUrl = new URL(
  "../migrations/202608030006_task_revision_batch_rpc.sql",
  import.meta.url,
);
const deviceRevocationMigrationUrl = new URL(
  "../migrations/202608040001_device_revocation.sql",
  import.meta.url,
);
const edgePushDispatchMigrationUrl = new URL(
  "../migrations/202608090001_edge_push_dispatch.sql",
  import.meta.url,
);
const edgePushReadinessMigrationUrl = new URL(
  "../migrations/202608090002_edge_push_readiness.sql",
  import.meta.url,
);
const structuredReadingV2MigrationUrl = new URL(
  "../migrations/202608130001_structured_reading_v2.sql",
  import.meta.url,
);
const privateSnapshotRelayMigrationUrl = new URL(
  "../migrations/202608130002_private_snapshot_relay.sql",
  import.meta.url,
);
const eventSyncPrivacyReadinessMigrationUrl = new URL(
  "../migrations/202608200001_event_sync_privacy_readiness.sql",
  import.meta.url,
);
const playEntitlementsMigrationUrl = new URL(
  "../migrations/202608230001_play_entitlements.sql",
  import.meta.url,
);
const dynamicEntitlementLeasesMigrationUrl = new URL(
  "../migrations/202608310001_dynamic_entitlement_leases.sql",
  import.meta.url,
);
const productAccessBoundaryMigrationUrl = new URL(
  "../migrations/202608310002_product_access_boundary.sql",
  import.meta.url,
);
const taskConfigV4MigrationUrl = new URL(
  "../migrations/202608310003_task_config_v4.sql",
  import.meta.url,
);
const currentTaskEventContractMigrationUrl = new URL(
  "../migrations/202608270001_current_task_event_contract.sql",
  import.meta.url,
);
const realtimeAclReadinessMigrationUrl = new URL(
  "../migrations/202608270002_realtime_acl_readiness.sql",
  import.meta.url,
);
const realtimePolicyReadinessMigrationUrl = new URL(
  "../migrations/202608270003_realtime_policy_readiness.sql",
  import.meta.url,
);
const taskTitleSchemaAlignmentMigrationUrl = new URL(
  "../migrations/202608270004_task_title_schema_alignment.sql",
  import.meta.url,
);

test("Supabase schema enables same-account RLS and the client data plane", async () => {
  const sql = `${await readFile(coreMigrationUrl, "utf8")}\n${await readFile(durableMigrationUrl, "utf8")}\n${await readFile(clientDataPlaneMigrationUrl, "utf8")}\n${await readFile(pushDeliveryMigrationUrl, "utf8")}\n${await readFile(eventPayloadContractMigrationUrl, "utf8")}\n${await readFile(eventRetentionMigrationUrl, "utf8")}\n${await readFile(clientUpsertPrivilegesMigrationUrl, "utf8")}\n${await readFile(taskRevisionBatchRpcMigrationUrl, "utf8")}\n${await readFile(deviceRevocationMigrationUrl, "utf8")}\n${await readFile(edgePushDispatchMigrationUrl, "utf8")}\n${await readFile(edgePushReadinessMigrationUrl, "utf8")}\n${await readFile(structuredReadingV2MigrationUrl, "utf8")}\n${await readFile(privateSnapshotRelayMigrationUrl, "utf8")}\n${await readFile(eventSyncPrivacyReadinessMigrationUrl, "utf8")}\n${await readFile(playEntitlementsMigrationUrl, "utf8")}\n${await readFile(currentTaskEventContractMigrationUrl, "utf8")}\n${await readFile(realtimeAclReadinessMigrationUrl, "utf8")}\n${await readFile(realtimePolicyReadinessMigrationUrl, "utf8")}\n${await readFile(taskTitleSchemaAlignmentMigrationUrl, "utf8")}\n${await readFile(dynamicEntitlementLeasesMigrationUrl, "utf8")}\n${await readFile(productAccessBoundaryMigrationUrl, "utf8")}\n${await readFile(taskConfigV4MigrationUrl, "utf8")}`.toLowerCase();
  for (const table of ["devices", "tasks", "events", "event_receipts"]) {
    assert.match(sql, new RegExp(`alter table public\\.${table} enable row level security`));
    assert.match(sql, new RegExp(`create policy ${table}_same_account on public\\.${table}`));
  }
  assert.match(sql, /auth\.uid\(\)\) = account_id/);
  assert.match(sql, /notifications_enabled boolean not null default false/);
  assert.match(sql, /grant select on public\.devices, public\.tasks, public\.events, public\.event_receipts to authenticated/);
  assert.match(sql, /grant insert \([\s\S]*\) on public\.devices to authenticated/);
  assert.match(sql, /grant update \([\s\S]*\) on public\.devices to authenticated/);
  for (const column of ["schema_version", "device_profile", "abi"]) {
    assert.match(
      sql,
      new RegExp(`grant update \\([\\s\\S]*${column}[\\s\\S]*\\) on public\\.devices to authenticated`),
    );
  }
  assert.match(sql, /grant insert \([\s\S]*\) on public\.tasks to authenticated/);
  assert.match(sql, /grant update \([\s\S]*\) on public\.tasks to authenticated/);
  assert.match(sql, /revoke insert, update on public\.tasks from authenticated/);
  assert.match(sql, /create or replace function public\.beyoureyes_client_upsert_task_batch/);
  assert.match(sql, /revoke insert, update, delete on public\.events, public\.event_receipts, public\.sync_changes from authenticated/);
  assert.match(sql, /alter table public\.devices alter column account_id set default auth\.uid\(\)/);
  assert.match(sql, /alter table public\.tasks alter column account_id set default auth\.uid\(\)/);
  assert.doesNotMatch(sql, /password|password_hash/);
  assert.doesNotMatch(sql, /device_role|\brole\s+text/);
  assert.match(sql, /create or replace function public\.beyoureyes_event_payload_is_valid/);
  assert.match(sql, /add constraint events_payload_contract_valid/);
  assert.match(sql, /raise exception 'event_payload_invalid'/);
  assert.match(
    sql,
    /revoke all on function public\.beyoureyes_event_payload_is_valid\(jsonb\)[\s\S]*from public, anon, authenticated/,
  );
});

test("product access gates data, Realtime, and FCM while account cleanup stays open", async () => {
  const sql = (await readFile(productAccessBoundaryMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /create or replace function public\.beyoureyes_account_has_active_entitlement/);
  assert.match(sql, /subscription_state in \([\s\S]*subscription_state_active[\s\S]*subscription_state_in_grace_period[\s\S]*subscription_state_canceled/);
  assert.match(sql, /e\.expires_at > p_at/);
  for (const policy of [
    "tasks_same_account",
    "events_same_account",
    "event_receipts_same_account",
    "sync_changes_same_account",
    "beyoureyes_snapshot_broadcast_receive",
    "beyoureyes_snapshot_broadcast_send",
  ]) {
    assert.match(
      sql,
      new RegExp(`create policy ${policy}[\\s\\S]*beyoureyes_has_active_entitlement`),
    );
  }
  for (const rpc of [
    "upsert_task_batch",
    "insert_event_batch",
    "add_event_receipt",
    "register_push_token",
  ]) {
    assert.match(
      sql,
      new RegExp(`create function public\\.beyoureyes_client_${rpc}[\\s\\S]*subscription_required`),
    );
  }
  assert.match(sql, /beyoureyes_claim_push_batch[\s\S]*entitlement_inactive/);
  assert.doesNotMatch(sql, /alter function public\.beyoureyes_client_revoke_device/);
  assert.doesNotMatch(sql, /alter function public\.beyoureyes_client_unregister_push_token/);
});

test("Play entitlements are RLS-protected, service-only, and never store raw purchase tokens", async () => {
  const sql = (await readFile(playEntitlementsMigrationUrl, "utf8")).toLowerCase();
  const forward = (await readFile(dynamicEntitlementLeasesMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /create table public\.account_entitlements/);
  assert.match(sql, /alter table public\.account_entitlements enable row level security/);
  assert.match(
    sql,
    /revoke all on table public\.account_entitlements from public, anon, authenticated/,
  );
  assert.match(
    sql,
    /grant select, insert, update, delete on table public\.account_entitlements to service_role/,
  );
  assert.doesNotMatch(sql, /create policy[^;]*account_entitlements/);
  assert.match(sql, /purchase_token_sha256 text not null unique/);
  assert.doesNotMatch(sql, /\bpurchase_token\b/);
  assert.doesNotMatch(sql, /grant[^;]*account_entitlements[^;]*to (?:public|anon|authenticated)/);
  assert.match(forward, /drop column if exists access_until/);
  assert.match(forward, /drop index if exists public\.account_entitlements_access_until_idx/);
});

test("snapshot relay is private, account-scoped, ephemeral, and carries source identity", async () => {
  const sql = (await readFile(privateSnapshotRelayMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /revoke select, insert on table realtime\.messages from anon/);
  assert.match(sql, /create policy beyoureyes_snapshot_broadcast_receive[\s\S]*for select[\s\S]*to authenticated/);
  assert.match(sql, /create policy beyoureyes_snapshot_broadcast_send[\s\S]*for insert[\s\S]*to authenticated/);
  assert.match(sql, /extension = 'broadcast'/);
  assert.match(sql, /beyoureyes:snapshots:' \|\| \(select auth\.uid\(\)\)::text/);
  assert.match(sql, /'monitoring_device_id', v_monitoring_device_id/);
  assert.doesNotMatch(sql, /storage\.objects|create table|image_url|media_url|ciphertext[\s_]*bytea/);
});

test("final Event sync schema keeps source identity and payload-free delete tombstones", async () => {
  const sql = (await readFile(eventSyncPrivacyReadinessMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /delete from public\.sync_changes c[\s\S]*c\.resource_type = 'event'/);
  assert.match(sql, /c\.resource_id = v_row\.event_id[\s\S]*c\.operation = 'upsert'[\s\S]*c\.value is not null/);
  assert.match(sql, /v_row\.account_id, 'event', 'delete', v_row\.event_id, null/);
  assert.match(sql, /'monitoring_device_id', v_monitoring_device_id/);
  assert.match(sql, /policyname = 'beyoureyes_snapshot_broadcast_receive'/);
  assert.match(sql, /not has_function_privilege\([\s\S]*'authenticated'[\s\S]*beyoureyes_purge_expired_events/);
});

test("historical Realtime ACL hardening remains explicit", async () => {
  const sql = (await readFile(realtimeAclReadinessMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /revoke all privileges on table realtime\.messages from anon, authenticated/);
  assert.match(sql, /grant select, insert on table realtime\.messages to authenticated/);
  assert.doesNotMatch(sql, /grant[^;]*to anon/);
});

test("Realtime readiness follows RLS policy scope instead of owner-managed grants", async () => {
  const sql = (await readFile(realtimePolicyReadinessMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /roles = array\['authenticated'\]::name\[\]/);
  assert.match(sql, /'anon' = any\(roles\) or 'public' = any\(roles\)/);
  assert.doesNotMatch(sql, /not has_table_privilege\('anon'/);
});

test("applied Task migrations stay immutable and the title rename is forward-only", async () => {
  const core = (await readFile(coreMigrationUrl, "utf8")).toLowerCase();
  const durable = (await readFile(durableMigrationUrl, "utf8")).toLowerCase();
  const alignment = (await readFile(taskTitleSchemaAlignmentMigrationUrl, "utf8")).toLowerCase();
  assert.match(core, /target_id text not null/);
  assert.doesNotMatch(core, /title text not null/);
  assert.match(durable, /'target_id', v_row\.target_id/);
  assert.match(alignment, /alter table public\.tasks rename column target_id to title/);
  assert.match(alignment, /add constraint tasks_title_check/);
  assert.match(alignment, /'title', v_row\.title/);
  assert.match(alignment, /'route_binding'/);
  assert.match(alignment, /'package_binding'/);
});

test("current Task and Event contracts deploy in a forward-only migration", async () => {
  const historical = (await readFile(eventSyncPrivacyReadinessMigrationUrl, "utf8")).toLowerCase();
  const current = (await readFile(currentTaskEventContractMigrationUrl, "utf8")).toLowerCase();
  const v4 = (await readFile(taskConfigV4MigrationUrl, "utf8")).toLowerCase();
  assert.doesNotMatch(historical, /beyoureyes_task_route_binding_is_valid/);
  assert.match(current, /create or replace function public\.beyoureyes_task_route_binding_is_valid/);
  assert.match(current, /create or replace function public\.beyoureyes_task_package_binding_is_valid/);
  assert.match(current, /'access_tier'/);
  assert.match(v4, /create or replace function public\.beyoureyes_task_package_binding_is_valid/);
  assert.match(v4, /'package_id', 'package_version', 'manifest_sha256',\s*'artifact_identity_sha256'/);
  const v4PackageValidator = v4.match(
    /create or replace function public\.beyoureyes_task_package_binding_is_valid[\s\S]*?\$\$;/,
  )?.[0] ?? "";
  assert.doesNotMatch(v4PackageValidator, /access_tier/);
  assert.match(v4, /create or replace function public\.beyoureyes_record_task_change/);
  assert.match(v4, /'schema_version', '4\.0'/);
  assert.match(v4, /beyoureyes_data_ready_pre_task_config_v4/);
  assert.match(current, /array\[\s*'task_id', 'revision', 'catalog_version', 'capability_id', 'title'/);
  assert.match(current, /create or replace function public\.beyoureyes_client_upsert_task_batch/);
  assert.match(current, /create or replace function public\.beyoureyes_client_insert_event_batch/);
});

test("structured reading v2 plus the forward contract define current reading shapes", async () => {
  const historical = (await readFile(structuredReadingV2MigrationUrl, "utf8")).toLowerCase();
  const current = (await readFile(currentTaskEventContractMigrationUrl, "utf8")).toLowerCase();
  assert.match(historical, /create or replace function public\.beyoureyes_confirmed_reading_format_is_valid/);
  assert.match(historical, /confirmed_reading_format_v2/);
  assert.match(historical, /create or replace function public\.beyoureyes_structured_reading_is_valid/);
  assert.match(historical, /p_reading->>'type' <> 'structured_reading'/);
  assert.match(current, /p_payload->'reading'->>'status' <> 'stable'/);
  assert.match(current, /p_target->'confirmed_format'/);
  assert.match(historical, /drop function if exists public\.beyoureyes_numeric_reading_is_valid\(jsonb\)/);
  assert.doesNotMatch(`${historical}\n${current}`, /region[^\n]*center|confirmed_numeric_scale/);
  assert.doesNotMatch(
    current,
    /'type',\s*'operator',\s*'(?:lower_threshold_decimal',\s*'upper_threshold_decimal'|threshold_decimal)'[\s\S]{0,120}'stable_frames'/,
  );
});

test("device revocation is account-derived, irreversible by normal upsert, and retires push state", async () => {
  const sql = (await readFile(deviceRevocationMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /create or replace function public\.beyoureyes_client_revoke_device\(p_device_id uuid\)/);
  assert.match(sql, /v_account_id uuid := auth\.uid\(\)/);
  assert.match(sql, /where account_id = v_account_id[\s\S]*and device_id = p_device_id/);
  assert.match(sql, /old\.revoked_at is not null[\s\S]*new\.revoked_at := old\.revoked_at/);
  assert.match(sql, /delete from public\.device_push_tokens[\s\S]*device_id = p_device_id/);
  assert.match(sql, /delete from public\.push_outbox[\s\S]*device_id = p_device_id/);
  assert.match(sql, /grant execute on function public\.beyoureyes_client_revoke_device\(uuid\)[\s\S]*to authenticated/);
  assert.doesNotMatch(sql, /grant execute on function public\.beyoureyes_client_revoke_device\(uuid\)[\s\S]*to anon/);
  assert.match(sql, /to_regprocedure\('public\.beyoureyes_client_revoke_device\(uuid\)'\)/);
});

test("device upsert grant stays narrow while Tasks use the account-derived monotonic batch RPC", async () => {
  const privilegeSql = (await readFile(clientUpsertPrivilegesMigrationUrl, "utf8")).toLowerCase();
  assert.match(privilegeSql, /grant update \(device_id\) on public\.devices to authenticated;/);
  assert.match(privilegeSql, /grant update \(task_id\) on public\.tasks to authenticated;/);
  assert.doesNotMatch(privilegeSql, /account_id|revision|created_at|updated_at/);
  assert.doesNotMatch(privilegeSql, /grant update on|grant all|to anon|to public|service_role/);

  const taskSql = (await readFile(taskRevisionBatchRpcMigrationUrl, "utf8")).toLowerCase();
  assert.match(taskSql, /revoke insert, update on public\.tasks from authenticated;/);
  assert.match(taskSql, /create or replace function public\.beyoureyes_client_upsert_task_batch\(p_tasks jsonb\)/);
  assert.match(taskSql, /v_account_id uuid := auth\.uid\(\)/);
  assert.match(taskSql, /excluded\.revision > public\.tasks\.revision/);
  assert.match(taskSql, /raise exception 'task_revision_conflict'/);
  assert.match(taskSql, /raise exception 'task_id_conflict'/);
  assert.match(taskSql, /create or replace function public\.beyoureyes_task_write_is_valid/);
  assert.match(taskSql, /create or replace function public\.beyoureyes_uuid_v7_is_valid/);
  assert.match(taskSql, /grant execute on function public\.beyoureyes_client_upsert_task_batch\(jsonb\)[\s\S]*to authenticated/);
  assert.doesNotMatch(taskSql, /grant execute on function public\.beyoureyes_client_upsert_task_batch\(jsonb\)[\s\S]*to anon/);

  const clientSql = (await readFile(clientDataPlaneMigrationUrl, "utf8")).toLowerCase();
  assert.match(clientSql, /new\.account_id := old\.account_id;[\s\S]*new\.device_id := old\.device_id;/);
  assert.match(clientSql, /new\.account_id := old\.account_id;[\s\S]*new\.task_id := old\.task_id;/);
  assert.doesNotMatch(clientSql, /grant update \([\s\S]*account_id[\s\S]*\) on public\.(devices|tasks)/);
});

test("push tokens are write-only RPC data and the service worker uses leased idempotent rows", async () => {
  const sql = (await readFile(pushDeliveryMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /create table if not exists public\.device_push_tokens/);
  assert.match(sql, /create table if not exists public\.push_outbox/);
  assert.match(sql, /unique \(account_id, event_id, device_id\)/);
  assert.match(sql, /alter table public\.device_push_tokens enable row level security/);
  assert.match(sql, /revoke all on public\.device_push_tokens, public\.push_outbox from public, anon, authenticated/);
  assert.match(sql, /create or replace function public\.beyoureyes_client_register_push_token/);
  assert.match(sql, /v_account_id uuid := auth\.uid\(\)/);
  assert.match(sql, /grant execute on function public\.beyoureyes_client_register_push_token\(uuid, text\)[\s\S]*to authenticated/);
  assert.match(sql, /create trigger events_enqueue_pushes[\s\S]*after insert on public\.events/);
  assert.match(sql, /new\.event_sequence::text/);
  assert.match(sql, /create or replace function public\.beyoureyes_claim_push_batch/);
  assert.match(sql, /for update of o skip locked/);
  assert.match(sql, /create or replace function public\.beyoureyes_complete_push/);
  assert.match(sql, /p_permanent_failure[\s\S]*disabled_at = p_now/);
  assert.match(sql, /grant execute on function public\.beyoureyes_claim_push_batch[\s\S]*to service_role/);
  assert.doesNotMatch(sql, /grant select[\s\S]*device_push_tokens[\s\S]*to authenticated/);
});

test("durable schema has account-scoped cursors, atomic event RPCs, and service-role-only server functions", async () => {
  const sql = (await readFile(durableMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /create table if not exists public\.sync_changes/);
  assert.match(sql, /alter table public\.sync_changes enable row level security/);
  assert.match(sql, /create policy sync_changes_same_account/);
  assert.match(sql, /event_sequence bigint generated always as identity/);
  assert.match(sql, /create or replace function public\.beyoureyes_insert_event_batch/);
  assert.match(sql, /on conflict \(account_id, event_id\) do nothing/);
  assert.match(sql, /raise exception 'event_id_conflict'/);
  assert.match(sql, /create or replace function public\.beyoureyes_add_event_receipt/);
  assert.match(sql, /create or replace function public\.beyoureyes_purge_account_data/);
  assert.match(sql, /create or replace function public\.beyoureyes_data_ready/);
  for (const fn of ["beyoureyes_insert_event_batch", "beyoureyes_add_event_receipt", "beyoureyes_purge_account_data"]) {
    assert.match(sql, new RegExp(`revoke all on function public\\.${fn}`));
    assert.match(sql, new RegExp(`grant execute on function public\\.${fn}[\\s\\S]*to service_role`));
  }
  assert.match(sql, /beyoureyes_jsonb_contains_media_key/);
});

test("client event and receipt RPCs derive account scope from auth.uid", async () => {
  const sql = (await readFile(clientDataPlaneMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /create or replace function public\.beyoureyes_client_insert_event_batch\(p_events jsonb\)/);
  assert.match(sql, /v_account_id uuid := auth\.uid\(\)/);
  assert.match(sql, /beyoureyes_insert_event_batch\(v_account_id, p_events\)/);
  assert.match(sql, /grant execute on function public\.beyoureyes_client_insert_event_batch\(jsonb\) to authenticated/);
  assert.match(sql, /create or replace function public\.beyoureyes_client_add_event_receipt/);
  assert.match(sql, /beyoureyes_add_event_receipt\([\s\S]*v_account_id/);
  assert.match(sql, /grant execute on function public\.beyoureyes_client_add_event_receipt[\s\S]*to authenticated/);
  assert.doesNotMatch(sql, /grant execute[\s\S]*beyoureyes_client_[\s\S]*to anon/);
  assert.match(sql, /to_regprocedure\('public\.beyoureyes_client_insert_event_batch\(jsonb\)'\)/);
  assert.match(sql, /has_column_privilege\('authenticated', 'public\.devices', 'display_name', 'insert'\)/);
  assert.match(sql, /not has_table_privilege\('authenticated', 'public\.events', 'insert'\)/);
  assert.match(sql, /tg_op = 'delete' and not exists \(select 1 from auth\.users where id = v_row\.account_id\)/);
});

test("30-day retention redacts sync payloads, preserves tombstones, and is service-only scheduled work", async () => {
  const sql = (await readFile(eventRetentionMigrationUrl, "utf8")).toLowerCase();
  assert.match(sql, /e\.received_at < p_now - interval '30 days'/);
  assert.match(sql, /and c\.resource_type = 'event'[\s\S]*and c\.resource_id = v_row\.event_id[\s\S]*and c\.value is not null/);
  assert.match(sql, /v_row\.account_id, 'event', 'delete', v_row\.event_id, null/);
  assert.match(sql, /c\.operation = 'delete'[\s\S]*c\.value is null[\s\S]*v_duplicates/);
  assert.match(sql, /create extension if not exists pg_cron/);
  assert.match(sql, /beyoureyes-event-retention-hourly-v1/);
  assert.match(sql, /'17 \* \* \* \*'/);
  assert.match(
    sql,
    /revoke all on function public\.beyoureyes_purge_expired_events\(uuid, timestamptz, integer\)[\s\S]*from public, anon, authenticated/,
  );
  assert.match(
    sql,
    /grant execute on function public\.beyoureyes_purge_expired_events\(uuid, timestamptz, integer\)[\s\S]*to service_role/,
  );
  assert.match(sql, /not has_function_privilege\([\s\S]*'authenticated'[\s\S]*beyoureyes_purge_expired_events/);
  assert.match(sql, /from cron\.job[\s\S]*jobname = 'beyoureyes-event-retention-hourly-v1'[\s\S]*and active/);
});
