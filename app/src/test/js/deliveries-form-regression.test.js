'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const projectRoot = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(projectRoot, 'main/assets/screens/deliveries.html'), 'utf8');
const mainActivity = fs.readFileSync(
    path.join(projectRoot, 'main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'),
    'utf8'
);
const deliveryAccounting = fs.readFileSync(
    path.join(projectRoot, 'main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryFeeAccounting.kt'),
    'utf8'
);

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitFor(predicate, label, timeoutMs = 6000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
        if (predicate()) return;
        await delay(10);
    }
    throw new Error('Timed out waiting for ' + label);
}

function applicationScript() {
    const parsed = new JSDOM(html);
    const scripts = Array.from(parsed.window.document.querySelectorAll('script:not([src])'));
    const script = scripts.find(item => item.textContent.includes('function loadLatestCustomerSale'));
    parsed.window.close();
    assert.ok(script, 'deliveries.html must contain its application controller');
    return script.textContent;
}

function testFeeNormalizer() {
    const match = applicationScript().match(
        /function normalizeDeliveryFeeInput\(value\)\s*\{[\s\S]*?\n    \}\s*\n\n    function setDeliveryFeeInput\(value\)\s*\{[\s\S]*?\n    \}/
    );
    assert.ok(match, 'the production screen must expose the centralized fee normalizer and setter');

    const input = { value: '' };
    const helpers = new Function('$', match[0] + '\nreturn { normalizeDeliveryFeeInput, setDeliveryFeeInput };')(
        id => id === 'f_delivery_service_fee' ? input : null
    );

    for (const invalid of [null, undefined, '', '.', ',', '-.', '-,', 'NaN', 'invalid', '-12.50']) {
        assert.equal(helpers.normalizeDeliveryFeeInput(invalid), '0.00',
            'invalid fee input must normalize to zero: ' + String(invalid));
    }
    assert.equal(helpers.normalizeDeliveryFeeInput('12.5'), '12.50');
    assert.equal(helpers.normalizeDeliveryFeeInput('1,234.5'), '1234.50');
    helpers.setDeliveryFeeInput('.');
    assert.equal(input.value, '0.00');
    helpers.setDeliveryFeeInput('89.7');
    assert.equal(input.value, '89.70');
}

