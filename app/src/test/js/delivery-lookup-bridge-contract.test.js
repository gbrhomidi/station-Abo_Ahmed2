'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { create } = require('../../main/assets/assets-local/js/fuel-sales-async-bridge.js');
const { spawnSync } = require('node:child_process');

// Resolve from app/src so main/assets and main/java map to the actual Android source tree.
const root = path.resolve(__dirname, '../..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

async function run() {
    const fuelScreen = read('main/assets/screens/fuel-sales.html');
    const deliveriesScreen = read('main/assets/screens/deliveries.html');
    const mainActivity = read('main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
    const accounting = read('main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryFeeAccounting.kt');
    const contract = read('main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryLookupBridgeContract.kt');

    // Search every tracked file; construct the legacy name from fragments to avoid self-matching.
    const retiredMethod = ['getRecent', 'FuelSale', 'Customer', 'Ids'].join('');
    const projectRoot = path.resolve(__dirname, '../../../../');
    const legacyScan = spawnSync('git', ['grep', '-n', retiredMethod, '--', '.'], {
        cwd: projectRoot,
        encoding: 'utf8'
    });
    assert.equal(legacyScan.error, undefined, 'git grep must be available for the repository-wide API scan');
    assert.equal(legacyScan.status, 1,
        'the retired Android Bridge method must not exist in any tracked file:\\n' +
        (legacyScan.stdout || legacyScan.stderr || ''));


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
    const recentLoaderStart = deliveriesScreen.indexOf('async function loadRecentCustomerSaleOrder(');
    const recentLoaderEnd = deliveriesScreen.indexOf('function selectAnonymousCustomer(', recentLoaderStart);
    assert.ok(recentLoaderStart >= 0 && recentLoaderEnd > recentLoaderStart,
        'deliveries must retain the recent-customer loader');
    const recentLoaderSource = deliveriesScreen.slice(recentLoaderStart, recentLoaderEnd);
    assert.doesNotMatch(recentLoaderSource, /typeof bridge\\.getRecentSaleCustomerIds/,
        'the loader must not silently skip an absent Bridge method');
    assert.match(recentLoaderSource, /!Array\\.isArray\\(values\\)/,
        'the loader must reject malformed non-array payloads');
    assert.match(recentLoaderSource, /state\\.recentSaleCustomerIds = \\[\\]/,
        'the loader must clear stale ids after a failed read');
    assert.match(recentLoaderSource, /state\\.recentSaleCustomersLoadedAt = 0/,
        'the loader must clear stale cache age after a failed read');

    // Execute the actual helper functions and loader extracted from deliveries.html.
    const extract = (startMarker, endMarker) => {
        const start = deliveriesScreen.indexOf(startMarker);
        const end = deliveriesScreen.indexOf(endMarker, start);
        assert.ok(start >= 0 && end > start, 'missing helper source: ' + startMarker);
        return deliveriesScreen.slice(start, end);
    };
    const pageHelpers = new Function('bridge',
        extract('function parseResponse(', 'function dataOf(') +
        extract('function dataOf(', 'function invoke(') +
        extract('function invoke(', 'function toast(') +
        '\\nreturn { invoke, dataOf };'
    );
    const createLoader = (state, bridge, consoleApi) => {
        const helpers = pageHelpers(bridge);
        return new Function('state', 'bridge', 'invoke', 'dataOf', 'console',
            recentLoaderSource + '\\nreturn loadRecentCustomerSaleOrder;'
        )(state, bridge, helpers.invoke, helpers.dataOf, consoleApi);
    };

    const called = [];
    const successBridge = {
        getRecentSaleCustomerIds() {
            called.push('getRecentSaleCustomerIds');
            return JSON.stringify({
                success: true,
                data: [{ party_id: 31 }, { customer_party_id: '22' }, 31, 0, -3, { id: 'bad' }]
            });
        }
    };
    const successState = { recentSaleCustomerIds: [], recentSaleCustomersLoadedAt: 0, recentSaleCustomersPromise: null };
    const successLoader = createLoader(successState, successBridge, { warn() {} });
    const successIds = await successLoader();
    assert.deepEqual(Array.from(successIds), [31, 22],
        'the real page loader must normalize, validate and deduplicate IDs');
    assert.deepEqual(called, ['getRecentSaleCustomerIds'],
        'the new Android Bridge API must be invoked exactly once');
    assert.ok(successState.recentSaleCustomersLoadedAt > 0, 'valid results must be cached');

    const missingState = { recentSaleCustomerIds: [91], recentSaleCustomersLoadedAt: Date.now(), recentSaleCustomersPromise: null };
    const missingWarnings = [];
    const missingLoader = createLoader(missingState, {}, { warn: (...args) => missingWarnings.push(args) });
    const missingIds = await missingLoader(true);
    assert.deepEqual(Array.from(missingIds), [],
        'a missing new Bridge method must not silently retain stale customer IDs');
    assert.equal(missingState.recentSaleCustomersLoadedAt, 0);
    assert.match(String(missingWarnings[0] && missingWarnings[0].join(' ')), /getRecentSaleCustomerIds/);

    const malformedState = { recentSaleCustomerIds: [81], recentSaleCustomersLoadedAt: Date.now(), recentSaleCustomersPromise: null };
    const malformedWarnings = [];
    const malformedBridge = {
        getRecentSaleCustomerIds() {
            return JSON.stringify({ success: true, data: { ids: [71] } });
        }
    };
    const malformedLoader = createLoader(malformedState, malformedBridge, { warn: (...args) => malformedWarnings.push(args) });
    const malformedIds = await malformedLoader(true);
    assert.deepEqual(Array.from(malformedIds), [], 'malformed success data must not become a cached empty ranking');
    assert.equal(malformedState.recentSaleCustomersLoadedAt, 0);
    assert.match(String(malformedWarnings[0] && malformedWarnings[0].join(' ')), /مصفوفة معرفات العملاء/);

    const failedState = { recentSaleCustomerIds: [71], recentSaleCustomersLoadedAt: Date.now(), recentSaleCustomersPromise: null };
    const failedWarnings = [];
    const failedBridge = {
        getRecentSaleCustomerIds() {
            return JSON.stringify({ success: false, error: 'SQLite recent customer query failed' });
        }
    };
    const failedLoader = createLoader(failedState, failedBridge, { warn: (...args) => failedWarnings.push(args) });
    const failedIds = await failedLoader(true);
    assert.deepEqual(Array.from(failedIds), [], 'SQLite read errors must not leave stale ranking IDs');
    assert.equal(failedState.recentSaleCustomersLoadedAt, 0);
    assert.match(String(failedWarnings[0] && failedWarnings[0].join(' ')), /SQLite recent customer query failed/);

    assert.match(deliveriesScreen, /function uniqueCustomers\(customers\)/,
        'deliveries.html must remove duplicate customers before rendering the menu');
    assert.match(deliveriesScreen, /setDeliveryFeeInput\(0\)/,
        'new and reset delivery forms must normalize the service fee to zero');
    assert.match(deliveriesScreen, /function normalizeDeliveryFeeInput\(value\)/,
        'delivery fee input must handle empty, dot-only and invalid values centrally');
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
