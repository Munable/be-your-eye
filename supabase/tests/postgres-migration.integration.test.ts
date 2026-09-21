import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { validateContract } from "@be-your-eyes/catalog-validator/src/index.mjs";
import { uuidV7 } from "./support.js";

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

async function migratedDatabase(): Promise<PGlite> {
  const db = new PGlite();
  await db.waitReady;
  // Hosted Supabase provides these extensions. PGlite needs only empty control files because the
  // fixture below supplies the exact SQL surfaces exercised by the migrations.
  const extensionDirectory = "/pglite/share/postgresql/extension";
  for (const extension of ["pg_net", "pg_cron", "supabase_vault"]) {
    db.Module.FS.writeFile(
      `${extensionDirectory}/${extension}.control`,
      "default_version = '1.0'\nrelocatable = true\n",
    );
    db.Module.FS.writeFile(
      `${extensionDirectory}/${extension}--1.0.sql`,
      "-- PGlite hosted-extension fixture\n",
    );
  }
  await db.exec(`
    create role anon noinherit;
    create role authenticated noinherit;
    create role service_role noinherit bypassrls;
    create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    $$;

    -- PGlite does not bundle pg_cron. This fixture models only the named-job upsert contract
    -- used by the production migration; cleanup SQL itself still executes unchanged.
    create schema cron;
    create table cron.job(
      jobid bigint generated always as identity primary key,
      jobname text not null unique,
      schedule text not null,
      command text not null,
      active boolean not null default true
    );
    create function cron.schedule(p_jobname text, p_schedule text, p_command text)
    returns bigint
    language plpgsql
    as $$
    declare
      v_jobid bigint;
    begin
      insert into cron.job(jobname, schedule, command)
      values (p_jobname, p_schedule, p_command)
      on conflict (jobname) do update
        set schedule = excluded.schedule, command = excluded.command, active = true
      returning jobid into v_jobid;
      return v_jobid;
    end
    $$;
    create function cron.unschedule(p_jobid bigint)
    returns boolean
    language plpgsql
    as $$
    begin
      delete from cron.job where jobid = p_jobid;
      return found;
    end
    $$;

    create schema extensions;
    create schema net;
    create function net.http_post(
      url text,
      headers jsonb default '{}'::jsonb,
      body jsonb default '{}'::jsonb,
      timeout_milliseconds integer default 1000
    )
    returns bigint
    language sql
    as $$ select 1::bigint $$;

    create schema vault;
    create table vault.decrypted_secrets(
      name text primary key,
      decrypted_secret text not null
    );

    -- Supabase Realtime owns this table and channel-context function in hosted projects.
    create schema realtime;
    grant usage on schema realtime to anon, authenticated;
    create function realtime.topic()
    returns text
    language sql
    stable
    as $$ select nullif(current_setting('realtime.topic', true), '') $$;
    create table realtime.messages(
      id bigint generated always as identity primary key,
      extension text not null,
      payload jsonb
    );
    alter table realtime.messages enable row level security;
  `);
  await db.exec(await readFile(coreMigrationUrl, "utf8"));
  await db.exec(await readFile(durableMigrationUrl, "utf8"));
  await db.exec(await readFile(clientDataPlaneMigrationUrl, "utf8"));
  await db.exec(await readFile(pushDeliveryMigrationUrl, "utf8"));
  await db.exec(await readFile(eventPayloadContractMigrationUrl, "utf8"));
  await db.exec(await readFile(eventRetentionMigrationUrl, "utf8"));
  await db.exec(await readFile(clientUpsertPrivilegesMigrationUrl, "utf8"));
  await db.exec(await readFile(taskRevisionBatchRpcMigrationUrl, "utf8"));
  await db.exec(await readFile(deviceRevocationMigrationUrl, "utf8"));
  await db.exec(await readFile(edgePushDispatchMigrationUrl, "utf8"));
  await db.exec(await readFile(edgePushReadinessMigrationUrl, "utf8"));
  await db.exec(await readFile(structuredReadingV2MigrationUrl, "utf8"));
  await db.exec(await readFile(privateSnapshotRelayMigrationUrl, "utf8"));
  await db.exec(await readFile(eventSyncPrivacyReadinessMigrationUrl, "utf8"));
  await db.exec(await readFile(playEntitlementsMigrationUrl, "utf8"));
  await db.exec(await readFile(currentTaskEventContractMigrationUrl, "utf8"));
  await db.exec(await readFile(realtimeAclReadinessMigrationUrl, "utf8"));
  // Supabase Realtime owns this table and may refresh its platform grants after migrations.
  await db.exec("grant select, insert, update on realtime.messages to anon, authenticated");
  await db.exec(await readFile(realtimePolicyReadinessMigrationUrl, "utf8"));
  await db.exec(await readFile(taskTitleSchemaAlignmentMigrationUrl, "utf8"));
  await db.exec(await readFile(dynamicEntitlementLeasesMigrationUrl, "utf8"));
  await db.exec(await readFile(productAccessBoundaryMigrationUrl, "utf8"));
  await db.exec(await readFile(taskConfigV4MigrationUrl, "utf8"));
  await db.exec(await readFile(new URL("../migrations/202609170001_website_payments.sql", import.meta.url), "utf8"));
  await db.exec(await readFile(new URL("../migrations/202609170002_website_payment_terms.sql", import.meta.url), "utf8"));
  return db;
}

async function grantProductAccess(
  db: PGlite,
  accountId: string,
  identity: number,
  expiresAt = "2099-01-01T00:00:00.000Z",
): Promise<void> {
  const obfuscatedAccountId = identity.toString(16).padStart(64, "0");
  const tokenHash = (identity + 10_000).toString(16).padStart(64, "0");
  await db.query(
    `insert into public.account_entitlements(
       account_id, tier, source, product_id, obfuscated_account_id,
       purchase_token_sha256, subscription_state, expires_at, verified_at
     ) values ($1, 'pro', 'google_play', 'be_your_eye_pro', $2, $3,
       'SUBSCRIPTION_STATE_ACTIVE', $4, statement_timestamp())`,
    [accountId, obfuscatedAccountId, tokenHash, expiresAt],
  );
}

