import { adminClient, boundedBody, verifiedUser } from "../_shared/website.ts";
import {
  configuredPrice,
  PRODUCT,
  reconcileSession,
  stripe,
  validateStripeAccount,
} from "../_shared/stripe.ts";

const admin = adminClient();
const origin = Deno.env.get("WEBSITE_ORIGIN") ?? "https://beyoureye.com";
const cors = {
  "access-control-allow-origin": origin,
  "access-control-allow-headers": "authorization, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "vary": "Origin",
  "cache-control": "no-store",
};
const json = (body: unknown, status = 200) =>
  Response.json(body, { status, headers: cors });
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const LOCALES = new Set([
  "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
]);

function requestLocale(value: unknown): string {
  return typeof value === "string" && LOCALES.has(value) ? value : "en";
}

Deno.serve(async (request) => {
  if (
    request.headers.has("origin") && request.headers.get("origin") !== origin
  ) return json({ error: "origin_not_allowed" }, 403);
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: cors });
  }
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }
  try {
    const user = await verifiedUser(request, admin);
    if (!user) return json({ error: "confirmed_sign_in_required" }, 401);
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(await boundedBody(request));
      if (!body || typeof body !== "object" || Array.isArray(body)) {
        throw new Error();
      }
    } catch {
      return json({ error: "invalid_request" }, 400);
    }
    const billingEnabled = Deno.env.get("WEBSITE_BILLING_ENABLED") === "true";
    if (body.action === "set_locale") {
      const locale = requestLocale(body.locale);
      const { error } = await admin.auth.admin.updateUserById(user.id, {
        user_metadata: {
          ...(user.user_metadata ?? {}),
          preferred_locale: locale,
        },
      });
      if (error) throw error;
      return json({ ok: true, locale });
    }
    if (body.action === "status") {
      const { data, error } = await admin.rpc(
        "beyoureyes_product_entitlement",
        { p_account_id: user.id },
      );
      if (error) throw error;
      // Pausing new sales must not hide already granted access or require Stripe availability.
      if (!billingEnabled) {
        return json({
          ...data,
          billing_enabled: false,
          trial_available: false,
        });
      }
      const { data: trial, error: trialError } = await admin.from(
        "website_trials",
      ).select("expires_at").eq("account_id", user.id).maybeSingle();
      if (trialError) throw trialError;
      const client = stripe();
      await validateStripeAccount(client);
      const price = await configuredPrice(client);
      return json({
        ...data,
        billing_enabled: true,
        trial_available: trial === null && data.state === "none",
        amount: price.unit_amount,
        currency: price.currency,
        access_days: 30,
      });
    }
    // Existing paid sessions can still reconcile while new purchases and trials are paused.
    if (!billingEnabled && body.action !== "sync") {
      return json({ error: "website_billing_not_enabled" }, 503);
    }
    if (body.action === "trial") {
      // Starting a trial is an explicit authenticated user action; retries cannot extend it.
      const { error } = await admin.from("website_trials").upsert({
        account_id: user.id,
      }, { onConflict: "account_id", ignoreDuplicates: true });
      if (error) throw error;
      return json({ ok: true });
    }
    const client = stripe();
    await validateStripeAccount(client);
    if (
      body.action === "sync" && typeof body.session_id === "string" &&
      /^cs_(test_|live_)[A-Za-z0-9]+$/.test(body.session_id)
    ) {
      await reconcileSession(client, admin, body.session_id, user.id);
      return json({ ok: true });
    }
    if (
      body.action !== "checkout" || typeof body.request_id !== "string" ||
      !UUID.test(body.request_id)
    ) return json({ error: "invalid_request" }, 400);
    const locale = requestLocale(body.locale);
    const price = await configuredPrice(client);
    const { data: order, error } = await admin.rpc(
      "beyoureyes_reserve_website_order",
      {
        p_id: body.request_id,
        p_account_id: user.id,
        p_price_id: price.id,
        p_amount: price.unit_amount,
        p_currency: price.currency,
        p_locale: locale,
      },
    );
    if (error) {
      if (error.message.includes("checkout_rate_limited")) {
        return json({ error: "checkout_rate_limited" }, 429);
      }
      throw error;
    }
    if (
      order.state !== "pending" ||
      Date.parse(order.created_at) < Date.now() - 25 * 60 * 1000
    ) return json({ error: "new_checkout_required" }, 409);
    const session = await client.checkout.sessions.create({
      mode: "payment",
      locale: stripeLocale(order.locale),
      line_items: [{ price: order.price_id, quantity: 1 }],
      client_reference_id: order.id,
      metadata: { product: PRODUCT, order_id: order.id },
      payment_intent_data: {
        metadata: { product: PRODUCT, order_id: order.id },
      },
      success_url: `${origin}/account/?session_id={CHECKOUT_SESSION_ID}`,
      cancel_url: `${origin}/account/?canceled=1`,
      // Fixed per order for idempotent retries, with at least 30 minutes left at creation.
      expires_at: Math.floor(Date.parse(order.created_at) / 1000) + 3600,
      adaptive_pricing: { enabled: false },
      custom_text: {
        submit: {
          message:
            checkoutCopy(order.locale),
        },
      },
    }, { idempotencyKey: `website-order-${order.id}` });
    if (
      !session.url || !session.url.startsWith("https://checkout.stripe.com/")
    ) throw new Error("checkout_url_missing");
    const { error: writeError } = await admin.from("website_orders").update({
      checkout_session_id: session.id,
    }).eq("id", order.id).is("checkout_session_id", null);
    if (writeError) throw writeError;
    return json({ url: session.url });
  } catch {
    return json({ error: "billing_unavailable" }, 503);
  }
});

function stripeLocale(locale: string): string {
  return ({
    "zh-Hans": "zh",
    "zh-Hant": "zh-TW",
    "pt-BR": "pt-BR",
  } as Record<string, string>)[locale] ?? locale;
}

function checkoutCopy(locale: string): string {
  return ({
    "zh-Hans": "30 天 Be Your Eye Pro 使用权。一次付款，不自动续费。",
    "zh-Hant": "30 天 Be Your Eye Pro 使用權。一次付款，不自動續費。",
    ja: "Be Your Eye Pro 30日間利用権。一回払いで、自動更新はありません。",
    ko: "Be Your Eye Pro 30일 이용 권한입니다. 일회성 결제이며 자동 갱신되지 않습니다.",
    es: "Acceso a Be Your Eye Pro durante 30 días. Pago único; no se renueva automáticamente.",
    fr: "Accès à Be Your Eye Pro pendant 30 jours. Paiement unique, sans renouvellement automatique.",
    de: "30 Tage Be Your Eye Pro. Einmalzahlung; keine automatische Verlängerung.",
    "pt-BR": "Acesso ao Be Your Eye Pro por 30 dias. Pagamento único; sem renovação automática.",
  } as Record<string, string>)[locale] ?? "30 days of Be Your Eye Pro. One-time payment; no automatic renewal.";
}
