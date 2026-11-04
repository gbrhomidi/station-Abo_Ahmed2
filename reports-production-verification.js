const fs = require('fs');
const path = require('path');
const read = p => fs.readFileSync(path.join(process.cwd(), p), 'utf8');

const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const bridge = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const main = read('app/src/main/assets/main.html');
const kpi = read('app/src/main/assets/screens/kpi.html');
const sales = read('app/src/main/assets/screens/sales-reports.html');
const inventory = read('app/src/main/assets/screens/inventory-reports.html');
const fuel = read('app/src/main/assets/screens/fuel-reports.html');
const readings = read('app/src/main/assets/screens/meter-readings.html');
const reportTests = read('app/src/test/java/com/aistudio/dieselstationsms/kxmpzq/ReportsPaginationRobolectricTest.kt');

const failures = [];
const need = (text, re, label) => { if (!re.test(text)) failures.push(label); };
const count = (text, re) => (text.match(re) || []).length;
function section(source, start, end) {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from + start.length);
  if (from < 0 || to < 0) throw new Error('Unable to isolate source contract: ' + start);
  return source.slice(from, to);
}

// Historical trends: real SQLite aggregates and valid previous-period handling.
need(db, /getSalesAmountBetween\(stationId, currentStart, currentEnd\)/, 'trends: current SQLite period aggregate missing');
need(db, /getSalesAmountBetween\(stationId, previousStart, previousEnd\)/, 'trends: previous-equivalent-period aggregate missing');
need(db, /require\(!currentStartDate\.after\(currentEndDate\)\)/, 'trends: reversed date-range validation missing');
need(db, /parseDateOnlyStrict/, 'trends: strict date parsing missing');
need(db, /sales_trend_data/, 'trends: structured sales result missing');
need(db, /products_trend_data/, 'trends: structured product result missing');
need(db, /customers_trend_data/, 'trends: structured customer result missing');
need(db, /put\("percentage_change", JSONObject\.NULL\)/, 'trends: zero-baseline null handling missing');
need(main, /stats\.sales_trend_data/, 'main: structured sales trend consumer missing');
need(main, /stats\.products_trend_data/, 'main: structured product trend consumer missing');
need(kpi, /apiCall\('getKPIDashboard', params\)/, 'KPI: dashboard call missing');

// Operational reporting: row and COUNT queries must share station and filter predicates.
const rowsMethod = section(db, 'fun getOperationalRows(', 'private fun getOperationalTotalCount(');
const countMethod = section(db, 'private fun getOperationalTotalCount(', 'fun getOperationalReport(');
for (const [label, source] of [['rows', rowsMethod], ['COUNT', countMethod]]) {
  need(source, /appendMeterReadingFilters\(params, where, args\)/, 'operational ' + label + ': meter-reading filters are not shared');
  need(source, /\(station_id = \? OR station_id IS NULL\)/, 'operational ' + label + ': shared template station scope missing');
  need(source, /scope_sale\.id = deliveries\.sale_id AND scope_sale\.station_id = \?/, 'operational ' + label + ': delivery station scope missing');
}
if (count(rowsMethod, /appendMeterReadingFilters\(params, where, args\)/g) !== 1 ||
    count(countMethod, /appendMeterReadingFilters\(params, where, args\)/g) !== 1) {
  failures.push('operational: meter-reading row and COUNT filters must each call the shared predicate builder exactly once');
}
need(db, /SELECT COUNT\(\*\) FROM \$\{spec\.table\}\$whereSql/, 'operational: SQLite COUNT query missing');
need(db, /LIMIT \? OFFSET \?/, 'operational: parameterized bounded query missing');
need(db, /ORDER BY \$orderBy/, 'operational: deterministic ordering expression missing');
need(db, /\$sortColumn \$sortDirection, id DESC/, 'operational: stable unique pagination tie-breaker missing');
need(db, /lastPageOffset/, 'operational: out-of-range report pages are not clamped');
need(db, /put\("total_count", totalCount\)/, 'operational: total_count metadata missing');
need(db, /put\("total_pages", totalPages\)/, 'operational: total_pages metadata missing');
need(db, /put\("has_next", pageSize > 0 && page < totalPages\)/, 'operational: has_next metadata missing');
need(db, /put\("has_previous", pageSize > 0 && page > 1\)/, 'operational: has_previous metadata missing');

