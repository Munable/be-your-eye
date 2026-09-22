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

export function validateAndroidVoicePolicy(manifestSource, androidSources) {
  const errors = [];
  if (/RECORD_AUDIO|FOREGROUND_SERVICE_MICROPHONE|foregroundServiceType=["'][^"']*microphone/u.test(manifestSource)) {
    errors.push("microphone permission or service is not allowed");
  }
  const sources = androidSources instanceof Map ? androidSources : new Map(Object.entries(androidSources ?? {}));
  for (const [path, source] of sources) {
    if (/\b(?:MediaRecorder|AudioRecord|AssistantVoiceRecorder)\b|\bsetAudioSource\s*\(/u.test(source)) {
      errors.push(`microphone capture is not allowed: ${path}`);
    }
  }
  return errors;
}
