const fs = require('fs');
const vm = require('vm');

const files = [
  'app/src/main/assets/main.html',
  ...['sales-reports', 'eod-report', 'inventory-reports', 'customer-reports', 'fuel-reports', 'kpi', 'forecasts', 'accounting-reports']
    .map(name => `app/src/main/assets/screens/${name}.html`)
];
const failures = [];
const scriptPattern = /<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi;

for (const file of files) {
  const html = fs.readFileSync(file, 'utf8');
  const scripts = [...html.matchAll(scriptPattern)].map(match => match[1]).filter(Boolean);
  scripts.forEach((source, index) => {
    try {
      new vm.Script(source, { filename: `${file}#script-${index + 1}` });
    } catch (error) {
      failures.push(`${file}#script-${index + 1}: ${error.message}`);
    }
  });
}

console.log(`Checked ${files.length} report screens.`);
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Reports script syntax PASS.');
