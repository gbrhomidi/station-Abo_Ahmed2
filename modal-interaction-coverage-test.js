const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const screensDir = 'app/src/main/assets/screens';
const modalMarker = /modal-overlay|class=["'][^"']*\bmodal\b|role=["']dialog["']/i;
const guardMarker = /assets-local\/js\/modal-interaction\.js/;
const missingGuard = [];
let modalScreenCount = 0;

for (const file of fs.readdirSync(screensDir).filter((name) => name.endsWith('.html')).sort()) {
  const content = fs.readFileSync(path.join(screensDir, file), 'utf8');
  if (!modalMarker.test(content)) continue;
  modalScreenCount += 1;
  if (!guardMarker.test(content)) missingGuard.push(file);
}

assert.ok(modalScreenCount > 0, 'Expected modal-bearing screens');
assert.deepEqual(missingGuard, [], `Modal screens missing shared interaction guard: ${missingGuard.join(', ')}`);

const guard = fs.readFileSync('app/src/main/assets/assets-local/js/modal-interaction.js', 'utf8');
assert.match(guard, /new MutationObserver/);
assert.match(guard, /queueModalPreparation/);
assert.match(guard, /window\.addEventListener\('pagehide'/);
assert.match(guard, /modalObserver\.disconnect\(\)/);
assert.match(guard, /document\.removeEventListener\('touchmove'/);
assert.match(guard, /document\.removeEventListener\('focusin'/);

console.log(`Shared modal interaction coverage PASS (${modalScreenCount} modal screen(s)).`);
