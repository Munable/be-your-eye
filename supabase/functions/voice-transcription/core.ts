import {
  bailianChatCompletionsEndpoint,
  boundedUtf8ResponseText,
} from "../_shared/bailian.ts";

export const VOICE_TRANSCRIPTION_SCHEMA_VERSION = "1.0";
export const QWEN_ASR_MODEL = "qwen3-asr-flash-2026-02-10";
export const MAX_VOICE_REQUEST_BYTES = 768 * 1024;
export const MAX_AUDIO_BYTES = 512 * 1024;
export const MAX_PROVIDER_RESPONSE_BYTES = 32 * 1024;
export const DEFAULT_ASR_TIMEOUT_MS = 30_000;

const MIN_AUDIO_BYTES = 32;
const MIN_AUDIO_DURATION_MS = 400;
const MAX_AUDIO_DURATION_MS = 30_000;
const MAX_TRANSCRIPT_CODE_POINTS = 500;
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const LOCALE = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$/;
const BASE64 =
  /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const FORBIDDEN_TEXT_CONTROLS =
  /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;

export interface VoiceTranscriptionRequest {
  schema_version: "1.0";
  request_id: string;
  locale: string;
  audio: {
    media_type: "audio/mp4";
    duration_millis: number;
    base64: string;
    byte_length: number;
  };
}

export interface VoiceTranscriptionResponse {
  schema_version: "1.0";
  request_id: string;
  text: string;
}

export type VoiceTranscriptionErrorCode =
  | "invalid_request"
  | "provider_unavailable"
  | "voice_not_configured";

export class VoiceTranscriptionError extends Error {
  constructor(readonly code: VoiceTranscriptionErrorCode) {
    super(code);
    this.name = "VoiceTranscriptionError";
  }
}

export function parseVoiceTranscriptionRequest(
  value: unknown,
): VoiceTranscriptionRequest {
  const root = exactRecord(value, [
    "schema_version",
    "request_id",
    "locale",
    "audio",
  ]);
  if (root.schema_version !== VOICE_TRANSCRIPTION_SCHEMA_VERSION) invalid();
  const requestId = strictString(root.request_id, 36, UUID);
  const locale = strictString(root.locale, 35, LOCALE);
  const audio = exactRecord(root.audio, [
    "media_type",
    "duration_millis",
    "base64",
    "byte_length",
  ]);
  if (audio.media_type !== "audio/mp4") invalid();
  const durationMillis = strictInteger(
    audio.duration_millis,
    MIN_AUDIO_DURATION_MS,
    MAX_AUDIO_DURATION_MS,
  );
  const byteLength = strictInteger(
    audio.byte_length,
    MIN_AUDIO_BYTES,
    MAX_AUDIO_BYTES,
  );
  const base64 = strictBase64(audio.base64);
  const bytes = decodeBase64(base64);
  if (bytes.byteLength !== byteLength || !hasMp4FileTypeBox(bytes)) invalid();
  return {
    schema_version: VOICE_TRANSCRIPTION_SCHEMA_VERSION,
    request_id: requestId,
    locale,
    audio: {
      media_type: "audio/mp4",
      duration_millis: durationMillis,
      base64,
      byte_length: byteLength,
    },
  };
}

export interface AsrModelClient {
  transcribe(request: VoiceTranscriptionRequest): Promise<string>;
}

export class BailianAsrClient implements AsrModelClient {
  private readonly endpoint: string;

  constructor(
    private readonly options: {
      apiKey: string;
      baseUrl: string;
      fetch?: typeof fetch;
      timeoutMs?: number;
    },
  ) {
    if (options.apiKey.length < 20 || options.apiKey.length > 512) {
      throw new Error("invalid_dashscope_api_key");
    }
    this.endpoint = bailianChatCompletionsEndpoint(options.baseUrl);
    const timeout = options.timeoutMs ?? DEFAULT_ASR_TIMEOUT_MS;
    if (!Number.isInteger(timeout) || timeout < 1_000 || timeout > 45_000) {
      throw new Error("invalid_bailian_asr_timeout");
    }
  }

