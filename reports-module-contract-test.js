const fs = require('fs');
const path = require('path');

const reports = {
  'main.html': ['getDashboardStats'],
  'screens/sales-reports.html': ['generateSalesTransactionReport', 'retrieveInvoice'],
  'screens/eod-report.html': ['getEodReport', 'getBalanceSheet'],
  'screens/inventory-reports.html': ['generateInventoryReport', 'getWarehouses', 'getCategories', 'getInventoryProductDetails'],
  'screens/customer-reports.html': ['generateCRMReport', 'getCustomers'],
  'screens/fuel-reports.html': ['getFuelReport', 'getTanks', 'getPumps'],
  'screens/kpi.html': ['getDashboardStats'],
  'screens/forecasts.html': ['getPredictionRecords'],
  'screens/accounting-reports.html': ['getProfitReport', 'getBalanceSheet', 'getLedgerStats']
};

const kotlin = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');
const failures = [];

for (const [relative, methods] of Object.entries(reports)) {
  const file = path.join('app', 'src', 'main', 'assets', relative);
  const html = fs.readFileSync(file, 'utf8');
  const linkCount = (html.match(/assets-local\/css\/theme\.css/g) || []).length;
  if (linkCount !== 1) failures.push(`${relative}: theme.css link count=${linkCount}`);
  if (!html.includes('reports-runtime.js')) failures.push(`${relative}: missing reports-runtime.js`);
  if (!html.includes('id="reportsDataSource"')) failures.push(`${relative}: missing data source state`);
  const meta = html.match(/name="reports-bridge-methods" content="([^"]*)"/i);
  if (!meta) failures.push(`${relative}: missing reports bridge metadata`);
  else if (meta[1].split(',').filter(Boolean).sort().join(',') !== methods.slice().sort().join(',')) failures.push(`${relative}: bridge metadata mismatch`);
  if (/Math\.random\s*\(/.test(html)) failures.push(`${relative}: Math.random detected`);
  if (/>0%<|>0\.0%<|>0\.00%</.test(html)) failures.push(`${relative}: static trend percentage detected`);
}

for (const method of [...new Set(Object.values(reports).flat())]) {
  const methodPattern = new RegExp(`@JavascriptInterface\\s+fun\\s+${method}\\s*\\(`);
  if (!methodPattern.test(kotlin)) failures.push(`MainActivity: @JavascriptInterface method missing: ${method}`);
}

console.log(`Reports contract checked: ${Object.keys(reports).length} screens.`);
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Reports SQLite/Bridge contract PASS.');
