const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, 'app', 'src', 'main', 'assets', 'screens');
const screens = [
  'accounting-reports.html','balance-sheet.html','banks-accounts.html','chart-of-accounts.html',
  'employee-payments.html','expense-categories.html','expenses.html','journal-entries.html',
  'ledger.html','payments.html','receipts.html','vehicle-expenses.html','cashboxes.html',
  'cash-movements.html','cash-deposits.html'
];
const critical = new Set(['payments.html','receipts.html','expenses.html','employee-payments.html','cash-movements.html']);
for (const name of screens) {
  const file = path.join(root, name);
  if (!fs.existsSync(file)) throw new Error(`Missing finance screen: ${name}`);
  const text = fs.readFileSync(file, 'utf8');
  if (!text.includes('report-context-persistence.js')) throw new Error(`${name}: missing report context persistence`);
  if (!text.includes('source-trace-contract.js')) throw new Error(`${name}: missing source trace contract`);
  if (!text.includes('finance-integrity-runtime.js')) throw new Error(`${name}: missing finance integrity runtime`);
  if (critical.has(name) && /operationalDelete\([^)]*\b(payments|receipts|expenses|employee_payments|cash_movements)\b/.test(text)) {
    throw new Error(`${name}: destructive generic operational delete remains`);
  }
}

const db = fs.readFileSync(path.join(__dirname,'app','src','main','java','com','aistudio','dieselstationsms','kxmpzq','DatabaseHelper.kt'),'utf8');
const main = fs.readFileSync(path.join(__dirname,'app','src','main','java','com','aistudio','dieselstationsms','kxmpzq','MainActivity.kt'),'utf8');
for (const required of [
  'ensureFinanceIntegritySchema', 'getFinanceIntegritySnapshot', 'reversePaymentRecord',
  'voidReceiptRecord', 'reverseExpenseRecord', 'reverseCashMovementRecord',
  'reverseEmployeePaymentRecord', 'postFinanceJournal', 'journal_entry_id'
]) if (!db.includes(required)) throw new Error(`DatabaseHelper missing: ${required}`);
for (const required of [
  'getFinanceIntegritySnapshot', 'reversePaymentRecord', 'voidReceiptRecord',
  'reverseExpenseRecord', 'reverseCashMovementRecord', 'reverseEmployeePaymentRecord'
]) if (!main.includes(required)) throw new Error(`MainActivity missing: ${required}`);
if (!db.includes('p.station_id = ?')) throw new Error('Payments are not directly station scoped');
if (!db.includes('r.station_id = ?')) throw new Error('Receipts are not directly station scoped');
if (!db.includes('je.station_id=?')) throw new Error('Trial-balance station scope is missing');
if (!db.includes('je.station_id = ?')) throw new Error('Balance-sheet station scope is missing');

console.log('PASS: finance/accounting screen contracts and destructive-operation guards');
