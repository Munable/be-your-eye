import {
  deepStrictEqual as assertEquals,
  rejects as assertRejects,
} from "node:assert/strict";
import { reconcileSession, Stripe } from "../_shared/stripe.ts";
import { boundedBody, entitlementResponse } from "../_shared/website.ts";

const order = {
  id: "order-one",
  account_id: "account-one",
  amount: 4800,
  currency: "hkd",
  price_id: "price_pass",
  checkout_session_id: "cs_test_one",
};
function payment() {
  return {
    id: "cs_test_one",
    livemode: false,
    mode: "payment",
    status: "complete",
    payment_status: "paid",
    metadata: { product: "be_your_eye_pro", order_id: order.id },
    client_reference_id: order.id,
    amount_total: 4800,
    currency: "hkd",
    line_items: {
      has_more: false,
      data: [{ price: { id: "price_pass" }, quantity: 1 }],
    },
    payment_intent: {
      id: "pi_one",
      status: "succeeded",
      amount_received: 4800,
      currency: "hkd",
      metadata: { product: "be_your_eye_pro", order_id: order.id },
      latest_charge: {
        paid: true,
        livemode: false,
        disputed: false,
        refunded: false,
        amount_refunded: 0,
      },
    },
  };
}
function fixture(session = payment()) {
  const writes: Record<string, unknown>[] = [];
  const client = {
    checkout: { sessions: { retrieve: () => Promise.resolve(session) } },
  } as unknown as Stripe;
  const admin = {
    from: () => ({
      select: () => ({
        eq: () => ({
          single: () => Promise.resolve({ data: order, error: null }),
        }),
      }),
    }),
    rpc: (_name: string, values: Record<string, unknown>) => {
      writes.push(values);
      return Promise.resolve({ error: null });
    },
  } as unknown as Parameters<typeof reconcileSession>[1];
  return { client, admin, writes };
}
Deno.test("reconciliation verifies account, exact product, price, amount and live mode", async () => {
  Deno.env.set("STRIPE_LIVE_MODE", "false");
  for (
    const change of [
      (p: ReturnType<typeof payment>) => {
        p.amount_total = 1;
      },
      (p: ReturnType<typeof payment>) => {
        p.currency = "usd";
      },
      (p: ReturnType<typeof payment>) => {
        p.metadata.product = "other-product";
      },
      (p: ReturnType<typeof payment>) => {
        p.line_items.data[0].quantity = 2;
      },
      (p: ReturnType<typeof payment>) => {
        p.line_items.data[0].price.id = "price_wrong";
      },
      (p: ReturnType<typeof payment>) => {
        p.livemode = true;
      },
      (p: ReturnType<typeof payment>) => {
        p.payment_intent.metadata.order_id = "other-order";
      },
    ]
  ) {
    const session = payment();
    change(session);
    const f = fixture(session);
    await assertRejects(() =>
      reconcileSession(f.client, f.admin, session.id, order.account_id)
    );
    assertEquals(f.writes, []);
  }
  const f = fixture();
  await assertRejects(() =>
    reconcileSession(f.client, f.admin, "cs_test_one", "another-account")
  );
  assertEquals(f.writes, []);
});
Deno.test("unpaid sessions grant nothing; current refunds and disputes revoke", async () => {
  Deno.env.set("STRIPE_LIVE_MODE", "false");
  const pending = payment();
  pending.payment_status = "unpaid";
  const waiting = fixture(pending);
  await reconcileSession(waiting.client, waiting.admin, pending.id);
  assertEquals(waiting.writes, []);
  for (const kind of ["paid", "refunded", "disputed"]) {
    const session = payment();
    session.payment_intent.latest_charge.refunded = kind === "refunded";
    session.payment_intent.latest_charge.disputed = kind === "disputed";
    const f = fixture(session);
    await reconcileSession(f.client, f.admin, session.id);
    assertEquals(f.writes.length, 1);
    assertEquals(f.writes[0].p_revoked, kind !== "paid");
  }
});
Deno.test("Stripe signature verification rejects forged or modified raw bodies", async () => {
  const client = new Stripe("fixture-test-key");
  const secret = "fixture-signing-secret";
  const payload = JSON.stringify({
    id: "evt_fixture",
    type: "checkout.session.completed",
    data: { object: {} },
  });
  const header = await client.webhooks.generateTestHeaderStringAsync({
    payload,
    secret,
  });
  const verify = (body: string, signature: string) =>
    client.webhooks.constructEventAsync(
      body,
      signature,
      secret,
      300,
      Stripe.createSubtleCryptoProvider(),
    );
  assertEquals((await verify(payload, header)).id, "evt_fixture");
  await assertRejects(() => verify(payload + " ", header));
  await assertRejects(() => verify(payload, "t=1,v1=0000"));
});
Deno.test("website leases expire within an hour, require known states and bound bodies", async () => {
  const now = Date.parse("2026-09-17T00:00:00Z");
  const pass = entitlementResponse({
    state: "WEBSITE_PASS_ACTIVE",
    expires_at: "2026-10-17T00:00:00Z",
  }, now);
  assertEquals(pass.active, true);
  assertEquals(pass.expires_at, "2026-09-17T01:00:00.000Z");
  assertEquals(pass.refresh_after, "2026-09-17T00:05:00.000Z");
  assertEquals(
    entitlementResponse({
      state: "WEBSITE_TRIAL_ACTIVE",
      expires_at: "2026-09-17T00:02:00Z",
    }, now).expires_at,
    "2026-09-17T00:02:00.000Z",
  );
  assertEquals(
    entitlementResponse({
      state: "invented",
      expires_at: "2099-01-01T00:00:00Z",
    }, now).active,
    false,
  );
  assertEquals(
    entitlementResponse({
      state: "WEBSITE_PASS_ACTIVE",
      expires_at: "2026-01-01T00:00:00Z",
    }, now).active,
    false,
  );
  await assertRejects(() =>
    boundedBody(
      new Request("https://test.invalid", { method: "POST", body: "abcdef" }),
      5,
    )
  );
});
