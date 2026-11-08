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
assert.ok(repository.includes('readDebts(helper.readableDatabase, stationId)'), 'summary must use the invoice-level canonical read');
assert.ok(repository.includes('val remainingExpr = if ("remaining_amount" in s)'), 'saved SQLite remaining balance remains the preferred balance source');
assert.ok(repository.includes('val today = LocalDate.parse(asOfDate)'), 'maturity classification must use one explicit as-of date');

assert.ok(database.includes('DebtDueDateRepository.getCustomerDebtSummary(this, stationId, today)'), 'dashboard debt KPIs must use the central repository');
assert.ok(database.includes('val reportRows = when (reportType)'), 'CRM report result must be decorated from one report projection');
assert.ok(database.includes('reportType == "customers"'), 'customer CRM report must receive central balances');

assert.ok(activity.includes('db.getDashboardStatsForDate(stationId, params, businessDate)'), 'dashboard must use one business-date snapshot');
assert.ok(activity.includes('fun getCustomerDebtSummary()'), 'summary bridge must be exposed to the customer report');
assert.ok(activity.includes('DebtDueDateRepository.getCustomerDebtSummary(db, stationId, businessDate)'), 'summary bridge must return the repository result');

assert.ok(customerReport.includes('case \'getCustomerDebtSummary\''), 'customer report must call the summary bridge');
assert.ok(customerReport.includes('const debtByCustomerId = new Map('), 'customer rows must be mapped by their party ID');
assert.ok(customerReport.includes('debt_summary_source: \'DebtDueDateRepository.readDebts\''), 'customer balances must be explicitly marked as repository sourced');
assert.ok(customerReport.includes('current_balance: balance'), 'denormalized party balance must be replaced');

assert.ok(dashboard.includes('data.customer_debt_summary || {}'), 'legacy dashboard must read the central summary');
assert.ok(dashboard.includes('data.customer_debts'), 'legacy dashboard must remain compatible with the canonical dashboard key');

console.log('Customer-debt central source, dashboard and CRM projection contracts passed.');