  async transcribe(request: VoiceTranscriptionRequest): Promise<string> {
    let response: Response;
    try {
      response = await (this.options.fetch ?? fetch)(this.endpoint, {
        method: "POST",
        redirect: "error",
        signal: AbortSignal.timeout(
          this.options.timeoutMs ?? DEFAULT_ASR_TIMEOUT_MS,
        ),
        headers: {
          authorization: `Bearer ${this.options.apiKey}`,
          "content-type": "application/json; charset=utf-8",
          accept: "application/json",
        },
        body: JSON.stringify({
          model: QWEN_ASR_MODEL,
          messages: [{
            role: "user",
            content: [{
              type: "input_audio",
              input_audio: {
                data:
                  `data:${request.audio.media_type};base64,${request.audio.base64}`,
              },
            }],
          }],
          asr_options: {
            language: localeLanguage(request.locale),
            enable_itn: true,
          },
          stream: false,
        }),
      });
    } catch {
      throw new VoiceTranscriptionError("provider_unavailable");
    }
    let body: string;
    try {
      body = await boundedUtf8ResponseText(
        response,
        MAX_PROVIDER_RESPONSE_BYTES,
      );
    } catch {
      throw new VoiceTranscriptionError("provider_unavailable");
    }
    if (!response.ok) {
      throw new VoiceTranscriptionError("provider_unavailable");
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch {
      throw new VoiceTranscriptionError("provider_unavailable");
    }
    return parseProviderTranscript(parsed);
  }
}

function localeLanguage(locale: string): string | undefined {
  const language = locale.split("-", 1)[0]?.toLowerCase();
  return language === "zh" || language === "en" ? language : undefined;
}

function parseProviderTranscript(value: unknown): string {
  const root = record(value, true);
  const choices = root.choices;
  if (!Array.isArray(choices) || choices.length !== 1) providerInvalid();
  const choice = record(choices[0], true);
  const message = record(choice.message, true);
  const content = message.content;
  if (typeof content !== "string") providerInvalid();
  const text = content.trim();
  if (
    codePointLength(text) < 1 ||
    codePointLength(text) > MAX_TRANSCRIPT_CODE_POINTS ||
    FORBIDDEN_TEXT_CONTROLS.test(text)
  ) providerInvalid();
  return text;
}

export interface VoiceTranscriptionHandlerDependencies {
  authenticate(request: Request): Promise<string | null>;
  authorize(accountId: string): Promise<boolean>;
  client: AsrModelClient | null;
  nowMillis?: () => number;
  log?: (record: Readonly<Record<string, unknown>>) => void;
}

export function createVoiceTranscriptionHandler(
  dependencies: VoiceTranscriptionHandlerDependencies,
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
      accountId = null;
    }
    if (accountId === null) return errorResponse(401, "sign_in_required");
    let authorized = false;
    try {
      authorized = await dependencies.authorize(accountId);
    } catch {
      authorized = false;
    }
    if (!authorized) return errorResponse(402, "subscription_required");
    if (dependencies.client === null) {
      return errorResponse(503, "voice_not_configured");
    }

