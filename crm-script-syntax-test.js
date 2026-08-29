const assert = require('node:assert/strict');
const fs = require('node:fs');

const html = fs.readFileSync('app/src/main/assets/screens/crm.html', 'utf8');
const inlineScripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map((match) => match[1]);
assert.ok(inlineScripts.length > 0, 'CRM must contain inline JavaScript');

for (const [index, script] of inlineScripts.entries()) {
  try {
    new Function(script);
  } catch (error) {
    throw new Error(`CRM inline script ${index + 1} has invalid JavaScript: ${error.message}`);
  }
}

console.log(`CRM inline JavaScript syntax PASS (${inlineScripts.length} block(s)).`);
