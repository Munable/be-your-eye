import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import {
  hardCodedWebsiteDisplay,
  resourceContractFailures,
  SUPPORTED_LOCALES,
} from "./check-i18n.mjs";

test("the supported language contract is stable and ordered", () => {
  assert.deepEqual(SUPPORTED_LOCALES, [
    "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
  ]);
});

test("the hard-coded display guard rejects a literal website message", () => {
  assert.equal(hardCodedWebsiteDisplay("message('Missing translation')"), true);
  assert.equal(hardCodedWebsiteDisplay("message(t('serviceUnavailable'))"), false);
});

test("the resource contract rejects a missing translation key", () => {
  const base = new Map([
    ["welcome", { type: "string", placeholders: [], quantities: [] }],
    ["items", { type: "plurals", placeholders: ["1:d"], quantities: ["one", "other"] }],
  ]);
  const locale = new Map([
    ["items", { type: "plurals", placeholders: ["1:d"], quantities: ["one", "other"] }],
  ]);
  assert.deepEqual(resourceContractFailures(base, locale, "fr"), [
    "missing Android translation key welcome in fr",
  ]);
});

test("browser locale matching routes scripts, regions and Portuguese", () => {
  const source = readFileSync(new URL("../../release/google-play/privacy/i18n.js", import.meta.url), "utf8");
  const context = {
    URL,
    URLSearchParams,
    CustomEvent: class CustomEvent {},
    navigator: { languages: ["xx", "zh-TW", "pt-PT"], language: "xx" },
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
  vm.runInNewContext(`${source}\n;globalThis.__runtime = { normalize, supportedFromTag, browserLocale };`, context);
  assert.equal(context.__runtime.normalize("zh-CN"), "zh-Hans");
  assert.equal(context.__runtime.normalize("zh-HK"), "zh-Hant");
  assert.equal(context.__runtime.normalize("pt-PT"), "pt-BR");
  assert.equal(context.__runtime.browserLocale(), "zh-Hant");
  context.navigator.languages = ["xx", "pt-PT"];
  assert.equal(context.__runtime.browserLocale(), "pt-BR");
  assert.equal(context.__runtime.supportedFromTag("xx-YY"), null);
});
