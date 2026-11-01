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
}

async function run() {
    const calls = { customers: 0, latest: [], recent: [], scope: 0, page: 0 };
    const salesByCustomer = {
        11: {
            sale_id: 7011, fuel_sale_id: 7011, customer_party_id: 11,
            quantity: 10, price_per_liter: 1000, total_amount: 10000,
            delivery_service_fee: 125.75, delivery_fee_mode: 'driver',
            delivery_location: 'حي الاختبار', payment_method: 'cash', fuel_type_name: 'ديزل'
        },
        33: {
            sale_id: 7033, fuel_sale_id: 7033, customer_party_id: 33,
            quantity: 8, price_per_liter: 900, total_amount: 7200,
            delivery_service_fee: 88.5, delivery_fee_mode: 'driver',
            delivery_location: 'شارع الاختبار', payment_method: 'cash', fuel_type_name: 'ديزل'
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
        ]
    };

    const json = value => JSON.stringify(value);
    const pendingRankings = [];
    let activeScope = { user_id: 101, station_id: 1 };
    let failNextRanking = false;
    let failNextScope = false;
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
                queueMicrotask(() => {
                    window.__fuelSalesNativeResult(
                        requestId,
                        json(fail
                            ? { success: false, error: 'SQLite scope test failure' }
                            : { success: true, ...scope })
                    );
                });
                return json({ success: true, queued: true });
            }

            assert.equal(operation, 'getRecentSaleCustomerIds',
                'deliveries must route customer ranking through the asynchronous native bridge');
            const record = {
                requestId,
                scope: { ...activeScope },
                index: calls.recent.length + 1
            };
            calls.recent.push(record);

            if (failNextRanking) {
                failNextRanking = false;
                queueMicrotask(() => {
                    window.__fuelSalesNativeResult(
                        requestId,
                        json({ success: false, error: 'SQLite simulated ranking read failure' })
                    );
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
    window.eval(applicationScript());

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
        window.__fuelSalesNativeResult(
            record.requestId,
            json({
                success: true,
                data: ids,
                user_id: record.scope.user_id,
                station_id: record.scope.station_id
            })
        );
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

        // SQLite failure is non-fatal: the form and customers remain usable in source order.
        activeScope = { user_id: 505, station_id: 5 };
        failNextRanking = true;
        window.dispatchEvent(new window.Event('focus'));
        await waitFor(() => calls.recent.length === 5,
            'ranking request that will return a SQLite failure');
        openCustomerPicker();
        await waitFor(() => menuIds().includes('88') && menuIds().includes('89'),
            'station-5 customer list remains available after a ranking failure');
        await delay(60);
        assert.deepEqual(menuIds(), ['88', '89'],
            'a failed recent-sales query must fall back to all customers, not block the picker');

        testFeeNormalizer();
        console.log(
            'Deliveries customer ranking regression tests passed: immediate modal, delayed async lookup, ' +
            'selection preservation, search, cache reuse, stale station response, empty results and SQLite failure.'
        );
    } finally {
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
