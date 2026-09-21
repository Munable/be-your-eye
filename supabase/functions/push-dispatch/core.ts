export const MAX_BATCH_SIZE = 20;
export const DEFAULT_FCM_TIMEOUT_MS = 8_000;
const MAX_FCM_RESPONSE_BYTES = 16 * 1024;

export interface PushDelivery {
  outbox_id: number;
  event_id: string;
  device_id: string;
  token: string;
  cursor_hint: string;
}

export interface PushCompletion {
  outboxId: number;
  leaseId: string;
  success: boolean;
  permanentFailure: boolean;
  error: string | null;
  nowIso: string;
}

export interface PushOutbox {
  claim(
    limit: number,
    leaseId: string,
    nowIso: string,
  ): Promise<PushDelivery[]>;
  complete(completion: PushCompletion): Promise<boolean>;
}

export interface PushSender {
  send(delivery: PushDelivery): Promise<void>;
}

export class PushSendError extends Error {
  constructor(
    readonly code: string,
    readonly permanentForToken = false,
  ) {
    super(code);
    this.name = "PushSendError";
  }
}

export interface DispatchResult {
  claimed: number;
  sent: number;
  retryableFailures: number;
  permanentFailures: number;
  failureCodes: Record<string, number>;
}

/** One bounded, sequential lease iteration. The database owns retry timing and idempotency. */
export async function dispatchPushBatch(options: {
  outbox: PushOutbox;
  sender: PushSender;
  leaseId: string;
  nowIso: () => string;
  limit?: number;
}): Promise<DispatchResult> {
  const limit = options.limit ?? MAX_BATCH_SIZE;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_BATCH_SIZE) {
    throw new Error("push batch size must be between 1 and 20");
  }
  requireUuid(options.leaseId);
  const deliveries = await options.outbox.claim(
    limit,
    options.leaseId,
    options.nowIso(),
  );
  if (deliveries.length > limit) {
    throw new Error("push claim exceeded the requested limit");
  }
  const result: DispatchResult = {
    claimed: deliveries.length,
    sent: 0,
    retryableFailures: 0,
    permanentFailures: 0,
    failureCodes: {},
  };
  for (const delivery of deliveries) {
    requireDelivery(delivery);
    try {
      await options.sender.send(delivery);
      await requireCompleted(options.outbox, {
        outboxId: delivery.outbox_id,
        leaseId: options.leaseId,
        success: true,
        permanentFailure: false,
        error: null,
        nowIso: options.nowIso(),
      });
      result.sent += 1;
    } catch (error) {
      const normalized = error instanceof PushSendError
        ? error
        : new PushSendError("worker_send_failed");
      await requireCompleted(options.outbox, {
        outboxId: delivery.outbox_id,
        leaseId: options.leaseId,
        success: false,
        permanentFailure: normalized.permanentForToken,
        error: normalized.code,
        nowIso: options.nowIso(),
      });
      if (normalized.permanentForToken) result.permanentFailures += 1;
      else result.retryableFailures += 1;
      result.failureCodes[normalized.code] =
        (result.failureCodes[normalized.code] ?? 0) + 1;
    }
  }
  return result;
}

async function requireCompleted(
  outbox: PushOutbox,
  completion: PushCompletion,
): Promise<void> {
  if (!await outbox.complete(completion)) {
    throw new Error("push lease was lost");
  }
}

export class FcmHttpSender implements PushSender {
  private readonly endpoint: string;

  constructor(
    private readonly options: {
      projectId: string;
      accessToken: () => Promise<string>;
      fetch?: typeof fetch;
      timeoutMs?: number;
    },
  ) {
    if (!/^[a-z][a-z0-9-]{4,62}$/.test(options.projectId)) {
      throw new Error("invalid Firebase project id");
    }
    const timeout = options.timeoutMs ?? DEFAULT_FCM_TIMEOUT_MS;
    if (!Number.isInteger(timeout) || timeout < 1_000 || timeout > 15_000) {
      throw new Error("invalid FCM timeout");
    }
    this.endpoint =
      `https://fcm.googleapis.com/v1/projects/${options.projectId}/messages:send`;
  }

