const fs = require('fs');
const path = require('path');
const root = process.cwd();
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const failures = [];
const reportScreens = [
  'screens/sales-reports.html',
  'screens/inventory-reports.html',
  'screens/fuel-reports.html',
  'screens/customer-reports.html',
  'screens/accounting-reports.html',
  'screens/eod-report.html',
  'screens/forecasts.html'
];

for (const file of reportScreens) {
  const html = read(path.join('app', 'src', 'main', 'assets', file));
  if (/limit\s*:\s*(?:[0-9]{2,}|[1-9][0-9]?)(?:\s*,|\s*})/.test(html)) {
    failures.push(`${file}: fixed query limit detected`);
  }
}

const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
for (const marker of [
  'val returnAll = params.optBoolean("return_all", false)',
  'val returnAll = data.optBoolean("return_all", false)',
  'getOperationalTotalCount(screenKey, params).coerceAtLeast(1)',
  'if (returnAll) 0 else requestedLimit.coerceIn(1, 500)',
  'if (returnAll) 0 else data.optInt("offset", 0).coerceAtLeast(0)'
]) {
  if (!db.includes(marker)) failures.push(`DatabaseHelper: missing unbounded contract marker: ${marker}`);
}

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Reports unbounded-query regression PASS.');
