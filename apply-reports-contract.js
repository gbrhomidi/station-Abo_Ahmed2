const fs = require('fs');
const path = require('path');

const screens = {
  'main.html': ['getDashboardStats'],
  'screens/sales-reports.html': ['generateSalesTransactionReport', 'retrieveInvoice', 'getShifts'],
  'screens/eod-report.html': ['getEodReport', 'getBalanceSheet'],
  'screens/inventory-reports.html': ['generateInventoryReport', 'getWarehouses', 'getCategories', 'getInventoryProductDetails'],
  'screens/customer-reports.html': ['generateCRMReport', 'getCustomers'],
  'screens/fuel-reports.html': ['getFuelReport', 'getTanks', 'getPumps'],
  'screens/kpi.html': ['getDashboardStats'],
  'screens/forecasts.html': ['getPredictionRecords'],
  'screens/accounting-reports.html': ['getProfitReport', 'getBalanceSheet', 'getLedgerStats']
};

const banner = `    <div id="reportsDataSource" class="report-source-banner" data-state="unavailable" role="status"><i class="fas fa-plug-circle-xmark" aria-hidden="true"></i><span>جاري التحقق من مصدر بيانات SQLite...</span></div>`;
const runtime = `    <script src="file:///android_asset/assets-local/js/reports-runtime.js" defer></script>`;
let updated = 0;

for (const [relative, methods] of Object.entries(screens)) {
  const filePath = path.join('app', 'src', 'main', 'assets', relative);
  let html = fs.readFileSync(filePath, 'utf8');
  const meta = `    <meta name="reports-bridge-methods" content="${methods.join(',')}">`;
  if (!html.includes('name="reports-bridge-methods"')) {
    html = html.replace(/<\/head>/i, `${meta}\n${runtime}\n</head>`);
  }
  if (!html.includes('id="reportsDataSource"')) {
    html = html.replace(/<body([^>]*)>/i, `<body$1>\n${banner}`);
  }
  fs.writeFileSync(filePath, html);
  updated++;
}

console.log(`Applied reports data contract to ${updated} screens.`);
