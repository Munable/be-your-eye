import {
  type EntitlementWrite,
  GRANTED_STATES,
  KNOWN_SUBSCRIPTION_STATES,
  obfuscatedAccountId,
  PLAY_PACKAGE_NAME,
  PLAY_PRODUCT_ID,
  type PlaySubscriptionVerifier,
  sha256Hex,
} from "../play-entitlement/core.ts";

const MAX_PUSH_BYTES = 32 * 1024;
const MAX_NOTIFICATION_BYTES = 8 * 1024;
const NOTIFICATION_TYPES = new Set([
  1,
  2,
  3,
  4,
  5,
  6,
  7,
  8,
  9,
  10,
  11,
  12,
  13,
  17,
  18,
  19,
  20,
  22,
]);

export interface RtdnEntitlementStore {
  accountIdForObfuscatedId(value: string): Promise<string | null>;
  /** False means this RTDN belongs to a purchase token already replaced. */
  write(value: EntitlementWrite): Promise<boolean>;
}

/**
 * Token owners which an RTDN may atomically update. A new token may take over
 * only from the exact predecessor verified by Google Play; an unrelated token
 * for the same obfuscated account never becomes an implicit replacement.
 */
export function rtdnTakeoverTokenHashes(
  value: Pick<
    EntitlementWrite,
    "purchaseTokenSha256" | "replacesPurchaseTokenSha256"
  >,
): string[] {
  if (
    value.replacesPurchaseTokenSha256 === null ||
    value.replacesPurchaseTokenSha256 === value.purchaseTokenSha256
  ) return [value.purchaseTokenSha256];
  return [value.purchaseTokenSha256, value.replacesPurchaseTokenSha256];
}

export interface PlayRtdnDependencies {
  authenticate(request: Request): Promise<boolean>;
  verifier: PlaySubscriptionVerifier | null;
  store: RtdnEntitlementStore;
  now?: () => Date;
  log?: (record: Readonly<Record<string, unknown>>) => void;
}

export function createPlayRtdnHandler(
  dependencies: PlayRtdnDependencies,
): (request: Request) => Promise<Response> {
  return async (request) => {
    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { allow: "POST" } });
    }
    try {
      if (!await dependencies.authenticate(request)) {
        return new Response(null, { status: 401 });
      }
    } catch {
      return new Response(null, { status: 503 });
    }
    if (dependencies.verifier === null) {
      return new Response(null, { status: 503 });
    }

    let notification: SubscriptionNotification | null;
    try {
      notification = parsePush(await readBoundedBody(request));
    } catch {
      return new Response(null, { status: 400 });
    }
    if (notification === null) return new Response(null, { status: 204 });
    const now = (dependencies.now ?? (() => new Date()))();

    try {
      const verified = await dependencies.verifier.verify(
        notification.purchaseToken,
      );
      if (
        verified.obfuscatedExternalAccountId === null ||
        !verified.productIds.includes(PLAY_PRODUCT_ID)
      ) return new Response(null, { status: 204 });
      if (!KNOWN_SUBSCRIPTION_STATES.has(verified.state)) {
        throw new Error("unknown_subscription_state");
      }
      const accountId = await dependencies.store.accountIdForObfuscatedId(
        verified.obfuscatedExternalAccountId,
      );
      if (accountId === null) {
        dependencies.log?.({ kind: "play_rtdn", outcome: "unlinked_purchase" });
        return new Response(null, { status: 204 });
      }
      if (
        await obfuscatedAccountId(accountId) !==
          verified.obfuscatedExternalAccountId
      ) {
        dependencies.log?.({
          kind: "play_rtdn",
          outcome: "account_binding_mismatch",
        });
        return new Response(null, { status: 204 });
      }
      const expiryMillis = verified.expiresAt === null
        ? Number.NaN
        : Date.parse(verified.expiresAt);
      const granted = GRANTED_STATES.has(verified.state) &&
        Number.isFinite(expiryMillis) && expiryMillis > now.getTime();
      const write: EntitlementWrite = {
        accountId,
        obfuscatedAccountId: verified.obfuscatedExternalAccountId,
        productId: PLAY_PRODUCT_ID,
        state: verified.state,
        expiresAt: verified.expiresAt,
        purchaseTokenSha256: await sha256Hex(notification.purchaseToken),
        replacesPurchaseTokenSha256: verified.replacesPurchaseToken === null
          ? null
          : await sha256Hex(verified.replacesPurchaseToken),
        verifiedAt: now.toISOString(),
      };
      if (!await dependencies.store.write(write)) {
        dependencies.log?.({
          kind: "play_rtdn",
          outcome: "replaced_purchase_ignored",
          notification_type: notification.notificationType,
        });
        return new Response(null, { status: 204 });
      }
      if (granted && verified.acknowledgementPending) {
        await dependencies.verifier.acknowledge(
          PLAY_PRODUCT_ID,
          notification.purchaseToken,
        );
      }
      dependencies.log?.({
        kind: "play_rtdn",
        outcome: granted ? "active" : "inactive",
        state: verified.state,
        notification_type: notification.notificationType,
      });
      return new Response(null, { status: 204 });
    } catch {
      return new Response(null, { status: 503 });
    }
  };
}

