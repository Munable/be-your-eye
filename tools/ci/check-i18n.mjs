import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve } from "node:path";

export const SUPPORTED_LOCALES = [
  "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
];

const root = resolve(import.meta.dirname, "../..");
const failures = [];
const androidRes = join(root, "android/app/src/main/res");

function localeDirectory(tag) {
  return {
    en: "values",
    "zh-Hans": "values-b+zh+Hans",
    "zh-Hant": "values-b+zh+Hant",
    ja: "values-ja",
    ko: "values-ko",
    es: "values-es",
    fr: "values-fr",
    de: "values-de",
    "pt-BR": "values-pt-rBR",
  }[tag];
}

function placeholders(source) {
  return [...source.matchAll(/%(?:(\d+)\$)?([a-z])/giu)]
    .filter((match) => match[0] !== "%%")
    .map((match) => `${match[1] ?? ""}:${match[2].toLowerCase()}`)
    .sort();
}

function androidResources(path) {
  const source = readFileSync(path, "utf8");
  const resources = new Map();
  const duplicateNames = new Set();
  const entryPattern = /<(string|plurals|array)\b[^>]*\bname="([^"]+)"[^>]*>([\s\S]*?)<\/\1\s*>/gu;
  for (const match of source.matchAll(entryPattern)) {
    const [, type, name, body] = match;
    if (resources.has(name)) duplicateNames.add(name);
    const quantities = type === "plurals"
      ? [...body.matchAll(/<item\b[^>]*\bquantity="([^"]+)"[^>]*>/gu)].map((item) => item[1]).sort()
      : [];
    resources.set(name, {
      type,
      placeholders: placeholders(body),
      quantities,
    });
  }
  for (const match of source.matchAll(/<(string|plurals|array)\b[^>]*\bname="([^"]+)"[^>]*\/\s*>/gu)) {
    if (resources.has(match[2])) duplicateNames.add(match[2]);
    resources.set(match[2], { type: match[1], placeholders: [], quantities: [] });
  }
  for (const name of duplicateNames) failures.push(`duplicate Android resource key ${name} in ${relative(root, path)}`);
  return resources;
}

export function resourceContractFailures(baseResources, localeResources, locale) {
  const errors = [];
  for (const [name, expected] of baseResources) {
    const actual = localeResources.get(name);
    if (!actual) {
      errors.push(`missing Android translation key ${name} in ${locale}`);
      continue;
    }
    if (actual.type !== expected.type) errors.push(`Android resource type mismatch for ${name} in ${locale}`);
    if (JSON.stringify(actual.placeholders) !== JSON.stringify(expected.placeholders)) {
      errors.push(`Android placeholder mismatch for ${name} in ${locale}`);
    }
    if (JSON.stringify(actual.quantities) !== JSON.stringify(expected.quantities)) {
      errors.push(`Android plural quantity mismatch for ${name} in ${locale}`);
    }
  }
  for (const name of localeResources.keys()) {
    if (!baseResources.has(name)) errors.push(`unknown Android translation key ${name} in ${locale}`);
  }
  return errors;
}

const baseResources = new Map();
for (const fileName of ["strings.xml", "community.xml"]) {
  const path = join(androidRes, "values", fileName);
  if (existsSync(path)) baseResources.set(fileName, androidResources(path));
}
for (const locale of SUPPORTED_LOCALES) {
  for (const [fileName, baseStrings] of baseResources) {
    const path = join(androidRes, localeDirectory(locale), fileName);
    if (!existsSync(path)) {
      failures.push(`missing Android ${fileName} resource for ${locale}: ${relative(root, path)}`);
      continue;
    }
    const resources = androidResources(path);
    failures.push(...resourceContractFailures(baseStrings, resources, locale));
  }
}

const localeConfigPath = join(androidRes, "xml/locales_config.xml");
if (!existsSync(localeConfigPath)) failures.push("Android locale config is missing");
else {
  const localeConfig = readFileSync(localeConfigPath, "utf8");
  for (const locale of SUPPORTED_LOCALES) {
    if (!localeConfig.includes(`android:name="${locale}"`)) failures.push(`locale config omits ${locale}`);
  }
}

function walk(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...walk(path));
    else if (entry.isFile()) files.push(path);
  }
  return files;
}

for (const path of [join(root, "android/app/src/main/java"), join(root, "android/core")]) {
  for (const file of walk(path).filter((value) => /\.(?:kt|java)$/u.test(value) && !/\/src\/(?:test|androidTest)\//u.test(value))) {
    const source = readFileSync(file, "utf8");
    if (/\bText\(\s*["']/u.test(source) || /contentDescription\s*=\s*["']/u.test(source)) {
      failures.push(`hard-coded Compose UI text in ${relative(root, file)}`);
    }
    if (/setContent(?:Title|Text)\(\s*["']/u.test(source) || /NotificationChannel\([^\n]*,[\s\n]*["']/u.test(source)) {
      failures.push(`hard-coded Android notification text in ${relative(root, file)}`);
    }
  }
}

export function catalogLabelFailures(target, path) {
  const labels = target.labels;
  if (!labels || JSON.stringify(Object.keys(labels).sort()) !== JSON.stringify([...SUPPORTED_LOCALES].sort())) {
    return [`Catalog target ${target.target_id} must have exactly nine locale labels in ${path}`];
  }
  return SUPPORTED_LOCALES.filter((locale) => typeof labels[locale] !== "string" || !labels[locale].trim() || /[\u0000-\u001f\u007f]/u.test(labels[locale]))
    .map((locale) => `Catalog target ${target.target_id} has an invalid ${locale} label in ${path}`);
}

for (const path of [
  "model-tools/v3/releases/community/templates/object.manifest.template.json",
  "android/app/src/community/assets/community-models/manifests/efficientdet_lite2_object_v1.json",
]) {
  const manifest = JSON.parse(readFileSync(join(root, path), "utf8"));
  for (const target of manifest.adapter_contract.class_map.targets) failures.push(...catalogLabelFailures(target, path));
}

if (failures.length) {
  console.error(["i18n policy failed:", ...failures.map((failure) => "- " + failure)].join("\n"));
  process.exitCode = 1;
} else {
  console.log(`i18n policy passed (${SUPPORTED_LOCALES.length} locales)`);
}
