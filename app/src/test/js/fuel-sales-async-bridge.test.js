'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { create } = require('../../main/assets/assets-local/js/fuel-sales-async-bridge.js');

async function runFuelSalesAsyncBridgeTests() {
    const requests = [];
    const fakeBridge = {
        executeFuelSalesAsync(requestId, operation, argsJson) {
            requests.push({ requestId, operation, args: JSON.parse(argsJson) });
            return JSON.stringify({ success: true, queued: true });
        }
    };
    const client = create(fakeBridge, { timeoutMs: 1500 });

    // A dispatch must return its acknowledgement without waiting for the native result.
    const startedAt = Date.now();
    const delayed = client.invoke('save', [{ invoice: 'اختبار' }], 1500);
    const dispatchDuration = Date.now() - startedAt;
    assert.ok(dispatchDuration < 500, 'dispatch must not wait for the delayed native/database result');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].operation, 'save');
    assert.deepEqual(requests[0].args, [{ invoice: 'اختبار' }]);
    assert.equal(client.pendingCount(), 1);

    let delayedResolved = false;
    delayed.then(() => { delayedResolved = true; });
    await new Promise(resolve => setTimeout(resolve, 40));
    assert.equal(delayedResolved, false, 'a queued request must not resolve before native confirmation');
    client.resolve(requests[0].requestId, JSON.stringify({ success: true, data: { sale_id: 71 } }));
    const saved = await delayed;
    assert.equal(saved.data.sale_id, 71);
    assert.equal(client.pendingCount(), 0);

    // Out-of-order callbacks must resolve only the request that owns each identifier.
    const first = client.invoke('first', [], 1500);
    const second = client.invoke('second', [], 1500);
    const firstId = requests[1].requestId;
    const secondId = requests[2].requestId;
    client.resolve(secondId, JSON.stringify({ success: true, value: 'second' }));
    client.resolve(firstId, JSON.stringify({ success: true, value: 'first' }));
    const results = await Promise.all([first, second]);
    assert.deepEqual(results, [{ success: true, value: 'first' }, { success: true, value: 'second' }]);

    // A SQLite/native error is surfaced as a rejection, not as an apparent success.
    const failed = client.invoke('save', [], 1500);
    client.resolve(requests[3].requestId, JSON.stringify({ success: false, error: 'SQLite rejected the write' }));
    await assert.rejects(failed, /SQLite rejected the write/);
    assert.equal(client.pendingCount(), 0);

    // Lost callbacks time out and release their pending entry.
    const timedOut = client.invoke('save', [], 1000);
    const timeoutRequestId = requests[4].requestId;
    await assert.rejects(timedOut, /انتهت مهلة انتظار Android/);
    assert.equal(client.pendingCount(), 0);
    assert.equal(client.resolve(timeoutRequestId, JSON.stringify({ success: true })), false);

    await assert.rejects(create(null).invoke('save', []), /غير متاحة/);

    // Static checks ensure the tested adapter is used by the actual screen and Kotlin bridge.
    const screenPath = path.join(__dirname, '../../main/assets/screens/fuel-sales.html');
    const screen = fs.readFileSync(screenPath, 'utf8');
    const activityPath = path.join(__dirname, '../../main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
    const activity = fs.readFileSync(activityPath, 'utf8');
    assert.ok(screen.includes('fuel-sales-async-bridge.js'), 'fuel-sales.html must load the tested bridge helper');
    const inlineScript = screen.match(/<script>([\s\S]*?)<\/script>/);
    assert.ok(inlineScript, 'fuel-sales.html must contain its inline screen controller');
    assert.doesNotThrow(() => new Function(inlineScript[1]), 'fuel-sales.html controller must remain valid JavaScript');
    assert.ok(screen.includes("invokeFuelSalesAsync('nozzles'"), 'nozzle queries must use the async bridge');
    assert.ok(screen.includes("invokeFuelSalesAsync('locations'"), 'saved-location queries must use the async bridge');
    assert.ok(!screen.includes("bridge.getPumpNozzles("), 'nozzle lookups must not call the blocking bridge directly');
    assert.ok(!screen.includes("invoke('getResolvedFuelSalePrice'"), 'price resolution must not call the blocking bridge directly');

    assert.ok(screen.includes("invokeFuelSalesAsync('formLookups'"), 'essential form reads must use the async bridge');
    assert.ok(screen.includes("invokeFuelSalesAsync('save'"), 'fuel-sale writes must use the async bridge');
    assert.ok(screen.includes("invokeFuelSalesAsync('resolvedPrice'"), 'price resolution must use the async bridge');
    assert.ok(screen.includes('const seq = ++state.priceLookupSeq;'), 'clearing a fuel selection must invalidate a pending price response');
    assert.ok(screen.includes('seq !== state.priceLookupSeq || !isCurrentFuelForm(openToken)'), 'late price responses must not overwrite a newer or closed form');

    assert.ok(screen.includes('if (state.isSaving) return;'), 'the save handler must reject repeat taps');
    assert.ok(screen.includes("showFormToast('جارٍ حفظ عملية البيع والتحقق من SQLite.'"), 'the form must show pending state before native confirmation');
    assert.ok(activity.includes('fun executeFuelSalesAsync('), 'MainActivity must expose the asynchronous fuel-sales endpoint');
    assert.ok(activity.includes('withContext(Dispatchers.IO)'), 'SQLite bridge work must execute on Dispatchers.IO');
    assert.ok(activity.includes('window.__fuelSalesNativeResult'), 'native results must return through the callback');

    console.log('Fuel-sales asynchronous bridge and save-safety tests passed.');
}

module.exports = runFuelSalesAsyncBridgeTests;

if (require.main === module) {
    runFuelSalesAsyncBridgeTests().catch(error => {
        console.error(error);
        process.exitCode = 1;
    });
}
