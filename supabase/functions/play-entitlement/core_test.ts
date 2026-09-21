import {
  createPlayEntitlementHandler,
  type EntitlementStore,
  type EntitlementWrite,
  mayReplaceStoredPurchase,
  obfuscatedAccountId,
  PLAY_PRODUCT_ID,
  type PlaySubscriptionVerifier,
} from "./core.ts";

function assert(
  condition: unknown,
  message = "assertion failed",
): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals(actual: unknown, expected: unknown): void {
  assert(
    JSON.stringify(actual) === JSON.stringify(expected),
    `${JSON.stringify(actual)} != ${JSON.stringify(expected)}`,
  );
}

const NOW = new Date("2026-08-23T00:00:00.000Z");
const ACCOUNT = "018f3f72-6e5c-7b4e-9a8f-1234567890ab";
const TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";

Deno.test("account binding matches the Android SHA-256 hexadecimal contract", async () => {
  assertEquals(
    await obfuscatedAccountId(ACCOUNT),
    "920ff54b82fc9766c78ec8061140fd9443c5a8945e0e0b7124711cf774e04432",
  );
});

Deno.test("purchase-token compare-and-swap accepts only same, linked or expired ownership", () => {
  const incoming: EntitlementWrite = {
    accountId: ACCOUNT,
    obfuscatedAccountId: "a".repeat(64),
    productId: PLAY_PRODUCT_ID,
    state: "SUBSCRIPTION_STATE_ACTIVE",
    expiresAt: "2026-09-23T00:00:00Z",
    purchaseTokenSha256: "b".repeat(64),
    replacesPurchaseTokenSha256: "c".repeat(64),
    verifiedAt: NOW.toISOString(),
  };
  assert(
    mayReplaceStoredPurchase("b".repeat(64), "2026-09-23T00:00:00Z", incoming),
  );
  assert(
    mayReplaceStoredPurchase("c".repeat(64), "2026-09-23T00:00:00Z", incoming),
  );
  assert(
    mayReplaceStoredPurchase("d".repeat(64), "2026-08-22T00:00:00Z", incoming),
  );
  assert(
    !mayReplaceStoredPurchase("d".repeat(64), "2026-09-23T00:00:00Z", incoming),
  );
});

class MemoryStore implements EntitlementStore {
  value: EntitlementWrite | null = null;
  read = async () => this.value;
  write = async (value: EntitlementWrite) => {
    this.value = value;
    return true;
  };
}

function request(body: unknown) {
  return new Request("https://example.invalid/functions/v1/play-entitlement", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer session",
    },
    body: JSON.stringify(body),
  });
}

Deno.test("verified active purchase is acknowledged, cached and returned", async () => {
  const store = new MemoryStore();
  let acknowledged = false;
  const verifier: PlaySubscriptionVerifier = {
    verify: async () => ({
      state: "SUBSCRIPTION_STATE_ACTIVE",
      expiresAt: "2026-09-23T00:00:00.000Z",
      productIds: [PLAY_PRODUCT_ID],
      obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
      acknowledgementPending: true,
      replacesPurchaseToken: null,
    }),
    acknowledge: async () => {
      acknowledged = true;
    },
  };
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store,
    verifier,
    now: () => NOW,
  })(
    request({
      action: "verify_purchase",
      product_id: PLAY_PRODUCT_ID,
      purchase_token: TOKEN,
    }),
  );
  assertEquals(response.status, 200);
  const status = await response.json();
  assertEquals(status.active, true);
  assertEquals(status.expires_at, "2026-08-26T00:00:00.000Z");
  assertEquals(status.refresh_after, "2026-08-24T00:00:00.000Z");
  assert(acknowledged);
  assertEquals(store.value?.purchaseTokenSha256.length, 64);
  assert(!JSON.stringify(store.value).includes(TOKEN));
});

Deno.test("pending, held and paused purchases never grant access", async () => {
  for (
    const state of [
      "SUBSCRIPTION_STATE_PENDING",
      "SUBSCRIPTION_STATE_ON_HOLD",
      "SUBSCRIPTION_STATE_PAUSED",
    ]
  ) {
    const store = new MemoryStore();
    const response = await createPlayEntitlementHandler({
      authenticate: async () => ACCOUNT,
      store,
      verifier: {
        verify: async () => ({
          state,
          expiresAt: "2026-09-23T00:00:00.000Z",
          productIds: [PLAY_PRODUCT_ID],
          obfuscatedExternalAccountId: await obfuscatedAccountId(ACCOUNT),
          acknowledgementPending: false,
          replacesPurchaseToken: null,
        }),
        acknowledge: async () => {
          throw new Error("must not acknowledge inactive state");
        },
      },
      now: () => NOW,
    })(
      request({
        action: "verify_purchase",
        product_id: PLAY_PRODUCT_ID,
        purchase_token: TOKEN,
      }),
    );
    assertEquals(response.status, 200);
    assertEquals((await response.json()).active, false);
  }
});

Deno.test("purchase must be bound to the signed-in Supabase account", async () => {
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store: new MemoryStore(),
    verifier: {
      verify: async () => ({
        state: "SUBSCRIPTION_STATE_ACTIVE",
        expiresAt: "2026-09-23T00:00:00.000Z",
        productIds: [PLAY_PRODUCT_ID],
        obfuscatedExternalAccountId: "another-account",
        acknowledgementPending: false,
        replacesPurchaseToken: null,
      }),
      acknowledge: async () => {},
    },
    now: () => NOW,
  })(
    request({
      action: "verify_purchase",
      product_id: PLAY_PRODUCT_ID,
      purchase_token: TOKEN,
    }),
  );
  assertEquals(response.status, 400);
  assertEquals((await response.json()).code, "purchase_account_mismatch");
});

