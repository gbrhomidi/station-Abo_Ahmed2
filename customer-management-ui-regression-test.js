const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = __dirname;
const read = (...parts) => fs.readFileSync(path.join(root, ...parts), 'utf8');
const customers = read('app/src/main/assets/screens/customers.html');
const database = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const activity = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');

function requires(fragment, message) {
  assert(customers.includes(fragment), message || `المقطع المطلوب غير موجود: ${fragment}`);
}
function excludes(fragment, message) {
  assert(!customers.includes(fragment), message || `المقطع غير المقبول ما زال موجوداً: ${fragment}`);
}

// Navigation and actions must remain native WebView-safe and discoverable on mobile.
excludes('href="main.html"', 'لا يجوز إعادة الرابط النسبي المعطل إلى main.html');
requires('function goToCustomerHome() { window.location.href = \'../main.html\'; }');
requires('customer-action-dock');
['openCustomerModal()', 'loadCustomers()', 'exportCustomers()', 'exportCustomersJSON()', 'openReportsModal()'].forEach(action => requires(action));
requires('🌙');
requires('🌞');
requires('🏠');

// The customer record tabs must use in-page forms; browser prompt/confirm/alert breaks Android WebView UX.
['customerSubRecordModal', 'customerConfirmModal', 'customerMessageModal', 'customerSavedViewModal', 'openCustomerSubRecord', 'saveCustomerSubRecord', 'confirmCustomerAction', 'openCustomerMessageModal'].forEach(requires);
['prompt(', 'confirm(', 'alert('].forEach(excludes);
requires("openCustomerSubRecord('contact')");
requires("openCustomerSubRecord('address')");
requires("openCustomerSubRecord('contact', contact)");
requires("openCustomerSubRecord('address', addr)");
requires("apiCall(action, payload)");
requires("case 'update_customer': return bridge.updateParty(Number(p.party_id || p.id), JSON.stringify(p));");
requires("case 'update_contact': return bridge.updatePartyContact(JSON.stringify(p));");
requires("case 'update_address': return bridge.updatePartyAddress(JSON.stringify(p));");

// Station authority belongs to Kotlin and SQLite, never the page.
assert(activity.includes('db.updatePartyContact(id, data, requireCurrentStationId(db, activity.currentUserId))'), 'يجب أن يستمد جسر تحديث جهة الاتصال نطاق المحطة من الجلسة');
assert(activity.includes('db.updatePartyAddress(id, data, requireCurrentStationId(db, activity.currentUserId))'), 'يجب أن يستمد جسر تحديث العنوان نطاق المحطة من الجلسة');
assert(database.includes('"id=? AND station_id=? AND is_deleted=0"'), 'يجب أن يبقى تحديث العميل مقيداً بالمحطة في SQLite');
assert(database.includes('if (data.has("phone")) put("phone", data.optString("phone"))'), 'يجب أن يسمح تحديث جهة الاتصال بإفراغ الهاتف صراحةً');
assert(database.includes('if (data.has("city")) put("city", data.optString("city"))'), 'يجب أن يسمح تحديث العنوان بإفراغ المدينة صراحةً');

const inlineScripts = [...customers.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(match => match[1]);
assert(inlineScripts.length > 0, 'لا توجد كتل JavaScript مضمّنة للاختبار');
inlineScripts.forEach((source, index) => {
  try { new Function(source); }
  catch (error) { throw new Error(`خطأ صياغة JavaScript في كتلة العملاء ${index + 1}: ${error.message}`); }
});

console.log('Customer management UI regression contract: PASS');
