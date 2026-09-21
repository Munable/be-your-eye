import {
  type EntitlementWrite,
  obfuscatedAccountId,
  PLAY_PRODUCT_ID,
  type PlaySubscriptionVerifier,
  sha256Hex,
} from "../play-entitlement/core.ts";
import {
  createPlayRtdnHandler,
  type RtdnEntitlementStore,
  rtdnTakeoverTokenHashes,
} from "./core.ts";

function equal(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
  }
}

const ACCOUNT = "018f3f72-6e5c-7b4e-9a8f-1234567890ab";
const TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";

class MemoryStore implements RtdnEntitlementStore {
  writeValue: EntitlementWrite | null = null;
  accountIdForObfuscatedId = async () => ACCOUNT;
  write = async (value: EntitlementWrite) => {
    this.writeValue = value;
    return true;
  };
}

function push(data: unknown, authorization = "Bearer google-id-token") {
  const encoded = btoa(JSON.stringify(data));
  return new Request("https://example.invalid/functions/v1/play-rtdn", {
    method: "POST",
    headers: { "content-type": "application/json", authorization },
    body: JSON.stringify({
      message: {
        data: encoded,
        messageId: "1",
      },
      subscription: "projects/project/subscriptions/play",
    }),
  });
}

function notification(purchaseToken = TOKEN) {
  return {
    version: "1.0",
    packageName: "app.beyoureyes.monitor",
    eventTimeMillis: "1787443200000",
    subscriptionNotification: {
      version: "1.0",
      notificationType: 2,
      purchaseToken,
    },
  };
}

Deno.test("authenticated subscription notification refreshes server entitlement", async () => {
  const store = new MemoryStore();
  const verifier: PlaySubscriptionVerifier = {
    verify: async () => ({
      state: "SUBSCRIPTION_STATE_ACTIVE",
      expiresAt: "2026-09-23T00:00:00Z",
      productIds: [PLAY_PRODUCT_ID],
      obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
      acknowledgementPending: false,
      replacesPurchaseToken: null,
    }),
    acknowledge: async () => {},
  };
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier,
    store,
    now: () => new Date("2026-08-23T00:00:00Z"),
  })(push(notification()));
  equal(response.status, 204);
  equal(store.writeValue?.state, "SUBSCRIPTION_STATE_ACTIVE");
});

Deno.test("RTDN takeover admits only the incoming token and its Play-linked predecessor", async () => {
  const unrelated = "c".repeat(64);
  const changes = [
    ["upgrade", "a".repeat(64), "b".repeat(64)],
    ["downgrade", "d".repeat(64), "e".repeat(64)],
    ["resubscribe", "f".repeat(64), "0".repeat(64)],
  ] as const;
  for (const [purchaseChange, incoming, predecessor] of changes) {
    const allowed = rtdnTakeoverTokenHashes({
      purchaseTokenSha256: incoming,
      replacesPurchaseTokenSha256: predecessor,
    });
    equal(allowed, [incoming, predecessor]);
    if (allowed.includes(unrelated)) {
      throw new Error(`${purchaseChange} widened token ownership`);
    }
  }
  const incoming = "a".repeat(64);
  equal(
    rtdnTakeoverTokenHashes({
      purchaseTokenSha256: incoming,
      replacesPurchaseTokenSha256: null,
    }),
    [incoming],
  );
});

Deno.test("Play-linked RTDN transfers ownership once and its stale predecessor cannot reclaim it", async () => {
  const predecessor = "predecessorabcdefghijklmnopqrstuvwxyz0123456789";
  const incoming = "incomingabcdefghijklmnopqrstuvwxyz0123456789";
  let currentTokenSha256 = await sha256Hex(predecessor);
  let acknowledgements = 0;
  const store = new MemoryStore();
  store.write = async (value) => {
    if (!rtdnTakeoverTokenHashes(value).includes(currentTokenSha256)) {
      return false;
    }
    currentTokenSha256 = value.purchaseTokenSha256;
    store.writeValue = value;
    return true;
  };
  const verifier: PlaySubscriptionVerifier = {
    verify: async (purchaseToken) => ({
      state: "SUBSCRIPTION_STATE_ACTIVE",
      expiresAt: "2026-09-23T00:00:00Z",
      productIds: [PLAY_PRODUCT_ID],
      obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
      acknowledgementPending: true,
      replacesPurchaseToken: purchaseToken === incoming ? predecessor : null,
    }),
    acknowledge: async () => {
      acknowledgements += 1;
    },
  };
  const handler = createPlayRtdnHandler({
    authenticate: async () => true,
    verifier,
    store,
    now: () => new Date("2026-08-23T00:00:00Z"),
  });

  equal((await handler(push(notification(incoming)))).status, 204);
  equal(currentTokenSha256, await sha256Hex(incoming));
  equal(
    store.writeValue?.replacesPurchaseTokenSha256,
    await sha256Hex(predecessor),
  );
  equal(acknowledgements, 1);

  store.writeValue = null;
  equal((await handler(push(notification(predecessor)))).status, 204);
  equal(currentTokenSha256, await sha256Hex(incoming));
  equal(store.writeValue, null);
  equal(acknowledgements, 1);
});

