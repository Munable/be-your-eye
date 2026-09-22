import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import path from "node:path";
import test from "node:test";
import {
  containsDeprecatedPluralCodeBrand,
  containsRemovedBrand,
  validateAndroidVoicePolicy,
} from "./repo-policy-rules.mjs";

const root = path.resolve(import.meta.dirname, "../..");

test("current repository has no maintained service or microphone path", () => {
  const output = execFileSync(process.execPath, [path.join(root, ".github/scripts/check-repo-policy.mjs")], { cwd: root, encoding: "utf8" });
  assert.match(output, /no microphone or maintainer services/u);
});

test("microphone permissions and capture cannot return", () => {
  assert.deepEqual(validateAndroidVoicePolicy('<service android:foregroundServiceType="camera" />', new Map()), []);
  assert.ok(validateAndroidVoicePolicy('<uses-permission android:name="android.permission.RECORD_AUDIO" />', new Map()).length);
  assert.ok(validateAndroidVoicePolicy('', new Map([['capture.kt', 'val recorder = MediaRecorder()']])).length);
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
