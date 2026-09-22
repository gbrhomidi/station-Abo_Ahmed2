const fs = require('fs');
const vm = require('vm');

const html = fs.readFileSync('app/src/main/assets/main.html', 'utf8');
const databaseHelper = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');

const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>(\s*[\s\S]*?)<\/script>/gi)]
  .map(m => m[1]).filter(s => s.trim().length > 0);

if (scripts.length === 0) { console.error('No inline script found'); process.exit(1); }

try {
  scripts.forEach(source => new vm.Script(source, { filename: 'main.html:inline-script' }));
  console.log('Syntax PASS: ' + scripts.length + ' inline script block(s) compiled successfully.');
} catch (e) {
  console.error('Syntax FAIL: ' + e.message);
  process.exit(1);
}

if (html.includes('Math.random()') || html.includes('progress: 70')) {
  console.error('Contract FAIL: forbidden mock/static KPI pattern found.');
  process.exit(1);
}
console.log('Contract PASS: no Math.random() or hardcoded progress KPI found.');

if (!databaseHelper.includes('SUM(current_quantity)') || !databaseHelper.includes('SUM(capacity_liters)')) {
  console.error('SQLite KPI FAIL: tank fill query is missing from DatabaseHelper.kt');
  process.exit(1);
}
console.log('SQLite KPI PASS: tank fill uses current_quantity/capacity_liters.');
