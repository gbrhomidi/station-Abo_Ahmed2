const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/screens/customers.html', 'utf8');
const start = source.indexOf('window.__stationBridgeResolveCustomerDetails');
const end = source.indexOf('\n        async function apiCall', start);
assert.notEqual(start, -1, 'Customer callback resolver is missing');
assert.notEqual(end, -1, 'Customer callback block end marker is missing');
const lifecycleSource = source.slice(start, end);

let nextTimerId = 0;
const scheduledTimers = new Map();
const bridgeCalls = [];
const windowMock = {
  setTimeout(callback, delay) {
    const id = ++nextTimerId;
    scheduledTimers.set(id, { callback, delay });
    return id;
  },
  clearTimeout(id) {
    scheduledTimers.delete(id);
  },
};

const context = vm.createContext({ window: windowMock, console, Map, Error, Number, Promise, bridgeCalls });
vm.runInContext(`
  let customerDetailsCallbackSequence = 0;
  const customerDetailsCallbacks = new Map();
  const bridge = {
    getPartyCrmBundleAsync(requestId) { bridgeCalls.push(requestId); return JSON.stringify({ success: true }); },
    getCustomerLedgerAsync(requestId) { bridgeCalls.push(requestId); return JSON.stringify({ success: true }); },
    getCustomerSalesAsync(requestId) { bridgeCalls.push(requestId); return JSON.stringify({ success: true }); }
  };
  function getBridge() { return bridge; }
  function normalizeBridgeResult(raw) {
    return typeof raw === 'string' ? JSON.parse(raw) : raw;
  }
  ${lifecycleSource}
  globalThis.__customerLifecycle = {
    invokeCustomerDetailsAsync,
    cancelPendingCustomerDetails,
    resolve: window.__stationBridgeResolveCustomerDetails,
    state: () => ({ callbacks: customerDetailsCallbacks.size })
  };
`, context);

function fireTimer(id) {
  const timer = scheduledTimers.get(id);
  assert.ok(timer, `Timer ${id} must be pending before it fires`);
  scheduledTimers.delete(id);
  timer.callback();
}

async function run() {
  const lifecycle = context.__customerLifecycle;
  const cancelled = [];

  for (let index = 0; index < 1000; index += 1) {
    const pending = lifecycle.invokeCustomerDetailsAsync('get_customer_bundle', index + 1);
    pending.catch(() => undefined);
    cancelled.push(pending);
  }
  assert.equal(lifecycle.state().callbacks, 1000, 'Each accepted bridge request must be tracked before cancellation');
  assert.equal(scheduledTimers.size, 1000, 'Each tracked request must own one timeout');

  lifecycle.cancelPendingCustomerDetails('stress cancellation');
  await Promise.allSettled(cancelled);
  assert.equal(lifecycle.state().callbacks, 0, 'Cancellation must release all callback references');
  assert.equal(scheduledTimers.size, 0, 'Cancellation must clear all scheduled timeouts');

  const resolved = lifecycle.invokeCustomerDetailsAsync('get_customer_ledger', 77);
  const resolvedRequestId = bridgeCalls.at(-1);
  assert.equal(lifecycle.state().callbacks, 1, 'A new request must be tracked');
  lifecycle.resolve('customer-details-unrelated', JSON.stringify({ success: true }));
  assert.equal(lifecycle.state().callbacks, 1, 'An unrelated late callback must not mutate pending state');
  lifecycle.resolve(resolvedRequestId, JSON.stringify({ success: true, data: { party_id: 77 } }));
  const resolvedPayload = await resolved;
  assert.equal(resolvedPayload.data.party_id, 77, 'Matching callback must resolve its original request');
  assert.equal(lifecycle.state().callbacks, 0, 'Successful resolution must release its callback reference');
  assert.equal(scheduledTimers.size, 0, 'Successful resolution must clear its timeout');

  const timedOut = lifecycle.invokeCustomerDetailsAsync('get_customer_sales', 88);
  timedOut.catch(() => undefined);
  const timeoutId = [...scheduledTimers.keys()][0];
  fireTimer(timeoutId);
  await assert.rejects(timedOut, /انتهت مهلة تحميل تفاصيل العميل/);
  assert.equal(lifecycle.state().callbacks, 0, 'Timeout must release its callback reference');
  assert.equal(scheduledTimers.size, 0, 'Timeout completion must leave no scheduled timer');
  lifecycle.resolve(bridgeCalls.at(-1), JSON.stringify({ success: true }));
  assert.equal(lifecycle.state().callbacks, 0, 'Late completion after timeout must be a no-op');

  console.log('Customer modal callback lifecycle stress PASS.');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
