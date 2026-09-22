import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";
import {
  containsDeprecatedPluralCodeBrand,
  containsRemovedBrand,
  validateAndroidVoicePolicy,
} from "./repo-policy-rules.mjs";

import { validateCommunityWorkflow } from "../../tools/ci/community-policy.mjs";

const root = resolve(import.meta.dirname, "../..");
const failures = [];
const required = [
  "README.md", "AGENTS.md", "SECURITY.md", "THIRD_PARTY_NOTICES.md", "LICENSE", "NOTICE",
  "docs/PRODUCT.md", "docs/ARCHITECTURE.md",
  "docs/DEVELOPMENT.md", "docs/RELEASE.md", "evidence/README.md",
  "evidence/current/01-foundation.json", "evidence/current/02-reference.json",
  "evidence/current/03-reading.json", "evidence/current/04-cloud.json",
  "evidence/current/05-release.json", "android/settings.gradle.kts",
  "android/core/domain/src/main/java/app/beyoureyes/core/domain/Monitor.kt",
  "android/app/src/main/java/app/beyoureyes/monitor/app/navigation/BeYourEyeApp.kt",
  "android/app/src/main/java/app/beyoureyes/monitor/service/monitoring/MonitoringService.kt",
  "android/app/src/main/java/app/beyoureyes/monitor/service/monitoring/MonitoringSession.kt",
  "model-tools/catalog-validator/package.json",
  "tools/ci/run-community.sh",
  "tools/ci/community-policy.mjs",
  "tools/ci/community-policy.test.mjs",
  "tools/ci/check-i18n.mjs",
  "tools/ci/check-i18n.test.mjs",
  ".github/scripts/repo-policy-rules.mjs",
  ".github/scripts/check-repo-policy.test.mjs",
];
for (const path of required) if (!existsSync(join(root, path))) failures.push(`missing required path: ${path}`);

for (const forbidden of [
  "supabase/functions", "release/website", "release/google-play",
  "android/app/src/main/java/app/beyoureyes/monitor/feature/assistant",
  "backend", "railway.toml", "docs/DEVELOPMENT-PLAN-v2.1.md",
  "docs/DEVELOPMENT-PLAN-v3.md", "docs/DEVELOPMENT-PLAN-v3.1.md",
  "docs/PRODUCT-EXPERIENCE-v1.md", "mobile_visual_monitor_app_complete_plan_zh.md",
  "evidence/gates", "LICENSE-POLICY.md", "PRIVACY-BOUNDARY.md",
  "model-tools/v3/STRUCTURED-READING-CANDIDATE-AUDIT-2026-08-03.md",
]) {
  if (existsSync(join(root, forbidden))) failures.push(`obsolete path must be removed: ${forbidden}`);
}
const licenseText = readFileSync(join(root, "LICENSE"), "utf8");
if (!licenseText.includes("Apache License") || !licenseText.includes("Version 2.0, January 2004")) {
  failures.push("first-party candidate requires the unmodified Apache-2.0 license text");
}

for (const name of readdirSync(join(root, ".github/workflows"))) {
  if (name !== "community.yml") failures.push(`unreviewed workflow: ${name}`);
  else failures.push(...validateCommunityWorkflow(readFileSync(join(root, ".github/workflows", name), "utf8")));
}

const trackedPaths = new Set(execFileSync("git", ["ls-files", "--cached", "-z"], {
  cwd: root, encoding: "utf8",
}).split("\0").filter(Boolean));

const policyPath = ".github/scripts/check-repo-policy.mjs";
for (const path of trackedPaths) {
  if (path === policyPath || !existsSync(join(root, path))) continue;
  if (containsRemovedBrand(path) || containsRemovedBrand(readFileSync(join(root, path)).toString("utf8"))) {
    failures.push(`tracked path contains the removed early product brand: ${path}`);
  }
  if (
    containsDeprecatedPluralCodeBrand(path) ||
    containsDeprecatedPluralCodeBrand(readFileSync(join(root, path)).toString("utf8"))
  ) {
    failures.push(`tracked path contains the deprecated plural product identifier: ${path}`);
  }
}

