import { adminClient, boundedBody, required } from "../_shared/website.ts";
import {
  liveMode,
  PRODUCT,
  reconcileSession,
  Stripe,
  stripe,
  validateStripeAccount,
} from "../_shared/stripe.ts";
const admin = adminClient();

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response(null, { status: 405 });
  let client: Stripe;
  let webhookSecret: string;
  try {
    client = stripe();
    webhookSecret = required("STRIPE_WEBHOOK_SECRET");
  } catch {
    return Response.json({ error: "webhook_not_configured" }, { status: 503 });
  }
  let event: Stripe.Event;
  try {
    event = await client.webhooks.constructEventAsync(
      await boundedBody(request, 256 * 1024),
      request.headers.get("stripe-signature") ?? "",
      webhookSecret,
      300,
      Stripe.createSubtleCryptoProvider(),
    );
    if (event.livemode !== liveMode() || event.account) {
      throw new Error("wrong_account_or_mode");
    }
  } catch {
    return Response.json({ error: "invalid_signature_or_mode" }, {
      status: 400,
    });
  }
  try {
    if (
      ["checkout.session.completed", "checkout.session.async_payment_succeeded"]
        .includes(event.type)
    ) {
      const session = event.data.object as Stripe.Checkout.Session;
      if (session.metadata?.product !== PRODUCT) {
        return Response.json({ received: true });
      }
      await validateStripeAccount(client);
      await reconcileSession(client, admin, session.id);
    } else if (
      ["charge.refunded", "charge.dispute.created", "charge.dispute.closed"]
        .includes(event.type)
    ) {
      const object = event.data.object as Stripe.Charge | Stripe.Dispute;
      const paymentIntent = typeof object.payment_intent === "string"
        ? object.payment_intent
        : object.payment_intent?.id;
      if (paymentIntent) {
        await validateStripeAccount(client);
        // A refund can arrive before the first grant, so locate it through Stripe, not only our ledger.
        const sessions = await client.checkout.sessions.list({
          payment_intent: paymentIntent,
          limit: 2,
        });
        for (const session of sessions.data) {
          if (session.metadata?.product === PRODUCT) {
            await reconcileSession(client, admin, session.id);
          }
        }
      }
    }
    return Response.json({ received: true });
  } catch {
    // Non-2xx makes Stripe retry. Never log event bodies, financial details or secrets.
    console.error(
      JSON.stringify({
        kind: "stripe_webhook",
        outcome: "reconciliation_failed",
        type: event.type,
      }),
    );
    return Response.json({ error: "reconciliation_unavailable" }, {
      status: 503,
    });
  }
});
