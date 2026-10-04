#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const root = __dirname;
const db = fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'),'utf8');
const main = fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'),'utf8');
const evidence = fs.readFileSync(path.join(root,'app/src/main/assets/assets-local/js/report-evidence-runtime.js'),'utf8');
function assert(c,m){if(!c) throw new Error(m)}
assert(db.includes('fun getReportEvidence(data: JSONObject, stationScopeId: Int): JSONObject'),'missing centralized report evidence method');
assert(db.includes('payment_reconciliation_delta'),'sales payment reconciliation missing');
assert(db.includes('reversed_without_origin'),'accounting reversal-origin check missing');
assert(db.includes('posted journal entries only; revenue and expense are classified from accounts.account_type'),'profit source contract missing');
assert(!/COALESCE\(SUM\(p\.amount\).*FROM sales_transactions s\s+LEFT JOIN payments p/s.test(db),'EOD still vulnerable to payment-row multiplication');
assert(main.includes('fun getReportEvidence(jsonData: String = "{}"): String'),'missing evidence bridge');
assert(main.includes('db.getProfitReport(from, to, stationId)'),'profit bridge does not use accounting-backed report');
assert(evidence.includes('getReportEvidence'),'export evidence runtime missing bridge');
for (const name of ['sales-reports.html','fuel-reports.html','accounting-reports.html','customer-reports.html']) {
 const s=fs.readFileSync(path.join(root,'app/src/main/assets/screens',name),'utf8');
 assert(s.includes('report-evidence-runtime.js'), name+' missing evidence runtime');
}
const sales=fs.readFileSync(path.join(root,'app/src/main/assets/screens/sales-reports.html'),'utf8');
assert(sales.includes('loadAllSalesRowsForExport'),'sales export still limited to visible page');
assert(sales.includes('limit: 0'),'sales export does not request full source set');
assert(sales.includes('ReportEvidenceRuntime.csvLines'),'sales export does not carry evidence manifest');
console.log('PASS: Evidence & Reconciliation source contract, station/date scope, accounting reversal checks, payment reconciliation, and full-source export evidence are wired.');