for (const path of trackedPaths) {
  if (!path.endsWith(".md") || !existsSync(join(root, path))) continue;
  const source = readFileSync(join(root, path), "utf8");
  if (source.includes("evidence/gates/")) {
    failures.push(`tracked documentation points to removed evidence/gates: ${path}`);
  }
}

for (const path of [
  "docs/PRODUCT.md", "docs/ARCHITECTURE.md", "docs/DEVELOPMENT.md", "docs/RELEASE.md",
]) {
  const source = readFileSync(join(root, path), "utf8");
  if (/\b[0-9a-f]{40,64}\b/iu.test(source)) {
    failures.push(`authority document contains dated artifact hash; move status to evidence/current: ${path}`);
  }
}
for (const packageFile of ["model-tools/catalog-validator/package.json"]) {
  const value = JSON.parse(readFileSync(join(root, packageFile), "utf8"));
  if (value.private !== true) failures.push(`${packageFile} must set private=true`);
  if (!value.scripts?.test) failures.push(`${packageFile} must expose tests`);
}

const expectedDocs = ["ARCHITECTURE.md", "DEVELOPMENT.md", "PRODUCT.md", "RELEASE.md"];
const actualDocs = readdirSync(join(root, "docs")).filter((name) => name.endsWith(".md")).sort();
if (JSON.stringify(actualDocs) !== JSON.stringify(expectedDocs)) {
  failures.push(`docs must contain exactly the four authorities: ${actualDocs.join(", ")}`);
}
const currentEvidence = readdirSync(join(root, "evidence/current")).filter((name) => name.endsWith(".json")).sort();
if (JSON.stringify(currentEvidence) !== JSON.stringify([
  "01-foundation.json", "02-reference.json", "03-reading.json", "04-cloud.json", "05-release.json",
])) failures.push(`current evidence must be exactly five summaries: ${currentEvidence.join(", ")}`);

function walk(directory) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...walk(path));
    else if (entry.isFile()) result.push(path);
  }
  return result;
}
const sourceRoots = ["android/app/src/main", "android/core", "supabase", "model-tools/catalog-validator"]
  .map((path) => join(root, path)).filter(existsSync);
const files = sourceRoots.flatMap(walk);

for (const path of files.filter((value) => [".json"].includes(extname(value)))) {
  try { JSON.parse(readFileSync(path, "utf8")); }
  catch (error) { failures.push(`invalid JSON ${relative(root, path)}: ${error.message}`); }
}
const forbiddenModelExtensions = new Set([".bin", ".ckpt", ".h5", ".keras", ".lite", ".mlmodel", ".onnx", ".ort", ".pb", ".pt", ".pth", ".safetensors", ".task", ".tflite"]);
for (const path of trackedPaths) {
  if (forbiddenModelExtensions.has(extname(path).toLowerCase())) {
    failures.push(`model binary must remain external to Git: ${path}`);
  }
}

const modelToolSources = [...trackedPaths]
  .filter((path) => path.startsWith("model-tools/") && /\.(?:py|js|mjs|ts)$/u.test(path))
  .filter((path) => existsSync(join(root, path)));
