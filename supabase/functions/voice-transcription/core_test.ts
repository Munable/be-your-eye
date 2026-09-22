import {
  type AsrModelClient,
  BailianAsrClient,
  createVoiceTranscriptionHandler,
  parseVoiceTranscriptionRequest,
  QWEN_ASR_MODEL,
  type VoiceTranscriptionRequest,
} from "./core.ts";

function assert(
  condition: unknown,
  message = "assertion failed",
): asserts condition {
  if (!condition) throw new Error(message);
}

function equal(actual: unknown, expected: unknown): void {
  assert(
    JSON.stringify(actual) === JSON.stringify(expected),
    `${JSON.stringify(actual)} != ${JSON.stringify(expected)}`,
  );
}

function mp4Bytes(): Uint8Array {
  const bytes = new Uint8Array(64);
  bytes.set([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x4d, 0x34, 0x41, 0x20]);
  return bytes;
}

function baseRequest(): Record<string, unknown> {
  const bytes = mp4Bytes();
  return {
    schema_version: "1.0",
    request_id: "84e0f63f-e594-42b5-9c41-c68001538861",
    locale: "zh-Hans",
    audio: {
      media_type: "audio/mp4",
      duration_millis: 1_200,
      base64: btoa(String.fromCharCode(...bytes)),
      byte_length: bytes.byteLength,
    },
  };
}

Deno.test("voice request is exact, bounded, and requires a real MPEG-4 file type box", () => {
  equal(parseVoiceTranscriptionRequest(baseRequest()), baseRequest());
  for (
    const mutate of [
      (value: Record<string, unknown>) => Object.assign(value, { extra: true }),
      (value: Record<string, unknown>) =>
        Object.assign(value.audio as Record<string, unknown>, {
          byte_length: 63,
        }),
      (value: Record<string, unknown>) =>
        Object.assign(value.audio as Record<string, unknown>, {
          duration_millis: 399,
        }),
      (value: Record<string, unknown>) =>
        Object.assign(value.audio as Record<string, unknown>, {
          base64: btoa("not an mp4 file despite enough padding"),
          byte_length: 35,
        }),
    ]
  ) {
    const request = structuredClone(baseRequest());
    mutate(request);
    let rejected = false;
    try {
      parseVoiceTranscriptionRequest(request);
    } catch {
      rejected = true;
    }
    assert(rejected);
  }
});

Deno.test("Bailian ASR sends one private base64 clip with locale and ITN", async () => {
  let url = "";
  let sent: Record<string, unknown> = {};
  const client = new BailianAsrClient({
    apiKey: `sk-${"a".repeat(40)}`,
    baseUrl:
      "https://voice-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    fetch: async (input, init) => {
      url = String(input);
      sent = JSON.parse(String(init?.body));
      return Response.json({
        choices: [{ message: { content: "苹果出现时提醒我" } }],
      });
    },
  });
  const transcript = await client.transcribe(
    parseVoiceTranscriptionRequest(baseRequest()),
  );
  equal(transcript, "苹果出现时提醒我");
  equal(
    url,
    "https://voice-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
  );
  equal(sent.model, QWEN_ASR_MODEL);
  equal(sent.stream, false);
  equal(sent.asr_options, { language: "zh", enable_itn: true });
  const messages = sent.messages as Array<Record<string, unknown>>;
  equal(messages.length, 1);
  const content = messages[0]!.content as Array<Record<string, unknown>>;
  const inputAudio = content[0]!.input_audio as Record<string, unknown>;
  equal(Object.keys(inputAudio), ["data"]);
  assert(
    String(inputAudio.data).startsWith("data:audio/mp4;base64,"),
  );
  assert(!JSON.stringify(sent).includes("request_id"));
});

Deno.test("voice handler requires auth, returns no transcript when unconfigured, and logs metadata only", async () => {
  const unauthenticated = createVoiceTranscriptionHandler({
    authenticate: async () => null,
    authorize: async () => true,
    client: { transcribe: async () => "must not run" },
  });
  const unauthorized = await unauthenticated(jsonRequest(baseRequest()));
  equal(unauthorized.status, 401);

  const unconfigured = createVoiceTranscriptionHandler({
    authenticate: async () => "account-id",
    authorize: async () => true,
    client: null,
  });
  const unavailable = await unconfigured(jsonRequest(baseRequest()));
  equal(unavailable.status, 503);
  equal(await unavailable.json(), { code: "voice_not_configured" });

  const records: Array<Record<string, unknown>> = [];
  const client: AsrModelClient = {
    transcribe: async (_request: VoiceTranscriptionRequest) => "蒸汽泄漏",
  };
  let now = 1_000;
  const handler = createVoiceTranscriptionHandler({
    authenticate: async () => "account-id",
    authorize: async () => true,
    client,
    nowMillis: () => now += 10,
    log: (record) => records.push({ ...record }),
  });
  const response = await handler(jsonRequest(baseRequest()));
  equal(response.status, 200);
  equal(await response.json(), {
    schema_version: "1.0",
    request_id: "84e0f63f-e594-42b5-9c41-c68001538861",
    text: "蒸汽泄漏",
  });
  const log = JSON.stringify(records);
  assert(!log.includes("蒸汽") && !log.includes("base64"));
  assert(log.includes("audio_duration_ms") && log.includes("audio_bytes"));
});

Deno.test("voice handler classifies malformed JSON as a client error", async () => {
  const handler = createVoiceTranscriptionHandler({
    authenticate: async () => "account-id",
    authorize: async () => true,
    client: { transcribe: async () => "must not run" },
  });
  const response = await handler(
    new Request("https://test.invalid", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{",
    }),
  );
  equal(response.status, 400);
  equal(await response.json(), { code: "invalid_request" });

  const widenedContentType = await handler(
    new Request("https://test.invalid", {
      method: "POST",
      headers: { "content-type": "application/jsonp" },
      body: JSON.stringify(baseRequest()),
    }),
  );
  equal(widenedContentType.status, 400);
  equal(await widenedContentType.json(), { code: "invalid_request" });
});

function jsonRequest(value: unknown): Request {
  return new Request("https://test.invalid", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(value),
  });
}
