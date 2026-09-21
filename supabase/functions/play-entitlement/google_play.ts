import {
  KNOWN_SUBSCRIPTION_STATES,
  PLAY_PACKAGE_NAME,
  PLAY_PRODUCT_ID,
  type PlaySubscriptionVerifier,
  type VerifiedPlaySubscription,
} from "./core.ts";

const ANDROID_PUBLISHER_SCOPE =
  "https://www.googleapis.com/auth/androidpublisher";
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const PKCS8_PEM_LABEL = "PRIVATE KEY";
const PKCS8_PEM_BEGIN = `-----BEGIN ${PKCS8_PEM_LABEL}-----`;
const PKCS8_PEM_END = `-----END ${PKCS8_PEM_LABEL}-----`;
const MAX_PROVIDER_RESPONSE_BYTES = 128 * 1024;
const MAX_LINE_ITEMS = 100;
const PURCHASE_TOKEN = /^[A-Za-z0-9._=\-]+$/;
const ACCOUNT_HASH = /^[0-9a-f]{64}$/;
const RFC3339 =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;

interface ServiceAccount {
  clientEmail: string;
  privateKey: string;
}

export class GooglePlaySubscriptionVerifier
  implements PlaySubscriptionVerifier {
  private cachedToken: { value: string; expiresAtMillis: number } | null = null;

  constructor(
    private readonly serviceAccount: ServiceAccount,
    private readonly fetcher: typeof fetch = fetch,
    private readonly nowMillis: () => number = Date.now,
  ) {
    if (!serviceAccount.clientEmail.endsWith(".iam.gserviceaccount.com")) {
      throw new Error("invalid_service_account_email");
    }
    if (!serviceAccount.privateKey.includes("BEGIN PRIVATE KEY")) {
      throw new Error("invalid_service_account_key");
    }
  }

  async verify(purchaseToken: string): Promise<VerifiedPlaySubscription> {
    const response = await this.authorizedFetch(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${
        encodeURIComponent(PLAY_PACKAGE_NAME)
      }/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`,
      { method: "GET", headers: { accept: "application/json" } },
    );
    const body = await boundedJson(response);
    if (!response.ok) throw new Error("play_get_failed");
    const root = object(body);
    if (
      !Array.isArray(root.lineItems) || root.lineItems.length === 0 ||
      root.lineItems.length > MAX_LINE_ITEMS
    ) throw new Error("play_response_invalid");
    const lineItems = root.lineItems.map(object);
    const state = requiredString(root.subscriptionState, 1, 100);
    if (!KNOWN_SUBSCRIPTION_STATES.has(state)) {
      throw new Error("play_state_invalid");
    }
    const products = lineItems.map((item) =>
      requiredString(
        item.productId,
        1,
        256,
      )
    );
    const targetExpiryTimes = lineItems
      .filter((item) => item.productId === PLAY_PRODUCT_ID)
      .map((item) => optionalTimestamp(item.expiryTime))
      .filter((value): value is string => value !== null);
    const expiresAt = targetExpiryTimes.sort(
      (left, right) => Date.parse(right) - Date.parse(left),
    )[0] ?? null;
    if (
      products.includes(PLAY_PRODUCT_ID) &&
      KNOWN_SUBSCRIPTION_STATES.has(state) &&
      (state === "SUBSCRIPTION_STATE_ACTIVE" ||
        state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ||
        state === "SUBSCRIPTION_STATE_CANCELED") &&
      expiresAt === null
    ) throw new Error("play_expiry_invalid");
    const external = objectOrNull(root.externalAccountIdentifiers);
    const rawObfuscatedAccountId = external?.obfuscatedExternalAccountId;
    const obfuscatedExternalAccountId = rawObfuscatedAccountId === undefined ||
        rawObfuscatedAccountId === null
      ? null
      : requiredString(rawObfuscatedAccountId, 64, 64);
    if (
      obfuscatedExternalAccountId !== null &&
      !ACCOUNT_HASH.test(obfuscatedExternalAccountId)
    ) throw new Error("play_account_binding_invalid");
    const acknowledgementState = requiredString(
      root.acknowledgementState,
      1,
      100,
    );
    if (
      acknowledgementState !== "ACKNOWLEDGEMENT_STATE_PENDING" &&
      acknowledgementState !== "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
    ) throw new Error("play_acknowledgement_invalid");
    const outOfApp = objectOrNull(root.outOfAppPurchaseContext);
    const replacesPurchaseToken = purchaseTokenOrNull(
      root.linkedPurchaseToken ?? outOfApp?.expiredPurchaseToken,
    );
    return {
      state,
      expiresAt,
      productIds: products,
      obfuscatedExternalAccountId,
      acknowledgementPending: acknowledgementState ===
        "ACKNOWLEDGEMENT_STATE_PENDING",
      replacesPurchaseToken,
    };
  }

  async acknowledge(productId: string, purchaseToken: string): Promise<void> {
    const response = await this.authorizedFetch(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${
        encodeURIComponent(PLAY_PACKAGE_NAME)
      }/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${
        encodeURIComponent(purchaseToken)
      }:acknowledge`,
      {
        method: "POST",
        headers: { "content-type": "application/json; charset=utf-8" },
        body: "{}",
      },
    );
    await boundedBytes(response);
    if (!response.ok) throw new Error("play_acknowledge_failed");
  }

  private async authorizedFetch(
    url: string,
    init: RequestInit,
  ): Promise<Response> {
    const accessToken = await this.accessToken();
    return await this.fetcher(url, {
      ...init,
      redirect: "error",
      signal: AbortSignal.timeout(15_000),
      headers: { ...init.headers, authorization: `Bearer ${accessToken}` },
    });
  }

  private async accessToken(): Promise<string> {
    const now = this.nowMillis();
    if (
      this.cachedToken !== null &&
      this.cachedToken.expiresAtMillis - 60_000 > now
    ) {
      return this.cachedToken.value;
    }
    const assertion = await createServiceAccountAssertion(
      this.serviceAccount,
      now,
    );
    const body = new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    });
    const response = await this.fetcher(GOOGLE_TOKEN_URL, {
      method: "POST",
      redirect: "error",
      signal: AbortSignal.timeout(15_000),
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body,
    });
    const parsed = object(await boundedJson(response));
    if (!response.ok) throw new Error("google_oauth_failed");
    const value = optionalString(parsed.access_token, 16_384);
    const expiresIn = typeof parsed.expires_in === "number"
      ? parsed.expires_in
      : 0;
    if (
      value === null || /\s/.test(value) ||
      parsed.token_type !== "Bearer" || !Number.isSafeInteger(expiresIn) ||
      expiresIn < 60 || expiresIn > 3_600
    ) {
      throw new Error("google_oauth_invalid");
    }
    this.cachedToken = { value, expiresAtMillis: now + expiresIn * 1_000 };
    return value;
  }
}

