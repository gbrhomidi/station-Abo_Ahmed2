const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const files = [
  'app/src/main/assets/main.html',
  ...['sales-reports','eod-report','inventory-reports','customer-reports','fuel-reports','kpi','forecasts','accounting-reports']
    .map(name => `app/src/main/assets/screens/${name}.html`)
];
const failures = [];
for (const file of files) {
  const html = fs.readFileSync(file, 'utf8');
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'file:///android_asset/' });
  const doc = dom.window.document;
  const idCounts = new Map();
  for (const el of doc.querySelectorAll('[id]')) idCounts.set(el.id, (idCounts.get(el.id) || 0) + 1);
  for (const [id, count] of idCounts) if (count > 1) failures.push(`${file}: duplicate id=${id} count=${count}`);
  if (doc.documentElement.getAttribute('dir') !== 'rtl' && !html.includes('direction: rtl')) failures.push(`${file}: RTL contract missing`);
  if (!/theme\.css/.test(html)) failures.push(`${file}: theme.css missing`);
  if (!/reports-runtime\.js/.test(html)) failures.push(`${file}: reports-runtime.js missing`);
  if (!doc.getElementById('reportsDataSource')) failures.push(`${file}: reportsDataSource missing`);
  if (!/loading|skeleton|aria-busy/i.test(html)) failures.push(`${file}: loading state marker missing`);
  if (!/empty|لا توجد|لا يوجد|no data/i.test(html)) failures.push(`${file}: empty state marker missing`);
  if (!/error|خطأ|فشل|catch/i.test(html)) failures.push(`${file}: error state marker missing`);
  if (!/export|تصدير|print|طباعة|pdf|csv/i.test(html)) failures.push(`${file}: export/print action missing`);
  if (!/@media|viewport/i.test(html)) failures.push(`${file}: responsive marker missing`);
  if (!/data-theme="dark"|\[data-theme="dark"\]|toggleTheme|applyTheme/i.test(html)) failures.push(`${file}: dark mode marker missing`);
}
const inventory = fs.readFileSync('app/src/main/assets/screens/inventory-reports.html', 'utf8');
if (!/id="inventorySearch"/.test(inventory) || !/applyInventorySearch/.test(inventory)) failures.push('inventory-reports.html: search workflow missing');
const fuel = fs.readFileSync('app/src/main/assets/screens/fuel-reports.html', 'utf8');
if (!/id="fuelSearch"/.test(fuel) || !/applyFuelSearch/.test(fuel)) failures.push('fuel-reports.html: search workflow missing');
if (!/switchTab\('readings'\)|value="readings"|generateMeterReadingReport|meter_readings/.test(fuel)) failures.push('fuel-reports.html: real readings workflow missing');
const renderTableBody = fuel.slice(fuel.indexOf('function renderTable'), fuel.indexOf('// ===================================================================', fuel.indexOf('function renderTable') + 1));
if (/&& item\\.type === '(?:sale|refill)'/.test(renderTableBody)) failures.push('fuel-reports.html: renderTable item scope regression');
console.log(`UI/WebView DOM checked: ${files.length} screens.`);
if (failures.length) { console.error(failures.join('\n')); process.exit(1); }
console.log('UI/WebView DOM contract PASS.');
