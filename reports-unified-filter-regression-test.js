const fs = require('fs');
const db = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt','utf8');
const runtime = fs.readFileSync('app/src/main/assets/assets-local/js/reports-runtime.js','utf8');
const screens = ['sales-reports.html','inventory-reports.html','fuel-reports.html','customer-reports.html','accounting-reports.html','eod-report.html','forecasts.html'];
const failures=[];
const need=(text,re,msg)=>{if(!re.test(text)) failures.push(msg)};
need(runtime,/normalizeReportFilters/,'runtime normalization missing');
need(runtime,/validateReportFilters/,'runtime filter validation missing');
need(runtime,/out\.return_all = source\.return_all !== false/,'runtime default full dataset missing');
need(db,/val returnAll = data\.optBoolean\("return_all", false\)/,'sales/fuel return_all contract missing');
need(db,/val returnAll = params\.optBoolean\("return_all", false\)/,'journal return_all contract missing');
need(db,/return if \(returnAll\) JSONObject\(\)\.apply/,'full dataset response envelope missing');
need(db,/getFuelReportTotalCount\(data, stationScopeId\)/,'fuel count must use same filters');
need(db,/val rowsSql = if \(returnAll\) sql else "\$sql LIMIT \? OFFSET \?"/,'inventory full dataset SQL path missing');
for (const name of screens) {
 const html=fs.readFileSync('app/src/main/assets/screens/'+name,'utf8');
 if(/limit\s*:\s*(?:50|100|200|500|1000|5000)/.test(html)) failures.push(`${name}: fixed query limit remains`);
 if(/slice\(0\s*,\s*(?:50|100|200|500|1000|5000)\)/.test(html)) failures.push(`${name}: fixed presentation cap remains`);
}
if(failures.length){console.error(failures.join('\n'));process.exit(1)}
console.log('Unified report filter/full-dataset regression PASS.');
