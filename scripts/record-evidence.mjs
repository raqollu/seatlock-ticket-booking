#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFile, writeFile, mkdir, readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const xml = await readFile(path.join(root,'target/site/jacoco/jacoco.xml'),'utf8');
const coverage = {};
for (const match of xml.matchAll(/<counter type="([A-Z]+)" missed="(\d+)" covered="(\d+)"\s*\/>/g)) {
  const missed=Number(match[2]),covered=Number(match[3]);
  coverage[match[1]]={missed,covered,percent:Number((covered/(covered+missed)*100).toFixed(2))};
}
const junit = await readFile(path.join(root,'target/surefire-reports/TEST-dev.seatlock.BookingIntegrationTest.xml'),'utf8');
const suite = junit.match(/<testsuite\s[^>]*>/)[0];
const tests = Object.fromEntries(['tests','errors','failures','skipped'].map(name => [name,Number(suite.match(new RegExp(`${name}="(\\d+)"`))[1])]));
assert.equal(tests.failures+tests.errors+tests.skipped,0); assert.ok(coverage.LINE.percent>=90);
async function sources(directory) {
  const entries = await readdir(directory,{withFileTypes:true});
  return (await Promise.all(entries.map(entry => entry.isDirectory() ? sources(path.join(directory,entry.name)) : [path.join(directory,entry.name)]))).flat();
}
const digests={};
for (const file of (await sources(path.join(root,'src/main'))).sort()) digests[path.relative(root,file)] = createHash('sha256').update(await readFile(file)).digest('hex');
const report = {recordedAt:new Date().toISOString(),tests,coverage,coverageScope:'All production Java classes; no class exclusions. JavaScript not instrumented.',
  reproduction:'./scripts/test.sh && node scripts/record-evidence.mjs',sourceSha256:digests};
await mkdir(path.join(root,'docs/evidence'),{recursive:true});
await writeFile(path.join(root,'docs/evidence/test-summary.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({tests,coverage},null,2));