test("final schema keeps cumulative privacy and readiness gates", async () => {
  // Hosted has already applied the historical migrations. The new forward migration refreshes
  // the current Task/Event contracts without rewriting their recorded history.
  const db = await migratedDatabase();
  try {
    const ready = async (): Promise<boolean> => (
      await db.query<{ ready: boolean }>("select public.beyoureyes_data_ready() as ready")
    ).rows[0]?.ready ?? false;

    assert.equal(await ready(), true);

    const v4PackageBinding = {
      package_id: "efficientdet_lite2_object_v1",
      package_version: "1.0.0",
      manifest_sha256: "0".repeat(64),
      artifact_identity_sha256: "1".repeat(64),
    };
    const validV4Binding = await db.query<{ valid: boolean }>(
      "select public.beyoureyes_task_package_binding_is_valid($1::jsonb) as valid",
      [JSON.stringify(v4PackageBinding)],
    );
    assert.equal(validV4Binding.rows[0]?.valid, true);
    const legacyPackageBinding = await db.query<{ valid: boolean }>(
      "select public.beyoureyes_task_package_binding_is_valid($1::jsonb) as valid",
      [JSON.stringify({ ...v4PackageBinding, access_tier: "free" })],
    );
    assert.equal(legacyPackageBinding.rows[0]?.valid, false);

    const taskColumns = await db.query<{ column_name: string }>(
      `select column_name from information_schema.columns
       where table_schema = 'public' and table_name = 'tasks'
         and column_name in ('target_id', 'title')
       order by column_name`,
    );
    assert.deepEqual(taskColumns.rows, [{ column_name: "title" }]);
    const taskRecorder = await db.query<{ source: string }>(
      `select p.prosrc as source from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname = 'beyoureyes_record_task_change'`,
    );
    assert.match(taskRecorder.rows[0]!.source, /'title', v_row\.title/);
    assert.match(taskRecorder.rows[0]!.source, /'route_binding'/);
    assert.match(taskRecorder.rows[0]!.source, /'package_binding'/);
    assert.equal((await db.query<{ granted: boolean }>(
      "select has_table_privilege('anon', 'realtime.messages', 'SELECT') as granted",
    )).rows[0]?.granted, true);
    await db.exec(`
      create policy unexpected_anon_snapshot_access
      on realtime.messages for select to anon using (true)
    `);
    assert.equal(await ready(), false);
    await db.exec("drop policy unexpected_anon_snapshot_access on realtime.messages");
    assert.equal(await ready(), true);
    const eventRecorder = await db.query<{ source: string }>(
      `select p.prosrc as source
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname = 'beyoureyes_record_event_change'`,
    );
    assert.match(eventRecorder.rows[0]!.source, /delete from public\.sync_changes c/);
    assert.match(eventRecorder.rows[0]!.source, /c\.operation = 'upsert'/);
    assert.match(eventRecorder.rows[0]!.source, /'monitoring_device_id', v_monitoring_device_id/);

    const snapshotPolicies = await db.query<{ policyname: string; cmd: string }>(
      `select policyname, cmd
       from pg_policies
       where schemaname = 'realtime' and tablename = 'messages'
       order by policyname`,
    );
    assert.deepEqual(snapshotPolicies.rows, [
      { policyname: "beyoureyes_snapshot_broadcast_receive", cmd: "SELECT" },
      { policyname: "beyoureyes_snapshot_broadcast_send", cmd: "INSERT" },
    ]);

    await db.exec("update cron.job set active = false where jobname = 'beyoureyes-event-retention-hourly-v1'");
    assert.equal(await ready(), false);
    await db.exec("update cron.job set active = true where jobname = 'beyoureyes-event-retention-hourly-v1'");
    assert.equal(await ready(), true);

    await db.exec("drop policy beyoureyes_snapshot_broadcast_receive on realtime.messages");
    assert.equal(await ready(), false);
    await db.exec(`
      create policy beyoureyes_snapshot_broadcast_receive
      on realtime.messages
      for select
      to authenticated
      using (
        extension = 'broadcast'
        and (select realtime.topic()) =
          'beyoureyes:snapshots:' || (select auth.uid())::text
        and (select public.beyoureyes_has_active_entitlement())
      )
    `);
    assert.equal(await ready(), true);

    await db.exec(
      "grant execute on function public.beyoureyes_purge_expired_events(uuid, timestamptz, integer) to authenticated",
    );
    assert.equal(await ready(), false);
    await db.exec(
      "revoke execute on function public.beyoureyes_purge_expired_events(uuid, timestamptz, integer) from authenticated",
    );
    assert.equal(await ready(), true);

    const base = Date.parse("2026-08-20T00:00:00Z");
    const accountId = "5f6d2f57-f3f7-4c4e-96c7-94f1f1557065";
    const deviceId = uuidV7(base);
    const taskId = uuidV7(base + 1);
    const taskConfig = {
      target_definition: { mode: "reference_images" },
      roi: { left: 0, top: 0, right: 1, bottom: 1 },
      sampling_policy: { mode: "package_default" },
      route_binding: {
        model_profile_key: "reference_object_matching",
        recipe_id: "reference_match_general_v1",
        intent_key: "visual.reference.user_target",
      },
      package_binding: {
        package_id: "reference_match_target_v1",
        package_version: "1.0.0",
        manifest_sha256: "3".repeat(64),
        artifact_identity_sha256: "a".repeat(64),
      },
      rule: {
        type: "presence_duration",
        condition: "remains",
        duration_ms: 3000,
        min_positive_count: 5,
        max_positive_gap_ms: 1000,
        rearm_absence_ms: 3000,
      },
    };
    const taskWrite = {
      task_id: taskId,
      revision: 1,
      catalog_version: "2026.08.20.1",
      capability_id: "visual_target",
      title: "门口有人",
      monitoring_device_id: deviceId,
      config: taskConfig,
    };
    const readingRule = {
      type: "reading_threshold",
      operator: "gte",
      threshold_decimal: "12.0",
      duration_ms: 3000,
      hysteresis_decimal: "0.5",
      cooldown_ms: 0,
    };
    const { duration_ms: _removedDuration, ...readingRuleWithoutDuration } = readingRule;
    assert.equal((await db.query<{ valid: boolean }>(
      "select public.beyoureyes_task_rule_is_valid('structured_reading', $1::jsonb) as valid",
      [JSON.stringify(readingRule)],
    )).rows[0]?.valid, true);
    assert.equal((await db.query<{ valid: boolean }>(
      "select public.beyoureyes_task_rule_is_valid('structured_reading', $1::jsonb) as valid",
      [JSON.stringify(readingRuleWithoutDuration)],
    )).rows[0]?.valid, false);

    await db.query("insert into auth.users(id) values ($1)", [accountId]);
    await grantProductAccess(db, accountId, 1);
    await db.query(
      "insert into public.devices(device_id, account_id, display_name) values ($1, $2, 'hosted-patch')",
      [deviceId, accountId],
    );
    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountId]);
    const taskResult = await db.query<{
      result: { upserted_ids: string[]; unchanged_ids: string[] };
    }>(
      "select public.beyoureyes_client_upsert_task_batch($1::jsonb) as result",
      [JSON.stringify([taskWrite])],
    );
    assert.deepEqual(taskResult.rows[0]?.result.upserted_ids, [taskId]);
    assert.deepEqual(taskResult.rows[0]?.result.unchanged_ids, []);

    const { condition: _removedCondition, ...ruleWithoutCondition } = taskConfig.rule;
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{
          ...taskWrite,
          task_id: uuidV7(base + 2),
          config: { ...taskConfig, rule: ruleWithoutCondition },
        }]),
      ]),
      /invalid_task_contract/,
    );

    const event = {
      schema_version: "3.0",
      event_id: uuidV7(base + 3),
      task_id: taskId,
      task_revision: 1,
      episode_id: uuidV7(base + 4),
      source_sequence: 1,
      occurred_at: "2026-08-20T00:00:03.000Z",
      payload: {
        type: "visual_condition_met",
        target_id: "person",
        condition: "present_for_duration",
        duration_ms: 3000,
      },
    };
    const eventResult = await db.query<{
      result: { accepted_ids: string[]; duplicate_ids: string[] };
    }>(
      "select public.beyoureyes_client_insert_event_batch($1::jsonb) as result",
      [JSON.stringify([event])],
    );
    assert.deepEqual(eventResult.rows[0]?.result, {
      accepted_ids: [event.event_id],
      duplicate_ids: [],
    });
    await assert.rejects(
      db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
        JSON.stringify([{
          ...event,
          event_id: uuidV7(base + 5),
          episode_id: uuidV7(base + 6),
          source_sequence: 2,
          payload: { ...event.payload, condition: "appeared" },
        }]),
      ]),
      /event_payload_invalid/,
    );
    await db.exec("reset role");
  } finally {
    await db.close();
  }
});

test("device revocation is same-account, retires push state, and cannot be undone by upsert", async () => {
  const db = await migratedDatabase();
  try {
    const accountA = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864";
    const accountB = "fb0348f8-7f14-4e2c-a942-20b3b1727f19";
    const deviceA = uuidV7(Date.parse("2026-08-04T00:00:00Z"));
    await db.query("insert into auth.users(id) values ($1), ($2)", [accountA, accountB]);
    await grantProductAccess(db, accountA, 2);
    await db.query(
      "insert into public.devices(device_id, account_id, display_name, notifications_enabled) values ($1, $2, 'A', true)",
      [deviceA, accountA],
    );

    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountA]);
    await db.query("select public.beyoureyes_client_register_push_token($1, $2)", [
      deviceA,
      "fcm-device-a-token-0123456789",
    ]);
    assert.equal((await db.query<{ changed: boolean }>(
      "select public.beyoureyes_client_revoke_device($1) as changed",
      [deviceA],
    )).rows[0]?.changed, true);
    assert.equal((await db.query<{ changed: boolean }>(
      "select public.beyoureyes_client_revoke_device($1) as changed",
      [deviceA],
    )).rows[0]?.changed, false);
    await db.exec("reset role");
    assert.equal((await db.query<{ count: number }>(
      "select count(*)::int as count from public.device_push_tokens where account_id = $1 and device_id = $2",
      [accountA, deviceA],
    )).rows[0]?.count, 0);

    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountA]);
    await db.query(
      `insert into public.devices(
         device_id, schema_version, device_profile, display_name, android_api, abi,
         memory_mb, gms_available, notifications_enabled, app_version, revoked_at
       ) values ($1, '3.0', 'android_arm64_8gb_launch_v1', 'replayed', 36,
         'arm64-v8a', 8192, true, true, '0.1.0', null)
       on conflict (device_id) do update set
         display_name = excluded.display_name,
         revoked_at = excluded.revoked_at`,
      [deviceA],
    );
    assert.equal((await db.query<{ revoked: boolean }>(
      "select revoked_at is not null as revoked from public.devices where account_id = $1 and device_id = $2",
      [accountA, deviceA],
    )).rows[0]?.revoked, true);

    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountB]);
    assert.equal((await db.query<{ changed: boolean }>(
      "select public.beyoureyes_client_revoke_device($1) as changed",
      [deviceA],
    )).rows[0]?.changed, false);
    await db.exec("reset role");

    assert.equal((await db.query<{ ready: boolean }>(
      "select public.beyoureyes_data_ready() as ready",
    )).rows[0]?.ready, true);
  } finally {
    await db.close();
  }
});

