export const PLAY_PACKAGE_NAME = "app.beyoureyes.monitor";
export const PLAY_PRODUCT_ID = "be_your_eye_pro";

const MAX_BODY_BYTES = 8 * 1024;
const MAX_TOKEN_LENGTH = 4_096;
export const ENTITLEMENT_LEASE_MILLIS = 72 * 60 * 60 * 1_000;
export const ENTITLEMENT_REFRESH_MILLIS = 24 * 60 * 60 * 1_000;
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

export const GRANTED_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
  "SUBSCRIPTION_STATE_CANCELED",
]);

export const KNOWN_SUBSCRIPTION_STATES = new Set([
  "SUBSCRIPTION_STATE_PENDING",
  ...GRANTED_STATES,
  "SUBSCRIPTION_STATE_PAUSED",
  "SUBSCRIPTION_STATE_ON_HOLD",
  "SUBSCRIPTION_STATE_EXPIRED",
  "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED",
]);

export interface VerifiedPlaySubscription {
  state: string;
  expiresAt: string | null;
  productIds: string[];
  obfuscatedExternalAccountId: string | null;
  acknowledgementPending: boolean;
  /** Raw provider value. It is hashed before any persistence. */
  replacesPurchaseToken: string | null;
}

export interface PlaySubscriptionVerifier {
  verify(purchaseToken: string): Promise<VerifiedPlaySubscription>;
  acknowledge(productId: string, purchaseToken: string): Promise<void>;
}

export interface StoredEntitlement {
  productId: string;
  state: string;
  expiresAt: string | null;
}

export interface EntitlementWrite extends StoredEntitlement {
  accountId: string;
  obfuscatedAccountId: string;
  purchaseTokenSha256: string;
  replacesPurchaseTokenSha256: string | null;
  verifiedAt: string;
}

export interface EntitlementStore {
  read(accountId: string): Promise<StoredEntitlement | null>;
  /** False means a newer purchase token already owns the account row. */
  write(value: EntitlementWrite): Promise<boolean>;
}

export interface PlayEntitlementDependencies {
  authenticate(request: Request): Promise<string | null>;
  store: EntitlementStore;
  verifier: PlaySubscriptionVerifier | null;
  now?: () => Date;
  log?: (record: Readonly<Record<string, unknown>>) => void;
}

export function mayReplaceStoredPurchase(
  currentTokenSha256: string,
  currentExpiresAt: string | null,
  incoming: EntitlementWrite,
): boolean {
  const currentExpiryMillis = currentExpiresAt === null
    ? Number.NEGATIVE_INFINITY
    : Date.parse(currentExpiresAt);
  return currentTokenSha256 === incoming.purchaseTokenSha256 ||
    currentTokenSha256 === incoming.replacesPurchaseTokenSha256 ||
    (!Number.isFinite(currentExpiryMillis) ||
      currentExpiryMillis <= Date.parse(incoming.verifiedAt));
}

export function createPlayEntitlementHandler(
  dependencies: PlayEntitlementDependencies,
): (request: Request) => Promise<Response> {
  return async (request) => {
    if (request.method !== "POST") {
      return new Response(null, {
        status: 405,
        headers: { ...JSON_HEADERS, allow: "POST" },
      });
    }
    let accountId: string | null;
    try {
      accountId = await dependencies.authenticate(request);
    } catch {
      return errorResponse(503, "identity_unavailable");
    }
    if (accountId === null) return errorResponse(401, "sign_in_required");

    let command: EntitlementCommand;
    try {
      command = parseCommand(await readJsonBody(request));
    } catch {
      return errorResponse(400, "invalid_request");
    }

    const now = (dependencies.now ?? (() => new Date()))();
    if (command.action === "status") {
      try {
        return jsonResponse(
          200,
          statusResponse(await dependencies.store.read(accountId), now),
        );
      } catch {
        return errorResponse(503, "entitlement_unavailable");
      }
    }
    if (dependencies.verifier === null) {
      return errorResponse(503, "play_verification_not_configured");
    }

    const startedAt = Date.now();
    try {
      const verified = await dependencies.verifier.verify(
        command.purchaseToken,
      );
      const expectedAccount = await obfuscatedAccountId(accountId);
      if (
        verified.obfuscatedExternalAccountId === null ||
        !constantTimeEqual(
          verified.obfuscatedExternalAccountId,
          expectedAccount,
        ) ||
        !verified.productIds.includes(PLAY_PRODUCT_ID)
      ) {
        return errorResponse(400, "purchase_account_mismatch");
      }
      if (!KNOWN_SUBSCRIPTION_STATES.has(verified.state)) {
        throw new Error("unknown_subscription_state");
      }

      const expiryMillis = verified.expiresAt === null
        ? Number.NaN
        : Date.parse(verified.expiresAt);
      const granted = GRANTED_STATES.has(verified.state) &&
        Number.isFinite(expiryMillis) && expiryMillis > now.getTime();
      const write: EntitlementWrite = {
        accountId,
        obfuscatedAccountId: expectedAccount,
        productId: PLAY_PRODUCT_ID,
        state: verified.state,
        expiresAt: verified.expiresAt,
        purchaseTokenSha256: await sha256Hex(command.purchaseToken),
        replacesPurchaseTokenSha256: verified.replacesPurchaseToken === null
          ? null
          : await sha256Hex(verified.replacesPurchaseToken),
        verifiedAt: now.toISOString(),
      };
      if (!await dependencies.store.write(write)) {
        return errorResponse(409, "purchase_replaced");
      }
      if (granted && verified.acknowledgementPending) {
        await dependencies.verifier.acknowledge(
          PLAY_PRODUCT_ID,
          command.purchaseToken,
        );
      }
      dependencies.log?.({
        kind: "play_entitlement",
        outcome: granted ? "active" : "inactive",
        state: verified.state,
        duration_ms: Math.max(0, Date.now() - startedAt),
      });
      return jsonResponse(200, statusResponse(write, now));
    } catch {
      dependencies.log?.({
        kind: "play_entitlement",
        outcome: "verification_failed",
        duration_ms: Math.max(0, Date.now() - startedAt),
      });
      return errorResponse(503, "play_verification_unavailable");
    }
  };
}

