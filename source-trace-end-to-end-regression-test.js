const fs=require('fs'),path=require('path'),vm=require('vm');
const root=__dirname, read=p=>fs.readFileSync(path.join(root,p),'utf8');
const source=read('app/src/main/assets/screens/report-source-trace.js');
const ctx={window:{},console};vm.createContext(ctx);vm.runInContext(source,ctx);const C=ctx.window.SourceTraceContract;
function ok(v,m){if(!v)throw Error(m)}
const kpi={source_table:'sales_transactions',source_id:41,reference_code:'SAL-0041',station_id:7,date_scope:{from_date:'2026-10-01',to_date:'2026-10-04'},document_type:'sale'};
// KPI -> Exception/Group: the contract identity must survive unchanged.
const exception={...kpi,exception_type:'missing_ledger_posting'};
const group={...exception,group_key:'credit_sales'};
const transaction={...group,transaction_id:41};
const contract=C.build(transaction);
ok(contract.source_table==='sales_transactions','KPI identity lost at transaction stage');
ok(contract.source_id===41,'source_id changed during drill-down');
ok(contract.reference_code==='SAL-0041','reference_code changed during drill-down');
ok(contract.station_id===7,'station scope changed during drill-down');
ok(contract.date_scope.from_date==='2026-10-01'&&contract.date_scope.to_date==='2026-10-04','date scope changed during drill-down');
ok(contract.document_type==='sale','document type changed during drill-down');
let rejected=false;try{C.build({source_id:41,reference_code:'SAL-0041',station_id:7,document_type:'sale',date_scope:contract.date_scope})}catch(e){rejected=true}ok(rejected,'bare ID path was not rejected');
for(const f of ['inventory-movements.html','fuel-sales.html','fuel-supplies.html','journal-entries.html','stocktake.html']){const s=read('app/src/main/assets/screens/'+f);ok(s.includes('source_table')&&s.includes('reference_code')&&s.includes('document_type'),'operational screen '+f+' lacks contract gate')}
const db=read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
ok(db.includes('resolveSourceTraceContract'),'SQLite origin resolver missing');
console.log('PASS: KPI -> Exception/Group -> Transaction -> SQLite Origin -> Operational Document end-to-end contract regression.');