test("expired access keeps account controls open while product data, Realtime, and FCM fail closed", async () => {
  const db = await migratedDatabase();
  try {
    const accountId = "e3b2721e-f7c7-4b3f-97d3-10cebf723290";
    const base = Date.parse("2026-08-31T00:00:00Z");
    const deviceId = uuidV7(base);
    const taskId = uuidV7(base + 1);
    await db.query("insert into auth.users(id) values ($1)", [accountId]);
    await grantProductAccess(db, accountId, 5);
    await db.query(
      "insert into public.devices(device_id, account_id, display_name, notifications_enabled) values ($1, $2, 'locked-device', true)",
      [deviceId, accountId],
    );

    const taskWrite = {
      task_id: taskId,
      revision: 1,
      catalog_version: "v3",
      capability_id: "visual_target",
      title: "person",
      monitoring_device_id: deviceId,
      config: {
        target_definition: { mode: "reference_images" },
        roi: { left: 0, top: 0, right: 1, bottom: 1 },
        sampling_policy: { mode: "package_default" },
        route_binding: {
          model_profile_key: "reference_object_matching",
          recipe_id: "reference_match_general_v1",
          intent_key: "visual.reference.user_target",
        },
        package_binding: {
          package_id: "reference_match_target_v1",
          package_version: "1.0.0",
          manifest_sha256: "3".repeat(64),
          artifact_identity_sha256: "a".repeat(64),
        },
        rule: {
          type: "presence_duration",
          condition: "appears",
          duration_ms: 500,
          min_positive_count: 2,
          max_positive_gap_ms: 1500,
          rearm_absence_ms: 5000,
        },
      },
    };

    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountId]);
    await db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
      JSON.stringify([taskWrite]),
    ]);
    await db.query("select public.beyoureyes_client_register_push_token($1, $2)", [
      deviceId,
      "fcm-expiry-boundary-token-0123456789",
    ]);
    const eventId = uuidV7(base + 2);
    await db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
      JSON.stringify([{
        schema_version: "3.0",
        event_id: eventId,
        task_id: taskId,
        task_revision: 1,
        episode_id: uuidV7(base + 3),
        source_sequence: 1,
        occurred_at: "2026-08-31T00:00:03.000Z",
        payload: {
          type: "object_episode",
          target_id: "person",
          condition: "appeared",
          duration_ms: 0,
          count: 1,
        },
      }]),
    ]);
    await db.exec("reset role");

    await db.query(
      `update public.account_entitlements
       set subscription_state = 'SUBSCRIPTION_STATE_EXPIRED', expires_at = '2026-08-31T00:00:04Z'
       where account_id = $1`,
      [accountId],
    );
    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountId]);
    assert.equal((await db.query<{ active: boolean }>(
      "select public.beyoureyes_has_active_entitlement() as active",
    )).rows[0]?.active, false);
    assert.deepEqual((await db.query<{ device_id: string }>(
      "select device_id from public.devices",
    )).rows, [{ device_id: deviceId }]);
    assert.deepEqual((await db.query<{ task_id: string }>(
      "select task_id from public.tasks",
    )).rows, []);
    assert.deepEqual((await db.query<{ device_id: string }>(
      "update public.devices set display_name = 'blocked' where device_id = $1 returning device_id",
      [deviceId],
    )).rows, []);
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{ ...taskWrite, revision: 2 }]),
      ]),
      /subscription_required/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_client_register_push_token($1, $2)", [
        deviceId,
        "fcm-expired-replacement-token-0123456789",
      ]),
      /subscription_required/,
    );
    await db.query("select set_config('realtime.topic', $1, false)", [
      `beyoureyes:snapshots:${accountId}`,
    ]);
    await assert.rejects(
      db.query("insert into realtime.messages(extension, payload) values ('broadcast', '{}'::jsonb)"),
      /row-level security policy/,
    );
    await db.exec("reset role");

    const claimed = await db.query<{ outbox_id: number }>(
      "select outbox_id from public.beyoureyes_claim_push_batch(10, $1, $2)",
      ["c3678645-134d-449b-af34-7bb6dcf418fc", "2026-08-31T00:00:05Z"],
    );
    assert.deepEqual(claimed.rows, []);
    assert.deepEqual((await db.query<{ status: string; last_error: string }>(
      "select status, last_error from public.push_outbox where account_id = $1 and event_id = $2",
      [accountId, eventId],
    )).rows, [{ status: "dead", last_error: "entitlement_inactive" }]);

    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountId]);
    assert.equal((await db.query<{ changed: boolean }>(
      "select public.beyoureyes_client_revoke_device($1) as changed",
      [deviceId],
    )).rows[0]?.changed, true);
    await db.exec("reset role");
  } finally {
    await db.close();
  }
});

