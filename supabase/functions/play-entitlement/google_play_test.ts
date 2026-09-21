import {
  GooglePlaySubscriptionVerifier,
  parseGoogleServiceAccount,
} from "./google_play.ts";
import { obfuscatedAccountId, PLAY_PRODUCT_ID } from "./core.ts";

function assert(
  condition: unknown,
  message = "assertion failed",
): asserts condition {
  if (!condition) throw new Error(message);
}

function equal(actual: unknown, expected: unknown): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
  }
}

function decodeBase64Url(value: string): Uint8Array<ArrayBuffer> {
  const standard = value.replaceAll("-", "+").replaceAll("_", "/") +
    "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(standard);
  const result = new Uint8Array(new ArrayBuffer(binary.length));
  for (let index = 0; index < binary.length; index += 1) {
    result[index] = binary.charCodeAt(index);
  }
  return result;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  });
}

async function serviceAccount() {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(
    await crypto.subtle.exportKey("pkcs8", pair.privateKey),
  );
  let binary = "";
  for (const byte of pkcs8) binary += String.fromCharCode(byte);
  const lines = btoa(binary).match(/.{1,64}/g) ?? [];
  const privateKeyLabel = "PRIVATE KEY";
  const privateKey = `-----BEGIN ${privateKeyLabel}-----\n${
    lines.join("\n")
  }\n-----END ${privateKeyLabel}-----\n`;
  return {
    pair,
    parsed: parseGoogleServiceAccount(JSON.stringify({
      type: "service_account",
      client_email: "play@project.iam.gserviceaccount.com",
      private_key: privateKey,
      token_uri: "https://oauth2.googleapis.com/token",
    })),
  };
}

Deno.test("Google OAuth JWT, product-specific expiry and acknowledge use exact contracts", async () => {
  const account = await serviceAccount();
  const accountHash = await obfuscatedAccountId(
    "018f3f72-6e5c-7b4e-9a8f-1234567890ab",
  );
  const replacement = "replacementabcdefghijklmnopqrstuvwxyz012345";
  let assertion = "";
  let oauthCalls = 0;
  let acknowledgeCalls = 0;
  const fetcher =
    (async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      if (url === "https://oauth2.googleapis.com/token") {
        oauthCalls += 1;
        assert(init?.method === "POST");
        const form = init?.body as URLSearchParams;
        equal(
          form.get("grant_type"),
          "urn:ietf:params:oauth:grant-type:jwt-bearer",
        );
        assertion = form.get("assertion") ?? "";
        return json({
          access_token: "google-access-token",
          token_type: "Bearer",
          expires_in: 3600,
        });
      }
      assert(
        new Headers(init?.headers).get("authorization") ===
          "Bearer google-access-token",
      );
      if (url.endsWith(":acknowledge")) {
        acknowledgeCalls += 1;
        assert(init?.method === "POST");
        equal(init?.body, "{}");
        return new Response(null, { status: 200 });
      }
      assert(url.includes("/purchases/subscriptionsv2/tokens/"));
      assert(init?.method === "GET");
      return json({
        subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
        acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
        linkedPurchaseToken: replacement,
        externalAccountIdentifiers: {
          obfuscatedExternalAccountId: accountHash,
        },
        lineItems: [
          {
            productId: PLAY_PRODUCT_ID,
            expiryTime: "2026-09-23T00:00:00Z",
          },
          {
            productId: "unrelated_add_on",
            expiryTime: "2027-12-23T00:00:00Z",
          },
        ],
      });
    }) as typeof fetch;
  const verifier = new GooglePlaySubscriptionVerifier(
    account.parsed,
    fetcher,
    () => Date.parse("2026-08-23T00:00:00Z"),
  );
  const verified = await verifier.verify(
    "abcdefghijklmnopqrstuvwxyz0123456789",
  );
  equal(verified.expiresAt, "2026-09-23T00:00:00Z");
  equal(verified.productIds, [PLAY_PRODUCT_ID, "unrelated_add_on"]);
  equal(verified.obfuscatedExternalAccountId, accountHash);
  equal(verified.replacesPurchaseToken, replacement);
  equal(verified.acknowledgementPending, true);

  const parts = assertion.split(".");
  equal(parts.length, 3);
  const claims = JSON.parse(
    new TextDecoder().decode(decodeBase64Url(parts[1]!)),
  );
  equal(claims, {
    iss: "play@project.iam.gserviceaccount.com",
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    iat: 1787443200,
    exp: 1787446800,
  });
  assert(
    await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      account.pair.publicKey,
      decodeBase64Url(parts[2]!),
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
    ),
  );

  await verifier.acknowledge(
    PLAY_PRODUCT_ID,
    "abcdefghijklmnopqrstuvwxyz0123456789",
  );
  equal(oauthCalls, 1);
  equal(acknowledgeCalls, 1);
});

Deno.test("Google response fails closed on unknown state and oversized body", async () => {
  const account = await serviceAccount();
  for (
    const providerResponse of [
      json({
        subscriptionState: "SUBSCRIPTION_STATE_FUTURE_UNKNOWN",
        acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
        lineItems: [{
          productId: PLAY_PRODUCT_ID,
          expiryTime: "2026-09-23T00:00:00Z",
        }],
      }),
      new Response(new Uint8Array(128 * 1024 + 1), { status: 200 }),
    ]
  ) {
    const fetcher = (async (input: string | URL | Request) =>
      String(input) === "https://oauth2.googleapis.com/token"
        ? json({
          access_token: "google-access-token",
          token_type: "Bearer",
          expires_in: 3600,
        })
        : providerResponse.clone()) as typeof fetch;
    const verifier = new GooglePlaySubscriptionVerifier(
      account.parsed,
      fetcher,
      () =>
        Date.parse("2026-08-23T00:00:00Z"),
    );
    let failed = false;
    try {
      await verifier.verify("abcdefghijklmnopqrstuvwxyz0123456789");
    } catch {
      failed = true;
    }
    equal(failed, true);
  }
});
