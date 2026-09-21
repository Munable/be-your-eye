#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('usage: deno-lock-to-cyclonedx.mjs LOCK OUTPUT');
const lock = JSON.parse(readFileSync(resolve(input), 'utf8'));
if (
  lock.version !== '5' ||
  typeof lock.specifiers !== 'object' ||
  lock.specifiers === null ||
  Array.isArray(lock.specifiers) ||
  typeof lock.npm !== 'object' ||
  lock.npm === null ||
  Array.isArray(lock.npm)
) {
  throw new Error('expected a Deno v5 lock with resolved npm packages');
}

function identity(key) {
  const separator = key.lastIndexOf('@');
  if (separator <= 0 || separator === key.length - 1) throw new Error(`invalid npm lock identity: ${key}`);
  const fullName = key.slice(0, separator);
  const version = key.slice(separator + 1);
  const slash = fullName.startsWith('@') ? fullName.indexOf('/') : -1;
  const group = slash > 0 ? fullName.slice(0, slash) : '';
  const name = slash > 0 ? fullName.slice(slash + 1) : fullName;
  const encoded = group ? `${encodeURIComponent(group)}/${name}` : name;
  const purl = `pkg:npm/${encoded}@${version}`;
  return { group, name, version, purl, 'bom-ref': purl };
}

const packages = Object.entries(lock.npm).sort(([left], [right]) => left.localeCompare(right));
const components = packages.map(([key, value]) => ({
  type: 'library',
  ...identity(key),
  properties: value.integrity ? [{ name: 'deno.lock.integrity', value: value.integrity }] : [],
}));
const componentByName = new Map();
for (const [key] of packages) {
  const item = identity(key);
  const fullName = item.group ? `${item.group}/${item.name}` : item.name;
  const existing = componentByName.get(fullName) ?? [];
  existing.push(item['bom-ref']);
  componentByName.set(fullName, existing);
}
const dependencyRows = packages.map(([key, value]) => {
  const item = identity(key);
  const dependsOn = [];
  for (const dependency of value.dependencies ?? []) {
    const direct = dependency.lastIndexOf('@') > 0 ? identity(dependency)['bom-ref'] : undefined;
    const candidates = direct ? [direct] : (componentByName.get(dependency) ?? []);
    if (candidates.length === 1) dependsOn.push(candidates[0]);
  }
  return { ref: item['bom-ref'], dependsOn: [...new Set(dependsOn)].sort() };
});
const rootRef = 'pkg:generic/app.beyoureyes/supabase-edge-functions@1.0.0';
const componentRefs = new Set(components.map((component) => component['bom-ref']));
const directRefs = [...new Set(Object.entries(lock.specifiers).map(([specifier, resolved]) => {
  if (!specifier.startsWith('npm:')) {
    throw new Error(`unsupported direct Deno dependency: ${specifier}`);
  }
  if (typeof resolved !== 'string') throw new Error(`unresolved direct Deno dependency: ${specifier}`);
  const packageName = specifier.slice('npm:'.length).replace(/@[^@]+$/, '');
  const reference = identity(`${packageName}@${resolved}`)['bom-ref'];
  if (!componentRefs.has(reference)) {
    throw new Error(`direct Deno dependency is missing from the npm lock: ${specifier}`);
  }
  return reference;
}))].sort();

writeFileSync(resolve(output), `${JSON.stringify({
  bomFormat: 'CycloneDX',
  specVersion: '1.6',
  version: 1,
  metadata: {
    component: {
      type: 'application',
      name: 'supabase-edge-functions',
      group: 'app.beyoureyes',
      version: '1.0.0',
      'bom-ref': rootRef,
      purl: rootRef,
    },
    tools: { components: [{ type: 'application', name: 'deno-lock-to-cyclonedx', version: '1' }] },
  },
  components,
  dependencies: [{ ref: rootRef, dependsOn: directRefs }, ...dependencyRows],
}, null, 2)}\n`);
