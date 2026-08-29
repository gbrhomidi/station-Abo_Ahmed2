const assert = require('node:assert/strict');
const fs = require('node:fs');

const crm = fs.readFileSync('app/src/main/assets/screens/crm.html', 'utf8');
const activity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

function between(source, start, end) {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `Missing ${start}`);
  const to = source.indexOf(end, from + start.length);
  assert.notEqual(to, -1, `Missing end marker ${end}`);
  return source.slice(from, to);
}

const editParty = between(crm, 'function editParty', '// ============================================================\n        // 20. حذف طرف');
const viewParty = between(crm, 'async function viewParty', 'function renderPartyDetails');
const closeModal = between(crm, 'function closeModal', '// ============================================================\n        // 18. حفظ الطرف');

assert.match(crm, /getPartyCrmBundleForCrmAsync/);
assert.match(crm, /window\.__stationBridgeResolvePartyCrm/);
assert.match(crm, /function cancelPendingPartyCrmRequests\(/);
assert.match(crm, /let partyCrmRequestSequence = 0;/);
assert.match(crm, /let partyCrmCallbackSequence = 0;/);
assert.match(crm, /\+\+partyCrmCallbackSequence/);
assert.match(crm, /partyCrmCallbacks\.delete\(requestId\)/);
assert.match(crm, /window\.clearTimeout\(pending\.timeoutId\)/);
assert.match(editParty, /populatePartyEditForm\(item\)/);
assert.match(editParty, /requestAnimationFrame/);
assert.match(editParty, /void loadPartyCrmDetails/);
assert.doesNotMatch(editParty, /showLoading\(true\)/);
assert.doesNotMatch(editParty, /apiCall\('getPartyCrmBundle'/);
assert.match(viewParty, /invokePartyCrmBundleAsync\(id\)/);
assert.doesNotMatch(viewParty, /showLoading\(true\)/);
assert.match(closeModal, /cancelPendingPartyCrmRequests\(\)/);

assert.match(activity, /fun getPartyCrmBundleForCrmAsync\(requestId: String, id: Long\)/);
assert.match(activity, /fun runPartyCrmRead\(/);
assert.match(activity, /lifecycleScope\.launch\(Dispatchers\.IO\)/);
assert.match(activity, /requireCurrentStationId\(db, userId\)/);
assert.match(activity, /__stationBridgeResolvePartyCrm/);

console.log('CRM modal async bridge regression contract PASS.');