test("migrations execute on PostgreSQL and enforce atomic events, RLS, receipts, sync, and purge", async () => {
  const db = await migratedDatabase();
  try {
    const accountA = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864";
    const accountB = "fb0348f8-7f14-4e2c-a942-20b3b1727f19";
    const base = Date.parse("2026-08-02T00:00:00Z");
    const deviceA = uuidV7(base);
    const deviceB = uuidV7(base + 1);
    const taskA = uuidV7(base + 2);
    await db.query("insert into auth.users(id) values ($1), ($2)", [accountA, accountB]);
    await grantProductAccess(db, accountA, 3);
    await grantProductAccess(db, accountB, 4);
    await db.query(
      "insert into public.devices(device_id, account_id, display_name, notifications_enabled) values ($1, $2, 'A', true), ($3, $4, 'B', false)",
      [deviceA, accountA, deviceB, accountB],
    );
    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountA]);
    await db.query("select public.beyoureyes_client_register_push_token($1, $2)", [
      deviceA,
      "fcm-device-a-token-0123456789",
    ]);
    await db.exec("reset role");
    const taskConfig = {
      target_definition: { mode: "reference_images" },
      roi: { left: 0, top: 0, right: 1, bottom: 1 },
      sampling_policy: { mode: "package_default" },
      route_binding: {
        model_profile_key: "reference_object_matching",
        recipe_id: "reference_match_general_v1",
        intent_key: "visual.reference.user_target",
      },
      package_binding: {
        package_id: "reference_match_target_v1",
        package_version: "1.0.0",
        manifest_sha256: "3".repeat(64),
        artifact_identity_sha256: "a".repeat(64),
      },
      rule: {
        type: "presence_duration",
        condition: "appears",
        duration_ms: 500,
        min_positive_count: 2,
        max_positive_gap_ms: 1500,
        rearm_absence_ms: 5000,
      },
    };
    await db.query(
      "insert into public.tasks(task_id, account_id, catalog_version, capability_id, title, monitoring_device_id, config) values ($1, $2, 'v3', 'visual_target', '门口有人', $3, $4::jsonb)",
      [taskA, accountA, deviceA, JSON.stringify(taskConfig)],
    );
    assert.equal((await db.query<{ valid: boolean }>(
      "select public.beyoureyes_task_rule_is_valid('visual_target', $1::jsonb) as valid",
      [JSON.stringify({
        type: "absence_duration",
        condition: "disappears",
        duration_ms: 3000,
        min_negative_count: 7,
        max_observation_gap_ms: 1000,
        rearm_presence_ms: 3000,
      })],
    )).rows[0]?.valid, true);

    const eventId = uuidV7(base + 3);
    const event = {
      schema_version: "3.0",
      event_id: eventId,
      task_id: taskA,
      task_revision: 1,
      episode_id: uuidV7(base + 4),
      source_sequence: 1,
      occurred_at: "2026-08-02T00:00:05.000Z",
      payload: { type: "object_episode", target_id: "person", condition: "appeared", duration_ms: 0, count: 1 },
    };
    const inserted = await db.query<{ result: { accepted_ids: string[]; duplicate_ids: string[] } }>(
      "select public.beyoureyes_insert_event_batch($1, $2::jsonb) as result",
      [accountA, JSON.stringify([event])],
    );
    assert.deepEqual(inserted.rows[0]?.result, { accepted_ids: [eventId], duplicate_ids: [] });
    const queuedPush = await db.query<{
      event_id: string;
      device_id: string;
      cursor_hint: string;
      status: string;
      attempts: number;
    }>(
      "select event_id, device_id, cursor_hint, status, attempts from public.push_outbox where account_id = $1",
      [accountA],
    );
    assert.deepEqual(queuedPush.rows, [{
      event_id: eventId,
      device_id: deviceA,
      cursor_hint: "1",
      status: "pending",
      attempts: 0,
    }]);
    const duplicate = await db.query<{ result: { accepted_ids: string[]; duplicate_ids: string[] } }>(
      "select public.beyoureyes_insert_event_batch($1, $2::jsonb) as result",
      [accountA, JSON.stringify([event])],
    );
    assert.deepEqual(duplicate.rows[0]?.result, { accepted_ids: [], duplicate_ids: [eventId] });
    assert.equal((await db.query<{ count: number }>(
      "select count(*)::int as count from public.push_outbox where account_id = $1 and event_id = $2",
      [accountA, eventId],
    )).rows[0]?.count, 1);

    const leaseId = "84e0f63f-e594-42b5-9c41-c68001538861";
    const claimed = await db.query<{
      outbox_id: number;
      event_id: string;
      device_id: string;
      token: string;
      cursor_hint: string;
    }>(
      "select * from public.beyoureyes_claim_push_batch(10, $1, $2)",
      [leaseId, "2030-08-02T00:00:10.000Z"],
    );
    assert.equal(claimed.rows.length, 1);
    assert.equal(claimed.rows[0]?.event_id, eventId);
    assert.equal(claimed.rows[0]?.device_id, deviceA);
    assert.equal(claimed.rows[0]?.token, "fcm-device-a-token-0123456789");
    assert.equal(claimed.rows[0]?.cursor_hint, "1");
    assert.equal((await db.query<{ completed: boolean }>(
      "select public.beyoureyes_complete_push($1, $2, true, false, null, $3) as completed",
      [claimed.rows[0]!.outbox_id, leaseId, "2030-08-02T00:00:11.000Z"],
    )).rows[0]?.completed, true);
    assert.deepEqual((await db.query<{ status: string; attempts: number }>(
      "select status, attempts from public.push_outbox where outbox_id = $1",
      [claimed.rows[0]!.outbox_id],
    )).rows[0], { status: "sent", attempts: 1 });

    await assert.rejects(
      db.query("select public.beyoureyes_insert_event_batch($1, $2::jsonb)", [
        accountA,
        JSON.stringify([{ ...event, payload: { ...event.payload, count: 2 } }]),
      ]),
      /event_id_conflict/,
    );
    assert.equal((await db.query<{ valid: boolean }>(
      "select public.beyoureyes_event_payload_is_valid($1::jsonb) as valid",
      [JSON.stringify({
        type: "visual_condition_met",
        target_id: "person",
        condition: "present_for_duration",
        duration_ms: 3000,
      })],
    )).rows[0]?.valid, true);
    assert.equal((await db.query<{ valid: boolean }>(
      "select public.beyoureyes_event_payload_is_valid($1::jsonb) as valid",
      [JSON.stringify({
        type: "visual_condition_met",
        target_id: "person",
        condition: "absent_for_duration",
        duration_ms: 3000,
      })],
    )).rows[0]?.valid, true);
    assert.equal((await db.query<{ valid: boolean }>(
      "select public.beyoureyes_event_payload_is_valid($1::jsonb) as valid",
      [JSON.stringify({
        type: "visual_condition_met",
        target_id: "person",
        condition: "appeared",
        duration_ms: 3000,
      })],
    )).rows[0]?.valid, false);

    const rolledBackId = uuidV7(base + 6);
    const missingTaskId = uuidV7(base + 7);
    await assert.rejects(
      db.query("select public.beyoureyes_insert_event_batch($1, $2::jsonb)", [
        accountA,
        JSON.stringify([
          { ...event, event_id: rolledBackId, episode_id: uuidV7(base + 8), source_sequence: 2 },
          { ...event, event_id: uuidV7(base + 9), task_id: missingTaskId, episode_id: uuidV7(base + 10), source_sequence: 3 },
        ]),
      ]),
      /task_not_found/,
    );
    const rolledBack = await db.query<{ count: number }>(
      "select count(*)::int as count from public.events where account_id = $1 and event_id = $2",
      [accountA, rolledBackId],
    );
    assert.equal(rolledBack.rows[0]?.count, 0);

    const receipt = await db.query<{ result: { received_at: string } }>(
      "select public.beyoureyes_add_event_receipt($1, $2, $3, 'displayed', $4) as result",
      [accountA, eventId, deviceA, "2026-08-02T00:00:06.000Z"],
    );
    const receiptRetry = await db.query<{ result: { received_at: string } }>(
      "select public.beyoureyes_add_event_receipt($1, $2, $3, 'displayed', $4) as result",
      [accountA, eventId, deviceA, "2026-08-02T00:00:07.000Z"],
    );
    assert.equal(receiptRetry.rows[0]?.result.received_at, receipt.rows[0]?.result.received_at);

    const changes = await db.query<{
      sequence: number;
      account_id: string;
      resource_type: "device" | "task" | "event";
      operation: "upsert";
      resource_id: string;
      value: unknown;
      created_at: string | Date;
    }>(
      "select sequence, account_id, resource_type, operation, resource_id, value, created_at from public.sync_changes where account_id = $1 order by sequence",
      [accountA],
    );
    assert.deepEqual(changes.rows.map((row) => row.resource_type), ["device", "task", "event"]);
    assert.equal(changes.rows.every((row) => row.account_id === accountA), true);
    assert.equal(
      (changes.rows.find((row) => row.resource_type === "event")?.value as {
        monitoring_device_id?: string;
      }).monitoring_device_id,
      deviceA,
    );
    const syncBody = {
      schema_version: "3.0",
      cursor: `sync_${changes.rows.at(-1)!.sequence}`,
      has_more: false,
      changes: changes.rows.map((row) => ({
        change_id: `${row.resource_type}:${row.resource_id}:${row.sequence}`,
        entity_type: row.resource_type,
        operation: row.operation,
        updated_at: new Date(row.created_at).toISOString(),
        entity: row.value,
      })),
    };
    const syncValidation = validateContract("sync", syncBody);
    assert.equal(syncValidation.ok, true, JSON.stringify(syncValidation.errors));

    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountA]);
    const clientUpdatePrivileges = await db.query<{ table_name: string; column_name: string }>(
      `select table_name, column_name
       from information_schema.column_privileges
       where table_schema = 'public'
         and grantee = 'authenticated'
         and privilege_type = 'UPDATE'
         and table_name in ('devices', 'tasks')
       order by table_name, column_name`,
    );
    assert.deepEqual(clientUpdatePrivileges.rows, [
      { table_name: "devices", column_name: "abi" },
      { table_name: "devices", column_name: "android_api" },
      { table_name: "devices", column_name: "app_version" },
      { table_name: "devices", column_name: "device_id" },
      { table_name: "devices", column_name: "device_profile" },
      { table_name: "devices", column_name: "display_name" },
      { table_name: "devices", column_name: "gms_available" },
      { table_name: "devices", column_name: "memory_mb" },
      { table_name: "devices", column_name: "notifications_enabled" },
      { table_name: "devices", column_name: "revoked_at" },
      { table_name: "devices", column_name: "schema_version" },
    ]);
    const visibleToA = await db.query<{ account_id: string }>("select account_id from public.devices order by account_id");
    assert.deepEqual(visibleToA.rows.map((row) => row.account_id), [accountA]);
    for (const table of ["tasks", "events", "event_receipts", "sync_changes"] as const) {
      const visible = await db.query<{ account_id: string }>(`select account_id from public.${table}`);
      assert.equal(visible.rows.length > 0, true, `${table} should expose account A rows to account A`);
      assert.equal(visible.rows.every((row) => row.account_id === accountA), true, `${table} leaked another account`);
    }
    const clientDevice = uuidV7(base + 11);
    const insertedClientDevice = await db.query<{ account_id: string; revision: number }>(
      "insert into public.devices(device_id, display_name, notifications_enabled) values ($1, 'client peer', true) returning account_id, revision",
      [clientDevice],
    );
    assert.deepEqual(insertedClientDevice.rows[0], { account_id: accountA, revision: 1 });
    // Mirrors the Android PostgREST upsert payload. PostgREST includes the conflict
    // key in the UPDATE branch, which is why migration 005 grants only device_id.
    const upsertedClientDevice = await db.query<{
      account_id: string;
      revision: number;
      notifications_enabled: boolean;
    }>(
      `insert into public.devices(
        device_id, schema_version, device_profile, display_name, android_api, abi,
        memory_mb, gms_available, notifications_enabled, app_version, revoked_at
      ) values ($1, '3.0', 'android_arm64_8gb_launch_v1', 'client peer', 36,
        'arm64-v8a', 8192, true, false, '0.1.0', null)
      on conflict (device_id) do update set
        device_id = excluded.device_id,
        schema_version = excluded.schema_version,
        device_profile = excluded.device_profile,
        display_name = excluded.display_name,
        android_api = excluded.android_api,
        abi = excluded.abi,
        memory_mb = excluded.memory_mb,
        gms_available = excluded.gms_available,
        notifications_enabled = excluded.notifications_enabled,
        app_version = excluded.app_version,
        revoked_at = excluded.revoked_at
      returning account_id, revision, notifications_enabled`,
      [clientDevice],
    );
    assert.deepEqual(upsertedClientDevice.rows[0], {
      account_id: accountA,
      revision: 2,
      notifications_enabled: false,
    });
    const attemptedDeviceIdentity = uuidV7(base + 26);
    const immutableClientDevice = await db.query<{ device_id: string; revision: number }>(
      "update public.devices set device_id = $2 where device_id = $1 returning device_id, revision",
      [clientDevice, attemptedDeviceIdentity],
    );
    assert.deepEqual(immutableClientDevice.rows[0], { device_id: clientDevice, revision: 3 });
    await assert.rejects(
      db.query("update public.devices set account_id = $2 where device_id = $1", [clientDevice, accountB]),
      /permission denied/,
    );
    await db.query("select public.beyoureyes_client_register_push_token($1, $2)", [
      clientDevice,
      "fcm-client-device-token-0123456789",
    ]);
    await assert.rejects(
      db.query("select token from public.device_push_tokens"),
      /permission denied/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_event_payload_is_valid('{}'::jsonb)"),
      /permission denied/,
    );
    const updatedClientDevice = await db.query<{ revision: number; notifications_enabled: boolean }>(
      "update public.devices set notifications_enabled = false where device_id = $1 and revision = 3 returning revision, notifications_enabled",
      [clientDevice],
    );
    assert.deepEqual(updatedClientDevice.rows[0], { revision: 4, notifications_enabled: false });
    const reenabledClientDevice = await db.query<{ revision: number; notifications_enabled: boolean }>(
      "update public.devices set notifications_enabled = true where device_id = $1 and revision = 4 returning revision, notifications_enabled",
      [clientDevice],
    );
    assert.deepEqual(reenabledClientDevice.rows[0], { revision: 5, notifications_enabled: true });

    const clientTask = uuidV7(base + 12);
    const clientTaskWrite = {
      task_id: clientTask,
      revision: 2,
      catalog_version: "v3",
      capability_id: "visual_target",
      title: "门口有人",
      monitoring_device_id: clientDevice,
      config: taskConfig,
    };
    // A direct table insert/update no longer owns Task revisions.
    await assert.rejects(
      db.query(
        "insert into public.tasks(task_id, catalog_version, capability_id, title, monitoring_device_id, config) values ($1, 'v3', 'visual_target', 'direct', $2, $3::jsonb)",
        [clientTask, clientDevice, JSON.stringify(taskConfig)],
      ),
      /permission denied/,
    );
    const firstTaskUpload = await db.query<{
      result: {
        upserted_ids: string[];
        unchanged_ids: string[];
        task_revisions: Array<{ task_id: string; revision: number }>;
      };
    }>(
      "select public.beyoureyes_client_upsert_task_batch($1::jsonb) as result",
      [JSON.stringify([clientTaskWrite])],
    );
    assert.deepEqual(firstTaskUpload.rows[0]?.result, {
      upserted_ids: [clientTask],
      unchanged_ids: [],
      task_revisions: [{ task_id: clientTask, revision: 2 }],
    });
    const insertedClientTask = await db.query<{
      account_id: string;
      revision: number;
      title: string;
    }>(
      "select account_id, revision, title from public.tasks where task_id = $1",
      [clientTask],
    );
    assert.deepEqual(insertedClientTask.rows[0], {
      account_id: accountA,
      revision: 2,
      title: "门口有人",
    });

    const identicalRetry = await db.query<{ result: { upserted_ids: string[]; unchanged_ids: string[] } }>(
      "select public.beyoureyes_client_upsert_task_batch($1::jsonb) as result",
      [JSON.stringify([clientTaskWrite])],
    );
    assert.deepEqual(identicalRetry.rows[0]?.result.upserted_ids, []);
    assert.deepEqual(identicalRetry.rows[0]?.result.unchanged_ids, [clientTask]);

    const staleRetry = await db.query<{ result: { upserted_ids: string[]; unchanged_ids: string[] } }>(
      "select public.beyoureyes_client_upsert_task_batch($1::jsonb) as result",
      [JSON.stringify([{ ...clientTaskWrite, revision: 1, title: "stale" }])],
    );
    assert.deepEqual(staleRetry.rows[0]?.result.upserted_ids, []);
    assert.deepEqual(staleRetry.rows[0]?.result.unchanged_ids, [clientTask]);
    assert.deepEqual((await db.query<{ revision: number; title: string }>(
      "select revision, title from public.tasks where task_id = $1",
      [clientTask],
    )).rows[0], { revision: 2, title: "门口有人" });

    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{ ...clientTaskWrite, title: "same-revision-different-bytes" }]),
      ]),
      /task_revision_conflict/,
    );
    const rolledBackTask = uuidV7(base + 30);
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([
          { ...clientTaskWrite, task_id: rolledBackTask, revision: 3 },
          { ...clientTaskWrite, title: "same-revision-batch-conflict" },
        ]),
      ]),
      /task_revision_conflict/,
    );
    assert.equal((await db.query<{ count: number }>(
      "select count(*)::int as count from public.tasks where task_id = $1",
      [rolledBackTask],
    )).rows[0]?.count, 0);
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{ ...clientTaskWrite, unexpected: true }]),
      ]),
      /invalid_task_contract/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{
          ...clientTaskWrite,
          task_id: uuidV7(base + 33),
          revision: 1,
          config: {
            ...taskConfig,
            rule: { ...taskConfig.rule, condition: "disappears" },
          },
        }]),
      ]),
      /invalid_task_contract/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{ ...clientTaskWrite, task_id: "550e8400-e29b-41d4-a716-446655440000" }]),
      ]),
      /invalid_task_contract/,
    );

    const structuredTask = uuidV7(base + 31);
    const structuredTaskWrite = {
      task_id: structuredTask,
      revision: 1,
      catalog_version: "2026.08.13.1",
      capability_id: "structured_reading",
      title: "数字读数",
      monitoring_device_id: clientDevice,
      config: {
        target_definition: {
          mode: "none",
          confirmed_format: {
            profile_id: "confirmed_reading_format_v2",
            kind: "time",
            fractional_digits: 0,
            time_segments: 3,
            unit: null,
          },
        },
        roi: { left: 0, top: 0, right: 1, bottom: 1 },
        sampling_policy: { mode: "package_default" },
        route_binding: {
          model_profile_key: "numeric_display_reading",
          recipe_id: "reading_pipeline_general_v1",
          intent_key: "reading.numeric.display",
        },
        package_binding: {
          package_id: "reading_pipeline_a_v1",
          package_version: "1.0.0",
          manifest_sha256: "4".repeat(64),
          artifact_identity_sha256: "b".repeat(64),
        },
        rule: {
          type: "reading_threshold",
          operator: "gte",
          threshold_decimal: "142",
          duration_ms: 1000,
          hysteresis_decimal: "0",
          cooldown_ms: 0,
        },
      },
    };
    const structuredTaskUpload = await db.query<{
      result: { upserted_ids: string[]; task_revisions: Array<{ task_id: string; revision: number }> };
    }>(
      "select public.beyoureyes_client_upsert_task_batch($1::jsonb) as result",
      [JSON.stringify([structuredTaskWrite])],
    );
    assert.deepEqual(structuredTaskUpload.rows[0]?.result, {
      upserted_ids: [structuredTask],
      unchanged_ids: [],
      task_revisions: [{ task_id: structuredTask, revision: 1 }],
    });
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{
          ...structuredTaskWrite,
          task_id: uuidV7(base + 33),
          config: {
            ...structuredTaskWrite.config,
            rule: { ...structuredTaskWrite.config.rule, duration_ms: 2000 },
          },
        }]),
      ]),
      /invalid_task_contract/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{
          ...structuredTaskWrite,
          task_id: uuidV7(base + 32),
          config: {
            ...structuredTaskWrite.config,
            target_definition: { mode: "none" },
            roi: { left: 0.25, top: 0.35, right: 0.75, bottom: 0.65 },
          },
        }]),
      ]),
      /invalid_task_contract/,
    );

    const advancedTaskUpload = await db.query<{
      result: { upserted_ids: string[]; task_revisions: Array<{ task_id: string; revision: number }> };
    }>(
      "select public.beyoureyes_client_upsert_task_batch($1::jsonb) as result",
      [JSON.stringify([{ ...clientTaskWrite, revision: 4, title: "门口有人 v4" }])],
    );
    assert.deepEqual(advancedTaskUpload.rows[0]?.result.upserted_ids, [clientTask]);
    assert.deepEqual(advancedTaskUpload.rows[0]?.result.task_revisions, [
      { task_id: clientTask, revision: 4 },
    ]);
    await assert.rejects(
      db.query("update public.tasks set config = $2::jsonb where task_id = $1", [
        clientTask,
        JSON.stringify(taskConfig),
      ]),
      /permission denied/,
    );

    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountB]);
    await assert.rejects(
      db.query("select public.beyoureyes_client_upsert_task_batch($1::jsonb)", [
        JSON.stringify([{
          ...clientTaskWrite,
          revision: 5,
          monitoring_device_id: deviceB,
        }]),
      ]),
      /task_id_conflict/,
    );
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountA]);

    const clientEvent = {
      ...event,
      event_id: uuidV7(base + 13),
      task_id: clientTask,
      task_revision: 4,
      episode_id: uuidV7(base + 14),
      source_sequence: 2,
    };
    const clientEventResult = await db.query<{ result: { accepted_ids: string[]; duplicate_ids: string[] } }>(
      "select public.beyoureyes_client_insert_event_batch($1::jsonb) as result",
      [JSON.stringify([clientEvent])],
    );
    assert.deepEqual(clientEventResult.rows[0]?.result, {
      accepted_ids: [clientEvent.event_id],
      duplicate_ids: [],
    });
    await assert.rejects(
      db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
        JSON.stringify([{
          ...clientEvent,
          event_id: uuidV7(base + 28),
          episode_id: uuidV7(base + 29),
          source_sequence: 8,
          task_revision: 5,
        }]),
      ]),
      /invalid_task_revision/,
    );
    const clientEventRetry = await db.query<{ result: { accepted_ids: string[]; duplicate_ids: string[] } }>(
      "select public.beyoureyes_client_insert_event_batch($1::jsonb) as result",
      [JSON.stringify([clientEvent])],
    );
    assert.deepEqual(clientEventRetry.rows[0]?.result, {
      accepted_ids: [],
      duplicate_ids: [clientEvent.event_id],
    });
    await assert.rejects(
      db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
        JSON.stringify([{
          ...clientEvent,
          event_id: uuidV7(base + 18),
          episode_id: uuidV7(base + 19),
          source_sequence: 4,
          payload: { ...clientEvent.payload, data: "opaque-unbounded-content" },
        }]),
      ]),
      /event_payload_invalid/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
        JSON.stringify([{
          ...clientEvent,
          event_id: uuidV7(base + 20),
          episode_id: uuidV7(base + 21),
          source_sequence: 5,
          payload: {
            ...clientEvent.payload,
            nested: { image: "base64-media" },
          },
        }]),
      ]),
      /event_payload_contains_media/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
        JSON.stringify([{
          ...clientEvent,
          event_id: uuidV7(base + 24),
          episode_id: uuidV7(base + 25),
          source_sequence: 7,
          payload: {
            type: "reading_threshold_crossed",
            reading: null,
            operator: "gte",
            threshold_decimal: "12.0",
          },
        }]),
      ]),
      /event_payload_invalid/,
    );
    const validReadingEvent = {
      ...clientEvent,
      event_id: uuidV7(base + 22),
      episode_id: uuidV7(base + 23),
      source_sequence: 6,
      payload: {
        type: "reading_threshold_crossed",
        reading: {
          type: "structured_reading",
          status: "stable",
          display_text: "12.5%",
          value_decimal: "12.5",
          format: {
            profile_id: "confirmed_reading_format_v2",
            kind: "percent",
            fractional_digits: 1,
            time_segments: null,
            unit: "%",
          },
          unit: "%",
          confidence: 0.98,
          source_kind: "digital_display",
          observed_at: "2026-08-02T00:00:09.000Z",
        },
        operator: "gte",
        threshold_decimal: "12.0",
      },
    };
    const validReadingResult = await db.query<{
      result: { accepted_ids: string[]; duplicate_ids: string[] };
    }>(
      "select public.beyoureyes_client_insert_event_batch($1::jsonb) as result",
      [JSON.stringify([validReadingEvent])],
    );
    assert.deepEqual(validReadingResult.rows[0]?.result, {
      accepted_ids: [validReadingEvent.event_id],
      duplicate_ids: [],
    });
    const clientReceipt = await db.query<{
      result: { event_id: string; device_id: string; receipt_type: string; received_at: string };
    }>(
      "select public.beyoureyes_client_add_event_receipt($1, $2, 'displayed', $3) as result",
      [clientEvent.event_id, clientDevice, "2026-08-02T00:00:08.000Z"],
    );
    assert.equal(clientReceipt.rows[0]?.result.event_id, clientEvent.event_id);
    assert.equal(clientReceipt.rows[0]?.result.device_id, clientDevice);
    assert.equal(clientReceipt.rows[0]?.result.receipt_type, "displayed");
    assert.equal(Date.parse(clientReceipt.rows[0]!.result.received_at), Date.parse("2026-08-02T00:00:08.000Z"));
    await assert.rejects(
      db.query(
        "insert into public.events(account_id, event_id, task_id, task_revision, episode_id, source_sequence, occurred_at, payload) values ($1, $2, $3, 1, $4, 3, now(), '{}'::jsonb)",
        [accountA, uuidV7(base + 15), clientTask, uuidV7(base + 16)],
      ),
      /permission denied/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_insert_event_batch($1, $2::jsonb)", [accountA, JSON.stringify([event])]),
      /permission denied/,
    );
    await assert.rejects(
      db.query("select public.beyoureyes_purge_account_data($1)", [accountA]),
      /permission denied/,
    );
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountB]);
    const visibleToB = await db.query<{ account_id: string }>("select account_id from public.devices order by account_id");
    assert.deepEqual(visibleToB.rows.map((row) => row.account_id), [accountB]);
    for (const table of ["tasks", "events", "event_receipts", "sync_changes"] as const) {
      const visible = await db.query<{ account_id: string }>(`select account_id from public.${table}`);
      assert.equal(visible.rows.every((row) => row.account_id === accountB), true, `${table} leaked account A rows to account B`);
      assert.equal(visible.rows.length, table === "sync_changes" ? 1 : 0);
    }
    const crossAccountUpdate = await db.query<{ device_id: string }>(
      "update public.devices set display_name = 'forbidden' where device_id = $1 returning device_id",
      [clientDevice],
    );
    assert.equal(crossAccountUpdate.rows.length, 0);
    await assert.rejects(
      db.query("select public.beyoureyes_client_insert_event_batch($1::jsonb)", [
        JSON.stringify([{ ...clientEvent, event_id: uuidV7(base + 17) }]),
      ]),
      /task_not_found/,
    );
    await db.exec("reset role");

    const pendingClientPush = await db.query<{
      outbox_id: number;
      device_id: string;
    }>(
      "select outbox_id, device_id from public.push_outbox where account_id = $1 and event_id = $2",
      [accountA, clientEvent.event_id],
    );
    assert.deepEqual(pendingClientPush.rows.map((row) => row.device_id).sort(), [deviceA, clientDevice].sort());
    const permanentLease = "84e0f63f-e594-42b5-9c41-c68001538862";
    const permanentClaim = await db.query<{ outbox_id: number; device_id: string }>(
      "select outbox_id, device_id from public.beyoureyes_claim_push_batch(10, $1, $2)",
      [permanentLease, "2030-08-02T00:01:00.000Z"],
    );
    const clientClaim = permanentClaim.rows.find((row) => row.device_id === clientDevice);
    const retryableClaim = permanentClaim.rows.find((row) => row.device_id === deviceA);
    assert.ok(clientClaim);
    assert.ok(retryableClaim);
    assert.equal((await db.query<{ completed: boolean }>(
      "select public.beyoureyes_complete_push($1, $2, false, true, 'UNREGISTERED', $3) as completed",
      [clientClaim.outbox_id, permanentLease, "2030-08-02T00:01:01.000Z"],
    )).rows[0]?.completed, true);
    assert.ok((await db.query<{ disabled_at: string | Date | null }>(
      "select disabled_at from public.device_push_tokens where account_id = $1 and device_id = $2",
      [accountA, clientDevice],
    )).rows[0]?.disabled_at);
    assert.equal((await db.query<{ completed: boolean }>(
      "select public.beyoureyes_complete_push($1, $2, false, false, 'UNAVAILABLE', $3) as completed",
      [retryableClaim.outbox_id, permanentLease, "2030-08-02T00:01:01.000Z"],
    )).rows[0]?.completed, true);
    const retryableState = (await db.query<{ status: string; available_at: string | Date }>(
      "select status, available_at from public.push_outbox where outbox_id = $1",
      [retryableClaim.outbox_id],
    )).rows[0]!;
    assert.equal(retryableState.status, "pending");
    assert.equal(new Date(retryableState.available_at).toISOString(), "2030-08-02T00:02:01.000Z");

    await db.query("select public.beyoureyes_purge_account_data($1)", [accountA]);
    const retained = await db.query<{ account_id: string }>("select account_id from public.devices order by account_id");
    assert.deepEqual(retained.rows.map((row) => row.account_id), [accountB]);
    for (const table of ["tasks", "events", "event_receipts", "sync_changes", "device_push_tokens", "push_outbox"]) {
      const result = await db.query<{ count: number }>(`select count(*)::int as count from public.${table} where account_id = $1`, [accountA]);
      assert.equal(result.rows[0]?.count, 0, `${table} retained deleted account data`);
    }
    await db.query("delete from auth.users where id = $1", [accountB]);
    for (const table of ["devices", "tasks", "events", "event_receipts", "sync_changes", "device_push_tokens", "push_outbox"]) {
      const result = await db.query<{ count: number }>(`select count(*)::int as count from public.${table} where account_id = $1`, [accountB]);
      assert.equal(result.rows[0]?.count, 0, `${table} did not cascade auth.users deletion`);
    }
    assert.equal((await db.query<{ ready: boolean }>("select public.beyoureyes_data_ready() as ready")).rows[0]?.ready, true);
    await db.exec("alter table public.events drop constraint events_payload_contract_valid");
    assert.equal((await db.query<{ ready: boolean }>("select public.beyoureyes_data_ready() as ready")).rows[0]?.ready, false);
    await db.exec(
      "alter table public.events add constraint events_payload_contract_valid check (public.beyoureyes_event_payload_is_valid(payload))",
    );
    assert.equal((await db.query<{ ready: boolean }>("select public.beyoureyes_data_ready() as ready")).rows[0]?.ready, true);
  } finally {
    await db.close();
  }
});

