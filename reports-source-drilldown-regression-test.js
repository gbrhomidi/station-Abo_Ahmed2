const fs=require('fs'),path=require('path');const root=__dirname;
const db=fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'),'utf8');
const bridge=fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'),'utf8');
function ok(c,m){if(!c)throw Error(m);console.log('PASS',m)}
ok(db.includes('fun getReportSourceDrilldown(data: JSONObject, stationScopeId: Int)'), 'canonical source drilldown exists');
ok(db.includes('fun getReportExceptions(data: JSONObject = JSONObject(), stationScopeId: Int)'), 'exception contract exists');
ok(bridge.includes('fun getReportSourceDrilldown(jsonData: String = "{}")'), 'source drilldown bridge exists');
ok(bridge.includes('fun getReportExceptions(jsonData: String = "{}")'), 'exceptions bridge exists');
const start=db.indexOf('fun getReportSourceDrilldown'), end=db.indexOf('fun getFuelReport',start);const m=db.slice(start,end);
ok(!/LIMIT\s+\d+/i.test(m),'no fixed LIMIT in canonical drilldown');
for(const x of ['inventory','fuel','accounting','exception','same_report_filters','inventory_movements','journal_entries','sales_transactions','tank_refills'])ok(m.includes(x),`contract contains ${x}`);
for(const f of ['inventory-reports.html','fuel-reports.html','accounting-reports.html','reports-reconciliation.html','reports-drilldown.html']){const s=fs.readFileSync(path.join(root,'app/src/main/assets/screens',f),'utf8');ok(s.includes('report-drilldown.js')||f==='reports-drilldown.html',`${f} participates in drilldown contract`)}
console.log('Reports source drill-down regression PASS.');
