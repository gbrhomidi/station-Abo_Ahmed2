const fs = require('fs');
const path = require('path');

const reports = {
  'main.html': ['getDashboardStats'],
  'screens/sales-reports.html': [
    'generateSalesTransactionReport', 'retrieveInvoice', 'getProducts', 'getCustomers'
  ],
  'screens/eod-report.html': ['generateSalesTransactionReport', 'getEodReport'],
  'screens/inventory-reports.html': [
    'generateInventoryReport', 'getWarehouses', 'getCategories', 'getInventoryProductDetails'
  ],
  'screens/customer-reports.html': [
    'generateCRMReport', 'getPartyTypes', 'getCustomerDebts',
    'getCustomerDebtSummary', 'getCustomerDebtReportBundle',
    'getPartyCrmBundle', 'getPartyCrmBundleAsync', 'updatePartyCreditLimit'
  ],
  'screens/fuel-reports.html': [
    'getFuelReportPage', 'getFuelReport', 'getTanks', 'getFuelTypes', 'getFuelTransactionDetails'
  ],
  'screens/kpi.html': ['getKPIDashboard', 'getKPIDetails'],
  'screens/forecasts.html': ['getPredictionRecords', 'deletePredictionRecord'],
  'screens/accounting-reports.html': [
    'getKPIDashboard', 'getEodReport', 'getBalanceSheet', 'getChartTrialBalance',
    'getLedgerEntries', 'getProfitReport', 'getJournalEntries', 'getOverdueReport', 'saveBalanceSheet'
  ]
};

const kotlin = fs.readFileSync(
  'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8'
);
const failures = [];

for (const [relative, methods] of Object.entries(reports)) {
  const file = path.join('app', 'src', 'main', 'assets', relative);
  const html = fs.readFileSync(file, 'utf8');
  const matches = html.match(/<meta\s+name="reports-bridge-methods"\s+content="([^"]*)"\s*>/gi) || [];
  if (matches.length !== 1) {
    failures.push(`${relative}: expected exactly one reports-bridge-methods declaration, found ${matches.length}`);
    continue;
  }
  const declared = matches[0].match(/content="([^"]*)"/i)?.[1] || '';
  if (declared.split(',').filter(Boolean).sort().join(',') !== methods.slice().sort().join(',')) {
    failures.push(`${relative}: bridge metadata mismatch; declared=[${declared}]`);
  }
  const executableMarkup = html.replace(/<meta\s+name="reports-bridge-methods"[^>]*>/gi, '');
  for (const method of methods) {
    if (!executableMarkup.includes(method)) failures.push(`${relative}: bridge method is declared but not referenced by the screen: ${method}`);
    const methodPattern = new RegExp(`@JavascriptInterface\\s+fun\\s+${method}\\s*\\(`);
    if (!methodPattern.test(kotlin)) failures.push(`MainActivity: @JavascriptInterface method missing: ${method}`);
  }
  if (!/<\/body>/i.test(html) || !/<\/html>/i.test(html)) {
    failures.push(`${relative}: incomplete HTML closing structure`);
  }
}

const meterReadings = fs.readFileSync(
  path.join('app', 'src', 'main', 'assets', 'screens', 'meter-readings.html'), 'utf8'
);
for (const [label, condition] of [
  ['meter-readings report configuration', meterReadings.includes('"reportKind": "operational:MeterReading"')],
  ['meter-readings SQLite list bridge', meterReadings.includes('"listMethod": "getMeterReadingRecords"')],
  ['meter-readings dynamic report bridge', meterReadings.includes("invoke('generate' + suffix + 'Report'")],
  ['meter-readings bounded page size', meterReadings.includes('limit: REPORT_PAGE_SIZE')],
  ['meter-readings page offset', meterReadings.includes('offset: (reportPage - 1) * REPORT_PAGE_SIZE')],
  ['meter-readings pagination renderer', meterReadings.includes('function renderReportPagination(')]
]) {
  if (!condition) failures.push(`meter-readings: ${label} missing`);
}
for (const method of ['getMeterReadingRecords', 'generateMeterReadingReport']) {
  const methodPattern = new RegExp(`@JavascriptInterface\\s+fun\\s+${method}\\s*\\(`);
  if (!methodPattern.test(kotlin)) failures.push(`MainActivity: @JavascriptInterface method missing: ${method}`);
}

console.log(`Reports bridge contract checked: ${Object.keys(reports).length} screens + Fuel Readings.`);
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Reports bridge and Fuel Readings contract PASS.');