interface SubscriptionNotification {
  notificationType: number;
  purchaseToken: string;
}

function parsePush(value: unknown): SubscriptionNotification | null {
  const root = object(value);
  exactKnownKeys(root, ["message", "subscription"], ["deliveryAttempt"]);
  if (
    typeof root.subscription !== "string" ||
    !/^projects\/[A-Za-z0-9._~+%:\-]{1,256}\/subscriptions\/[A-Za-z0-9._~+%:\-]{1,256}$/
      .test(
        root.subscription,
      )
  ) invalid();
  const message = object(root.message);
  exactKnownKeys(
    message,
    ["data", "messageId"],
    ["attributes", "orderingKey", "publishTime"],
  );
  if (
    typeof message.messageId !== "string" ||
    !/^[A-Za-z0-9_-]{1,256}$/.test(message.messageId)
  ) invalid();
  if (
    message.publishTime !== undefined &&
    (typeof message.publishTime !== "string" ||
      !Number.isFinite(Date.parse(message.publishTime)))
  ) invalid();
  if (
    typeof message.data !== "string" ||
    message.data.length > MAX_NOTIFICATION_BYTES * 2
  ) invalid();
  const decoded = decodeBase64(message.data);
  if (decoded.byteLength === 0 || decoded.byteLength > MAX_NOTIFICATION_BYTES) {
    invalid();
  }
  const notification = object(
    JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(decoded)),
  );
  if (
    typeof notification.version !== "string" ||
    notification.version.length === 0 || notification.version.length > 16 ||
    typeof notification.packageName !== "string" ||
    typeof notification.eventTimeMillis !== "string" ||
    !/^\d{1,20}$/.test(notification.eventTimeMillis)
  ) invalid();
  if (notification.packageName !== PLAY_PACKAGE_NAME) return null;
  if (notification.subscriptionNotification === undefined) return null;
  exactKnownKeys(
    notification,
    [
      "version",
      "packageName",
      "eventTimeMillis",
      "subscriptionNotification",
    ],
    [],
  );
  const subscription = object(notification.subscriptionNotification);
  exactKnownKeys(
    subscription,
    ["version", "notificationType", "purchaseToken"],
    [],
  );
  if (
    typeof subscription.version !== "string" ||
    subscription.version.length === 0 || subscription.version.length > 16
  ) invalid();
  if (
    typeof subscription.notificationType !== "number" ||
    !Number.isSafeInteger(subscription.notificationType) ||
    !NOTIFICATION_TYPES.has(subscription.notificationType)
  ) invalid();
  if (
    typeof subscription.purchaseToken !== "string" ||
    subscription.purchaseToken.length < 20 ||
    subscription.purchaseToken.length > 4_096 ||
    !/^[A-Za-z0-9._=\-]+$/.test(subscription.purchaseToken)
  ) invalid();
  return {
    notificationType: subscription.notificationType,
    purchaseToken: subscription.purchaseToken,
  };
}

async function readBoundedBody(request: Request): Promise<unknown> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) invalid();
  const declaredLength = request.headers.get("content-length");
  if (
    declaredLength !== null &&
    (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_PUSH_BYTES)
  ) invalid();
  if (request.body === null) invalid();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > MAX_PUSH_BYTES) {
        await reader.cancel();
        invalid();
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  if (size === 0) invalid();
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

function decodeBase64(value: string): Uint8Array {
  if (value.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) {
    invalid();
  }
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid();
  }
  return value as Record<string, unknown>;
}

function exactKnownKeys(
  value: Record<string, unknown>,
  required: string[],
  optional: string[],
) {
  const keys = Object.keys(value);
  if (required.some((key) => !keys.includes(key))) invalid();
  if (keys.some((key) => !required.includes(key) && !optional.includes(key))) {
    invalid();
  }
}

function invalid(): never {
  throw new Error("invalid_push");
}