export function parseGoogleServiceAccount(raw: string): ServiceAccount {
  if (raw.length === 0 || raw.length > 32 * 1024) {
    throw new Error("service_account_missing");
  }
  const root = object(JSON.parse(raw));
  if (root.type !== "service_account" || root.token_uri !== GOOGLE_TOKEN_URL) {
    throw new Error("service_account_invalid");
  }
  const clientEmail = optionalString(root.client_email, 320);
  const privateKey = optionalString(root.private_key, 16 * 1024);
  if (clientEmail === null || privateKey === null) {
    throw new Error("service_account_invalid");
  }
  return { clientEmail, privateKey };
}

async function createServiceAccountAssertion(
  account: ServiceAccount,
  nowMillis: number,
): Promise<string> {
  const issuedAt = Math.floor(nowMillis / 1_000);
  const header = base64Url(
    new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })),
  );
  const claim = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: account.clientEmail,
    scope: ANDROID_PUBLISHER_SCOPE,
    aud: GOOGLE_TOKEN_URL,
    iat: issuedAt,
    exp: issuedAt + 3_600,
  })));
  const signingInput = `${header}.${claim}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemBytes(account.privateKey),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64Url(new Uint8Array(signature))}`;
}

function pemBytes(value: string): ArrayBuffer {
  const match = value.match(
    new RegExp(
      `^${PKCS8_PEM_BEGIN}\\r?\\n([A-Za-z0-9+/=\\r\\n]+)\\r?\\n${PKCS8_PEM_END}\\r?\\n?$`,
    ),
  );
  if (match === null) throw new Error("invalid_service_account_key");
  const base64 = match[1]!.replaceAll(/\s/g, "");
  const binary = atob(base64);
  if (binary.length === 0 || binary.length > 16 * 1024) {
    throw new Error("invalid_service_account_key");
  }
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(
    /=+$/,
    "",
  );
}

async function boundedJson(response: Response): Promise<unknown> {
  const bytes = await boundedBytes(response);
  if (bytes.byteLength === 0) return {};
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

async function boundedBytes(response: Response): Promise<Uint8Array> {
  const declaredLength = response.headers.get("content-length");
  if (
    declaredLength !== null &&
    (!/^\d+$/.test(declaredLength) ||
      Number(declaredLength) > MAX_PROVIDER_RESPONSE_BYTES)
  ) throw new Error("provider_response_too_large");
  if (response.body === null) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > MAX_PROVIDER_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("provider_response_too_large");
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("invalid_provider_response");
  }
  return value as Record<string, unknown>;
}

function objectOrNull(value: unknown): Record<string, unknown> | null {
  return value === undefined || value === null ? null : object(value);
}

function optionalString(value: unknown, maximum: number): string | null {
  return typeof value === "string" && value.length > 0 &&
      value.length <= maximum
    ? value
    : null;
}

function requiredString(
  value: unknown,
  minimum: number,
  maximum: number,
): string {
  if (
    typeof value !== "string" || value.length < minimum ||
    value.length > maximum
  ) throw new Error("play_response_invalid");
  return value;
}

function optionalTimestamp(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  const timestamp = requiredString(value, 20, 40);
  if (!RFC3339.test(timestamp) || !Number.isFinite(Date.parse(timestamp))) {
    throw new Error("play_expiry_invalid");
  }
  return timestamp;
}

function purchaseTokenOrNull(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  const token = requiredString(value, 20, 4_096);
  if (!PURCHASE_TOKEN.test(token)) throw new Error("play_token_invalid");
  return token;
}