// The operational report must be backed by an explicit table specification and real SQLite regression tests.
need(db, /"meter_readings" -> OperationalTableSpec\([\s\S]{0,1400}table = "meter_readings"/, 'meter readings: operational table specification missing');
need(reportTests, /meterReadingReportUsesSqliteCountAndStableBoundedPages/, 'meter readings: SQLite pagination regression test missing');
need(reportTests, /meterReadingFiltersAreSharedByRowsCountAndAggregates/, 'meter readings: SQLite filter/count parity regression test missing');
need(reportTests, /meterReadingReportCannotReturnRowsForAnotherStationScope/, 'meter readings: station-scope isolation regression test missing');

// Meter-reading filters and full-result aggregates must be computed in SQLite.
need(db, /pump_id = \?/, 'meter readings: pump SQL filter missing');
need(db, /LOWER\(COALESCE\(period, ''\)\) = LOWER\(\?\)/, 'meter readings: period SQL filter missing');
need(db, /COALESCE\(is_balanced, 0\) = 1/, 'meter readings: balanced SQL filter missing');
need(db, /COALESCE\(is_balanced, 0\) = 0/, 'meter readings: unbalanced SQL filter missing');
need(db, /totals\.put\("balanced_count", balancedReadings\)/, 'meter readings: full-result balanced aggregate missing');
need(db, /totals\.put\("unbalanced_count", unbalancedReadings\)/, 'meter readings: full-result unbalanced aggregate missing');

// Fuel report: pagination, search, movement type, and count must use the same SQL predicates.
need(db, /fuelReportConditions/, 'fuel report: shared SQL filter builder missing');
need(db, /getFuelReportTotalCount/, 'fuel report: COUNT query missing');
need(db, /val includeSales = reportType != "refills"/, 'fuel report: movement/sales inclusion logic missing');
need(db, /val includeRefills = reportType != "sales"/, 'fuel report: movement/refills inclusion logic missing');
need(db, /lastPageOffset = if \(totalCount > 0\) \(\(totalCount - 1\) \/ limit\) \* limit else 0/, 'fuel report: out-of-range page clamp missing');
need(db, /val requestedSort = data.optString\("sort_by", "date"\)/, 'fuel report: allow-listed sort input missing');
need(db, /val orderBy = if \(sortColumn == "id"\) "id \$sortDirection, type ASC"/, 'fuel report: stable cross-source sort tie-breaker missing');
need(fuel, /getFuelReportPage/, 'fuel UI: paged SQLite bridge call missing');
need(fuel, /search: search,/, 'fuel UI: search is not sent to SQLite');
need(fuel, /limit: pagination\.pageSize/, 'fuel UI: bounded page size not sent');
need(fuel, /offset: \(pagination\.page - 1\) \* pagination\.pageSize/, 'fuel UI: page offset not sent');
need(fuel, /function goToPage\(page\)[\s\S]{0,220}generateReport\(targetPage\)/, 'fuel UI: page navigation does not preserve requested page');
need(fuel, /function extractFuelReport\(res\)/, 'fuel UI: response metadata normalization missing');
need(fuel, /payload\.total_pages/, 'fuel UI: total_pages is not consumed');
need(fuel, /sort_by: fuelSort\.by/, 'fuel UI: quick-preset sort not sent to SQLite');
need(fuel, /sort_dir: fuelSort\.direction/, 'fuel UI: quick-preset sort direction not sent to SQLite');
need(fuel, /function applyPreset\(presetId\)/, 'fuel UI: preset handler missing');
need(fuel, /pagination\.page = 1;\s*generateReport\(\);\s*haptic\('light'\)/, 'fuel UI: presets do not reload the server report');
if (/filterByPreset\(state\.allRows, state\.activePreset\)/.test(fuel)) failures.push('fuel UI: page-local preset processing remains');
need(fuel, /function applyFuelSearch\(value\)[\s\S]{0,300}generateReport\(\)/, 'fuel UI: search is not reloaded from SQLite');
if (/haystack\.includes\(q\)/.test(fuel)) failures.push('fuel UI: page-local search still bypasses SQL pagination');

// Fuel Readings has its own operational report screen and bridge contract.
need(bridge, /@JavascriptInterface\s+fun getMeterReadingRecords\(/, 'bridge: getMeterReadingRecords missing');
need(bridge, /@JavascriptInterface\s+fun generateMeterReadingReport\(/, 'bridge: generateMeterReadingReport missing');
need(readings, /"reportKind": "operational:MeterReading"/, 'meter readings: operational report configuration missing');
need(readings, /"listMethod": "getMeterReadingRecords"/, 'meter readings: list bridge configuration missing');
need(readings, /invoke\('generate' \+ suffix \+ 'Report'/, 'meter readings: report bridge invocation missing');
need(readings, /limit: REPORT_PAGE_SIZE/, 'meter readings: bounded page size missing');
need(readings, /offset: \(reportPage - 1\) \* REPORT_PAGE_SIZE/, 'meter readings: page offset missing');
need(readings, /renderReportPagination\(reportData\)/, 'meter readings: page navigation is not rendered');
need(readings, /buildReportSummary\(rows, reportData\)/, 'meter readings: summary does not consume SQLite aggregates');
if (/rows = rows\.filter\(row => \{/.test(readings)) failures.push('meter readings: report filters are still applied only to the current page');

// Screens and bridges remain tied to the production SQLite contracts.
need(fuel, /name="reports-bridge-methods" content="[^"]*getFuelReportPage[^"]*getFuelTransactionDetails/, 'fuel UI: bridge metadata is incomplete');
need(main, /sales_trend_data/, 'main: trend consumer marker missing');
need(sales, /total_count/, 'sales report: count metadata missing');
need(inventory, /total_count/, 'inventory report: count metadata missing');

console.log('Reports production verification checks:', 1);
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Reports production verification PASS: filters, station isolation, COUNT parity, pagination and fuel readings.');
