import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import {
  containsDeprecatedPluralCodeBrand,
  containsRemovedBrand,
  validateAndroidVoicePolicy,
  validateSupabaseConfig,
} from "./repo-policy-rules.mjs";

const root = path.resolve(import.meta.dirname, "../..");

test("current repository policy accepts the bounded assistant and foreground-only voice input", () => {
  const output = execFileSync(process.execPath, [path.join(root, ".github/scripts/check-repo-policy.mjs")], {
    cwd: root,
    encoding: "utf8",
  });
  assert.match(
    output,
    /three manual Android flows plus the bounded DeepSeek proposal assistant and foreground-only hold-to-talk/u,
  );
});

test("voice policy permits only the assistant screen's bounded hold-to-talk recorder", () => {
  const recorderPath =
    "android/app/src/main/java/app/beyoureyes/monitor/feature/assistant/AssistantVoiceRecorder.kt";
  const screenPath =
    "android/app/src/main/java/app/beyoureyes/monitor/feature/assistant/AssistantScreen.kt";
  const manifest = '<uses-permission android:name="android.permission.RECORD_AUDIO" />';
  const sources = new Map([
    [recorderPath, [
      "class AssistantVoiceRecorder",
      "MediaRecorder.AudioSource.MIC",
      "setMaxFileSize(MAX_VOICE_AUDIO_BYTES.toLong())",
      "fun stop()",
      "fun cancel()",
    ].join("\n")],
    [screenPath, [
      "AssistantVoiceRecorder(context)",
      "Manifest.permission.RECORD_AUDIO",
      "awaitEachGesture",
      "awaitLongPressOrCancellation",
      "recorder.start()",
      "recorder.stop()",
      "recorder.cancel()",
      "onDispose",
    ].join("\n")],
  ]);
  assert.deepEqual(validateAndroidVoicePolicy(manifest, sources), []);

  sources.set(
    "android/app/src/main/java/app/beyoureyes/monitor/service/monitoring/MonitoringService.kt",
    "val recorder = MediaRecorder()",
  );
  assert.ok(validateAndroidVoicePolicy(manifest, sources).some((failure) =>
    failure.includes("microphone capture must stay in the foreground assistant screen")
  ));
  assert.ok(validateAndroidVoicePolicy(
    `${manifest}\n<service android:foregroundServiceType="camera|microphone" />`,
    new Map([...sources].filter(([path]) => !path.includes("/service/"))),
  ).some((failure) => failure.includes("foreground-service microphone capture")));
});

test("removed brand detection covers historical ASCII and Chinese variants", () => {
  const asciiPrefix = "Frame";
  const asciiSuffix = "Ping";
  const variants = [
    `${asciiPrefix}${asciiSuffix}`,
    `${asciiPrefix} ${asciiSuffix}`,
    `${asciiPrefix.toLowerCase()}-${asciiSuffix.toLowerCase()}`,
    `${asciiPrefix.toLowerCase()}_${asciiSuffix.toLowerCase()}`,
    `${asciiPrefix}.${asciiSuffix}`,
    ["守", "见"].join(""),
    ["守", "見"].join(""),
  ];

  for (const variant of variants) {
    assert.equal(containsRemovedBrand(`docs/${variant}/README.md`), true, variant);
    assert.equal(containsRemovedBrand(`product name: ${variant}`), true, variant);
  }
  assert.equal(containsRemovedBrand("frame rendering and network ping latency"), false);
  assert.equal(containsRemovedBrand("Be Your Eye"), false);
});

test("deprecated plural code identifiers cannot replace the singular product name", () => {
  const compact = ["Be", "Your", "Eyes"].join("");
  const hyphenated = ["Be", "Your", "Eyes"].join("-");
  assert.equal(containsDeprecatedPluralCodeBrand(`${compact}Application.kt`), true);
  assert.equal(containsDeprecatedPluralCodeBrand(`${hyphenated}-ModelReview/1.0`), true);
  assert.equal(containsDeprecatedPluralCodeBrand("BeYourEyeApplication.kt"), false);
  assert.equal(containsDeprecatedPluralCodeBrand("app.beyoureyes.monitor"), false);
});

test("current Supabase config keeps vector storage disabled and has no active OPENAI setting", async () => {
  const source = await readFile(path.join(root, "supabase/config.toml"), "utf8");
  assert.deepEqual(validateSupabaseConfig(source), []);
});

test("Supabase config policy fails closed for missing, duplicate, or enabled vector storage", () => {
  const valid = "[storage.vector]\nenabled = false\n";
  assert.deepEqual(validateSupabaseConfig(valid), []);
  assert.ok(validateSupabaseConfig("").some((failure) => failure.includes("exactly one [storage.vector]")));
  assert.ok(validateSupabaseConfig("[storage.vector]\nenabled = true\n").some((failure) =>
    failure.includes("enabled = false exactly once")
  ));
  assert.ok(validateSupabaseConfig(`${valid}\n[storage.vector]\nenabled = false\n`).some((failure) =>
    failure.includes("exactly one [storage.vector]")
  ));
  assert.ok(validateSupabaseConfig("[storage.vector]\nenabled = false\nenabled = false\n").some((failure) =>
    failure.includes("enabled = false exactly once")
  ));
  assert.ok(validateSupabaseConfig(`storage.vector.enabled = true\n${valid}`).some((failure) =>
    failure.includes("enabled = false exactly once")
  ));
  assert.ok(validateSupabaseConfig(`${valid}\n[storage]\nvector.enabled = true\n`).some((failure) =>
    failure.includes("enabled = false exactly once")
  ));
});

test("Supabase config policy rejects active OPENAI settings but ignores comments", () => {
  const active = [
    "[studio]",
    'openai_api_key = "env(OPENAI_API_KEY)"',
    "[storage.vector]",
    "enabled = false",
  ].join("\n");
  assert.ok(validateSupabaseConfig(active).some((failure) => failure.includes("must not configure OPENAI")));

  const commented = [
    "# OPENAI_API_KEY is intentionally not configured.",
    "[storage.vector]",
    "enabled = false # required product boundary",
  ].join("\n");
  assert.deepEqual(validateSupabaseConfig(commented), []);
});
