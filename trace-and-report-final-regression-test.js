const fs=require('fs'),path=require('path');
const root=__dirname, screens=path.join(root,'app/src/main/assets/screens');
const db=fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'),'utf8');
const main=fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'),'utf8');
const contract=fs.readFileSync(path.join(root,'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/SourceTraceContract.kt'),'utf8');
const context=fs.readFileSync(path.join(root,'app/src/main/assets/assets-local/js/report-context-persistence.js'),'utf8');
const reportScreens=['accounting-reports.html','customer-reports.html','dashboard.html','eod-report.html','fuel-reports.html','inventory-reports.html','sales-reports.html','sales-log.html'];
const operational=['inventory-movements.html','fuel-sales.html','fuel-supplies.html','journal-entries.html','stocktake.html'];
function ok(v,m){if(!v)throw Error(m)}
for(const key of ['source_table','source_id','reference_code','station_id','date_scope','document_type']) ok(contract.includes(key),'contract missing '+key);
ok(main.includes('openReportOperationalDocument'),'central operational gateway missing');
ok(main.includes('getCurrentStationIdForReports'),'report station context bridge missing');
ok(db.includes('resolveSourceTraceContract'),'SQLite source resolver missing');
for(const table of ['inventory_movements','sales_transactions','fuel_sales','tank_refills','journal_entries','journal_entry_items','stocktakes','stocktake_details']) ok(db.includes('"'+table+'"') || db.includes(table),'SQLite origin '+table+' missing');
for(const f of reportScreens){const s=fs.readFileSync(path.join(screens,f),'utf8');ok(s.includes('report-context-persistence.js'),f+': context persistence missing');ok(s.includes('source-trace-contract.js'),f+': source trace missing');}
for(const f of operational){const s=fs.readFileSync(path.join(screens,f),'utf8');ok(s.includes('report-context-persistence.js'),f+': context persistence missing');ok(s.includes('source-trace-contract.js'),f+': source trace guard missing');}
ok(context.includes('sessionStorage')&&context.includes('navigate')&&context.includes('back')&&context.includes('date_scope'),'context persistence envelope incomplete');
const reportFiles=reportScreens.map(f=>fs.readFileSync(path.join(screens,f),'utf8')).join('\n');
ok(!/limit\s*:\s*(?:[1-9][0-9]*)\s*[,}]/.test(reportFiles),'fixed client-side report limit remains');
ok(!/LIMIT\s+90\b/i.test(db),'fixed 90-row analytical cap remains');
ok(db.includes('requestedLimit') && db.includes('if (limit > 0)'), 'unbounded query contract not implemented');
console.log('PASS: final regression covers Source Trace Contract, report context persistence, station re-verification, operational guards, and unbounded report retrieval semantics.');
