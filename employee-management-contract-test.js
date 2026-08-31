const fs = require('node:fs');
const assert = require('node:assert/strict');
const html = fs.readFileSync('app/src/main/assets/screens/employees.html', 'utf8');
const db = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');
const main = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

for (const field of ['bank_name','bank_account','tax_deduction','other_deductions','extra_data']) assert.match(html, new RegExp(`\\['${field}'`));
assert.match(html, /\['national_id','text'/);
assert.match(html, /\['employee_code','text'.*1,1/s);
assert.doesNotMatch(html, /\['party_id'/);
assert.match(html, /type="date"/);
assert.match(html, /pattern="\[\+0-9 \(\)-\]\{7,\}"/);
assert.match(html, /data-screen="attendance\.html"/);
assert.match(html, /data-screen="payroll\.html"/);
assert.match(html, /saveEmployeePerformance/);
assert.match(html, /status='terminated'/);
assert.match(html, /أرشفة هذا الموظف/);
assert.match(db, /CREATE TABLE IF NOT EXISTS employee_performance/);
assert.match(db, /CREATE TABLE IF NOT EXISTS employee_audit_log/);
assert.match(db, /migrateV32ToV33/);
assert.match(db, /fun saveEmployeePerformance/);
assert.match(db, /fun getEmployeePerformance/);
assert.doesNotMatch(db, /"employees" -> OperationalTableSpec\([\s\S]{0,500}?party_id/);
assert.match(main, /fun saveEmployeePerformance/);
assert.match(main, /fun getEmployeePerformance/);
console.log('Employee management contract PASS.');

// price-change-log screen contract: protects the custom DOM structure expected by its report logic.
const price = fs.readFileSync('app/src/main/assets/screens/price-change-log.html', 'utf8');
assert.match(price, /id=['"]reportTable['"]/);
assert.match(price, /exportRows/);
console.log('Price-change-log contract PASS.');

