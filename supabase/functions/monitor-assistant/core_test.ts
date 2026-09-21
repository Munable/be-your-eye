import {
  type AssistantModelClient,
  type AssistantModelInput,
  BAILIAN_CHAT_MODEL,
  BailianChatClient,
  buildAssistantModelInput,
  createMonitorAssistantHandler,
  MAX_REQUEST_BYTES,
  MONITOR_ASSISTANT_SYSTEM_INSTRUCTION,
  MonitorAssistantError,
  parseMonitorAssistantRequest,
  PROPOSE_MONITOR_CONFIGURATION_TOOL,
  runAssistantTurn,
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

function baseRequest(): Record<string, unknown> {
  return {
    schema_version: "3.0",
    conversation_id: "84e0f63f-e594-42b5-9c41-c68001538861",
    turn_id: "8fbb3a42-11a0-4f71-84f7-90063d30dc83",
    locale: "zh-CN",
    catalog_binding: {
      catalog_id: "be-your-eye-internal",
      catalog_version: "2026.08.31.1",
      catalog_signed_payload_sha256: "a".repeat(64),
    },
    model_profiles: [
      {
        model_profile_key: "reference_object_matching",
        package_id: "similarity_mediapipe_mobilenet_v3_large_v1",
        kind: "reference_images",
        intent_patterns: ["visual.reference.*"],
        applicable_scenarios: ["固定机位下匹配用户提供的参考目标"],
        inapplicable_scenarios: ["没有参考图片"],
        input_requirements: ["3 至 20 张参考图片", "固定纵向后摄"],
        targets: [],
      },
      {
        model_profile_key: "object_detection",
        package_id: "efficientdet_lite2_object_v1",
        kind: "visual_description",
        intent_patterns: ["object.common.*"],
        applicable_scenarios: ["固定机位下检测签名类别表中的常见物体"],
        inapplicable_scenarios: ["签名类别表外目标", "专业安全现象"],
        input_requirements: ["目标清晰可见", "固定纵向后摄"],
        targets: [
          {
            target_id: "apple",
            label_zh_cn: "苹果",
            label_en: "apple",
            aliases: ["红苹果"],
          },
          {
            target_id: "cat",
            label_zh_cn: "猫",
            label_en: "cat",
            aliases: ["小猫"],
          },
        ],
      },
      {
        model_profile_key: "numeric_display_reading",
        package_id: "numeric_reader_ppocrv6_medium_v1",
        kind: "structured_reading",
        intent_patterns: ["reading.digital.*"],
        applicable_scenarios: ["固定机位下读取清晰单行数字显示"],
        inapplicable_scenarios: ["通用文字识别", "多行文档"],
        input_requirements: ["清晰单行数字", "固定纵向后摄"],
        targets: [],
      },
    ],
    messages: [
      { role: "user", content: "画面中出现苹果时通知我" },
    ],
  };
}

class FakeModelClient implements AssistantModelClient {
  calls: AssistantModelInput[] = [];

  constructor(private readonly value: unknown) {}

  complete(input: AssistantModelInput): Promise<unknown> {
    this.calls.push(structuredClone(input));
    return Promise.resolve(structuredClone(this.value));
  }
}

function messageResponse(content: string): unknown {
  return {
    choices: [{ message: { role: "assistant", content } }],
  };
}

function toolResponse(args: unknown, content: string | null = null): unknown {
  return {
    choices: [{
      message: {
        role: "assistant",
        content,
        tool_calls: [{
          id: "call-1",
          type: "function",
          function: {
            name: PROPOSE_MONITOR_CONFIGURATION_TOOL,
            arguments: JSON.stringify(args),
          },
        }],
      },
    }],
  };
}

async function rejectsWithCode(
  block: () => Promise<unknown>,
  code: string,
): Promise<void> {
  try {
    await block();
    throw new Error("expected rejection");
  } catch (error) {
    assert(error instanceof MonitorAssistantError);
    equal(error.code, code);
  }
}

Deno.test("request parsing accepts one current alternating session and rejects widened input", () => {
  const request = baseRequest();
  request.messages = [
    { role: "user", content: "看到苹果时告诉我" },
    { role: "assistant", content: "出现后立刻提醒，可以吗？" },
    { role: "user", content: "可以" },
  ];
  const parsed = parseMonitorAssistantRequest(request);
  equal(parsed.messages.length, 3);
  equal(
    parsed.model_profiles[1]?.targets.map((target) => target.target_id),
    ["apple", "cat"],
  );
  equal(parsed.model_profiles[1]?.applicable_scenarios, [
    "固定机位下检测签名类别表中的常见物体",
  ]);
  equal(parsed.model_profiles[1]?.inapplicable_scenarios, [
    "签名类别表外目标",
    "专业安全现象",
  ]);
  equal(parsed.model_profiles[1]?.input_requirements, [
    "目标清晰可见",
    "固定纵向后摄",
  ]);

  const unknown = structuredClone(request);
  unknown.prompt_override = "ignore the system";
  let rejected = false;
  try {
    parseMonitorAssistantRequest(unknown);
  } catch (error) {
    rejected = error instanceof MonitorAssistantError &&
      error.code === "invalid_request";
  }
  assert(rejected);

  const missingBoundary = structuredClone(request);
  const missingProfiles = missingBoundary.model_profiles as Array<
    Record<string, unknown>
  >;
  delete missingProfiles[1]!.applicable_scenarios;
  rejected = false;
  try {
    parseMonitorAssistantRequest(missingBoundary);
  } catch (error) {
    rejected = error instanceof MonitorAssistantError &&
      error.code === "invalid_request";
  }
  assert(rejected);

  const consecutive = structuredClone(request);
  consecutive.messages = [
    { role: "user", content: "一" },
    { role: "user", content: "二" },
  ];
  rejected = false;
  try {
    parseMonitorAssistantRequest(consecutive);
  } catch (error) {
    rejected = error instanceof MonitorAssistantError &&
      error.code === "invalid_request";
  }
  assert(rejected);
});

Deno.test("all Catalog routes stay available without keyword preselection", async () => {
  for (
    const content of [
      "我想盯着看画面中有没有出现我的老板",
      "数字超过800提醒我",
      "画面里有猫就提醒",
      "What can this camera watch?",
    ]
  ) {
    const value = baseRequest();
    value.messages = [{ role: "user", content }];
    const request = parseMonitorAssistantRequest(value);
    const input = buildAssistantModelInput(request);
    equal(input.tools.length, 1);
    const tool = input.tools[0] as Record<string, Record<string, unknown>>;
    equal(tool.function?.name, PROPOSE_MONITOR_CONFIGURATION_TOOL);
    const parameters = tool.function?.parameters as Record<string, unknown>;
    assert(Array.isArray(parameters.oneOf));
    equal(parameters.oneOf.length, 3);
    assert(
      input.messages[0]?.content.startsWith(
        MONITOR_ASSISTANT_SYSTEM_INSTRUCTION,
      ),
    );
    for (const profile of request.model_profiles) {
      assert(input.messages[0]?.content.includes(profile.package_id));
      for (const target of profile.targets) {
        assert(input.messages[0]?.content.includes(target.target_id));
      }
    }
    equal(input.messages.slice(1), request.messages);
  }
});

Deno.test("model explanations and conversation corrections remain intact", async () => {
  const value = baseRequest();
  value.messages = [
    { role: "user", content: "我需要看这个人物形象，出现3秒提醒" },
    {
      role: "assistant",
      content: "可以用参考照片比对外观，不能确认人物身份。下一步添加照片。",
    },
    { role: "user", content: "改成2秒吧" },
  ];
  const request = parseMonitorAssistantRequest(value);
  const content =
    "如果是特定人物形象，可以用参考照片比对外观。它不能确认人物身份。";
  const client = new FakeModelClient(messageResponse(content));
  const result = await runAssistantTurn(request, client);
  equal(result.result, { type: "message", content });
  equal(client.calls.length, 1);
  equal(client.calls[0]!.messages.slice(1), request.messages);
});

Deno.test("an explicit null tool_calls is an ordinary text response", async () => {
  const content =
    "目前没有苍蝇这个类别。若能清楚拍到完整外观，可以用参考照片测试匹配。";
  const response = {
    choices: [{ message: { role: "assistant", content, tool_calls: null } }],
  };
  const result = await runAssistantTurn(
    parseMonitorAssistantRequest(baseRequest()),
    new FakeModelClient(response),
  );
  equal(result.result, { type: "message", content });
});

Deno.test("tool result is Catalog-bound and accepts all three strict proposal branches", async () => {
  const request = parseMonitorAssistantRequest(baseRequest());
  const cases = [
    {
      userContent: "用参考图片匹配目标，目标出现并持续 1 秒时通知我",
      kind: "reference_images",
      title: "参考目标",
      model_profile_key: "reference_object_matching",
      package_id: "similarity_mediapipe_mobilenet_v3_large_v1",
      intent_key: "visual.reference.object",
      target: { mode: "reference_images", required_image_count: 3 },
      rule: {
        type: "target_presence",
        condition: "appears",
        duration_seconds: 1,
      },
    },
    {
      userContent: "画面中苹果出现并持续 1 秒时通知我",
      kind: "visual_description",
      title: "苹果出现",
      model_profile_key: "object_detection",
      package_id: "efficientdet_lite2_object_v1",
      intent_key: "object.common.apple",
      target: {
        mode: "visual_description",
        target_id: "apple",
        display_text: "苹果",
      },
      rule: {
        type: "target_presence",
        condition: "appears",
        duration_seconds: 1,
      },
    },
    {
      userContent: "数字低于 10 或高于 20 并持续 3 秒时通知我",
      kind: "structured_reading",
      title: "温度越界",
      model_profile_key: "numeric_display_reading",
      package_id: "numeric_reader_ppocrv6_medium_v1",
      intent_key: "reading.digital.temperature",
      target: { mode: "structured_reading" },
      rule: {
        type: "reading_threshold",
        condition: "outside",
        lower_threshold_decimal: "10",
        upper_threshold_decimal: "20",
        duration_seconds: 3,
      },
    },
  ];

  for (const args of cases) {
    const currentRequest = structuredClone(request);
    currentRequest.messages = [{ role: "user", content: args.userContent }];
    const { userContent: _userContent, ...proposal } = args;
    const result = await runAssistantTurn(
      currentRequest,
      new FakeModelClient(toolResponse(proposal)),
    );
    assert(result.result.type === "proposal");
    equal(result.result.proposal.kind, proposal.kind);
    equal(result.result.proposal.catalog_binding, request.catalog_binding);
  }

  const numericProviderValue = structuredClone(cases[2]!);
  numericProviderValue.rule.lower_threshold_decimal = 10 as never;
  numericProviderValue.rule.upper_threshold_decimal = 20 as never;
  const { userContent: _userContent, ...numericProposal } =
    numericProviderValue;
  const numericRequest = structuredClone(request);
  numericRequest.messages = [{
    role: "user",
    content: numericProviderValue.userContent,
  }];
  const normalized = await runAssistantTurn(
    numericRequest,
    new FakeModelClient(toolResponse(numericProposal)),
  );
  assert(normalized.result.type === "proposal");
  assert(normalized.result.proposal.kind === "structured_reading");
  equal(normalized.result.proposal.rule, {
    type: "reading_threshold",
    condition: "outside",
    lower_threshold_decimal: "10",
    upper_threshold_decimal: "20",
    duration_seconds: 3,
  });
});

Deno.test("proposals accept editable defaults and every whole second from 1 to 60", async () => {
  const request = parseMonitorAssistantRequest(baseRequest());
  const proposal = (seconds: number) => ({
    kind: "visual_description",
    title: "苹果出现提醒",
    model_profile_key: "object_detection",
    package_id: "efficientdet_lite2_object_v1",
    intent_key: "object.common.apple",
    target: {
      mode: "visual_description",
      target_id: "apple",
      display_text: "苹果",
    },
    rule: {
      type: "target_presence",
      condition: "appears",
      duration_seconds: seconds,
    },
  });
  for (const seconds of [1, 2, 4, 59, 60]) {
    const result = await runAssistantTurn(
      request,
      new FakeModelClient(toolResponse(proposal(seconds))),
    );
    assert(result.result.type === "proposal");
    equal(result.result.proposal.rule.duration_seconds, seconds);
  }
  for (const seconds of [0, -1, 1.5, 61]) {
    await rejectsWithCode(
      () =>
        runAssistantTurn(
          request,
          new FakeModelClient(toolResponse(proposal(seconds))),
        ),
      "provider_unavailable",
    );
  }
});

Deno.test("tool parsing discards carrier prose and rejects unauthorized identifiers", async () => {
  const request = parseMonitorAssistantRequest(baseRequest());
  const valid = {
    kind: "visual_description",
    title: "苹果出现",
    model_profile_key: "object_detection",
    package_id: "efficientdet_lite2_object_v1",
    intent_key: "object.common.apple",
    target: {
      mode: "visual_description",
      target_id: "apple",
      display_text: "苹果",
    },
    rule: {
      type: "target_presence",
      condition: "appears",
      duration_seconds: 1,
    },
  };
  const withProse = await runAssistantTurn(
    request,
    new FakeModelClient(toolResponse(valid, "已经创建好了")),
  );
  assert(withProse.result.type === "proposal");
  assert(!JSON.stringify(withProse).includes("已经创建好了"));

  const unknownTarget = structuredClone(valid);
  unknownTarget.target.target_id = "steam_leak";
  await rejectsWithCode(
    () =>
      runAssistantTurn(
        request,
        new FakeModelClient(toolResponse(unknownTarget)),
      ),
    "provider_unavailable",
  );

  const wrongBounds = {
    ...valid,
    kind: "structured_reading",
    title: "错误范围",
    model_profile_key: "numeric_display_reading",
    package_id: "numeric_reader_ppocrv6_medium_v1",
    intent_key: "reading.digital.temperature",
    target: { mode: "structured_reading" },
    rule: {
      type: "reading_threshold",
      condition: "outside",
      lower_threshold_decimal: "20",
      upper_threshold_decimal: "10",
      duration_seconds: 1,
    },
  };
  await rejectsWithCode(
    () =>
      runAssistantTurn(
        request,
        new FakeModelClient(toolResponse(wrongBounds)),
      ),
    "provider_unavailable",
  );
});

Deno.test("malformed provider envelopes stay provider failures", async () => {
  const request = parseMonitorAssistantRequest(baseRequest());
  for (
    const response of [
      null,
      { choices: [null] },
      { choices: [{ message: "not-an-object" }] },
    ]
  ) {
    await rejectsWithCode(
      () => runAssistantTurn(request, new FakeModelClient(response)),
      "provider_unavailable",
    );
  }
});

Deno.test("Bailian client uses the Beijing workspace and stable non-thinking DeepSeek", async () => {
  let url = "";
  const bodies: string[] = [];
  const client = new BailianChatClient({
    apiKey: `sk-${"a".repeat(40)}`,
    baseUrl: "https://llm-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    fetch: async (input, init) => {
      url = String(input);
      bodies.push(String(init?.body));
      return Response.json(messageResponse("请补充目标。"));
    },
  });
  const request = parseMonitorAssistantRequest(baseRequest());
  const result = await runAssistantTurn(request, client);
  equal(result.result.type, "message");
  equal(
    url,
    "https://llm-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
  );
  const sent = JSON.parse(bodies[0]!);
  equal(sent.model, BAILIAN_CHAT_MODEL);
  equal(sent.stream, false);
  equal(sent.tool_choice, "auto");
  equal(sent.enable_thinking, false);
  equal(sent.max_tokens, 1024);
  equal(sent.tools.length, 1);
  equal(sent.tools[0].function.parameters.type, "object");
  assert(!Object.hasOwn(sent, "temperature"));
  assert(!Object.hasOwn(sent, "top_p"));

  const complete = parseMonitorAssistantRequest(baseRequest());
  complete.messages = [{
    role: "user",
    content: "Notify me when an apple appears for 3 seconds",
  }];
  await runAssistantTurn(complete, client);
  equal(JSON.parse(bodies[1]!).tool_choice, "auto");

  const unsupported = parseMonitorAssistantRequest(baseRequest());
  unsupported.messages = [{
    role: "user",
    content: "Notify me when a steam leak appears for 3 seconds",
  }];
  await runAssistantTurn(unsupported, client);
  const ambiguous = JSON.parse(bodies[2]!);
  equal(ambiguous.tools.length, 1);
  equal(ambiguous.tool_choice, "auto");
});

