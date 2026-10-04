const fs = require('fs');
const path = require('path');
const vm = require('vm');
const root = __dirname;
const screens = path.join(root, 'app/src/main/assets/screens');
const reportScreens = ['accounting-reports.html','customer-reports.html','fuel-reports.html','inventory-reports.html','sales-reports.html','dashboard.html'];
const operational = ['inventory-movements.html','fuel-sales.html','fuel-supplies.html','journal-entries.html','stocktake.html'];
const contractSource = fs.readFileSync(path.join(screens,'source-trace-contract.js'),'utf8');
const ctx={window:{location:{search:'',pathname:'/test'}},console,URLSearchParams}; vm.createContext(ctx); vm.runInContext(contractSource,ctx);
const C=ctx.window.SourceTraceContract;
if(C.VERSION!==2) throw Error('unexpected contract version');
for(const file of reportScreens){
  const s=fs.readFileSync(path.join(screens,file),'utf8');
  if(!s.includes('source-trace-contract.js')) throw Error(file+': shared contract not loaded');
}
for(const file of operational){
  const s=fs.readFileSync(path.join(screens,file),'utf8');
  if(!s.includes('source-trace-contract.js')) throw Error(file+': operational entry guard not loaded');
}
const valid=C.build({source_table:'sales_transactions',source_id:12,reference_code:'SAL-0012',station_id:4,document_type:'sale',date_scope:{from_date:'2026-10-01',to_date:'2026-10-05'}});
if(valid.source_id!==12 || valid.station_id!==4) throw Error('valid contract mutation');
for(const missing of ['source_table','source_id','reference_code','station_id','document_type']){
  const bad={...valid}; delete bad[missing]; let rejected=false; try{C.build(bad)}catch(e){rejected=true} if(!rejected) throw Error('missing '+missing+' was accepted');
}
console.log('PASS: Trace Integrity coverage checks report entry points, operational contract fields, valid contract identity, and mandatory-field rejection.');