type EntitlementCommand =
  | { action: "status" }
  | { action: "verify_purchase"; purchaseToken: string };

function parseCommand(value: unknown): EntitlementCommand {
  const root = strictObject(value);
  if (root.action === "status") {
    exactKeys(root, ["action"]);
    return { action: "status" };
  }
  if (root.action === "verify_purchase") {
    exactKeys(root, ["action", "product_id", "purchase_token"]);
    if (root.product_id !== PLAY_PRODUCT_ID) invalid();
    const token = strictString(root.purchase_token, 20, MAX_TOKEN_LENGTH);
    if (!/^[A-Za-z0-9._=\-]+$/.test(token)) invalid();
    return { action: "verify_purchase", purchaseToken: token };
  }
  invalid();
}

function statusResponse(value: StoredEntitlement | null, now: Date) {
  if (value === null || value.productId !== PLAY_PRODUCT_ID) {
    return {
      product_id: PLAY_PRODUCT_ID,
      active: false,
      state: "none",
      expires_at: null,
      refresh_after: null,
    };
  }
  const expiresAt = value.expiresAt === null
    ? Number.NaN
    : Date.parse(value.expiresAt);
  const active = GRANTED_STATES.has(value.state) &&
    Number.isFinite(expiresAt) && expiresAt > now.getTime();
  const leaseExpiresAt = active
    ? Math.min(expiresAt, now.getTime() + ENTITLEMENT_LEASE_MILLIS)
    : null;
  const refreshAfter = leaseExpiresAt === null ? null : new Date(
    Math.min(leaseExpiresAt, now.getTime() + ENTITLEMENT_REFRESH_MILLIS),
  ).toISOString();
  return {
    product_id: PLAY_PRODUCT_ID,
    active,
    state: value.state,
    expires_at: leaseExpiresAt === null
      ? value.expiresAt
      : new Date(leaseExpiresAt).toISOString(),
    refresh_after: refreshAfter,
  };
}

export async function obfuscatedAccountId(accountId: string): Promise<string> {
  return await sha256Hex(accountId);
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
  );
  return [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(left: string, right: string): boolean {
  const length = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < length; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^
      (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

async function readJsonBody(request: Request): Promise<unknown> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) invalid();
  const declaredLength = request.headers.get("content-length");
  if (
    declaredLength !== null &&
    (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_BODY_BYTES)
  ) invalid();
  if (request.body === null) invalid();
  const raw = await readBounded(request.body, MAX_BODY_BYTES);
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(raw));
  } catch {
    invalid();
  }
}

async function readBounded(
  stream: ReadableStream<Uint8Array>,
  maximumBytes: number,
): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > maximumBytes) {
        await reader.cancel();
        invalid();
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  if (size === 0) invalid();
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

function strictObject(value: unknown): Record<string, unknown> {
  if (
    value === null || typeof value !== "object" || Array.isArray(value) ||
    Object.getPrototypeOf(value) !== Object.prototype
  ) invalid();
  return value as Record<string, unknown>;
}

function exactKeys(root: Record<string, unknown>, keys: string[]) {
  const actual = Object.keys(root).sort();
  const expected = [...keys].sort();
  if (
    actual.length !== expected.length ||
    actual.some((key, index) => key !== expected[index])
  ) {
    invalid();
  }
}

function strictString(
  value: unknown,
  minimum: number,
  maximum: number,
): string {
  if (
    typeof value !== "string" || value.length < minimum ||
    value.length > maximum
  ) invalid();
  return value;
}

function invalid(): never {
  throw new Error("invalid_request");
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function errorResponse(status: number, code: string): Response {
  return jsonResponse(status, { code });
}
