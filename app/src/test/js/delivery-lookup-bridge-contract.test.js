'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { create } = require('../../main/assets/assets-local/js/fuel-sales-async-bridge.js');

// Resolve from app/src so main/assets and main/java map to the actual Android source tree.
const root = path.resolve(__dirname, '../..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

async function run() {
    const fuelScreen = read('main/assets/screens/fuel-sales.html');
    const deliveriesScreen = read('main/assets/screens/deliveries.html');
    const mainActivity = read('main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
    const accounting = read('main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryFeeAccounting.kt');
    const contract = read('main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryLookupBridgeContract.kt');

    assert.match(fuelScreen, /invokeFuelSalesAsync\(['"]locations['"],\s*\[\s*\]\)/,
        'fuel-sales.html must request saved locations through the asynchronous locations operation');
    assert.match(fuelScreen, /window\.__fuelSalesNativeResult\s*=\s*function/,
        'fuel-sales.html must expose the callback that resolves native results');
    assert.doesNotMatch(fuelScreen, /(?:bridge|AndroidInterface)\.getSavedDeliveryLocations\s*\(/,
        'fuel-sales.html must not invoke a synchronous saved-location bridge method');

    assert.match(mainActivity, /@JavascriptInterface\s+fun executeFuelSalesAsync\s*\(/,
        'MainActivity must expose the allowlisted asynchronous endpoint');
    assert.match(mainActivity, /"locations"\s*->\s*readSavedDeliveryLocationsForFuelSales\(\)/,
        'the asynchronous locations operation must reach the private query implementation');
    assert.match(mainActivity, /private fun readSavedDeliveryLocationsForFuelSales\(\): String/,
        'saved-location lookup must remain an internal implementation, not a JavaScript API');
    assert.doesNotMatch(mainActivity, /@JavascriptInterface\s+fun getSavedDeliveryLocations\s*\(/,
        'redundant synchronous getSavedDeliveryLocations exposure must be removed');
    assert.doesNotMatch(mainActivity, /fun getSavedDeliveryLocations\s*\(/,
        'the old public bridge method name must not remain');
    assert.match(mainActivity, /addJavascriptInterface\(webAppInterface!!,\s*"AndroidInterface"\)/,
        'the WebAppInterface instance must be injected under the expected JS name');

    assert.match(deliveriesScreen, /invoke\(['"]getRecentSaleCustomerIds['"]\)/,
        'deliveries.html must use the current recent-customer method name');
    assert.match(mainActivity, /@JavascriptInterface\s+fun getRecentSaleCustomerIds\(\): String/,
        'the recent-customer method must remain exposed for the current deliveries caller');

    assert.match(contract, /DeliveryFeeAccounting\.savedDeliveryLocations\(helper,\s*stationId,\s*queryRunner\)/,
        'saved-location response contract must call the production SQLite query');
    assert.match(contract, /DeliveryFeeAccounting\.recentCustomerSaleIds\(helper,\s*stationId,\s*queryRunner\)/,
        'recent-customer response contract must call the production SQLite query');
    assert.match(contract, /put\("success",\s*true\)[\s\S]*put\("data",\s*read\(\)\)/,
        'success responses must preserve the {success,data} JSON envelope');
    assert.match(contract, /put\("success",\s*false\)[\s\S]*put\("error"/,
        'read failures must preserve the {success:false,error} JSON envelope');

    const locationsMethod = accounting.match(/internal fun savedDeliveryLocations\([\s\S]*?\n    }/);
    const customersMethod = accounting.match(/internal fun recentCustomerSaleIds\([\s\S]*?\n    }/);
    assert.ok(locationsMethod, 'savedDeliveryLocations production method must exist');
    assert.ok(customersMethod, 'recentCustomerSaleIds production method must exist');
    for (const [name, body] of [['locations', locationsMethod[0]], ['customers', customersMethod[0]]]) {
        assert.match(body, /helper\.readableDatabase/, name + ' must use readableDatabase');
        assert.match(body, /queryRunner\.rawQuery/, name + ' must execute the final SELECT through the query runner');
        assert.doesNotMatch(body, /writableDatabase|execSQL|\b(?:INSERT|UPDATE|DELETE)\b/i,
            name + ' lookup must not mutate SQLite or its schema');
    }

    // Exercise the JS async adapter's exact acknowledgement/callback/JSON response contract.
    const requests = [];
    let client;
    const fakeAndroidInterface = {
        executeFuelSalesAsync(requestId, operation, argsJson) {
            requests.push({ requestId, operation, args: JSON.parse(argsJson) });
            queueMicrotask(() => client.resolve(
                requestId,
                JSON.stringify({ success: true, data: ['شارع الاختبار', 'حي المحطة'] })
            ));
            return JSON.stringify({ success: true, queued: true });
        }
    };
    client = create(fakeAndroidInterface, { timeoutMs: 1500 });
    const result = await client.invoke('locations', []);
    assert.deepEqual(result.data, ['شارع الاختبار', 'حي المحطة']);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].operation, 'locations');
    assert.deepEqual(requests[0].args, []);
    assert.equal(client.pendingCount(), 0);

    let failingClient;
    const failingAndroidInterface = {
        executeFuelSalesAsync(requestId) {
            queueMicrotask(() => failingClient.resolve(
                requestId,
                JSON.stringify({ success: false, error: 'SQLite location query failed' })
            ));
            return JSON.stringify({ success: true, queued: true });
        }
    };
    failingClient = create(failingAndroidInterface, { timeoutMs: 1500 });
    await assert.rejects(failingClient.invoke('locations', []), /SQLite location query failed/);
    assert.equal(failingClient.pendingCount(), 0);

    console.log('Delivery lookup bridge integration contract tests passed.');
}

run().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
