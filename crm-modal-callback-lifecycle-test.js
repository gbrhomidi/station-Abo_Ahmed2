const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/screens/crm.html', 'utf8');
const start = source.indexOf('window.__stationBridgeResolvePartyCrm');
const end = source.indexOf('\n        function setCrmPartyDetailsLoading', start);
assert.notEqual(start, -1, 'CRM callback resolver is missing');
assert.notEqual(end, -1, 'CRM callback block end marker is missing');
const lifecycleSource = source.slice(start, end);

let nextTimerId = 0;
const scheduledTimers = new Map();
const bridgeCalls = [];
const windowMock = {
  AndroidInterface: {
    getPartyCrmBundleForCrmAsync(requestId) {
      bridgeCalls.push(requestId);
      return JSON.stringify({ success: true });
    },
  },
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
  let partyCrmRequestSequence = 0;
  let partyCrmCallbackSequence = 0;
  const partyCrmCallbacks = new Map();
  function parseBridgeResponse(raw) {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!parsed || parsed.success === false) throw new Error(parsed?.error || 'Bridge failed');
    return parsed;
  }
  ${lifecycleSource}
  globalThis.__crmLifecycle = {
    invokePartyCrmBundleAsync,
    cancelPendingPartyCrmRequests,
    resolve: window.__stationBridgeResolvePartyCrm,
    callbackCount: () => partyCrmCallbacks.size
  };
`, context);

function fireTimer(id) {
  const timer = scheduledTimers.get(id);
  assert.ok(timer, `Timer ${id} must be pending before it fires`);
  scheduledTimers.delete(id);
  timer.callback();
}

async function run() {
  const lifecycle = context.__crmLifecycle;
  const cancelled = [];
  for (let index = 0; index < 1000; index += 1) {
    const pending = lifecycle.invokePartyCrmBundleAsync(index + 1);
    pending.catch(() => undefined);
    cancelled.push(pending);
  }
  assert.equal(lifecycle.callbackCount(), 1000, 'Each accepted CRM request must be tracked');
  assert.equal(scheduledTimers.size, 1000, 'Each CRM request must own one timeout');

  lifecycle.cancelPendingPartyCrmRequests('stress cancellation');
  await Promise.allSettled(cancelled);
  assert.equal(lifecycle.callbackCount(), 0, 'CRM cancellation must release all callbacks');
  assert.equal(scheduledTimers.size, 0, 'CRM cancellation must clear all timeouts');

  const successful = lifecycle.invokePartyCrmBundleAsync(77);
  const successfulRequestId = bridgeCalls.at(-1);
  lifecycle.resolve(successfulRequestId, JSON.stringify({ success: true, data: { party_id: 77 } }));
  const successPayload = await successful;
  assert.equal(successPayload.data.party_id, 77, 'Matching CRM callback must resolve its request');
  assert.equal(lifecycle.callbackCount(), 0, 'Successful CRM response must release callback state');
  assert.equal(scheduledTimers.size, 0, 'Successful CRM response must clear its timeout');

  const timedOut = lifecycle.invokePartyCrmBundleAsync(88);
  timedOut.catch(() => undefined);
  fireTimer([...scheduledTimers.keys()][0]);
  await assert.rejects(timedOut, /انتهت مهلة تحميل تفاصيل الطرف/);
  assert.equal(lifecycle.callbackCount(), 0, 'CRM timeout must release its callback state');
  assert.equal(scheduledTimers.size, 0, 'CRM timeout must clear all retained timers');
  lifecycle.resolve(bridgeCalls.at(-1), JSON.stringify({ success: true }));
  assert.equal(lifecycle.callbackCount(), 0, 'Late CRM callback after timeout must be a no-op');

  console.log('CRM modal callback lifecycle stress PASS.');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
