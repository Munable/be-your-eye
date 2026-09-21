import Stripe from "npm:stripe@22.6.2";
import { adminClient, required } from "./website.ts";

export { Stripe };
export const PRODUCT = "be_your_eye_pro";
export const stripe = () =>
  new Stripe(required("STRIPE_SECRET_KEY"), {
    httpClient: Stripe.createFetchHttpClient(),
    timeout: 15_000,
    maxNetworkRetries: 1,
  });
export const liveMode = () => required("STRIPE_LIVE_MODE") === "true";

export async function validateStripeAccount(client: Stripe) {
  if (!["true", "false"].includes(required("STRIPE_LIVE_MODE"))) {
    throw new Error("invalid_stripe_mode");
  }
  const account = await client.accounts.retrieve(null);
  if (account.id !== required("STRIPE_ACCOUNT_ID")) {
    throw new Error("stripe_account_mismatch");
  }
  if (liveMode() && (!account.charges_enabled || !account.payouts_enabled)) {
    throw new Error("stripe_account_not_active");
  }
}

export async function configuredPrice(client: Stripe) {
  const price = await client.prices.retrieve(required("STRIPE_PRICE_ID"));
  if (
    !price.active || price.type !== "one_time" ||
    price.livemode !== liveMode() ||
    !price.unit_amount || price.unit_amount < 1 ||
    price.metadata.product !== PRODUCT ||
    price.metadata.access_days !== "30"
  ) throw new Error("invalid_website_price");
  return price;
}

/** Both webhook and the authenticated return page use this same idempotent reconciliation. */
export async function reconcileSession(
  client: Stripe,
  admin: ReturnType<typeof adminClient>,
  sessionId: string,
  accountId?: string,
) {
  const session = await client.checkout.sessions.retrieve(sessionId, {
    expand: ["line_items", "payment_intent.latest_charge"],
  });
  if (
    session.livemode !== liveMode() || session.mode !== "payment" ||
    session.metadata?.product !== PRODUCT
  ) throw new Error("session_mismatch");
  const orderId = session.metadata?.order_id;
  if (!orderId) throw new Error("order_missing");
  const { data: order, error } = await admin.from("website_orders").select("*")
    .eq("id", orderId).single();
  if (
    error || !order || (accountId && order.account_id !== accountId) ||
    session.client_reference_id !== order.id ||
    (order.checkout_session_id && order.checkout_session_id !== session.id)
  ) throw new Error("order_mismatch");
  const items = session.line_items?.data;
  if (
    session.amount_total !== order.amount ||
    session.currency !== order.currency ||
    items?.length !== 1 || session.line_items?.has_more ||
    items[0].price?.id !== order.price_id ||
    items[0].quantity !== 1
  ) throw new Error("price_mismatch");
  if (session.payment_status !== "paid" || session.status !== "complete") {
    return;
  }
  const intent = session.payment_intent;
  if (
    !intent || typeof intent === "string" || intent.status !== "succeeded" ||
    intent.amount_received !== order.amount ||
    intent.currency !== order.currency ||
    intent.metadata.order_id !== order.id || intent.metadata.product !== PRODUCT
  ) throw new Error("payment_mismatch");
  const charge = intent.latest_charge;
  if (
    !charge || typeof charge === "string" || !charge.paid ||
    charge.livemode !== liveMode()
  ) throw new Error("charge_unavailable");
  const revoked = charge.disputed || charge.refunded ||
    charge.amount_refunded >= order.amount;
  const { error: writeError } = await admin.rpc(
    "beyoureyes_apply_website_payment",
    {
      p_order_id: order.id,
      p_session_id: session.id,
      p_payment_intent_id: intent.id,
      p_amount: order.amount,
      p_currency: order.currency,
      p_revoked: revoked,
    },
  );
  if (writeError) throw writeError;
}
