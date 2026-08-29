const assert = require('node:assert/strict');
const fs = require('node:fs');

const contracts = fs.readFileSync('app/src/main/assets/screens/contracts.html', 'utf8');
const activity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

function between(source, start, end) {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `Missing ${start}`);
  const to = source.indexOf(end, from + start.length);
  assert.notEqual(to, -1, `Missing end marker ${end}`);
  return source.slice(from, to);
}

const editContract = between(contracts, 'function editContract', 'async function deleteContract');
const viewContract = between(contracts, 'async function viewContract', 'function closeDetails');
const closeModal = between(contracts, 'function closeModal', 'async function saveContract');

assert.match(contracts, /getContractBundleAsync/);
assert.match(contracts, /window\.__stationBridgeResolveContractDetails/);
assert.match(contracts, /function cancelPendingContractDetails\(/);
assert.match(contracts, /let contractDetailsRequestSequence = 0;/);
assert.match(contracts, /let contractDetailsCallbackSequence = 0;/);
assert.match(contracts, /\+\+contractDetailsCallbackSequence/);
assert.match(contracts, /contractDetailsCallbacks\.delete\(requestId\)/);
assert.match(contracts, /window\.clearTimeout\(pending\.timeoutId\)/);
assert.match(editContract, /populateContractEditForm\(item\)/);
assert.match(editContract, /requestAnimationFrame/);
assert.match(editContract, /void loadContractDetails/);
assert.doesNotMatch(editContract, /showLoading\(true\)/);
assert.doesNotMatch(editContract, /apiCall\('getContractBundle'/);
assert.match(viewContract, /invokeContractBundleAsync\(id\)/);
assert.doesNotMatch(viewContract, /apiCall\('getContractBundle'/);
assert.match(closeModal, /cancelPendingContractDetails\(\)/);

assert.match(activity, /fun getContractBundleAsync\(requestId: String, id: Long\)/);
assert.match(activity, /fun runContractDetailsRead\(/);
assert.match(activity, /lifecycleScope\.launch\(Dispatchers\.IO\)/);
assert.match(activity, /requireCurrentStationId\(db, userId\)/);
assert.match(activity, /__stationBridgeResolveContractDetails/);

console.log('Contracts modal async bridge regression contract PASS.');
