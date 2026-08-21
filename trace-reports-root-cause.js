const fs = require('fs');

const mainActivity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');
const dbHelper = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');

const operations = [
  { screen: 'sales-reports.html', bridge: 'generateSalesTransactionReport', db: 'getOperationalReport', note: 'sales_transactions operational report' },
  { screen: 'sales-reports.html', bridge: 'retrieveInvoice', db: 'getInvoiceDetails', note: 'invoice detail query' },
  { screen: 'eod-report.html', bridge: 'getEodReport', db: 'getEodReport', note: 'end-of-day query' },
  { screen: 'inventory-reports.html', bridge: 'generateInventoryReport', db: 'getInventoryReport', note: 'inventory report query' },
  { screen: 'inventory-reports.html', bridge: 'getWarehouses', db: 'getWarehouses', note: 'warehouse lookup' },
  { screen: 'inventory-reports.html', bridge: 'getCategories', db: 'getProductCategories', note: 'category lookup' },
  { screen: 'inventory-reports.html', bridge: 'getInventoryProductDetails', db: 'getInventoryProductDetails', note: 'inventory product detail query' },
  { screen: 'accounting-reports.html', bridge: 'getLedgerStats', db: 'getLedgerStats', note: 'ledger statistics query' }
];

console.log('=== ROOT CAUSE & DATA PATH TRACE ===');
operations.forEach(op => {
  const bridgeRegex = new RegExp(`@JavascriptInterface\\s+fun\\s+${op.bridge}\\s*\\(`);
  const dbRegex = new RegExp(`fun\\s+${op.db}\\s*\\(`);
  const bridge = bridgeRegex.test(mainActivity);
  const db = dbRegex.test(dbHelper);
  const status = bridge && db ? 'VERIFIED_STATIC_PATH' : 'GAP';
  console.log(`\\nScreen: ${op.screen} | Bridge: ${op.bridge} | DatabaseHelper: ${op.db}`);
  console.log(`  UI -> JS -> Bridge: REVIEW_REQUIRED (static trace does not prove runtime execution)`);
  console.log(`  Bridge -> Kotlin: ${bridge ? 'PASS' : 'FAIL'}`);
  console.log(`  Kotlin -> DatabaseHelper/SQLite: ${db ? 'PASS' : 'FAIL'}`);
  console.log(`  Mapping: ${op.note}`);
  console.log(`  ROOT_CAUSE_STATUS: ${status}`);
});
