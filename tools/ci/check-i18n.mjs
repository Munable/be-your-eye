import vm from "node:vm";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve } from "node:path";

export const SUPPORTED_LOCALES = [
  "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
];

export function hardCodedWebsiteDisplay(source) {
  return /\bmessage\(\s*["']/u.test(source) || /\.textContent\s*=\s*["'][^"']+[^"']*["']/u.test(source);
}

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

function loadWebI18n() {
  const source = readFileSync(join(root, "release/google-play/privacy/i18n.js"), "utf8");
  const context = {
    URL,
    URLSearchParams,
    CustomEvent: class CustomEvent { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
    navigator: { languages: ["en-US"], language: "en-US" },
    location: { search: "", pathname: "/" },
    localStorage: { getItem: () => null, setItem: () => {} },
    document: {
      documentElement: { lang: "" },
      querySelectorAll: () => [],
      querySelector: () => null,
      getElementById: () => null,
      addEventListener: () => {},
      createElement: () => ({ setAttribute: () => {}, append: () => {}, addEventListener: () => {} }),
      head: { append: () => {} },
      body: { prepend: () => {} },
    },
    window: {},
  };
  context.window = context;
  vm.runInNewContext(`${source}\n;globalThis.__i18nSnapshot = { languages: LANGUAGES, dictionaries: TEXT };`, context, {
    filename: "release/google-play/privacy/i18n.js",
  });
  return context.__i18nSnapshot;
}

try {
  const { languages, dictionaries } = loadWebI18n();
  const registered = languages.map(([tag]) => tag).filter((tag) => tag !== "system");
  if (JSON.stringify(registered) !== JSON.stringify(SUPPORTED_LOCALES)) {
    failures.push(`website language registry must be ${SUPPORTED_LOCALES.join(", ")}`);
  }
  const sourceKeys = Object.keys(dictionaries.en ?? {}).sort();
  for (const locale of SUPPORTED_LOCALES) {
    const dictionary = dictionaries[locale];
    if (!dictionary) {
      failures.push(`website dictionary omits ${locale}`);
      continue;
    }
    const keys = Object.keys(dictionary).sort();
    if (JSON.stringify(keys) !== JSON.stringify(sourceKeys)) failures.push(`website dictionary key mismatch for ${locale}`);
    for (const key of keys) if (typeof dictionary[key] !== "string" || dictionary[key].trim() === "") {
      failures.push(`website dictionary has empty value ${locale}.${key}`);
    }
  }
} catch (error) {
  failures.push(`website i18n dictionary could not be loaded: ${error.message}`);
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

for (const file of walk(join(root, "release/google-play/privacy")).filter((value) => value.endsWith(".html"))) {
  const source = readFileSync(file, "utf8");
  if (!/<script\s+src="\/i18n\.js"><\/script>/u.test(source)) {
    failures.push(`website page omits the shared i18n runtime: ${relative(root, file)}`);
  }
  if (/<html\s+lang="zh-CN"/u.test(source)) {
    failures.push(`website page keeps a fixed root zh-CN language: ${relative(root, file)}`);
  }
}

const websiteSourceFiles = walk(join(root, "release/google-play/privacy"))
  .filter((file) => file.endsWith(".js") && !file.endsWith("/i18n.js"));
for (const file of websiteSourceFiles) {
  const source = readFileSync(file, "utf8");
  if (hardCodedWebsiteDisplay(source)) {
    failures.push(`hard-coded website display text in ${relative(root, file)}`);
  }
}

for (const directory of ["supabase/templates", "supabase/email-templates", "release/email"]) {
  const path = join(root, directory);
  if (!existsSync(path)) continue;
  for (const file of walk(path).filter((value) => /\.(?:html|js|ts)$/u.test(value))) {
    const source = readFileSync(file, "utf8");
    if (/message\(\s*["']/u.test(source) || /textContent\s*=\s*["']/u.test(source)) {
      failures.push(`hard-coded email display text in ${relative(root, file)}`);
    }
  }
}

for (const path of [
  "android/app/src/main/java/app/beyoureyes/monitor/feature/assistant/AssistantViewModel.kt",
  "release/google-play/privacy/account/account.js",
]) {
  const source = readFileSync(join(root, path), "utf8");
  if (/zh-CN/u.test(source)) failures.push(`fixed zh-CN locale remains in ${path}`);
}

if (failures.length) {
  console.error(["i18n policy failed:", ...failures.map((failure) => "- " + failure)].join("\n"));
  process.exitCode = 1;
} else {
  console.log(`i18n policy passed (${SUPPORTED_LOCALES.length} locales)`);
}
