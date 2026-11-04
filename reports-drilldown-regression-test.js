const fs = require('fs');
const path = require('path');
const vm = require('vm');
const nodeAssert = require('assert');
const root = __dirname;
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const bridge = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const screen = read('app/src/main/assets/screens/reports-drilldown.html');
const pageRuntime = read('app/src/main/assets/screens/report-source-trace.js');
const contractRuntime = read('app/src/main/assets/screens/source-trace-contract.js');
function assert(condition, message) { if (!condition) throw new Error(message); }

// The current implementation is Source Trace v2, not the removed getReportDrilldown
// groups/transactions API previously assumed by this test.
assert(/fun resolveSourceTraceContract\(requestJson: JSONObject, currentStationId: Int\)/.test(db), 'SQLite source trace resolver missing');
assert(/@JavascriptInterface\s+fun openReportOperationalDocument\(jsonData: String = "\{\}"\)/.test(bridge), 'Android Source Trace bridge missing');
assert(/db\.resolveSourceTraceContract\(JSONObject\(jsonData\.ifBlank \{ "\{\}" \}\), stationId\)/.test(bridge), 'bridge must resolve against the authenticated station');
assert(/SourceTraceContract\.requireContract\(requestJson, currentStationId\)/.test(db), 'resolver must enforce Source Trace v2 and current station');
for (const table of ['inventory_movements', 'sales_transactions', 'fuel_sales', 'tank_refills', 'journal_entries', 'payments', 'receipts', 'expenses', 'employee_payments', 'cash_movements', 'cash_deposits', 'bank_accounts', 'stocktakes', 'stocktake_details']) {
  assert(db.includes(`"${table}" -> resolveTraceOrigin`), `SQLite resolver mapping missing for ${table}`);
}
for (const predicate of ['origin.station == currentStationId', 'origin.reference == c.referenceCode', 'origin.type == c.documentType']) {
  assert(db.includes(predicate), `SQLite source verification missing: ${predicate}`);
}
assert(screen.includes('source-trace-contract.js'), 'drilldown must load the canonical versioned contract');
assert(screen.includes('report-source-trace.js'), 'drilldown navigation helper missing');
assert(screen.includes('SourceTraceContract.open(contract)'), 'button must ask Android/SQLite to verify the source');
assert(screen.includes('ReportSourceTracePage.destination(verified)'), 'verified origin must navigate to the operational document');
assert(pageRuntime.includes('verified.operational_screen'), 'navigation must use the actual operational_screen response field');
assert(!pageRuntime.includes('result.screen'), 'navigation must not use the obsolete screen response field');
assert(pageRuntime.includes('allowedScreens.has(screen)'), 'navigation target must be allow-listed');

// Execute the destination builder to verify safe path selection and identity handoff.
const context = { window: {}, URLSearchParams, Set, JSON, Error };
vm.createContext(context);
vm.runInContext(pageRuntime, context);
const verified = {
  success: true, verified: true, contract_version: 2,
  source_table: 'sales_transactions', source_id: 41, reference_code: 'SAL-0041',
  station_id: 7, document_type: 'sale', operational_screen: 'sales-log.html',
  date_scope: { from_date: '2026-10-01', to_date: '2026-10-04' }, report_context: { report: 'sales' }
};
const destination = context.window.ReportSourceTracePage.destination(verified);
assert(destination.startsWith('sales-log.html?'), 'verified document must open its authorized screen');
const query = new URLSearchParams(destination.split('?')[1]);
assert(query.get('source_id') === '41' && query.get('reference_code') === 'SAL-0041', 'source identity must survive navigation');
assert(query.get('station_id') === '7' && query.get('contract_version') === '2', 'station and contract version must survive navigation');
assert(query.get('date_scope') === JSON.stringify(verified.date_scope), 'date range must survive navigation');
nodeAssert.throws(() => context.window.ReportSourceTracePage.destination({ ...verified, operational_screen: '../../outside.html' }), /غير مسموح/);
nodeAssert.throws(() => context.window.ReportSourceTracePage.destination({ ...verified, verified: false }), /لم يتم اعتماد/);

// Exercise the same versioned contract the page invokes against a mocked Android gateway.
assert(contractRuntime.includes('const VERSION = 2'), 'JavaScript contract version must match the Android v2 contract');
let bridgePayload;
const traceContext = {
  URLSearchParams,
  window: {
    location: { search: '' },
    history: { replaceState() {} },
    AndroidInterface: {
      openReportOperationalDocument(raw) {
        bridgePayload = JSON.parse(raw);
        return JSON.stringify(verified);
      }
    }
  }
};
vm.createContext(traceContext);
vm.runInContext(contractRuntime, traceContext);
const input = {
  source_table: 'sales_transactions', source_id: 41, reference_code: 'SAL-0041',
  station_id: 7, document_type: 'sale',
  date_scope: { from_date: '2026-10-01', to_date: '2026-10-04' }, report_context: { report: 'sales' }
};
const contract = traceContext.window.SourceTraceContract.build(input);
const bridgeResult = traceContext.window.SourceTraceContract.open(contract);
assert(bridgePayload.contract_version === 2, 'bridge request must use Source Trace v2');
assert(bridgePayload.source_id === 41 && bridgePayload.station_id === 7, 'bridge request must retain the source and station');
assert(bridgeResult.operational_screen === 'sales-log.html', 'bridge must return the verified operational destination');
nodeAssert.throws(() => traceContext.window.SourceTraceContract.build({ ...input, source_id: 0 }), /source_id/);
console.log('Reports Source Trace v2 drill-down regression PASS.');