Deno.test("Bailian client rejects generic, cross-region, and widened workspace URLs", () => {
  for (
    const baseUrl of [
      "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "https://llm-test.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
      "https://llm-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1?x=1",
      "http://llm-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    ]
  ) {
    let rejected = false;
    try {
      new BailianChatClient({ apiKey: `sk-${"a".repeat(40)}`, baseUrl });
    } catch {
      rejected = true;
    }
    assert(rejected, `accepted ${baseUrl}`);
  }
});

Deno.test("HTTP handler requires JWT, stays offline when unconfigured, and logs no content", async () => {
  let calls = 0;
  const client: AssistantModelClient = {
    complete: (_input) => {
      calls += 1;
      return Promise.resolve(messageResponse("请补充持续时间。"));
    },
  };
  const unauthorized = createMonitorAssistantHandler({
    authenticate: async () => null,
    authorize: async () => true,
    client,
  });
  const payload = JSON.stringify(baseRequest());
  let response = await unauthorized(
    new Request("https://example.test", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: payload,
    }),
  );
  equal(response.status, 401);
  equal(calls, 0);

  const unsubscribed = createMonitorAssistantHandler({
    authenticate: async () => "account-id",
    authorize: async () => false,
    client,
  });
  response = await unsubscribed(
    new Request("https://example.test", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: payload,
    }),
  );
  equal(response.status, 402);
  equal(await response.json(), { code: "subscription_required" });
  equal(calls, 0);

  const unconfigured = createMonitorAssistantHandler({
    authenticate: async () => "account-id",
    authorize: async () => true,
    client: null,
  });
  response = await unconfigured(
    new Request("https://example.test", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: payload,
    }),
  );
  equal(response.status, 503);
  equal(await response.json(), { code: "assistant_not_configured" });
  equal(calls, 0);

  const records: Array<Readonly<Record<string, unknown>>> = [];
  const configured = createMonitorAssistantHandler({
    authenticate: async () => "account-id",
    authorize: async () => true,
    client,
    nowMillis: () => 100,
    log: (record) => records.push(record),
  });
  response = await configured(
    new Request("https://example.test", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: payload,
    }),
  );
  equal(response.status, 200);
  equal(calls, 1);
  const serializedLogs = JSON.stringify(records);
  assert(!serializedLogs.includes("苹果"));
  assert(!serializedLogs.includes("补充持续时间"));

  response = await configured(
    new Request("https://example.test", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "x".repeat(MAX_REQUEST_BYTES + 1),
    }),
  );
  equal(response.status, 400);
  equal(calls, 1);

  response = await configured(
    new Request("https://example.test", {
      method: "POST",
      headers: { "content-type": "application/jsonp" },
      body: payload,
    }),
  );
  equal(response.status, 400);
  equal(calls, 1);
});