    const startedAt = (dependencies.nowMillis ?? Date.now)();
    let parsed: VoiceTranscriptionRequest | undefined;
    try {
      parsed = parseVoiceTranscriptionRequest(await readJsonBody(request));
      const text = await dependencies.client.transcribe(parsed);
      dependencies.log?.({
        kind: "voice_transcription",
        request_id: parsed.request_id,
        outcome: "completed",
        audio_duration_ms: parsed.audio.duration_millis,
        audio_bytes: parsed.audio.byte_length,
        duration_ms: Math.max(
          0,
          (dependencies.nowMillis ?? Date.now)() - startedAt,
        ),
      });
      const response: VoiceTranscriptionResponse = {
        schema_version: VOICE_TRANSCRIPTION_SCHEMA_VERSION,
        request_id: parsed.request_id,
        text,
      };
      return jsonResponse(200, response);
    } catch (error) {
      const code = error instanceof VoiceTranscriptionError
        ? error.code
        : "provider_unavailable";
      if (parsed !== undefined) {
        dependencies.log?.({
          kind: "voice_transcription",
          request_id: parsed.request_id,
          outcome: code,
          audio_duration_ms: parsed.audio.duration_millis,
          audio_bytes: parsed.audio.byte_length,
          duration_ms: Math.max(
            0,
            (dependencies.nowMillis ?? Date.now)() - startedAt,
          ),
        });
      }
      return errorResponse(code === "invalid_request" ? 400 : 503, code);
    }
  };
}

async function readJsonBody(request: Request): Promise<unknown> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (contentType.split(";", 1)[0]?.trim() !== "application/json") invalid();
  const declaredRaw = request.headers.get("content-length");
  if (declaredRaw !== null) {
    const declared = Number(declaredRaw);
    if (
      !Number.isSafeInteger(declared) || declared < 0 ||
      declared > MAX_VOICE_REQUEST_BYTES
    ) invalid();
  }
  if (request.body === null) invalid();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > MAX_VOICE_REQUEST_BYTES) {
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
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    invalid();
  }
}

function strictBase64(value: unknown): string {
  if (
    typeof value !== "string" || value.length < 4 ||
    value.length > Math.ceil(MAX_AUDIO_BYTES / 3) * 4 ||
    value.length % 4 !== 0 || !BASE64.test(value)
  ) invalid();
  return value;
}

function decodeBase64(value: string): Uint8Array {
  try {
    const decoded = atob(value);
    const bytes = new Uint8Array(decoded.length);
    for (let index = 0; index < decoded.length; index += 1) {
      bytes[index] = decoded.charCodeAt(index);
    }
    return bytes;
  } catch {
    invalid();
  }
}

function hasMp4FileTypeBox(value: Uint8Array): boolean {
  return value.byteLength >= MIN_AUDIO_BYTES &&
    value[4] === 0x66 && value[5] === 0x74 && value[6] === 0x79 &&
    value[7] === 0x70;
}

function strictString(
  value: unknown,
  maximumCodePoints: number,
  pattern: RegExp,
): string {
  if (
    typeof value !== "string" || codePointLength(value) < 1 ||
    codePointLength(value) > maximumCodePoints || !pattern.test(value)
  ) invalid();
  return value;
}

function strictInteger(
  value: unknown,
  minimum: number,
  maximum: number,
): number {
  if (
    typeof value !== "number" || !Number.isInteger(value) || value < minimum ||
    value > maximum
  ) invalid();
  return value;
}

function exactRecord(value: unknown, keys: string[]): Record<string, unknown> {
  const root = record(value);
  if (
    Object.keys(root).sort().join("\u0000") !==
      [...keys].sort().join("\u0000")
  ) invalid();
  return root;
}

function record(
  value: unknown,
  provider = false,
): Record<string, unknown> {
  if (
    typeof value !== "object" || value === null || Array.isArray(value) ||
    Object.getPrototypeOf(value) !== Object.prototype
  ) {
    if (provider) providerInvalid();
    invalid();
  }
  return value as Record<string, unknown>;
}

function codePointLength(value: string): number {
  return [...value].length;
}

const JSON_HEADERS = Object.freeze({
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
});

function jsonResponse(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), { status, headers: JSON_HEADERS });
}

function errorResponse(status: number, code: string): Response {
  return jsonResponse(status, { code });
}

function invalid(): never {
  throw new VoiceTranscriptionError("invalid_request");
}

function providerInvalid(): never {
  throw new VoiceTranscriptionError("provider_unavailable");
}
