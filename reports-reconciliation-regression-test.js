const fs = require('fs');
const root = __dirname;
const db = fs.readFileSync(`${root}/app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt`, 'utf8');
const bridge = fs.readFileSync(`${root}/app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt`, 'utf8');
const screen = fs.readFileSync(`${root}/app/src/main/assets/screens/reports-reconciliation.html`, 'utf8');
const dashboard = fs.readFileSync(`${root}/app/src/main/assets/screens/dashboard.html`, 'utf8');
function ok(c,m){if(!c) throw new Error(m); console.log('PASS',m)}
ok(db.includes('fun getReportsReconciliation'), 'SQLite reconciliation method exists');
ok(db.includes('SALE_PAYMENT_MISMATCH') && db.includes('CREDIT_SALE_WITHOUT_CUSTOMER'), 'sales/payment and credit exceptions exist');
ok(db.includes('UNBALANCED_JOURNAL') && db.includes('INVENTORY_MOVEMENT_ARITHMETIC_ERROR'), 'accounting/inventory exceptions exist');
ok(db.includes('SHIFT_SALES_MISMATCH') && db.includes('OPEN_SHIFT'), 'shift exceptions exist');
ok(db.includes('date(created_at) BETWEEN date(?) AND date(?)'), 'sales reconciliation is date-scoped');
ok(bridge.includes('fun getReportsReconciliation'), 'Android bridge exposes reconciliation');
ok(screen.includes('ReportsRuntime.validateReportFilters') && screen.includes('return_all:true'), 'screen uses unified filters and full-result contract');
ok(screen.includes('AndroidInterface.getReportsReconciliation'), 'screen calls real Android bridge');
ok(dashboard.includes('reports-reconciliation.html'), 'dashboard links reconciliation screen');
console.log('Reports reconciliation regression PASS.');
