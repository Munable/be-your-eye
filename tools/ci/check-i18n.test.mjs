import test from "node:test";
import assert from "node:assert/strict";
import {
  resourceContractFailures,
  catalogLabelFailures,
  SUPPORTED_LOCALES,
} from "./check-i18n.mjs";

test("the supported language contract is stable and ordered", () => {
  assert.deepEqual(SUPPORTED_LOCALES, [
    "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
  ]);
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

test("resource placeholders and plural branches must match", () => {
  const base = new Map([["items", { type: "plurals", placeholders: ["1:d"], quantities: ["one", "other"] }]]);
  const locale = new Map([["items", { type: "plurals", placeholders: ["1:s"], quantities: ["other"] }]]);
  assert.equal(resourceContractFailures(base, locale, "fr").length, 2);
});

test("new Catalog targets require all nine nonempty labels", () => {
  const target = { target_id: "cat", labels: Object.fromEntries(SUPPORTED_LOCALES.map((tag) => [tag, "Cat"])) };
  assert.deepEqual(catalogLabelFailures(target, "fixture"), []);
  target.labels.ja = "";
  assert.equal(catalogLabelFailures(target, "fixture").length, 1);
  delete target.labels.ja;
  assert.equal(catalogLabelFailures(target, "fixture").length, 1);
});