test("30-day retention cascades dependent rows, redacts sync payloads, and keeps an offline tombstone", async () => {
  const db = await migratedDatabase();
  try {
    const accountA = "c5d64b50-c6c1-4b3f-9c26-2f764b2441a7";
    const accountB = "fd1bd3e5-a191-48d0-aabe-68569f52f795";
    const base = Date.parse("2026-01-01T00:00:00Z");
    const deviceA = uuidV7(base);
    const deviceB = uuidV7(base + 1);
    const taskA = uuidV7(base + 2);
    const taskB = uuidV7(base + 3);
    const eventA = uuidV7(base + 4);
    const eventB = uuidV7(base + 5);

    await db.query("insert into auth.users(id) values ($1), ($2)", [accountA, accountB]);
    await db.query(
      "insert into public.devices(device_id, account_id, display_name, notifications_enabled) values ($1, $2, 'A', true), ($3, $4, 'B', false)",
      [deviceA, accountA, deviceB, accountB],
    );
    await db.query(
      "insert into public.device_push_tokens(account_id, device_id, token) values ($1, $2, $3)",
      [accountA, deviceA, "retention-fcm-token-0123456789"],
    );
    const taskConfig = JSON.stringify({
      target_definition: { mode: "reference_images" },
      roi: { left: 0, top: 0, right: 1, bottom: 1 },
      sampling_policy: { mode: "package_default" },
      route_binding: {
        model_profile_key: "reference_object_matching",
        recipe_id: "reference_match_general_v1",
        intent_key: "visual.reference.user_target",
      },
      package_binding: {
        package_id: "reference_match_target_v1",
        package_version: "1.0.0",
        manifest_sha256: "3".repeat(64),
        artifact_identity_sha256: "a".repeat(64),
      },
      rule: {
        type: "presence_duration",
        condition: "remains",
        duration_ms: 3000,
        min_positive_count: 5,
        max_positive_gap_ms: 1000,
        rearm_absence_ms: 3000,
      },
    });
    await db.query(
      `insert into public.tasks(
        task_id, account_id, catalog_version, capability_id, title,
        monitoring_device_id, config
      ) values
        ($1, $2, 'v3', 'visual_target', 'person', $3, $4::jsonb),
        ($5, $6, 'v3', 'visual_target', 'person', $7, $4::jsonb)`,
      [taskA, accountA, deviceA, taskConfig, taskB, accountB, deviceB],
    );
    const makeEvent = (eventId: string, taskId: string, episodeOffset: number) => ({
      schema_version: "3.0",
      event_id: eventId,
      task_id: taskId,
      task_revision: 1,
      episode_id: uuidV7(base + episodeOffset),
      source_sequence: 1,
      occurred_at: "2026-01-01T00:00:00.000Z",
      payload: {
        type: "object_episode",
        target_id: "person",
        condition: "appeared",
        duration_ms: 3000,
        count: 1,
      },
    });
    const eventBodyA = makeEvent(eventA, taskA, 6);
    const eventBodyB = makeEvent(eventB, taskB, 7);
    await db.query("select public.beyoureyes_insert_event_batch($1, $2::jsonb)", [
      accountA,
      JSON.stringify([eventBodyA]),
    ]);
    await db.query("select public.beyoureyes_insert_event_batch($1, $2::jsonb)", [
      accountB,
      JSON.stringify([eventBodyB]),
    ]);
    await db.query(
      "insert into public.event_receipts(account_id, event_id, device_id, receipt_type, received_at) values ($1, $2, $3, 'displayed', '2026-01-01T00:00:01Z')",
      [accountA, eventA, deviceA],
    );
    await db.query(
      "update public.events set received_at = '2026-01-01T00:00:00Z' where event_id in ($1, $2)",
      [eventA, eventB],
    );

    const boundary = await db.query<{ deleted: number }>(
      "select public.beyoureyes_purge_expired_events($1, $2, 100) as deleted",
      [accountA, "2026-01-31T00:00:00.000Z"],
    );
    assert.equal(boundary.rows[0]?.deleted, 0, "exactly 30 days must still be retained");

    const expired = await db.query<{ deleted: number }>(
      "select public.beyoureyes_purge_expired_events($1, $2, 100) as deleted",
      [accountA, "2026-01-31T00:00:00.001Z"],
    );
    assert.equal(expired.rows[0]?.deleted, 1);
    for (const table of ["events", "event_receipts", "push_outbox"] as const) {
      const count = await db.query<{ count: number }>(
        `select count(*)::int as count from public.${table} where account_id = $1 and event_id = $2`,
        [accountA, eventA],
      );
      assert.equal(count.rows[0]?.count, 0, `${table} retained the expired Event`);
    }

    const eventChanges = await db.query<{ operation: string; value: unknown }>(
      `select operation, value from public.sync_changes
       where account_id = $1 and resource_type = 'event' and resource_id = $2
       order by sequence`,
      [accountA, eventA],
    );
    assert.deepEqual(eventChanges.rows, [{ operation: "delete", value: null }]);
    assert.equal((await db.query<{ count: number }>(
      "select count(*)::int as count from public.events where account_id = $1 and event_id = $2",
      [accountB, eventB],
    )).rows[0]?.count, 1, "account-scoped smoke purge deleted the peer account");
    assert.equal((await db.query<{ count: number }>(
      `select count(*)::int as count from public.sync_changes
       where account_id = $1 and resource_type = 'event' and resource_id = $2 and value is not null`,
      [accountB, eventB],
    )).rows[0]?.count, 2, "account-scoped smoke purge redacted the peer account");

    const delayedRetry = await db.query<{
      result: { accepted_ids: string[]; duplicate_ids: string[] };
    }>("select public.beyoureyes_insert_event_batch($1, $2::jsonb) as result", [
      accountA,
      JSON.stringify([eventBodyA]),
    ]);
    assert.deepEqual(delayedRetry.rows[0]?.result, {
      accepted_ids: [],
      duplicate_ids: [eventA],
    });
    assert.equal((await db.query<{ count: number }>(
      "select count(*)::int as count from public.events where account_id = $1 and event_id = $2",
      [accountA, eventA],
    )).rows[0]?.count, 0, "an expired Event retry resurrected cloud payload");

    await db.exec("set role authenticated");
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [accountA]);
    await assert.rejects(
      db.query("select public.beyoureyes_purge_expired_events($1, $2, 100)", [
        accountA,
        "2026-01-31T00:00:00.001Z",
      ]),
      /permission denied/,
    );
    await db.exec("reset role");

    const cronJob = await db.query<{ schedule: string; command: string; active: boolean }>(
      "select schedule, command, active from cron.job where jobname = 'beyoureyes-event-retention-hourly-v1'",
    );
    assert.deepEqual(cronJob.rows, [{
      schedule: "17 * * * *",
      command: "select public.beyoureyes_purge_expired_events();",
      active: true,
    }]);
    assert.equal(
      (await db.query<{ ready: boolean }>("select public.beyoureyes_data_ready() as ready")).rows[0]?.ready,
      true,
    );
  } finally {
    await db.close();
  }
});

