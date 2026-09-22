const assert = require('node:assert/strict');
const fs = require('node:fs');

const customers = fs.readFileSync('app/src/main/assets/screens/customers.html', 'utf8');
const activity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');
const databaseHelper = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');

function between(source, start, end) {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `Missing ${start}`);
  const to = source.indexOf(end, from + start.length);
  assert.notEqual(to, -1, `Missing end marker ${end}`);
  return source.slice(from, to);
}

const customerDetails = between(customers, 'async function loadCustomerDetails', '// ============================================================\n        // عرض جهات الاتصال');
const saveCustomer = between(customers, 'async function saveCustomer', 'async function archiveCustomer');
const insertParty = between(databaseHelper, 'fun insertParty', 'fun updateParty');
const updateParty = between(databaseHelper, 'fun updateParty', 'fun deleteParty');
const updateCreditLimit = between(databaseHelper, 'fun updatePartyCreditLimit', 'fun getPartyCrmBundle');
const updatePartyContact = between(databaseHelper, 'fun updatePartyContact', 'fun deletePartyContact');
const deletePartyContact = between(databaseHelper, 'fun deletePartyContact', 'fun getPartyAddresses');
const updatePartyAddress = between(databaseHelper, 'fun updatePartyAddress', 'fun deletePartyAddress');
const deletePartyAddress = between(databaseHelper, 'fun deletePartyAddress', '// ========================================================================\n    // دوال ديون العملاء');
const getCustomerDebts = between(databaseHelper, 'fun getCustomerDebts(partyId', 'fun getCustomerDebts(fromDate');

assert.match(customers, /getPartyCrmBundleAsync/);
assert.match(customers, /getCustomerLedgerAsync/);
assert.match(customers, /getCustomerSalesAsync/);
assert.match(customers, /window\.__stationBridgeResolveCustomerDetails/);
assert.match(customers, /function setCustomerDetailsLoading/);
assert.match(customers, /customerDetailsStatus/);
assert.match(customers, /function cancelPendingCustomerDetails\(/);
assert.match(customers, /cancelPendingCustomerDetails\('تم استبدال طلب تفاصيل العميل بطلب أحدث'\)/);
assert.match(customers, /function closeCustomerModal\(\)[\s\S]*?cancelPendingCustomerDetails\(\)/);
assert.doesNotMatch(customerDetails, /showLoading\(true\)/);
assert.match(customerDetails, /invokeCustomerDetailsAsync\('get_customer_bundle'/);
assert.match(customerDetails, /requestVersion !== customerDetailsRequestSequence/);
assert.doesNotMatch(saveCustomer, /station_id\s*:/);
assert.match(customers, /id="stationId" readonly/);

assert.match(activity, /fun getPartyCrmBundleAsync\(requestId: String, id: Long\)/);
assert.match(activity, /fun getCustomerLedgerAsync\(requestId: String, partyId: Long\)/);
assert.match(activity, /fun getCustomerSalesAsync\(requestId: String, partyId: Long\)/);
assert.match(activity, /lifecycleScope\.launch\(Dispatchers\.IO\)/);
assert.match(activity, /requireCurrentStationId\(db, userId\)/);
assert.match(activity, /__stationBridgeResolveCustomerDetails/);

for (const [name, source] of [['insertParty', insertParty], ['updateParty', updateParty]]) {
  assert.match(source, /db\.beginTransaction\(\)/, `${name} must begin a transaction`);
  assert.match(source, /db\.setTransactionSuccessful\(\)/, `${name} must commit only after all writes succeed`);
  assert.match(source, /db\.endTransaction\(\)/, `${name} must always end its transaction`);
}
assert.match(insertParty, /targetTable = "parties"/);
assert.match(insertParty, /authorizedStationId/);
assert.match(updateParty, /targetTable = "parties"/);
assert.match(updateParty, /authorizedStationId/);
assert.match(updateCreditLimit, /station_id = \?/);
assert.match(updateCreditLimit, /selectionArgs/);
assert.doesNotMatch(updateCreditLimit, /station_id = \$\{stationScopeId\}/);
for (const [name, source] of [['updatePartyAddress', updatePartyAddress], ['deletePartyAddress', deletePartyAddress]]) {
  assert.match(source, /station_id = \?/ , `${name} must bind the station scope`);
  assert.match(source, /selectionArgs/, `${name} must use selection arguments`);
  assert.doesNotMatch(source, /station_id = \$\{stationScopeId\}/, `${name} must not interpolate the station scope`);
}
for (const [name, source] of [['updatePartyContact', updatePartyContact], ['deletePartyContact', deletePartyContact]]) {
  assert.match(source, /station_id = \?/ , `${name} must bind the station scope`);
  assert.match(source, /selectionArgs/, `${name} must use selection arguments`);
  assert.doesNotMatch(source, /station_id = \$\{stationScopeId\}/, `${name} must not interpolate the station scope`);
}
assert.match(getCustomerDebts, /s\.station_id = \? AND p\.station_id = \?/);
assert.match(getCustomerDebts, /val args = mutableListOf<String>\(\)/);
assert.doesNotMatch(getCustomerDebts, /station_id = \$\{stationScopeId\}/);

console.log('Customer modal async bridge regression contract PASS.');
