const fs = require('fs');
const path = require('path');
const root = process.cwd();
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const bridge = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const main = read('app/src/main/assets/main.html');
const kpi = read('app/src/main/assets/screens/kpi.html');
const sales = read('app/src/main/assets/screens/sales-reports.html');
const inventory = read('app/src/main/assets/screens/inventory-reports.html');
const fuel = read('app/src/main/assets/screens/fuel-reports.html');
const failures = [];
const need = (text, re, label) => { if (!re.test(text)) failures.push(label); };
const forbid = (text, re, label) => { if (re.test(text)) failures.push(label); };

// Historical trends: strict date range, equivalent previous period, real SQL aggregates, and no fake zero baseline.
need(db, /getSalesAmountBetween\(stationId, currentStart, currentEnd\)/, 'trends: current SQLite period aggregate missing');
need(db, /getSalesAmountBetween\(stationId, previousStart, previousEnd\)/, 'trends: previous SQLite period aggregate missing');
need(db, /require\(!currentStartDate\.after\(currentEndDate\)\)/, 'trends: reversed date range validation missing');
need(db, /parseDateOnlyStrict/, 'trends: strict date parsing missing');
need(db, /sales_trend_data/, 'trends: structured sales result missing');
need(db, /products_trend_data/, 'trends: structured product result missing');
need(db, /customers_trend_data/, 'trends: structured customer result missing');
need(db, /put\("percentage_change", JSONObject\.NULL\)/, 'trends: zero-baseline null handling missing');
need(main, /stats\.sales_trend_data/, 'main: structured sales trend is not consumed');
need(main, /stats\.products_trend_data/, 'main: structured product trend is not consumed');
need(kpi, /getDashboardStats\(JSON\.stringify\(trendParams \|\| \{\}\)\)/, 'kpi: report period is not passed to dashboard trends');
need(kpi, /dashResult && dashResult\.data \? dashResult\.data : dashResult/, 'kpi: dashboard envelope is not decoded');
forbid(main, /Number\.isFinite\(Number\(trendData\.percentage_change\)\).*return \{ text: '[^']*0%/, 'main: static trend fallback detected');

// Operational pagination and filters must share the same predicate construction for rows and COUNT.
need(db, /getOperationalTotalCount/, 'operational: COUNT helper missing');
need(db, /SELECT COUNT\(\*\) FROM \$\{spec\.table\}\$whereSql/, 'operational: COUNT query missing');
need(db, /LIMIT \? OFFSET \?/, 'operational: LIMIT/OFFSET query missing');
need(db, /optString\("from_date", params\.optString\("start_date"/, 'operational: start_date alias missing');
need(db, /optString\("to_date", params\.optString\("end_date"/, 'operational: end_date alias missing');
need(db, /payment_method = \?/, 'sales: payment_method SQL filter missing');
need(db, /customer_party_id = \?/, 'sales: customer SQL filter missing');
need(db, /EXISTS \(SELECT 1 FROM products/, 'sales: product/customer search join missing');
need(sales, /sort_by: salesPagination\.sortBy/, 'sales: sort state not sent to SQLite');
need(sales, /search: salesPagination\.search/, 'sales: search state not sent to SQLite');
need(inventory, /sort_by: inventoryPagination\.sortBy/, 'inventory: sort state not sent to SQLite');
need(inventory, /search: String\(document\.getElementById\('inventorySearch'\)/, 'inventory: search not sent to SQLite');
need(db, /totalPages = if \(totalCount == 0\) 0/, 'pagination: empty-page metadata handling missing');
need(db, /has_next/, 'pagination: has_next metadata missing');
need(db, /has_previous/, 'pagination: has_previous metadata missing');

// Fuel report filters and reading path.
need(db, /CREATE TABLE IF NOT EXISTS meter_readings/, 'fuel: meter_readings schema missing');
need(db, /"meter_readings" -> "reading_date"/, 'fuel: reading_date filter missing');
need(bridge, /@JavascriptInterface\s+fun generateMeterReadingReport\(/, 'fuel: meter reading bridge path missing');
need(fuel, /generateMeterReadingReport/, 'fuel UI: meter reading call missing');
need(fuel, /getFuelReportPage/, 'fuel UI: paged report call missing');
need(fuel, /fuel_type_id: fuelType/, 'fuel UI: fuel type not sent to SQLite');
need(fuel, /tank_id: tank/, 'fuel UI: tank filter not sent to SQLite');
need(fuel, /search: String\(document\.getElementById\('fuelSearch'\)/, 'fuel UI: search not sent to SQLite');
need(db, /fuelReportConditions/, 'fuel: shared SQL predicate builder missing');
need(db, /getFuelReportTotalCount/, 'fuel: COUNT predicate missing');
need(db, /ORDER BY date DESC, id DESC/, 'fuel: deterministic order missing');

// No prohibited production substitutes in the changed code.
const changed = [db, bridge, main, kpi, sales, inventory, fuel].join('\n');
forbid(changed, /Math\.random\s*\(/, 'forbidden Math.random detected');
forbid(changed, /mock|dummy|fake|simulated API/i, 'forbidden mock/fake/simulated marker detected');
forbid(changed, /Promise\.resolve\(\s*\[\s*\]\s*\)/, 'empty Promise fallback detected');

console.log('Production root-cause verification checks:', 0 + 1);
if (failures.length) { console.error(failures.join('\n')); process.exit(1); }
console.log('Production root-cause verification PASS: code paths and edge-case guards present.');