test("website payments grant once, isolate accounts, and revoke despite out-of-order delivery", async () => {
  const db = await migratedDatabase();
  try {
    const account = '00000000-0000-4000-8000-000000000101';
    const other = '00000000-0000-4000-8000-000000000102';
    const order = '00000000-0000-4000-8000-000000000103';
    const refunded = '00000000-0000-4000-8000-000000000104';
    await db.query('insert into auth.users(id) values ($1),($2)', [account, other]);
    await db.query('select public.beyoureyes_reserve_website_order($1,$2,$3,$4,$5)', [order, account, 'price_fixture', 4800, 'hkd']);
    await assert.rejects(db.query('select public.beyoureyes_reserve_website_order($1,$2,$3,$4,$5)', [order, other, 'price_fixture', 4800, 'hkd']), /order_account_mismatch/);
    const apply = (id: string, revoked: boolean, amount = 4800) => db.query(
      'select public.beyoureyes_apply_website_payment($1,$2,$3,$4,$5,$6)',
      [id, `cs_test_${id}`, `pi_${id}`, amount, 'hkd', revoked],
    );
    await assert.rejects(apply(order, false, 1), /payment_mismatch/);
    for (let index = 0; index < 6; index++) {
      const values: unknown[] = [order, `cs_test_${order}`, `pi_${order}`, 4800, 'hkd', false];
      values[index] = null;
      await assert.rejects(db.query('select public.beyoureyes_apply_website_payment($1,$2,$3,$4,$5,$6)', values), /payment_mismatch/);
    }
    await apply(order, false);
    const first = await db.query<{ expires_at: string }>('select expires_at from public.website_orders where id=$1', [order]);
    await apply(order, false);
    const repeated = await db.query<{ expires_at: string }>('select expires_at from public.website_orders where id=$1', [order]);
    assert.deepEqual(repeated.rows, first.rows);
    const active = await db.query<{ active: boolean }>('select public.beyoureyes_account_has_active_entitlement($1) as active', [account]);
    assert.equal(active.rows[0]?.active, true);
    const inactive = await db.query<{ active: boolean }>('select public.beyoureyes_account_has_active_entitlement($1) as active', [other]);
    assert.equal(inactive.rows[0]?.active, false);
    const source = await db.query<{ value: { state: string } }>('select public.beyoureyes_product_entitlement($1) as value', [account]);
    assert.equal(source.rows[0]?.value.state, 'WEBSITE_PASS_ACTIVE');
    assert.equal((await db.query('select * from public.account_entitlements')).rows.length, 0);
    await apply(order, true);
    await apply(order, false);
    assert.equal((await db.query<{ active: boolean }>('select public.beyoureyes_account_has_active_entitlement($1) as active', [account])).rows[0]?.active, false);
    await db.query('select public.beyoureyes_reserve_website_order($1,$2,$3,$4,$5)', [refunded, account, 'price_fixture', 4800, 'hkd']);
    await apply(refunded, true);
    await apply(refunded, false);
    assert.equal((await db.query<{ state: string }>('select state from public.website_orders where id=$1', [refunded])).rows[0]?.state, 'revoked');
    await db.exec('set role authenticated');
    await assert.rejects(db.query('select * from public.website_orders'), /permission denied/);
    await assert.rejects(db.query('insert into public.website_trials(account_id) values ($1)', [account]), /permission denied/);
    await assert.rejects(db.query('select public.beyoureyes_apply_website_payment($1,$2,$3,$4,$5,$6)', [order,'cs_bad','pi_bad',4800,'hkd',false]), /permission denied/);
    await db.exec('reset role');
    await db.query('delete from auth.users where id=$1', [account]);
    assert.equal((await db.query<{ account_id: string | null }>('select account_id from public.website_orders where id=$1', [order])).rows[0]?.account_id, null);
  } finally { await db.close(); }
});

