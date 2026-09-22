const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/screens/contracts.html', 'utf8');
const start = source.indexOf('window.__stationBridgeResolveContractDetails');
const end = source.indexOf('\n        function setContractDetailsLoading', start);
assert.notEqual(start, -1, 'Contract callback resolver is missing');
assert.notEqual(end, -1, 'Contract callback block end marker is missing');
const lifecycleSource = source.slice(start, end);

let nextTimerId = 0;
const scheduledTimers = new Map();
const bridgeCalls = [];
const windowMock = {
  AndroidInterface: {
    getContractBundleAsync(requestId) {
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
  let contractDetailsRequestSequence = 0;
  let contractDetailsCallbackSequence = 0;
  const contractDetailsCallbacks = new Map();
  function parseBridgeResponse(raw) {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!parsed || parsed.success === false) throw new Error(parsed?.error || 'Bridge failed');
    return parsed;
  }
  ${lifecycleSource}
  globalThis.__contractLifecycle = {
    invokeContractBundleAsync,
    cancelPendingContractDetails,
    resolve: window.__stationBridgeResolveContractDetails,
    callbackCount: () => contractDetailsCallbacks.size
  };
`, context);

function fireTimer(id) {
  const timer = scheduledTimers.get(id);
  assert.ok(timer, `Timer ${id} must be pending before it fires`);
  scheduledTimers.delete(id);
  timer.callback();
}

async function run() {
  const lifecycle = context.__contractLifecycle;
  const cancelled = [];
  for (let index = 0; index < 1000; index += 1) {
    const pending = lifecycle.invokeContractBundleAsync(index + 1);
    pending.catch(() => undefined);
    cancelled.push(pending);
  }
  assert.equal(lifecycle.callbackCount(), 1000, 'Each accepted contract request must be tracked');
  assert.equal(scheduledTimers.size, 1000, 'Each contract request must own one timeout');

  lifecycle.cancelPendingContractDetails('stress cancellation');
  await Promise.allSettled(cancelled);
  assert.equal(lifecycle.callbackCount(), 0, 'Contract cancellation must release all callbacks');
  assert.equal(scheduledTimers.size, 0, 'Contract cancellation must clear all timeouts');

  const successful = lifecycle.invokeContractBundleAsync(77);
  const successfulRequestId = bridgeCalls.at(-1);
  lifecycle.resolve(successfulRequestId, JSON.stringify({ success: true, data: { contract_id: 77 } }));
  const successPayload = await successful;
  assert.equal(successPayload.data.contract_id, 77, 'Matching contract callback must resolve its request');
  assert.equal(lifecycle.callbackCount(), 0, 'Successful contract response must release callback state');
  assert.equal(scheduledTimers.size, 0, 'Successful contract response must clear its timeout');

  const timedOut = lifecycle.invokeContractBundleAsync(88);
  timedOut.catch(() => undefined);
  fireTimer([...scheduledTimers.keys()][0]);
  await assert.rejects(timedOut, /انتهت مهلة تحميل تفاصيل العقد/);
  assert.equal(lifecycle.callbackCount(), 0, 'Contract timeout must release its callback state');
  assert.equal(scheduledTimers.size, 0, 'Contract timeout must clear all retained timers');
  lifecycle.resolve(bridgeCalls.at(-1), JSON.stringify({ success: true }));
  assert.equal(lifecycle.callbackCount(), 0, 'Late contract callback after timeout must be a no-op');

  console.log('Contracts modal callback lifecycle stress PASS.');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
