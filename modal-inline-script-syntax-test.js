const assert = require('node:assert/strict');
const fs = require('node:fs');

const files = [
  'app/src/main/assets/screens/customers.html',
  'app/src/main/assets/screens/crm.html',
  'app/src/main/assets/screens/contracts.html',
];

let scriptCount = 0;
for (const file of files) {
  const html = fs.readFileSync(file, 'utf8');
  const inlineScripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map((match) => match[1]);
  assert.ok(inlineScripts.length > 0, `${file} must contain inline JavaScript`);
  for (const [index, script] of inlineScripts.entries()) {
    try {
      new Function(script);
    } catch (error) {
      throw new Error(`${file} inline script ${index + 1} has invalid JavaScript: ${error.message}`);
    }
    scriptCount += 1;
  }
}

console.log(`Modal inline JavaScript syntax PASS (${scriptCount} block(s)).`);
