const fs = require('fs');
const path = require('path');
const root = process.cwd();
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const failures = [];

const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const fuel = read('app/src/main/assets/screens/fuel-reports.html');
const readings = read('app/src/main/assets/screens/meter-readings.html');

// Report-generation payloads must use bounded paging. CRUD/list preload limits elsewhere
// on these screens are independent and do not determine report pagination.
const fuelReportCode = fuel.slice(fuel.indexOf('async function generateReport('), fuel.indexOf('// 7. Quick Presets'));
const meterReportStart = readings.indexOf('async function generateReport(');
const meterReportCode = readings.slice(meterReportStart, readings.indexOf('function renderReport(', meterReportStart));
for (const [screen, source] of [['fuel-reports.html', fuelReportCode], ['meter-readings.html', meterReportCode]]) {
  if (!source || /limit\s*:\s*(?:[0-9]{2,}|[1-9][0-9]?)(?:\s*,|\s*})/.test(source)) {
    failures.push(`${screen}: report-generation query is unbounded or uses a hard-coded page size`);
  }
}
for (const [label, pattern] of [
  ['operational query must use bound LIMIT/OFFSET', /LIMIT \? OFFSET \?/],
  ['operational report must request rows using bounded page params', /put\("limit", pageSize\)[\s\S]{0,120}put\("offset", offset\)/],
  ['operational report aggregates the full result in bounded scan pages', /put\("limit", 1000\)[\s\S]{0,500}getOperationalRows\(screenKey, scanParams\)/],
  ['fuel report page must clamp offsets against total_count', /lastPageOffset = if \(totalCount > 0\) \(\(totalCount - 1\) \/ limit\) \* limit else 0/],
  ['fuel reports must send the requested page offset', /offset: \(pagination\.page - 1\) \* pagination\.pageSize/],
  ['meter readings must send the requested page offset', /offset: \(reportPage - 1\) \* REPORT_PAGE_SIZE/]
]) {
  if (!pattern.test(label.startsWith('fuel') ? fuel : label.startsWith('meter') ? readings : db)) failures.push(label);
}

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Reports bounded-query and pagination regression PASS.');
