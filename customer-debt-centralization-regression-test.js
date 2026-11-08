'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = __dirname;
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

const repository = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/DebtDueDateRepository.kt');
const database = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const activity = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const customerReport = read('app/src/main/assets/screens/customer-reports.html');
const dashboard = read('app/src/main/assets/screens/dashboard.html');

assert.ok(repository.includes('fun getCustomerDebtSummary('), 'one repository summary must exist');
assert.ok(repository.includes('fun getCustomerOutstandingBalance('), 'SMS/account balance contexts must use the canonical repository');
assert.ok(database.includes('stats.put("total_due", customerDebtSummary.optDouble("total_customer_debts", 0.0))'), 'legacy dashboard total_due must be a canonical alias');
assert.ok(database.includes('reportType in setOf("customers", "balance")'), 'CRM balance and customer reports must use canonical debt projections');
assert.ok(database.includes('DebtDueDateRepository.getCustomerOutstandingBalance(this, stationScopeId, customerId)'), 'credit-sale SMS balance must be canonical');
assert.ok(database.includes('DebtDueDateRepository.getCustomerOutstandingBalance(\n                    this, stationScopeId, customerId'), 'payment SMS balance must be canonical');
assert.ok(repository.includes('fun getCustomerDebtReportBundle('), 'one repository report bundle must exist');
assert.ok(repository.includes('val summary = buildCustomerDebtSummary(rows, stationId, asOfDate, today)'), 'summary and invoice-aging must derive from one row snapshot');
assert.ok(repository.includes('readDebts(helper.readableDatabase, stationId)'), 'summary must use the invoice-level canonical read');
assert.ok(repository.includes('val remainingExpr = if ("remaining_amount" in s)'), 'saved SQLite remaining balance remains the preferred balance source');
assert.ok(repository.includes('val today = LocalDate.parse(asOfDate)'), 'maturity classification must use one explicit as-of date');

assert.ok(database.includes('DebtDueDateRepository.getCustomerDebtSummary(this, stationId, today)'), 'dashboard debt KPIs must use the central repository');
assert.ok(database.includes('val reportRows = when (reportType)'), 'CRM report result must be decorated from one report projection');
assert.ok(database.includes('reportType in setOf("customers", "balance")'), 'customer and explicit balance CRM reports must receive central balances');

assert.ok(activity.includes('db.getDashboardStatsForDate(stationId, params, businessDate)'), 'dashboard must use one business-date snapshot');
assert.ok(activity.includes('fun getCustomerDebtSummary()'), 'summary bridge must be exposed to the customer report');
assert.ok(activity.includes('fun getCustomerDebtReportBundle(fromDate: String?, toDate: String?)'), 'one report bundle bridge must expose summary and invoices');
assert.ok(activity.includes('DebtDueDateRepository.getCustomerDebtReportBundle('), 'bridge must invoke one-snapshot repository bundle');
assert.ok(activity.includes('DebtDueDateRepository.getCustomerDebtSummary(db, stationId, businessDate)'), 'summary bridge must return the repository result');

assert.ok(customerReport.includes('case \'getCustomerDebtReportBundle\''), 'customer report must call the one-snapshot report bundle');
assert.ok(customerReport.includes('const debtSummary = debtBundleResult.data.summary;'), 'summary must come from the report bundle');
assert.ok(customerReport.includes('runtimeState.rawAgingDebts = debtBundleResult.data.invoices;'), 'Aging invoices must come from the same row snapshot');
assert.ok(customerReport.includes('const debtByCustomerId = new Map('), 'customer rows must be mapped by their party ID');
assert.ok(customerReport.includes('debt_summary_source: \'DebtDueDateRepository.readDebts\''), 'customer balances must be explicitly marked as repository sourced');
assert.ok(customerReport.includes('current_balance: balance'), 'denormalized party balance must be replaced');

assert.ok(dashboard.includes('data.customer_debt_summary || {}'), 'legacy dashboard must read the central summary');
assert.ok(dashboard.includes('data.customer_debts'), 'legacy dashboard must remain compatible with the canonical dashboard key');

console.log('Customer-debt central source, dashboard and CRM projection contracts passed.');
