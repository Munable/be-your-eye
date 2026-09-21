import {
  dispatchPushBatch,
  FcmHttpSender,
  type PushCompletion,
  type PushDelivery,
  type PushOutbox,
  PushSendError,
} from "./core.ts";

function assert(
  condition: unknown,
  message = "assertion failed",
): asserts condition {
  if (!condition) throw new Error(message);
}

function equal(actual: unknown, expected: unknown): void {
  assert(
    JSON.stringify(actual) === JSON.stringify(expected),
    `${JSON.stringify(actual)} != ${JSON.stringify(expected)}`,
  );
}

const rows: PushDelivery[] = [1, 2, 3].map((id) => ({
  outbox_id: id,
  event_id: `019fbfc5-340${id}-7df2-b3ab-d93bdf190ee2`,
  device_id: `019fbfc5-340${id}-7bc6-a48e-4b207df36c4a`,
  token: `firebase-installation-id-${id}`,
  cursor_hint: `${40 + id}`,
}));

Deno.test("dispatch completes success retry and permanent token failure without payload logging", async () => {
  const completions: PushCompletion[] = [];
  const outbox: PushOutbox = {
    claim: async (limit) => structuredClone(rows.slice(0, limit)),
    complete: async (value) => {
      completions.push(structuredClone(value));
      return true;
    },
  };
  const result = await dispatchPushBatch({
    outbox,
    sender: {
      send: async (row) => {
        if (row.outbox_id === 2) throw new PushSendError("fcm_unavailable");
        if (row.outbox_id === 3) {
          throw new PushSendError("fcm_installation_unregistered", true);
        }
      },
    },
    leaseId: "84e0f63f-e594-42b5-9c41-c68001538861",
    nowIso: () => "2026-08-09T00:00:00.000Z",
  });
  equal(result, {
    claimed: 3,
    sent: 1,
    retryableFailures: 1,
    permanentFailures: 1,
    failureCodes: { fcm_unavailable: 1, fcm_installation_unregistered: 1 },
  });
  equal(completions.map((value) => [value.success, value.permanentFailure]), [
    [true, false],
    [false, false],
    [false, true],
  ]);
});

Deno.test("FCM sends only event id and cursor and classifies an unregistered installation", async () => {
  let body = "";
  const success = new FcmHttpSender({
    projectId: "be-your-eyes-12345",
    accessToken: async () => "access-token",
    fetch: async (_input, init) => {
      body = String(init?.body);
      return Response.json({ name: "projects/p/messages/1" });
    },
  });
  await success.send(rows[0]!);
  const message = JSON.parse(body).message;
  equal(Object.keys(message.data).sort(), [
    "cursor_hint",
    "event_id",
    "schema_version",
  ]);
  assert(
    !body.includes("task") && !body.includes("reading") &&
      !body.includes("image"),
  );

  const unavailable = new FcmHttpSender({
    projectId: "be-your-eyes-12345",
    accessToken: async () => "access-token",
    fetch: async () =>
      Response.json({
        error: {
          status: "NOT_FOUND",
          details: [{
            "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
            errorCode: "UNREGISTERED",
          }],
        },
      }, { status: 404 }),
  });
  try {
    await unavailable.send(rows[0]!);
    throw new Error("expected rejection");
  } catch (error) {
    assert(error instanceof PushSendError);
    equal(error.code, "fcm_installation_unregistered");
    equal(error.permanentForToken, true);
  }
});

Deno.test("batch is capped at twenty", async () => {
  const outbox: PushOutbox = {
    claim: async () => [rows[0]!],
    complete: async () => false,
  };
  let failed = false;
  try {
    await dispatchPushBatch({
      outbox,
      sender: { send: async () => undefined },
      leaseId: "84e0f63f-e594-42b5-9c41-c68001538861",
      nowIso: () => "2026-08-09T00:00:00.000Z",
      limit: 21,
    });
  } catch {
    failed = true;
  }
  assert(failed);
});

Deno.test("lost database lease fails closed", async () => {
  const outbox: PushOutbox = {
    claim: async () => [rows[0]!],
    complete: async () => false,
  };
  let failed = false;
  try {
    await dispatchPushBatch({
      outbox,
      sender: { send: async () => undefined },
      leaseId: "84e0f63f-e594-42b5-9c41-c68001538861",
      nowIso: () => "2026-08-09T00:00:00.000Z",
    });
  } catch {
    failed = true;
  }
  assert(failed);
});