  async send(delivery: PushDelivery): Promise<void> {
    requireDelivery(delivery);
    const accessToken = await this.options.accessToken();
    if (accessToken.length === 0) {
      throw new PushSendError("fcm_auth_unavailable");
    }
    const response = await (this.options.fetch ?? fetch)(this.endpoint, {
      method: "POST",
      redirect: "error",
      signal: AbortSignal.timeout(
        this.options.timeoutMs ?? DEFAULT_FCM_TIMEOUT_MS,
      ),
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json; charset=utf-8",
      },
      body: JSON.stringify({
        message: {
          fid: delivery.token,
          data: {
            schema_version: "3.0",
            event_id: delivery.event_id,
            cursor_hint: delivery.cursor_hint,
          },
          android: { priority: "high", ttl: "300s" },
        },
      }),
    });
    const body = await boundedText(response, MAX_FCM_RESPONSE_BYTES);
    if (response.ok) {
      const parsed = parseRecord(body);
      if (typeof parsed.name !== "string" || parsed.name.length === 0) {
        throw new PushSendError("fcm_invalid_success");
      }
      return;
    }
    const providerCode = fcmErrorCode(body);
    if (
      providerCode === "UNREGISTERED" ||
      providerCode === "messaging/registration-token-not-registered"
    ) {
      throw new PushSendError("fcm_installation_unregistered", true);
    }
    throw new PushSendError(`fcm_${normalizeCode(providerCode)}`);
  }
}

function requireDelivery(delivery: PushDelivery): void {
  if (!Number.isSafeInteger(delivery.outbox_id) || delivery.outbox_id < 1) {
    throw new Error("invalid push outbox id");
  }
  requireUuid(delivery.event_id);
  requireUuid(delivery.device_id);
  if (delivery.token.length < 20 || delivery.token.length > 4096) {
    throw new Error("invalid push token");
  }
  if (delivery.cursor_hint.length < 1 || delivery.cursor_hint.length > 128) {
    throw new Error("invalid cursor hint");
  }
}

function requireUuid(value: string): void {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
      .test(value)
  ) {
    throw new Error("invalid UUID");
  }
}

function parseRecord(raw: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(raw);
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      throw new Error();
    }
    return value as Record<string, unknown>;
  } catch {
    throw new PushSendError("fcm_invalid_response");
  }
}

function fcmErrorCode(body: string): string {
  try {
    const root = parseRecord(body);
    const error = root.error;
    if (error !== null && typeof error === "object" && !Array.isArray(error)) {
      const record = error as Record<string, unknown>;
      if (Array.isArray(record.details)) {
        for (const detail of record.details) {
          if (
            detail === null || typeof detail !== "object" ||
            Array.isArray(detail)
          ) continue;
          const value = detail as Record<string, unknown>;
          if (
            value["@type"] ===
              "type.googleapis.com/google.firebase.fcm.v1.FcmError" &&
            typeof value.errorCode === "string" && value.errorCode.length <= 80
          ) {
            return value.errorCode;
          }
        }
      }
      if (
        typeof record.message === "string" &&
        record.message.includes("registration-token-not-registered")
      ) {
        return "messaging/registration-token-not-registered";
      }
      if (typeof record.status === "string" && record.status.length <= 80) {
        return record.status;
      }
    }
  } catch {
    // Invalid provider bodies stay retryable and are never logged or persisted.
  }
  return "unknown_error";
}

function normalizeCode(value: string): string {
  const code = value.toLowerCase().replaceAll(/[^a-z0-9]+/g, "_").replaceAll(
    /^_+|_+$/g,
    "",
  );
  return code.slice(0, 80) || "unknown_error";
}

async function boundedText(response: Response, limit: number): Promise<string> {
  if (response.body === null) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const next = await reader.read();
    if (next.done) break;
    total += next.value.byteLength;
    if (total > limit) {
      await reader.cancel();
      throw new PushSendError("fcm_response_too_large");
    }
    chunks.push(next.value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new PushSendError("fcm_invalid_utf8");
  }
}
