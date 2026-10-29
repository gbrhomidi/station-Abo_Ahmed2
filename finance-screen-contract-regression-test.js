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
  'requestOrReversePaymentRecord', 'voidReceiptRecord', 'reverseExpenseRecord',
  'requestOrReverseExpenseRecord', 'reverseCashMovementRecord',
  'reverseEmployeePaymentRecord', 'postFinanceJournal', 'journal_entry_id'
]) if (!db.includes(required)) throw new Error(`DatabaseHelper missing: ${required}`);
for (const required of [
  'getFinanceIntegritySnapshot', 'requestOrReversePaymentRecord', 'voidReceiptRecord',
  'requestOrReverseExpenseRecord', 'reverseCashMovementRecord', 'reverseEmployeePaymentRecord'
]) if (!main.includes(required)) throw new Error(`MainActivity missing: ${required}`);

// Reversal entry points exposed to JavaScript must go through approval-aware wrappers.
// Direct core methods remain internal to DatabaseHelper and must not be exposed by MainActivity.
for (const forbidden of ['fun reversePaymentRecord(', 'fun reverseExpenseRecord(']) {
  if (main.includes(forbidden)) throw new Error(`MainActivity exposes an unguarded reversal API: ${forbidden}`);
}
if (!db.includes('p.station_id = ?')) throw new Error('Payments are not directly station scoped');
if (!db.includes('r.station_id = ?')) throw new Error('Receipts are not directly station scoped');
if (!db.includes('je.station_id=?')) throw new Error('Trial-balance station scope is missing');
if (!db.includes('je.station_id = ?')) throw new Error('Balance-sheet station scope is missing');


// Additional contracts for the report-driven expansion of financial workflows.
const accountingReports = fs.readFileSync(path.join(root, 'accounting-reports.html'), 'utf8');
for (const required of [
  'expandedAccountingWorkspace',
  'supplier_aging',
  'supplier_statement',
  'purchase_invoice_create',
  'purchase_invoice_post',
  'purchase_return_create',
  'purchase_return_post',
  'cash_flow_statement',
  'equity_changes',
  'vat_return',
  'tax_profile_save',
  'account_classification_save'
]) if (!accountingReports.includes(required)) {
  throw new Error('Accounting reports UI is missing expanded workflow: ' + required);
}

const bankReconciliation = fs.readFileSync(path.join(root, 'bank-reconciliation.html'), 'utf8');
for (const required of [
  'bankStatementImportWorkspace',
  'bank_statement_import',
  'bank_statement_batches',
  'bank_statement_match',
  'FileReader',
  'data-open-import'
]) if (!bankReconciliation.includes(required)) {
  throw new Error('Bank statement import/reconciliation UI is missing: ' + required);
}
if (bankReconciliation.includes("$('bsiStart').value=today")) {
  throw new Error('Bank statement import must not assume imported statement dates are today');
}

for (const required of [
  'val linkedPaymentId = data.optLong("payment_id", 0L)',
  'sourceJournalValid',
  'journalId = linkedPaymentJournalId',
  'linkedPaymentId <= 0L && cashAmount > 0.0',
  'تحصيل دين العميل يجب أن يمر عبر شاشة ديون العملاء',
  'COALESCE(r.payment_id,0)>0 AND (p.id IS NULL OR pje.id IS NULL)'
]) if (!db.includes(required)) {
  throw new Error('Receipt/payment double-posting guard is missing: ' + required);
}

console.log('PASS: finance/accounting screen contracts, payment-receipt single-posting, expanded accounting UI, and bank-statement import/reconciliation guards');
