const removedAsciiBrandPattern = new RegExp(
  ["frame", String.raw`[ \t_.-]*`, "ping"].join(""),
  "iu",
);
const removedChineseBrandPattern = new RegExp(
  ["守", `[${["见", "見"].join("")}]`].join(""),
  "u",
);
const deprecatedPluralCodeBrandPattern = new RegExp(
  ["Be", "-?", "Your", "-?", "Eyes"].join(""),
  "u",
);

export function containsRemovedBrand(value) {
  const source = String(value);
  return removedAsciiBrandPattern.test(source) || removedChineseBrandPattern.test(source);
}

export function containsDeprecatedPluralCodeBrand(value) {
  return deprecatedPluralCodeBrandPattern.test(String(value));
}

const voiceRecorderPath =
  "android/app/src/main/java/app/beyoureyes/monitor/feature/assistant/AssistantVoiceRecorder.kt";
const voiceScreenPath =
  "android/app/src/main/java/app/beyoureyes/monitor/feature/assistant/AssistantScreen.kt";
const audioCapturePrimitivePattern =
  /\b(?:MediaRecorder|AudioRecord|AudioRecordingConfiguration|AAudio|Oboe)\b|(?:Manifest\.permission|android\.permission)\.RECORD_AUDIO|\bsetAudioSource\s*\(/u;

export function validateAndroidVoicePolicy(manifestSource, androidSources) {
  const failures = [];
  const manifest = String(manifestSource);
  const recordAudioPermissions = manifest.match(
    /<uses-permission\b[^>]*android:name=["']android\.permission\.RECORD_AUDIO["'][^>]*\/?\s*>/gu,
  ) ?? [];
  if (recordAudioPermissions.length !== 1) {
    failures.push("Android manifest must request RECORD_AUDIO exactly once for assistant hold-to-talk");
  }
  if (/FOREGROUND_SERVICE_MICROPHONE|foregroundServiceType=["'][^"']*microphone/iu.test(manifest)) {
    failures.push("background or foreground-service microphone capture is not allowed");
  }

  const sources = androidSources instanceof Map
    ? androidSources
    : new Map(Object.entries(androidSources ?? {}));
  const recorder = sources.get(voiceRecorderPath) ?? "";
  const screen = sources.get(voiceScreenPath) ?? "";
  for (const [token, description] of [
    ["class AssistantVoiceRecorder", "the assistant-only recorder"],
    ["MediaRecorder.AudioSource.MIC", "explicit microphone capture"],
    ["setMaxFileSize(MAX_VOICE_AUDIO_BYTES", "the bounded audio-file limit"],
    ["fun stop()", "release-time recording stop"],
    ["fun cancel()", "cancel-time recording cleanup"],
  ]) {
    if (!recorder.includes(token)) failures.push(`${voiceRecorderPath} must keep ${description}`);
  }
  for (const [token, description] of [
    ["AssistantVoiceRecorder(context)", "the screen-owned recorder"],
    ["Manifest.permission.RECORD_AUDIO", "the foreground permission request"],
    ["awaitEachGesture", "the bounded press gesture"],
    ["awaitLongPressOrCancellation", "long-press activation"],
    ["recorder.start()", "press-time recording start"],
    ["recorder.stop()", "release-time recording stop"],
    ["recorder.cancel()", "gesture-cancel cleanup"],
    ["onDispose", "screen-disposal cleanup"],
  ]) {
    if (!screen.includes(token)) failures.push(`${voiceScreenPath} must keep ${description}`);
  }

  const allowedCapturePaths = new Set([
    "android/app/src/main/AndroidManifest.xml",
    voiceRecorderPath,
    voiceScreenPath,
  ]);
  for (const [path, sourceValue] of sources) {
    const source = String(sourceValue);
    if (!allowedCapturePaths.has(path) && audioCapturePrimitivePattern.test(source)) {
      failures.push(`microphone capture must stay in the foreground assistant screen: ${path}`);
    }
    if (path.includes("/service/") && /microphone|record_audio|mediarecorder|audiorecord|setaudiosource|assistantvoicerecorder/iu.test(source)) {
      failures.push(`Android service must not capture microphone audio: ${path}`);
    }
  }
  return failures;
}

function stripTomlComment(line) {
  let quote = null;
  let escaped = false;
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (quote === '"' && escaped) {
      escaped = false;
      continue;
    }
    if (quote === '"' && character === "\\") {
      escaped = true;
      continue;
    }
    if (quote !== null) {
      if (character === quote) quote = null;
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      continue;
    }
    if (character === "#") return line.slice(0, index);
  }
  return line;
}

export function validateSupabaseConfig(source) {
  const failures = [];
  let currentSection = "";
  let vectorSectionCount = 0;
  const vectorEnabledAssignments = [];

  for (const [index, rawLine] of String(source).split(/\r?\n/u).entries()) {
    const lineNumber = index + 1;
    const activeLine = stripTomlComment(rawLine).trim();
    if (activeLine.length === 0) continue;

    if (/openai/iu.test(activeLine)) {
      failures.push(`supabase/config.toml must not configure OPENAI credentials or services (line ${lineNumber})`);
    }

    const sectionMatch = activeLine.match(/^\[\s*([^\[\]]+?)\s*\]$/u);
    if (sectionMatch) {
      currentSection = sectionMatch[1].toLowerCase();
      if (currentSection === "storage.vector") vectorSectionCount += 1;
      continue;
    }

    const assignmentMatch = activeLine.match(/^([A-Za-z0-9_.-]+)\s*=\s*(.+)$/u);
    const assignmentPath = assignmentMatch
      ? [currentSection, assignmentMatch[1].toLowerCase()].filter(Boolean).join(".")
      : "";
    if (assignmentPath === "storage.vector.enabled") {
      vectorEnabledAssignments.push({
        lineNumber,
        value: assignmentMatch[2].trim().toLowerCase(),
      });
    }
  }

  if (vectorSectionCount !== 1) {
    failures.push("supabase/config.toml must contain exactly one [storage.vector] section");
  }
  if (vectorEnabledAssignments.length !== 1 || vectorEnabledAssignments[0].value !== "false") {
    failures.push("supabase/config.toml must set [storage.vector] enabled = false exactly once");
  }

  return failures;
}