for (const path of modelToolSources) {
  const source = readFileSync(join(root, path), "utf8");
  if (/(?:^|\/)(?:train|finetune|fine_tune)[^/]*\.(?:py|js|mjs|ts)$/iu.test(path) ||
      /\btorch\.optim\b|\.backward\s*\(|\.train\s*\(\s*\)/u.test(source)) {
    failures.push(`first-party model training entrypoint is not allowed: ${path}`);
  }
}

const androidMain = walk(join(root, "android/app/src/main")).filter((path) => /\.(?:kt|java|xml)$/u.test(path));
const forbiddenProductTokens = [
  ["ProductShell", "obsolete ProductShell"],
  ["RAILWAY_API_URL", "Railway URL"],
  ["SUPABASE_URL", "maintainer backend"],
  ["FirebaseMessaging", "maintainer push service"],
  ["BillingClient", "billing"],
  ["ProductAccessDecision", "subscription gate"],
  ["getSharedPreferences", "legacy SharedPreferences storage"],
];
for (const path of androidMain) {
  const source = readFileSync(path, "utf8");
  for (const [token, label] of forbiddenProductTokens) {
    if (source.includes(token)) failures.push(`${label} reappeared in ${relative(root, path)}`);
  }
}
const uiFiles = androidMain.filter((path) => path.includes("/feature/") || path.includes("/app/navigation/"));
for (const path of uiFiles) {
  const source = readFileSync(path, "utf8");
  for (const wording of ["准备模型", "激活包", "选择监控区域", "填写单位"]) {
    if (source.includes(wording)) failures.push(`forbidden first-release UI wording '${wording}' in ${relative(root, path)}`);
  }
}

const androidManifest = readFileSync(join(root, "android/app/src/main/AndroidManifest.xml"), "utf8");
const androidVoiceSources = new Map(
  walk(join(root, "android/app/src/main"))
    .filter((path) => /\.(?:c|cc|cpp|h|java|kt|xml)$/u.test(path))
    .map((path) => [relative(root, path), readFileSync(path, "utf8")]),
);
failures.push(...validateAndroidVoicePolicy(androidManifest, androidVoiceSources));

const runtimeSources = files.filter((path) => /\.(?:kt|java|mjs)$/u.test(path));
const identityField = String.raw`(?:packageId|package_id|modelPackageId|model_package_id|modelSource|model_source|weightsSource|weights_source|artifactFile|artifact_file|artifactFilename|artifact_filename|fileName|filename|modelName|model_name|artifactUrl|artifact_url|downloadUrl|download_url|vendor|manufacturer)`;
const identityBranches = [
  new RegExp(String.raw`\b${identityField}\b(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*\s*(?:===|!==|==|!=)\s*["'\x60]`, "m"),
  new RegExp(String.raw`\b${identityField}\b(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*\s*\.\s*(?:contains|startsWith|endsWith|matches|includes)\s*\(\s*["'\x60]`, "m"),
  new RegExp(String.raw`\b(?:when|switch)\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*${identityField}\b`, "m"),
];
for (const path of runtimeSources) {
  const relativePath = relative(root, path);
  if (relativePath.includes("/test") || relativePath.includes("/androidTest") || relativePath.includes("/fixtures")) continue;
  const source = readFileSync(path, "utf8");
  if (identityBranches.some((pattern) => pattern.test(source))) {
    failures.push(`package/vendor identity controls runtime dispatch: ${relativePath}`);
  }
}

for (const path of ["README.md", "AGENTS.md", "docs/PRODUCT.md", "docs/ARCHITECTURE.md", "docs/DEVELOPMENT.md", "docs/RELEASE.md"]) {
  const source = readFileSync(join(root, path), "utf8");
  if (/Railway|ProductShell|DEVELOPMENT-PLAN-v3/iu.test(source)) {
    failures.push(`current authority still describes removed architecture: ${path}`);
  }
}
const productAuthority = readFileSync(join(root, "docs/PRODUCT.md"), "utf8");
const firstReleaseEntries = ["reference images", "numeric reading", "visual target"];
if (!firstReleaseEntries.every((entry) => productAuthority.includes(entry))) {
  failures.push("product authority must name all three first-release entries");
}
for (const [token, requirement] of [
  ["signed Catalog", "the single signed model Catalog"],
  ["paired alerts", "optional encrypted peer notifications"],
]) {
  if (!productAuthority.includes(token)) {
    failures.push(`product authority must define ${requirement}`);
  }
}

if (failures.length) {
  for (const failure of failures) process.stderr.write(`POLICY ERROR: ${failure}\n`);
  process.exit(1);
}
process.stdout.write("Repository policy passed: independent Community Android, encrypted paired alerts, one signed Catalog, no microphone or maintainer services.\n");
