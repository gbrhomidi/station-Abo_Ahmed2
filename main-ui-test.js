const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('app/src/main/assets/main.html', 'utf8');
const databaseHelper = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');
const mainActivity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');
const dom = new JSDOM(html);
const document = dom.window.document;

console.log("=== UI Tests for Main Dashboard (main.html) ===");

let passed = 0;
let total = 0;

function assert(name, condition) {
    total++;
    if (condition) {
        console.log(`✅ PASS: ${name}`);
        passed++;
    } else {
        console.log(`❌ FAIL: ${name}`);
    }
}

// 1. Check Math.random() is completely removed
assert('No Math.random() in code', !html.includes('Math.random()'));

// 2. Check hardcoded progress is removed
assert('No hardcoded progress (progress: 70)', !html.includes('progress: 70'));

// 3. Check dynamic data binding
assert('Binds products_trend', html.includes('stats.products_trend'));
assert('Binds sales_trend', html.includes('stats.sales_trend'));
assert('Binds occupancy_rate', html.includes('stats.occupancy_rate'));
assert('Binds total_products', html.includes('stats.total_products'));

// 4. Check Bridge Call and response contract
assert('Calls getDashboardStats', html.includes('getDashboardStats'));
assert('Uses handleAndroidResult wrapper', html.includes('handleAndroidResult'));
assert('MainActivity returns dashboard data through DatabaseHelper', mainActivity.includes('db.getDashboardStats(stationId)'));
assert('MainActivity wraps dashboard response under data', mainActivity.includes('dataResponse(stats)'));

// 5. Check the occupancy KPI is based on real tank columns, not a heuristic
assert('DatabaseHelper queries tank quantity and capacity', databaseHelper.includes('SUM(current_quantity)') && databaseHelper.includes('SUM(capacity_liters)'));
assert('DatabaseHelper scopes tank KPI by station', databaseHelper.includes('FROM tanks WHERE station_id=? AND is_deleted=0'));
assert('Dashboard labels occupancy as actual tank fill', html.includes('من الكمية والسعة الفعلية'));
assert('Dashboard does not contain the old product-count occupancy formula', !databaseHelper.includes('totalProducts * 100.0) / 50.0'));

console.log(`\nResults: ${passed}/${total} passed.`);
if (passed !== total) {
    process.exit(1);
}