Deno.test("OIDC failure is rejected before body processing", async () => {
  const response = await createPlayRtdnHandler({
    authenticate: async () => false,
    verifier: null,
    store: new MemoryStore(),
  })(push(notification(), "Bearer invalid"));
  equal(response.status, 401);
});

Deno.test("unrelated product is ignored after provider verification", async () => {
  const store = new MemoryStore();
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier: {
      verify: async () => ({
        state: "SUBSCRIPTION_STATE_ACTIVE",
        expiresAt: "2026-09-23T00:00:00Z",
        productIds: ["another_product"],
        obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
        acknowledgementPending: false,
        replacesPurchaseToken: null,
      }),
      acknowledge: async () => {},
    },
    store,
  })(push(notification()));
  equal(response.status, 204);
  equal(store.writeValue, null);
});

Deno.test("provider failure returns retryable status", async () => {
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier: {
      verify: async () => {
        throw new Error("offline");
      },
      acknowledge: async () => {},
    },
    store: new MemoryStore(),
  })(push(notification()));
  equal(response.status, 503);
});

Deno.test("OIDC key-provider outage is retryable instead of an auth rejection", async () => {
  const response = await createPlayRtdnHandler({
    authenticate: async () => {
      throw new Error("JWKS offline");
    },
    verifier: null,
    store: new MemoryStore(),
  })(push(notification()));
  equal(response.status, 503);
});

Deno.test("revocation persists inactive provider state immediately", async () => {
  const store = new MemoryStore();
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier: {
      verify: async () => ({
        state: "SUBSCRIPTION_STATE_EXPIRED",
        expiresAt: "2026-09-23T00:00:00Z",
        productIds: [PLAY_PRODUCT_ID],
        obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
        acknowledgementPending: false,
        replacesPurchaseToken: null,
      }),
      acknowledge: async () => {
        throw new Error("revocation must not be acknowledged");
      },
    },
    store,
    now: () => new Date("2026-08-23T00:00:00Z"),
  })(push(notification()));
  equal(response.status, 204);
  equal(store.writeValue?.state, "SUBSCRIPTION_STATE_EXPIRED");
});

Deno.test("replayed RTDN for a replaced token cannot overwrite or acknowledge", async () => {
  let acknowledged = false;
  const store = new MemoryStore();
  store.write = async () => false;
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier: {
      verify: async () => ({
        state: "SUBSCRIPTION_STATE_ACTIVE",
        expiresAt: "2026-09-23T00:00:00Z",
        productIds: [PLAY_PRODUCT_ID],
        obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
        acknowledgementPending: true,
        replacesPurchaseToken: null,
      }),
      acknowledge: async () => {
        acknowledged = true;
      },
    },
    store,
    now: () => new Date("2026-08-23T00:00:00Z"),
  })(push(notification()));
  equal(response.status, 204);
  equal(acknowledged, false);
  equal(store.writeValue, null);
});

Deno.test("database account mapping is rechecked against the account hash", async () => {
  const store = new MemoryStore();
  store.accountIdForObfuscatedId = async () =>
    "118f3f72-6e5c-7b4e-9a8f-1234567890ab";
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier: {
      verify: async () => ({
        state: "SUBSCRIPTION_STATE_ACTIVE",
        expiresAt: "2026-09-23T00:00:00Z",
        productIds: [PLAY_PRODUCT_ID],
        obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
        acknowledgementPending: false,
        replacesPurchaseToken: null,
      }),
      acknowledge: async () => {},
    },
    store,
  })(push(notification()));
  equal(response.status, 204);
  equal(store.writeValue, null);
});

Deno.test("Pub/Sub request body is stopped at the streaming byte limit", async () => {
  const oversized = new Request(
    "https://example.invalid/functions/v1/play-rtdn",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: "Bearer google-id-token",
      },
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new Uint8Array(32 * 1024));
          controller.enqueue(new Uint8Array([1]));
          controller.close();
        },
      }),
      duplex: "half",
    } as RequestInit,
  );
  const response = await createPlayRtdnHandler({
    authenticate: async () => true,
    verifier: {
      verify: async () => {
        throw new Error("must not verify oversized body");
      },
      acknowledge: async () => {},
    },
    store: new MemoryStore(),
  })(oversized);
  equal(response.status, 400);
});