function testNativeRankingContractIsReadOnlyAndStationScoped() {
    const methodStart = mainActivity.indexOf('fun getRecentSaleCustomerIds(): String');
    assert.ok(methodStart >= 0, 'MainActivity must expose the recent-sale ordering bridge');
    const methodEnd = mainActivity.indexOf('\n        @JavascriptInterface', methodStart);
    const method = mainActivity.slice(methodStart, methodEnd > methodStart ? methodEnd : undefined);

    assert.match(mainActivity, /"getRecentSaleCustomerIds"/,
        'the asynchronous native allowlist must expose the ranking operation');
    assert.match(mainActivity, /"scope"/,
        'the asynchronous native allowlist must expose authenticated scope');
    assert.match(mainActivity, /withContext\(Dispatchers\.IO\)/,
        'the asynchronous bridge must execute SQLite work away from the WebView thread');
    assert.match(method, /requireCurrentStationId\(db,\s*userId\)/,
        'the native query must derive its station from the authenticated user');
    assert.match(method, /DeliveryLookupBridgeContract\.recentCustomerIds\(db,\s*stationId\)/,
        'the actual ranking must use the tested read-only delivery lookup contract');
    assert.match(method, /put\("user_id",\s*userId\)/);
    assert.match(method, /put\("station_id",\s*stationId\)/,
        'each callback must identify the user and station that actually ran the query');
    assert.match(mainActivity, /db\.getParties\(type \?: "",\s*requireCurrentStationId\(db,\s*activity\.currentUserId\)\)/,
        'the unranked customer list must also be restricted to the authenticated station');
    assert.doesNotMatch(method, /saveFuelSaleRecord|updateFuelSaleRecord|completeSale|db\.(insert|update|delete)\s*\(/,
        'the ranking bridge must not write sales, accounting, or inventory data');

    const rankingStart = deliveryAccounting.indexOf('private fun buildRecentCustomerSaleIdsQuery(');
    const rankingEnd = deliveryAccounting.indexOf('\n    fun savedDeliveryLocations(', rankingStart);
    const queryBuilder = deliveryAccounting.slice(rankingStart, rankingEnd);
    assert.ok(rankingStart >= 0 && rankingEnd > rankingStart,
        'the station-scoped SQL ranking builder must exist');
    assert.match(queryBuilder, /for \(table in listOf\("fuel_sales", "sales_transactions"\)\)/,
        'both fuel and product sale sources must contribute to ranking');
    assert.match(queryBuilder, /src\.station_id=\?/,
        'standalone sale rows must be station-filtered through a bound parameter');
    assert.match(queryBuilder, /st\.station_id=\?/,
        'joined fuel sales must be scoped through their owning invoice when available');
    assert.match(queryBuilder, /product_id/,
        'product-sale rows must be identified without ranking fuel rows twice');
    assert.match(queryBuilder, /ORDER BY MAX\(COALESCE\(sale_epoch,0\)\) DESC/,
        'customers must be ordered by their most recent sale timestamp');
    assert.ok(html.includes('verifiedScopeKey !== scope.scopeKey'),
        'a failed ranking callback must revalidate the live station before retaining cached data');
}

function testDeliverySaveUsesSeparateInvoiceAndFuelSaleIds() {
    const methodStart = mainActivity.indexOf('fun saveDeliveryRecord(jsonData: String): String');
    assert.ok(methodStart >= 0, 'MainActivity must expose the delivery SQLite save operation');
    const methodEnd = mainActivity.indexOf('\n        @JavascriptInterface', methodStart);
    const method = mainActivity.slice(methodStart, methodEnd > methodStart ? methodEnd : undefined);

    assert.match(html, /id="f_fuel_sale_id"\s+name="fuel_sale_id"/,
        'the form must retain the exact fuel_sales row ID separately from its invoice ID');
    assert.match(method, /val fuelSaleReferenceId = input\.optLong\("fuel_sale_id", 0L\)/,
        'the save bridge must prefer the explicit fuel_sales ID for fee persistence');
    assert.match(method, /saleReferenceId = fuelSaleReferenceId/,
        'the exact fuel_sales row must be passed to fee persistence after the invoice delivery is created');
    assert.match(html, /fuel_sale_id:\s*number\(\$\('f_fuel_sale_id'\)\.value\)\s*\|\|\s*null/,
        'the UI save payload must retain the fuel_sales ID');
}

async function run() {
    const calls = { customers: 0, latest: [], recent: [], scope: 0, scopeRequests: [], page: 0 };
    const salesByCustomer = {
        11: {
            sale_id: 7011, fuel_sale_id: 7011, parent_sale_id: 97011, customer_party_id: 11,
            quantity: 10, price_per_liter: 1000, total_amount: 10000,
            delivery_service_fee: 125.75, delivery_fee_mode: 'driver',
            delivery_location: 'حي الاختبار', cash_customer_name: 'اسم نقدي للاختبار',
            payment_method: 'cash', fuel_type_name: 'ديزل'
        },
        33: {
            sale_id: 7033, fuel_sale_id: 7033, parent_sale_id: 97033, customer_party_id: 33,
            quantity: 8, price_per_liter: 900, total_amount: 7200,
            delivery_service_fee: 88.5, delivery_fee_mode: 'driver',
            delivery_location: 'شارع الاختبار', cash_customer_name: 'اسم نقدي ثانٍ',
            payment_method: 'cash', fuel_type_name: 'ديزل'
        }
    };
    const customersByStation = {
        1: [
            { id: 11, name: 'عميل لديه بيع سابق' },
            { id: 22, name: 'عميل بلا بيع سابق' },
            { id: 33, name: 'عميل آخر لديه بيع سابق' },
            { id: 11, name: 'نسخة مكررة للعميل الأول' }
        ],
        2: [
            { id: 12, name: 'عميل المحطة الثانية أ' },
            { id: 13, name: 'عميل المحطة الثانية ب' }
        ],
        3: [
            { id: 44, name: 'عميل المحطة الثالثة أ' },
            { id: 55, name: 'عميل المحطة الثالثة ب' },
            { id: 66, name: 'عميل المحطة الثالثة بلا بيع' }
        ],
        4: [
            { id: 71, name: 'عميل بلا تعاملات أ' },
            { id: 72, name: 'عميل بلا تعاملات ب' }
        ],
        5: [
            { id: 88, name: 'عميل مع تعذر الترتيب أ' },
            { id: 89, name: 'عميل مع تعذر الترتيب ب' }
        ],
        6: [
            { id: 99, name: 'عميل المحطة السادسة أ' },
            { id: 100, name: 'عميل المحطة السادسة ب' },
            { id: 101, name: 'عميل المحطة السادسة بلا تعاملات' }
        ]
    };

    const json = value => JSON.stringify(value);
    const pendingRankings = [];
    let activeScope = { user_id: 101, station_id: 1 };
    let failNextRanking = false;
    let failNextScope = false;
    let scopeAfterFailedRanking = null;
    let window;

    const bridge = {
        executeFuelSalesAsync(requestId, operation, argsJson) {
            assert.deepEqual(JSON.parse(argsJson || '[]'), [],
                'deliveries must invoke the lookup endpoints without user-supplied station IDs');

            if (operation === 'scope') {
                calls.scope += 1;
                const scope = { ...activeScope };
                const fail = failNextScope;
                failNextScope = false;
                const record = {
                    requestId,
                    scope,
                    status: 'pending',
                    response: null
                };
                calls.scopeRequests.push(record);
                queueMicrotask(() => {
                    record.status = fail ? 'failed' : 'succeeded';
                    record.response = fail
                        ? { success: false, error: 'SQLite scope test failure' }
                        : { success: true, ...scope };
                    window.__fuelSalesNativeResult(requestId, json(record.response));
                });
                return json({ success: true, queued: true });
            }

            assert.equal(operation, 'getRecentSaleCustomerIds',
                'deliveries must route customer ranking through the asynchronous native bridge');
            const record = {
                requestId,
                scope: { ...activeScope },
                index: calls.recent.length + 1,
                status: 'pending',
                response: null
            };
            calls.recent.push(record);

            if (failNextRanking) {
                failNextRanking = false;
                const nextScope = scopeAfterFailedRanking;
                scopeAfterFailedRanking = null;
                if (nextScope) activeScope = { ...nextScope };
                queueMicrotask(() => {
                    record.status = 'failed';
                    record.response = {
                        success: false,
                        error: 'SQLite simulated ranking read failure'
                    };
                    window.__fuelSalesNativeResult(requestId, json(record.response));
                });
            } else {
                // Deliberately hold the SQLite result to simulate slow storage and
                // verify that the modal/search/selection remain usable meanwhile.
                pendingRankings.push(record);
            }
            return json({ success: true, queued: true });
        },

        getDeliveriesPage() {
            calls.page += 1;
            return json({ success: true, data: { rows: [] } });
        },

        getCustomers() {
            calls.customers += 1;
            const rows = customersByStation[activeScope.station_id] || [];
            return json({ success: true, data: rows });
        },

        getVehicleRecords() { return json({ success: true, data: [] }); },
        getVehicles() { return json({ success: true, data: [] }); },
        getDriverRecords() { return json({ success: true, data: [] }); },
        getCurrentShift() { return json({ success: true, data: null }); },

        getLatestCustomerSaleForDelivery(customerId) {
            calls.latest.push(Number(customerId));
            const sale = salesByCustomer[Number(customerId)];
            return json({
                success: true,
                data: sale
                    ? { found: true, sale }
                    : { found: false, message: 'لا توجد عملية بيع سابقة' }
            });
        }
    };

    const dom = new JSDOM(html, {
        runScripts: 'outside-only',
        url: 'https://station.local/deliveries.html',
        pretendToBeVisual: true
    });
    window = dom.window;
    window.AndroidInterface = bridge;
    window.FuelSalesAsyncBridge = require('../../main/assets/assets-local/js/fuel-sales-async-bridge.js');
    window.HTMLFormElement.prototype.reportValidity = () => true;
    const controllerScript = applicationScript();
    const testableControllerScript = controllerScript.replace(
        /\n\}\)\(\);\s*$/,
        '\n    window.buildPayload = buildPayload;\n})();'
    );
    assert.notEqual(testableControllerScript, controllerScript,
        'the test fixture must instrument the controller before its private scope closes');
    window.eval(testableControllerScript);

    const document = window.document;
    const fee = document.getElementById('f_delivery_service_fee');
    const party = document.getElementById('f_party_display');
    const partyId = document.getElementById('f_party_id');
    const menu = document.getElementById('partyMenu');
    const formModal = document.getElementById('formModal');

    const menuIds = () => Array.from(menu.querySelectorAll('[data-id]')).map(item => item.dataset.id);
    const openCustomerPicker = () => {
        party.value = '';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        party.blur();
        party.focus();
    };
    const releaseRanking = (record, ids) => {
        const index = pendingRankings.indexOf(record);
        assert.ok(index >= 0, 'expected a delayed ranking callback to be pending');
        pendingRankings.splice(index, 1);
        record.status = 'succeeded';
        record.response = {
            success: true,
            data: ids,
            user_id: record.scope.user_id,
            station_id: record.scope.station_id
        };
        window.__fuelSalesNativeResult(record.requestId, json(record.response));
    };
    const expectMenuIds = async (ids, label) => {
        await waitFor(() => menu.classList.contains('show') &&
            JSON.stringify(menuIds()) === JSON.stringify(ids), label);
        assert.deepEqual(menuIds(), ids, label);
    };

    try {
        testNativeRankingContractIsReadOnlyAndStationScoped();
        assert.ok(fee, 'the delivery form must render its service-fee field');
        assert.equal(fee.readOnly, true, 'the service fee must remain readonly');

        await waitFor(() => calls.page > 0, 'initial deliveries page load');

        // The modal must become visible synchronously, before any lookup or SQLite ranking result.
        const openedAt = Date.now();
        const openPromise = window.openCreateModal();
        assert.ok(formModal.classList.contains('show'),
            'the create modal must be visible before its asynchronous lookup promises settle');
        assert.ok(Date.now() - openedAt < 250,
            'form opening should remain responsive while the recent-customer SQLite lookup is pending');
        await openPromise;
        assert.equal(fee.value, '0.00', 'a new delivery form must start at 0.00');

        await waitFor(() => calls.customers > 0 && menu.classList.contains('show'),
            'initial station-scoped customer list and picker');
        await waitFor(() => calls.recent.length === 1,
            'one asynchronous recent-customer ranking dispatch');
        assert.equal(pendingRankings.length, 1,
            'the recent ranking response must still be pending after the form becomes interactive');

        assert.deepEqual(menuIds(), ['11', '22', '33'],
            'the current customer list must be immediately usable before the ranking response arrives');

        // Search and select a customer while the ranking query is deliberately slow.
        party.value = 'عميل بلا بيع سابق';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        assert.deepEqual(menuIds(), ['22'],
            'search must filter the existing list while the ranking query is pending');
        menu.querySelector('[data-id="22"]').click();
        await waitFor(() => partyId.value === '22' && party.value === 'عميل بلا بيع سابق',
            'customer selection while ranking is pending');
        await waitFor(() => calls.latest.length === 1 && calls.latest[0] === 22,
            "the selected customer's own latest-sale lookup");
        assert.equal(fee.value, '0.00',
            'a customer with no previous sale must not inherit another customer fee');
        assert.equal(document.getElementById('f_fuel_sale_id').value, '',
            'a customer without a previous sale must not retain another customer fuel_sales ID');

        const stationOneRanking = calls.recent[0];
        releaseRanking(stationOneRanking, [33, 11]);
        await waitFor(() => pendingRankings.length === 0, 'delayed ranking completion');
        await delay(30);
        assert.equal(partyId.value, '22',
            'a completed ranking refresh must not clear or replace the selected customer ID');
        assert.equal(party.value, 'عميل بلا بيع سابق',
            'a completed ranking refresh must not overwrite the selected customer display name');
        assert.equal(fee.value, '0.00',
            'a ranking refresh must not modify customer sale context or delivery fees');

        // The refreshed order is used on the next open; non-recent customers remain available.
        openCustomerPicker();
        await expectMenuIds(['33', '11', '22'],
            'recent customers first, followed by customers without recent sales');
        assert.equal(menuIds().filter(id => id === '11').length, 1,
            'duplicate customer rows must be removed before rendering');

        const latestBeforeNormalFlow = calls.latest.length;
        party.value = 'عميل بلا بيع سابق';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        assert.deepEqual(menuIds(), ['22'],
            'search must still find a customer without any sale history');

        party.value = '';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        menu.querySelector('[data-id="11"]').click();
        await waitFor(() => fee.value === '125.75', 'actual fee from customer 11 original sale');
        assert.equal(fee.value, '125.75',
            'selecting a customer with an existing sale must preserve the actual original sale fee');
        assert.equal(document.getElementById('f_delivery_fee_mode').value, 'driver',
            'the latest fuel sale fee calculation mode must populate the form');
        assert.equal(document.getElementById('f_location').value, 'حي الاختبار',
            'the latest fuel sale delivery location must populate the form location');
        assert.equal(document.getElementById('f_cash_customer_name').value, 'اسم نقدي للاختبار',
            'the latest fuel sale cash-customer name must populate the form');
        assert.equal(document.getElementById('f_sale_id').value, '97011',
            'delivery.sale_id must retain the sales_transactions invoice ID');
        assert.equal(document.getElementById('f_fuel_sale_id').value, '7011',
            'fee persistence must retain the distinct fuel_sales row ID');
        const savePayload = window.buildPayload();
        assert.equal(savePayload.sale_id, 97011,
            'the save payload must reference the invoice row from sales_transactions');
        assert.equal(savePayload.fuel_sale_id, 7011,
            'the save payload must carry the source fuel_sales row ID');
        assert.equal(savePayload.delivery_service_fee, 125.75);
        assert.equal(savePayload.delivery_fee_mode, 'driver');
        assert.equal(savePayload.delivery_location, 'حي الاختبار');
        assert.equal(savePayload.cash_customer_name, 'اسم نقدي للاختبار');

        openCustomerPicker();
        await waitFor(() => menu.classList.contains('show'), 'customer picker after first selection');
        menu.querySelector('[data-id="22"]').click();
        await waitFor(() => fee.value === '0.00' && partyId.value === '22',
            'no-sale customer selection to clear the previous fee');
        assert.equal(fee.value, '0.00',
            'selecting a customer without a sale must not retain the previous customer fee');

        openCustomerPicker();
        await waitFor(() => menu.classList.contains('show'), 'customer picker after no-sale customer');
        menu.querySelector('[data-id="33"]').click();
        await waitFor(() => fee.value === '88.50', 'fee from the next selected customer');
        assert.equal(fee.value, '88.50',
            'changing customers several times must load the newly selected sale fee');

        openCustomerPicker();
        await waitFor(() => menu.classList.contains('show'), 'customer picker before clearing customer data');
        menu.querySelector('[data-anonymous]').click();
        assert.equal(partyId.value, '',
            'clearing customer data must clear the selected party ID');
        assert.equal(fee.value, '0.00',
            'clearing customer data must clear the previously loaded fee');

        // Reopening within the cache window must not dispatch another recent-sales query.
        const recentCallsBeforeCachedOpen = calls.recent.length;
        const secondOpen = window.openCreateModal();
        assert.ok(formModal.classList.contains('show'),
            'reopening the form must also display it before background lookups finish');
        await secondOpen;
        await waitFor(() => calls.customers >= 2, 'customer list reload on a fresh form');
        await delay(80);
        assert.equal(calls.recent.length, recentCallsBeforeCachedOpen,
            'the recent-sale ranking must be reused from the station-scoped 30-second cache');

        assert.ok(calls.scope >= 1, 'the cache must be tied to the authenticated user/station scope');
        assert.equal(calls.recent.length, 1,
            'reopening the delivery form must not rerun a fresh recent-sales query while the cache is valid');
        assert.deepEqual(calls.latest.slice(latestBeforeNormalFlow), [11, 22, 33],
            'latest-sale context must be loaded only for the customer selected by the operator');

        // Race: a delayed station-2 result must not replace a newer station-3 ranking.
        activeScope = { user_id: 202, station_id: 2 };
        window.dispatchEvent(new window.Event('focus'));
        await waitFor(() => calls.recent.length === 2,
            'ranking request for a newly authenticated station');
        const stationTwoRanking = calls.recent[1];

        activeScope = { user_id: 303, station_id: 3 };
        window.dispatchEvent(new window.Event('focus'));
        await waitFor(() => calls.recent.length === 3,
            'a newer station-scoped ranking request');
        const stationThreeRanking = calls.recent[2];

        openCustomerPicker();
        await waitFor(() => menuIds().includes('44') && menuIds().includes('66'),
            'customer rows refreshed from the newly authenticated station');
        assert.deepEqual(menuIds(), ['44', '55', '66'],
            'the old station customer rows must be cleared and replaced by the new station list');

        releaseRanking(stationTwoRanking, [33, 11]);
        await delay(40);
        assert.deepEqual(menuIds(), ['44', '55', '66'],
            'a delayed callback from the previous station must be ignored');

        // A live search remains intact while ranking data arrives for the new station.
        party.value = 'عميل المحطة الثالثة بلا بيع';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        assert.deepEqual(menuIds(), ['66'],
            'search must be scoped to the replacement station customer rows');
        assert.equal(party.value, 'عميل المحطة الثالثة بلا بيع',
            'a stale previous-station callback must not overwrite the current search text');
        assert.deepEqual(menuIds(), ['66'],
            'a stale previous-station callback must not alter the current station search results');

        releaseRanking(stationThreeRanking, [55, 44]);
        await waitFor(() => menu.classList.contains('show') && menuIds().join(',') === '66',
            'new station ranking must update without clearing the active search');
        assert.equal(party.value, 'عميل المحطة الثالثة بلا بيع',
            'a ranking refresh must preserve the operator search text');

        party.value = '';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        await expectMenuIds(['55', '44', '66'],
            'only the newest station ranking may reorder the current station customer list');

        const renderedMenuBeforeUnchangedRefresh = menu.innerHTML;
        const retainedMenuOption = menu.querySelector('[data-id="55"]');
        const recentCallsBeforeUnchangedRefresh = calls.recent.length;
        window.dispatchEvent(new window.Event('focus'));
        await delay(60);
        assert.equal(menu.innerHTML, renderedMenuBeforeUnchangedRefresh,
            'an unchanged cached ranking must retain the same menu markup');
        assert.strictEqual(menu.querySelector('[data-id="55"]'), retainedMenuOption,
            'an unchanged result must reuse the existing dropdown DOM nodes, not rebuild them');
        assert.equal(calls.recent.length, recentCallsBeforeUnchangedRefresh,
            'a valid cache must avoid repeating the recent-sales SQL query');

        // No-sales case: an empty recent-ID result leaves all customers visible in source order.
        activeScope = { user_id: 404, station_id: 4 };
        window.dispatchEvent(new window.Event('focus'));
        await waitFor(() => calls.recent.length === 4,
            'ranking request for a station with no recent sales');
        const noSaleRanking = calls.recent[3];
        openCustomerPicker();
        await waitFor(() => menuIds().includes('71') && menuIds().includes('72'),
            'station-4 customer list');
        releaseRanking(noSaleRanking, []);
        await expectMenuIds(['71', '72'],
            'an empty ranking must preserve every customer without changing order');
        // Let the just-released read finish its Promise/finally cleanup before the next
        // session switch. The next case still races a failed ranking against a live switch.
        await delay(25);

        // If the session changes after scope preflight but the ranking query fails,
        // the post-failure scope check must discard the old station's customer rows.
        activeScope = { user_id: 505, station_id: 5 };
        failNextRanking = true;
        scopeAfterFailedRanking = { user_id: 606, station_id: 6 };
        window.dispatchEvent(new window.Event('focus'));

        // Identify the station-5 command by its authenticated scope, not by a
        // positional array length. The failure callback may complete and dispatch
        // station 6 between two polling ticks, so count===5 is inherently racy.
        await waitFor(
            () => calls.recent.some(record =>
                record.scope.user_id === 505 && record.scope.station_id === 5),
            'the exact station-5 ranking request'
        );
        const stationFiveRankings = calls.recent.filter(record =>
            record.scope.user_id === 505 && record.scope.station_id === 5);
        assert.equal(stationFiveRankings.length, 1,
            'one station transition must not dispatch duplicate station-5 ranking queries');
        const stationFiveRanking = stationFiveRankings[0];
        await waitFor(() => stationFiveRanking.status === 'failed',
            'the station-5 SQLite failure callback');

        assert.equal(stationFiveRanking.response?.error, 'SQLite simulated ranking read failure',
            'the station-5 ranking must fail for the intended SQLite read error');
        assert.ok(
            calls.scopeRequests.some(record =>
                record.scope.user_id === 606 &&
                record.scope.station_id === 6 &&
                record.status === 'succeeded'),
            'after station-5 failure, the active authenticated scope must be re-read as 606:6'
        );

        await waitFor(
            () => calls.recent.some(record =>
                record.scope.user_id === 606 && record.scope.station_id === 6),
            'the recovery ranking request for authenticated station 6'
        );
        const stationSixRankings = calls.recent.filter(record =>
            record.scope.user_id === 606 && record.scope.station_id === 6);
        assert.equal(stationSixRankings.length, 1,
            'recovery must issue one station-6 ranking query, not duplicate requests');
        const stationSixRanking = stationSixRankings[0];
        assert.equal(stationSixRanking.status, 'pending',
            'station-6 result must remain independently pending until the test releases it');
        assert.deepEqual(stationSixRanking.scope, { user_id: 606, station_id: 6 },
            'the retry must execute under the newly authenticated user and station');

        openCustomerPicker();
        await waitFor(() => menuIds().includes('99') && menuIds().includes('101'),
            'station-6 customer rows must replace the previous station rows');
        assert.deepEqual(menuIds(), ['99', '100', '101'],
            'no station-5 or station-4 customer may remain visible after failed cross-station refresh');
        assert.equal(menuIds().includes('88'), false,
            'station-5 customer 88 must not leak into the station-6 picker');
        assert.equal(menuIds().includes('89'), false,
            'station-5 customer 89 must not leak into the station-6 picker');
        releaseRanking(stationSixRanking, [100, 99]);
        await expectMenuIds(['100', '99', '101'],
            'the newly authenticated station ranking must be applied after recovery');

        testFeeNormalizer();
        testDeliverySaveUsesSeparateInvoiceAndFuelSaleIds();
        console.log(
            'Deliveries customer ranking regression tests passed: immediate modal, delayed async lookup, ' +
            'selection preservation, search, cache reuse, stale station response, empty results, ' +
            'and identity-verified station-5 failure recovery into station 6.'
        );
    } finally {
        // Complete any deliberately delayed native callbacks before disposing the DOM.
        // This prevents a secondary timeout from firing against a closed WebView fixture
        // and obscuring the assertion that originally failed.
        pendingRankings.splice(0).forEach(record => {
            window.__fuelSalesNativeResult(
                record.requestId,
                json({
                    success: true,
                    data: [],
                    user_id: record.scope.user_id,
                    station_id: record.scope.station_id
                })
            );
        });
        await delay(20);
        dom.window.close();
    }
}

module.exports = run;

if (require.main === module) {
    run().catch(error => {
        console.error(error);
        process.exitCode = 1;
    });
}
