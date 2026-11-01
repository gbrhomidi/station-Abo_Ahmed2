'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { create } = require('../../main/assets/assets-local/js/fuel-sales-async-bridge.js');

const root = path.resolve(__dirname, '../..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

async function run() {
    const deliveries = read('main/assets/screens/deliveries.html');
    const mainActivity = read('main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
    const accounting = read('main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryFeeAccounting.kt');
    const contract = read('main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryLookupBridgeContract.kt');

    // The page must load and call the shared asynchronous bridge for both scope and ranking.
    assert.ok(deliveries.includes('assets-local/js/fuel-sales-async-bridge.js'),
        'deliveries.html must load the asynchronous native bridge adapter');
    assert.ok(deliveries.includes('window.__fuelSalesNativeResult'),
        'deliveries.html must install the asynchronous result callback');
    const loaderStart = deliveries.indexOf('async function loadRecentCustomerSaleOrder(');
    const loaderEnd = deliveries.indexOf('function selectAnonymousCustomer(', loaderStart);
    assert.ok(loaderStart >= 0 && loaderEnd > loaderStart,
        'the page must expose the current recent-customer ranking loader');
    const loader = deliveries.slice(loaderStart, loaderEnd);
    assert.ok(loader.includes("client.invoke('scope', [])"),
        'the ranking loader must resolve the authenticated user/station scope asynchronously');
    assert.ok(loader.includes("client.invoke('getRecentSaleCustomerIds', [])"),
        'the ranking loader must request the canonical recent-sale operation asynchronously');
    assert.ok(loader.includes('requestGeneration !== state.recentSaleCustomersRequestGeneration'),
        'out-of-order responses must be rejected by request generation');
    assert.ok(loader.includes('state.recentSaleCustomersScopeKey !== scope.scopeKey'),
        'callbacks from a different authenticated station must be rejected');
    assert.ok(loader.includes('sameRecentCustomerOrder'),
        'the page must avoid replacing an unchanged ranking');
    assert.ok(loader.includes('now - state.recentSaleCustomersLoadedAt < 30000'),
        'the recent-customer ranking must use a bounded cache');
    assert.ok(loader.includes('state.recentSaleCustomersRetryAfter = Date.now() + 5000'),
        'failed reads must use a bounded retry backoff');

    // The modal becomes visible before background lookups start and is not async/await gated.
    const createStart = deliveries.indexOf('function openCreateModal()');
    const editStart = deliveries.indexOf('function openEditModal(row)', createStart);
    assert.ok(createStart >= 0 && editStart > createStart, 'create and edit modal entry points must exist');
    const createModal = deliveries.slice(createStart, editStart);
    assert.ok(createModal.indexOf("openModal('formModal')") >= 0,
        'the create modal must be shown immediately');
    const rankingDispatchAt = createModal.search(/loadRecentCustomerSaleOrder\\s*\\(/);
    assert.ok(
        rankingDispatchAt >= 0 &&
        createModal.indexOf("openModal('formModal')") < rankingDispatchAt,
        'the modal must be opened before ranking lookup dispatch'
    );
    assert.doesNotMatch(createModal, /await\s+loadRecentCustomerSaleOrder|await\s+loadFormContext/,
        'the create modal must not wait for customer ranking or form context');
    assert.ok(createModal.includes('scheduleDeliveryFormLookups()'),
        'remaining form lookups must be scheduled after opening');
    assert.ok(createModal.includes('loadRecentCustomerSaleOrder(true)'),
        'every newly opened delivery form must revalidate its station before reusing cached ranking');

    // Dropdown behavior: ranked users first, all non-recent customers remain visible,
    // and identical markup reuses the current DOM instead of rebuilding it.
    const renderStart = deliveries.indexOf('function renderCustomerMenu(query)');
    const renderEnd = deliveries.indexOf('function firstSaleValue(', renderStart);
    assert.ok(renderStart >= 0 && renderEnd > renderStart, 'customer menu renderer must exist');
    const renderer = deliveries.slice(renderStart, renderEnd);
    assert.ok(renderer.includes('state.recentSaleCustomerIds.map'),
        'the customer menu must use the recent-sale order');
    assert.ok(renderer.includes('Number.MAX_SAFE_INTEGER'),
        'customers without recent sales must remain after ranked customers');
    assert.ok(renderer.includes('state.customerMenuMarkup !== markup'),
        'an unchanged menu must not be rebuilt');
    assert.ok(deliveries.includes('function uniqueCustomers(customers)'),
        'duplicate customer rows must be removed');
    assert.ok(deliveries.includes("renderCustomerMenu($('f_party_display').value)"),
        'opening the customer picker must preserve the current search text');
    assert.ok(deliveries.includes('reloadCustomersForAuthenticatedScope'),
        'station changes must reload the base customer rows for the authenticated station');

    // Native bridge routes are allowlisted and run their SQLite reads away from the WebView thread.
    assert.match(mainActivity, /@JavascriptInterface\s+fun executeFuelSalesAsync\s*\(/,
        'MainActivity must expose the asynchronous native endpoint');
    assert.match(mainActivity, /withContext\(Dispatchers\.IO\)/,
        'asynchronous lookup work must execute on Dispatchers.IO');
    assert.ok(mainActivity.includes('"scope", "locations", "getRecentSaleCustomerIds"'),
        'the scope and recent-ranking operations must be included in the async allowlist');
    assert.ok(mainActivity.includes('"scope" -> readFuelSalesScope()'),
        'the scope operation must reach the authenticated scope reader');
    assert.ok(mainActivity.includes('"getRecentSaleCustomerIds" -> getRecentSaleCustomerIds()'),
        'the recent-ranking operation must reach the canonical native bridge method');

    const nativeStart = mainActivity.indexOf('fun getRecentSaleCustomerIds(): String');
    const nativeEnd = mainActivity.indexOf('\n        @JavascriptInterface', nativeStart);
    assert.ok(nativeStart >= 0 && nativeEnd > nativeStart, 'native ranking method must exist');
    const nativeMethod = mainActivity.slice(nativeStart, nativeEnd);
    assert.ok(nativeMethod.includes('val userId = activity.currentUserId'),
        'native ranking must bind the authenticated user');
    assert.ok(nativeMethod.includes('requireCurrentStationId(db, userId)'),
        'native ranking must derive station scope from the authenticated user');
    assert.ok(nativeMethod.includes('DeliveryLookupBridgeContract.recentCustomerIds(db, stationId)'),
        'native ranking must use the read-only delivery SQLite query');
    assert.ok(nativeMethod.includes('put("user_id", userId)') &&
              nativeMethod.includes('put("station_id", stationId)'),
        'the native callback must carry the exact user and station that ran the query');
    assert.doesNotMatch(nativeMethod, /saveFuelSaleRecord|updateFuelSaleRecord|db\.(insert|update|delete)\s*\(/,
        'customer ranking must not write sales, inventory, or accounting data');

    // The unranked base customer list must also be station-scoped.
    assert.match(mainActivity,
        /db\.getParties\(type \?: "",\s*requireCurrentStationId\(db, activity\.currentUserId\)\)/,
        'getCustomers must return customers belonging to the current station only');

    // The production SQLite query merges fuel and product sources and is a SELECT-only operation.
    const queryStart = accounting.indexOf('private fun buildRecentCustomerSaleIdsQuery(');
    const queryEnd = accounting.indexOf('\n    fun savedDeliveryLocations(', queryStart);
    assert.ok(queryStart >= 0 && queryEnd > queryStart, 'recent-sale SQL builder must exist');
    const queryBuilder = accounting.slice(queryStart, queryEnd);
    assert.ok(queryBuilder.includes('listOf("fuel_sales", "sales_transactions")'),
        'both fuel and product transaction tables must contribute to recent-customer ranking');
    assert.ok(queryBuilder.includes('src.station_id=?') &&
              queryBuilder.includes('st.station_id=?') &&
              queryBuilder.includes('fs.station_id=?'),
        'all supported SQL paths must bind the current station');
    assert.ok(queryBuilder.includes('product_id'),
        'product transactions must be selected using product identity');
    assert.ok(queryBuilder.includes('ORDER BY MAX(COALESCE(sale_epoch,0)) DESC'),
        'customers must be ordered by the timestamp of their most recent transaction');

    const contractMethod = contract.match(/fun recentCustomerIds\([\s\S]*?\n    }/);
    assert.ok(contractMethod, 'the JSON boundary for recent-customer lookups must exist');
    assert.ok(contractMethod[0].includes('DeliveryFeeAccounting.recentCustomerSaleIds'),
        'the bridge contract must call the production SQL reader');
    const queryMethod = accounting.match(/internal fun recentCustomerSaleIds\([\s\S]*?\n    }/);
    assert.ok(queryMethod, 'the actual recent-customer query method must exist');
    assert.match(queryMethod[0], /helper\.readableDatabase/,
        'ranking must use readableDatabase');
    assert.match(queryMethod[0], /queryRunner\.rawQuery/,
        'ranking must execute through the read-only query runner');
    assert.doesNotMatch(queryMethod[0], /writableDatabase|execSQL|\b(?:INSERT|UPDATE|DELETE)\b/i,
        'recent-customer ranking must not mutate SQLite');

    // Exercise the real JavaScript adapter's async acknowledgement/callback contract.
    const requests = [];
    let client;
    const fakeAndroidInterface = {
        executeFuelSalesAsync(requestId, operation, argsJson) {
            const args = JSON.parse(argsJson || '[]');
            requests.push({ requestId, operation, args });
            const response = operation === 'scope'
                ? { success: true, user_id: 101, station_id: 7 }
                : (operation === 'getRecentSaleCustomerIds'
                    ? { success: true, data: [33, 11], user_id: 101, station_id: 7 }
                    : { success: false, error: 'Unsupported test operation' });
            queueMicrotask(() => client.resolve(requestId, JSON.stringify(response)));
            return JSON.stringify({ success: true, queued: true });
        }
    };
    client = create(fakeAndroidInterface, { timeoutMs: 1500 });

    const scopeResponse = await client.invoke('scope', []);
    assert.equal(scopeResponse.user_id, 101);
    assert.equal(scopeResponse.station_id, 7);
    const rankingResponse = await client.invoke('getRecentSaleCustomerIds', []);
    assert.deepEqual(rankingResponse.data, [33, 11]);
    assert.equal(rankingResponse.user_id, 101);
    assert.equal(rankingResponse.station_id, 7);
    assert.deepEqual(requests.map(item => item.operation), ['scope', 'getRecentSaleCustomerIds']);
    assert.ok(requests.every(item => item.args.length === 0),
        'station identity must come from the authenticated native session, not JavaScript arguments');
    assert.equal(client.pendingCount(), 0);

    let failedClient;
    const failingAndroidInterface = {
        executeFuelSalesAsync(requestId) {
            queueMicrotask(() => failedClient.resolve(
                requestId,
                JSON.stringify({ success: false, error: 'SQLite recent customer query failed' })
            ));
            return JSON.stringify({ success: true, queued: true });
        }
    };
    failedClient = create(failingAndroidInterface, { timeoutMs: 1500 });
    await assert.rejects(
        failedClient.invoke('getRecentSaleCustomerIds', []),
        /SQLite recent customer query failed/
    );
    assert.equal(failedClient.pendingCount(), 0);

    console.log('Delivery lookup async bridge and station-scoped SQLite contract tests passed.');
}

run().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
