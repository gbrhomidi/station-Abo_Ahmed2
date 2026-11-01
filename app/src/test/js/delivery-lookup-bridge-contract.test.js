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
        'the retired Android Bridge method must not exist in any tracked file:\n' +
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

    assert.ok(
        deliveriesScreen.includes('assets-local/js/fuel-sales-async-bridge.js'),
        'deliveries.html must load the asynchronous native bridge adapter'
    );
    assert.ok(
        deliveriesScreen.includes('window.__fuelSalesNativeResult'),
        'deliveries.html must resolve native async callbacks'
    );
    assert.ok(
        deliveriesScreen.includes("recentSaleCustomersBridgeClient.invoke(") &&
        deliveriesScreen.includes("'getRecentSaleCustomerIds'"),
        'the page must request the named recent-customer operation asynchronously'
    );
    assert.ok(mainActivity.includes('"getRecentSaleCustomerIds" -> getRecentSaleCustomerIds()'),
        'the IO dispatcher must route the async operation to the canonical API');
    assert.ok(mainActivity.includes('"locations", "getRecentSaleCustomerIds", "save"'),
        'the async bridge allowlist must explicitly include recent-customer ranking');

    const extract = (startMarker, endMarker) => {
        const start = deliveriesScreen.indexOf(startMarker);
        const end = deliveriesScreen.indexOf(endMarker, start);
        assert.ok(start >= 0 && end > start, 'missing page source: ' + startMarker);
        return deliveriesScreen.slice(start, end);
    };
    const recentOrderHelperSource = extract(
        'function sameRecentCustomerOrder(',
        'async function loadRecentCustomerSaleOrder('
    );
    const recentLoaderSource = recentOrderHelperSource + extract(
        'async function loadRecentCustomerSaleOrder(',
        'function selectAnonymousCustomer('
    );
    assert.ok(
        recentLoaderSource.includes("client.invoke('scope', [])") &&
        recentLoaderSource.includes("client.invoke('getRecentSaleCustomerIds', [])"),
        'the real page loader must validate authenticated scope and use the asynchronous callback bridge');
    assert.ok(recentLoaderSource.includes("'getRecentSaleCustomerIds'"),
        'the operation name must match the canonical Android Bridge API');
    assert.ok(recentLoaderSource.includes('state.recentSaleCustomersRequestGeneration'),
        'the page loader must guard against superseded responses');
    assert.ok(recentLoaderSource.includes('sameRecentCustomerOrder'),
        'the page loader must compare rankings before replacing state');
    assert.ok(recentLoaderSource.includes('state.recentSaleCustomerIds = [];'),
        'failed current requests must remove stale ranking data');

    const dataOfSource = extract('function dataOf(', 'function invoke(');
    const dataOf = new Function(dataOfSource + '\nreturn dataOf;')();
    const createLoader = (state, asyncClient, consoleApi) => new Function(
        'state', 'recentSaleCustomersBridgeClient', 'dataOf', 'console',
        recentLoaderSource + '\nreturn loadRecentCustomerSaleOrder;'
    )(state, asyncClient, dataOf, consoleApi);

    const validIds = [
        { party_id: 31 },
        { customer_party_id: '22' },
        31,
        0,
        -3,
        { id: 'bad' }
    ];
    let successCalls = 0;
    const successClient = {
        invoke(operation, args) {
            successCalls++;
            assert.equal(operation, 'getRecentSaleCustomerIds');
            assert.deepEqual(args, []);
            return Promise.resolve({ success: true, data: validIds });
        }
    };
    const successState = {
        recentSaleCustomerIds: [],
        recentSaleCustomersLoadedAt: 0,
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    const successLoader = createLoader(successState, successClient, { warn() {} });
    const successfulResult = await successLoader();
    assert.deepEqual(Array.from(successfulResult.ids), [31, 22],
        'the page must normalize, validate and deduplicate returned customer IDs');
    assert.equal(successfulResult.changed, true);
    assert.equal(successCalls, 1);
    assert.ok(successState.recentSaleCustomersLoadedAt > 0);

    // An unchanged result refreshes the TTL without replacing the array or rebuilding UI.
    const cachedArray = successState.recentSaleCustomerIds;
    successState.recentSaleCustomersLoadedAt = 0;
    const unchangedResult = await successLoader(true);
    assert.equal(unchangedResult.changed, false,
        'an unchanged ranking must not request a list rebuild');
    assert.equal(successState.recentSaleCustomerIds, cachedArray,
        'an unchanged ranking must retain the existing array instance');
    assert.equal(successCalls, 2);
    const cachedResult = await successLoader();
    assert.equal(cachedResult.cached, true,
        'the 30-second cache must avoid unnecessary native database calls');
    assert.equal(successCalls, 2);

    // Empty recent results must remain a valid result; ordinary customers are not removed.
    const emptyState = {
        recentSaleCustomerIds: [],
        recentSaleCustomersLoadedAt: 0,
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    const emptyLoader = createLoader(emptyState, {
        invoke: () => Promise.resolve({ success: true, data: [] })
    }, { warn() {} });
    const emptyResult = await emptyLoader();
    assert.deepEqual(Array.from(emptyResult.ids), []);
    assert.equal(emptyResult.failed, undefined);

    const missingState = {
        recentSaleCustomerIds: [91],
        recentSaleCustomersLoadedAt: Date.now(),
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    const missingWarnings = [];
    const missingLoader = createLoader(missingState, null, {
        warn: (...args) => missingWarnings.push(args)
    });
    const missingResult = await missingLoader(true);
    assert.deepEqual(Array.from(missingResult.ids), [],
        'an unavailable async bridge must not preserve stale recent IDs');
    assert.equal(missingState.recentSaleCustomersLoadedAt, 0);
    assert.match(String(missingWarnings[0] && missingWarnings[0].join(' ')), /غير متاحة/);

    const malformedState = {
        recentSaleCustomerIds: [81],
        recentSaleCustomersLoadedAt: Date.now(),
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    const malformedWarnings = [];
    const malformedLoader = createLoader(malformedState, {
        invoke: () => Promise.resolve({ success: true, data: { ids: [71] } })
    }, { warn: (...args) => malformedWarnings.push(args) });
    const malformedResult = await malformedLoader(true);
    assert.deepEqual(Array.from(malformedResult.ids), []);
    assert.equal(malformedState.recentSaleCustomersLoadedAt, 0);
    assert.match(String(malformedWarnings[0] && malformedWarnings[0].join(' ')),
        /مصفوفة معرفات العملاء/);

    const failedState = {
        recentSaleCustomerIds: [71],
        recentSaleCustomersLoadedAt: Date.now(),
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    const failedWarnings = [];
    const failedLoader = createLoader(failedState, {
        invoke: () => Promise.reject(new Error('SQLite recent customer query failed'))
    }, { warn: (...args) => failedWarnings.push(args) });
    const failedResult = await failedLoader(true);
    assert.deepEqual(Array.from(failedResult.ids), [],
        'SQLite read errors must not leave stale ranking IDs');
    assert.equal(failedState.recentSaleCustomersLoadedAt, 0);
    assert.match(String(failedWarnings[0] && failedWarnings[0].join(' ')),
        /SQLite recent customer query failed/);

    // Simulate a slow native query: the real create-modal function must show the
    // form before starting the extra lookup, and must not await the pending response.
    const contextSource = extract('async function loadFormContext()', 'async function openCreateModal()');
    assert.equal(contextSource.includes('loadRecentCustomerSaleOrder'), false,
        'loadFormContext must not schedule ranking work before the modal is opened');
    const openCreateSource = extract('async function openCreateModal()', 'async function openEditModal(');
    const openEvents = [];
    let releaseSlowQuery;
    const slowQuery = new Promise(resolve => { releaseSlowQuery = resolve; });
    const slowState = {
        recentSaleCustomerIds: [],
        recentSaleCustomersLoadedAt: 0,
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    const slowClient = {
        invoke(operation) {
            openEvents.push('sqlite-request-started:' + operation);
            return slowQuery;
        }
    };
    const slowLoader = createLoader(slowState, slowClient, { warn() {} });
    const formField = { textContent: '', focus() {} };
    const createModal = new Function(
        'resetForm', '$', 'loadFormContext', 'openModal', 'setTimeout',
        'loadRecentCustomerSaleOrder',
        openCreateSource + '\nreturn openCreateModal;'
    )(
        () => openEvents.push('reset'),
        id => id === 'formTitle' ? formField : formField,
        () => { openEvents.push('form-context-ready'); return Promise.resolve(); },
        id => openEvents.push('modal-opened:' + id),
        () => 0,
        slowLoader
    );
    await createModal();
    await Promise.resolve();
    assert.ok(openEvents.indexOf('modal-opened:formModal') >= 0);
    assert.ok(
        openEvents.indexOf('modal-opened:formModal') <
        openEvents.indexOf('sqlite-request-started:getRecentSaleCustomerIds'),
        'the form must be visible before the asynchronous SQLite query begins'
    );
    assert.equal(slowState.recentSaleCustomersLoadedAt, 0,
        'the open form must not wait for the delayed database response');
    releaseSlowQuery({ success: true, data: [44] });
    await slowState.recentSaleCustomersPromise;
    assert.deepEqual(Array.from(slowState.recentSaleCustomerIds), [44]);

    // A user can continue searching while the native query is pending. On completion,
    // the exact latest search text is used; a closed menu/selected customer is untouched.
    const focusStart = deliveriesScreen.indexOf("$('f_party_display').addEventListener('focus', () => {");
    const focusEnd = deliveriesScreen.indexOf("    $('f_party_display').addEventListener('input'", focusStart);
    assert.ok(focusStart >= 0 && focusEnd > focusStart, 'customer focus refresh handler must exist');
    const focusSource = deliveriesScreen.slice(focusStart, focusEnd);
    const focusListeners = {};
    const focusField = {
        value: '',
        addEventListener(eventName, handler) { focusListeners[eventName] = handler; }
    };
    let menuVisible = true;
    const menuNode = { classList: { contains: () => menuVisible } };
    const renderedQueries = [];
    const focusState = {
        recentSaleCustomerIds: [],
        recentSaleCustomersLoadedAt: 0,
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    let releaseFocusQuery;
    const focusLoader = createLoader(focusState, {
        invoke: () => new Promise(resolve => { releaseFocusQuery = resolve; })
    }, { warn() {} });
    new Function('$', 'renderCustomerMenu', 'loadRecentCustomerSaleOrder', 'document',
        focusSource
    )(
        id => id === 'f_party_display' ? focusField : menuNode,
        query => renderedQueries.push(query),
        focusLoader,
        { get activeElement() { return focusField; } }
    );
    focusListeners.focus();
    await Promise.resolve(); // Allow the asynchronous native request to be issued.
    focusField.value = 'عميل بحثه تغير أثناء الانتظار';
    releaseFocusQuery({ success: true, data: [5] });
    await focusState.recentSaleCustomersPromise;
    await Promise.resolve();
    assert.equal(renderedQueries[0], '',
        'the customer menu must appear immediately on focus');
    assert.equal(renderedQueries.at(-1), 'عميل بحثه تغير أثناء الانتظار',
        'a delayed ranking result must use the latest search query');

    // Selecting a customer closes the menu. A late response may update ranking state,
    // but it must neither reopen the menu nor change the user's current selection.
    const selectedFocusState = {
        recentSaleCustomerIds: [],
        recentSaleCustomersLoadedAt: 0,
        recentSaleCustomersPromise: null,
        recentSaleCustomersRequestGeneration: 0
    };
    let releaseSelectedQuery;
    const selectedFocusLoader = createLoader(selectedFocusState, {
        invoke: () => new Promise(resolve => { releaseSelectedQuery = resolve; })
    }, { warn() {} });
    const selectedField = { value: '', addEventListener(eventName, handler) { focusListeners[eventName] = handler; } };
    let selectedMenuVisible = true;
    const selectedRendered = [];
    new Function('$', 'renderCustomerMenu', 'loadRecentCustomerSaleOrder', 'document',
        focusSource
    )(
        id => id === 'f_party_display'
            ? selectedField
            : { classList: { contains: () => selectedMenuVisible } },
        query => selectedRendered.push(query),
        selectedFocusLoader,
        { get activeElement() { return selectedField; } }
    );
    focusListeners.focus();
    await Promise.resolve(); // Allow the asynchronous native request to be issued.
    selectedField.value = 'العميل الذي اختاره المستخدم';
    selectedMenuVisible = false;
    releaseSelectedQuery({ success: true, data: [8] });
    await selectedFocusState.recentSaleCustomersPromise;
    await Promise.resolve();
    assert.equal(selectedField.value, 'العميل الذي اختاره المستخدم');
    assert.deepEqual(selectedRendered, [''],
        'a closed menu must not be rebuilt after the user selects a customer');

    // Execute the page's actual rendering function: ranked customers lead, while
    // customers without sales remain visible in their original relative order.
    const customerNameSource = extract('function customerName(', 'function uniqueCustomers(');
    const uniqueCustomersSource = extract('function uniqueCustomers(', 'async function loadCustomers(');
    const renderSource = extract('function renderCustomerMenu(query)', 'function firstSaleValue(');
    const customerName = new Function(customerNameSource + '\nreturn customerName;')();
    const uniqueCustomers = new Function('customerName',
        uniqueCustomersSource + '\nreturn uniqueCustomers;'
    )(customerName);
    const menu = {
        innerHTML: '',
        classList: { add() {}, remove() {}, contains: () => true },
        querySelectorAll: () => []
    };
    const renderState = {
        customers: [
            { id: 1, name: 'عميل واحد' },
            { id: 2, name: 'عميل اثنان' },
            { id: 3, name: 'عميل ثلاثة' },
            { id: 4, name: 'عميل أربعة' }
        ],
        recentSaleCustomerIds: [2, 1]
    };
    const renderMenu = new Function('state', '$', 'uniqueCustomers', 'customerName', 'esc',
        renderSource + '\nreturn renderCustomerMenu;'
    )(
        renderState,
        id => menu,
        uniqueCustomers,
        customerName,
        value => String(value)
    );
    renderMenu('');
    const rankTwoAt = menu.innerHTML.indexOf('data-id="2"');
    const rankOneAt = menu.innerHTML.indexOf('data-id="1"');
    const noSaleThreeAt = menu.innerHTML.indexOf('data-id="3"');
    const noSaleFourAt = menu.innerHTML.indexOf('data-id="4"');
    assert.ok(rankTwoAt >= 0 && rankTwoAt < rankOneAt);
    assert.ok(rankOneAt < noSaleThreeAt && noSaleThreeAt < noSaleFourAt,
        'customers without prior sales must remain visible in their original order');
    renderState.recentSaleCustomerIds = [];
    renderMenu('');
    const noRankIds = [1, 2, 3, 4].map(id => menu.innerHTML.indexOf('data-id="' + id + '"'));
    assert.ok(noRankIds.every(index => index >= 0),
        'when there are no recent sales, every available customer must still be shown');
    assert.ok(noRankIds.every((index, position) =>
        position === 0 || noRankIds[position - 1] < index
    ), 'when the recent ranking is empty, the original customer order must be preserved');
    renderMenu('ثلاثة');
    assert.ok(menu.innerHTML.includes('data-id="3"'),
        'search must still find a customer regardless of its recent-sale rank');
    assert.equal(menu.innerHTML.includes('data-id="2"'), false,
        'search must filter the ranked list without changing the selected customer');

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

    // The exact recent-customer operation must travel through the same async
    // acknowledgement/callback path used by the page (never a synchronous SQLite call).
    const rankingRequests = [];
    let rankingClient;
    const fakeRankingAndroidInterface = {
        executeFuelSalesAsync(requestId, operation, argsJson) {
            rankingRequests.push({ requestId, operation, args: JSON.parse(argsJson) });
            queueMicrotask(() => rankingClient.resolve(
                requestId,
                JSON.stringify({ success: true, data: [12, 4, 9] })
            ));
            return JSON.stringify({ success: true, queued: true });
        }
    };
    rankingClient = create(fakeRankingAndroidInterface, { timeoutMs: 1500 });
    const rankingResponse = await rankingClient.invoke('getRecentSaleCustomerIds', []);
    assert.deepEqual(rankingResponse.data, [12, 4, 9]);
    assert.equal(rankingRequests.length, 1);
    assert.equal(rankingRequests[0].operation, 'getRecentSaleCustomerIds');
    assert.deepEqual(rankingRequests[0].args, []);
    assert.equal(rankingClient.pendingCount(), 0);

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