Deno.test("status is fail closed when missing, expired or not authenticated", async () => {
  const handler = createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store: new MemoryStore(),
    verifier: null,
    now: () => NOW,
  });
  const missing = await handler(request({ action: "status" }));
  assertEquals((await missing.json()).active, false);

  const unauthenticated = await createPlayEntitlementHandler({
    authenticate: async () => null,
    store: new MemoryStore(),
    verifier: null,
  })(request({ action: "status" }));
  assertEquals(unauthenticated.status, 401);
});

Deno.test("status distinguishes storage outage from an inactive account", async () => {
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store: {
      read: async () => {
        throw new Error("database offline");
      },
      write: async () => true,
    },
    verifier: null,
    now: () => NOW,
  })(request({ action: "status" }));
  assertEquals(response.status, 503);
  assertEquals((await response.json()).code, "entitlement_unavailable");
});

Deno.test("identity provider outage is retryable instead of a false sign-out", async () => {
  const response = await createPlayEntitlementHandler({
    authenticate: async () => {
      throw new Error("auth service offline");
    },
    store: new MemoryStore(),
    verifier: null,
  })(request({ action: "status" }));
  assertEquals(response.status, 503);
  assertEquals((await response.json()).code, "identity_unavailable");
});

Deno.test("status returns a lease capped at 72 hours with a 24 hour soft refresh", async () => {
  const store = new MemoryStore();
  store.value = {
    accountId: ACCOUNT,
    obfuscatedAccountId: await obfuscatedAccountId(ACCOUNT),
    productId: PLAY_PRODUCT_ID,
    state: "SUBSCRIPTION_STATE_ACTIVE",
    expiresAt: "2026-09-23T00:00:00Z",
    purchaseTokenSha256: await obfuscatedAccountId(TOKEN),
    replacesPurchaseTokenSha256: null,
    verifiedAt: NOW.toISOString(),
  };
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store,
    verifier: null,
    now: () => NOW,
  })(request({ action: "status" }));
  assertEquals(await response.json(), {
    product_id: PLAY_PRODUCT_ID,
    active: true,
    state: "SUBSCRIPTION_STATE_ACTIVE",
    expires_at: "2026-08-26T00:00:00.000Z",
    refresh_after: "2026-08-24T00:00:00.000Z",
  });
});

Deno.test("status lease and refresh never cross the provider expiry", async () => {
  const store = new MemoryStore();
  store.value = {
    accountId: ACCOUNT,
    obfuscatedAccountId: await obfuscatedAccountId(ACCOUNT),
    productId: PLAY_PRODUCT_ID,
    state: "SUBSCRIPTION_STATE_CANCELED",
    expiresAt: "2026-08-23T12:00:00Z",
    purchaseTokenSha256: await obfuscatedAccountId(TOKEN),
    replacesPurchaseTokenSha256: null,
    verifiedAt: NOW.toISOString(),
  };
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store,
    verifier: null,
    now: () => NOW,
  })(request({ action: "status" }));
  assertEquals(await response.json(), {
    product_id: PLAY_PRODUCT_ID,
    active: true,
    state: "SUBSCRIPTION_STATE_CANCELED",
    expires_at: "2026-08-23T12:00:00.000Z",
    refresh_after: "2026-08-23T12:00:00.000Z",
  });
});

Deno.test("status requires a future provider expiry", async () => {
  const store = new MemoryStore();
  store.value = {
    accountId: ACCOUNT,
    obfuscatedAccountId: await obfuscatedAccountId(ACCOUNT),
    productId: PLAY_PRODUCT_ID,
    state: "SUBSCRIPTION_STATE_ACTIVE",
    expiresAt: "2026-08-22T00:00:00Z",
    purchaseTokenSha256: await obfuscatedAccountId(TOKEN),
    replacesPurchaseTokenSha256: null,
    verifiedAt: NOW.toISOString(),
  };
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store,
    verifier: null,
    now: () => NOW,
  })(request({ action: "status" }));
  assertEquals((await response.json()).active, false);
});

Deno.test("replaced purchase token cannot overwrite or acknowledge the current purchase", async () => {
  let acknowledged = false;
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store: {
      read: async () => null,
      write: async () => false,
    },
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
    now: () => NOW,
  })(request({
    action: "verify_purchase",
    product_id: PLAY_PRODUCT_ID,
    purchase_token: TOKEN,
  }));
  assertEquals(response.status, 409);
  assertEquals((await response.json()).code, "purchase_replaced");
  assertEquals(acknowledged, false);
});

Deno.test("request body is stopped at the streaming byte limit", async () => {
  const oversized = new Request(
    "https://example.invalid/functions/v1/play-entitlement",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: "Bearer session",
      },
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new Uint8Array(8 * 1024));
          controller.enqueue(new Uint8Array([1]));
          controller.close();
        },
      }),
      duplex: "half",
    } as RequestInit,
  );
  const response = await createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store: new MemoryStore(),
    verifier: null,
  })(oversized);
  assertEquals(response.status, 400);
});

Deno.test("command parser rejects unknown fields and wrong product", async () => {
  const handler = createPlayEntitlementHandler({
    authenticate: async () => ACCOUNT,
    store: new MemoryStore(),
    verifier: null,
  });
  for (
    const body of [
      { action: "status", extra: true },
      { action: "verify_purchase", product_id: "other", purchase_token: TOKEN },
    ]
  ) {
    const response = await handler(request(body));
    assertEquals(response.status, 400);
  }
});