test("website trial is one-time and paid renewals preserve the purchased duration", async () => {
  const db = await migratedDatabase();
  try {
    const account = '00000000-0000-4000-8000-000000000201';
    await db.query('insert into auth.users(id) values ($1)', [account]);
    await db.query('insert into public.website_trials(account_id) values ($1)', [account]);
    const original = await db.query('select expires_at from public.website_trials');
    await db.query('insert into public.website_trials(account_id) values ($1) on conflict do nothing', [account]);
    assert.deepEqual((await db.query('select expires_at from public.website_trials')).rows, original.rows);
    assert.equal((await db.query<{ active: boolean }>("select public.beyoureyes_account_has_active_entitlement($1, now()+interval '4 days') as active", [account])).rows[0]?.active, false);
    for (let index = 1; index <= 2; index++) {
      const id = `00000000-0000-4000-8000-00000000020${index+1}`;
      await db.query('select public.beyoureyes_reserve_website_order($1,$2,$3,$4,$5)', [id, account,'price_fixture',4800,'hkd']);
      await db.query('select public.beyoureyes_apply_website_payment($1,$2,$3,$4,$5,$6)', [id,`cs_test_${index}`,`pi_${index}`,4800,'hkd',false]);
    }
    const lengths = await db.query<{ days: number }>('select extract(epoch from expires_at-starts_at)::int/86400 as days from public.website_orders');
    assert.deepEqual(lengths.rows.map(row => row.days), [30,30]);
    const adjacent = await db.query<{ adjacent: boolean }>('select min(expires_at)=max(starts_at) as adjacent from public.website_orders');
    assert.equal(adjacent.rows[0]?.adjacent,true);
    assert.equal((await db.query<{ active: boolean }>("select public.beyoureyes_account_has_active_entitlement($1, now()+interval '31 days') as active", [account])).rows[0]?.active,true);
    const total = await db.query<{ days: number }>("select extract(epoch from ((public.beyoureyes_product_entitlement($1)->>'expires_at')::timestamptz-now()))/86400 as days", [account]);
    assert.ok(Number(total.rows[0]?.days) > 59.99);
    // Refund the active term: the next purchase starts now and retains all 30 days.
    await db.query('select public.beyoureyes_apply_website_payment($1,$2,$3,$4,$5,$6)', ['00000000-0000-4000-8000-000000000202','cs_test_1','pi_1',4800,'hkd',true]);
    const remaining = await db.query<{ state: string; days: number }>("select public.beyoureyes_product_entitlement($1)->>'state' as state, extract(epoch from ((public.beyoureyes_product_entitlement($1)->>'expires_at')::timestamptz-now()))/86400 as days", [account]);
    assert.equal(remaining.rows[0]?.state, 'WEBSITE_PASS_ACTIVE');
    assert.ok(Number(remaining.rows[0]?.days) > 29.99 && Number(remaining.rows[0]?.days) <= 30);
  } finally { await db.close(); }
});
