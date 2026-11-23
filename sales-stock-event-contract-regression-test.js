const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = (...p) => fs.readFileSync(path.join(__dirname, ...p), 'utf8');
const main = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const pos = read('app/src/main/assets/screens/pos.html');
const fuel = read('app/src/main/assets/screens/fuel-sales.html');
const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
function part(s, a, b) { const i=s.indexOf(a); assert.notEqual(i,-1,a); const j=s.indexOf(b,i+a.length); assert.notEqual(j,-1,b); return s.slice(i,j); }

const posUi = part(pos, 'async function completeSale()', '// 11. عرض الفاتورة');
assert.equal((posUi.match(/apiCall\('completeSale'/g)||[]).length,1);
assert.ok(posUi.includes('idempotency_key: activeSaleOperationKey'));
for (const name of ['consumeProductStockJson','consumeFuelStockJson','publishEventJson','AndroidInterface.publishEvent']) assert.ok(!pos.includes(name), 'POS duplicate mutation: '+name);

const posBridge = part(main, 'fun completeSale(jsonData: String): String {', 'fun getNextInvoiceNumber(): String');
assert.ok(posBridge.includes('db.completeSale(data, stationId, activity.currentUserId)'));
assert.ok(posBridge.includes('db.completeSale(data, stationId, activity.currentUserId)'));
assert.ok(!posBridge.includes('db.publishEvent('), 'POS bridge must not enqueue outside the sale transaction');
assert.ok(posBridge.includes('dataResponse(result)'));

const dispatch = part(main, 'fun executeFuelSalesAsync(', 'fun getStartupState(): String');
assert.ok(dispatch.includes('"save" -> saveFuelSaleRecord(args.optString(0, "{}"))'));
assert.ok(fuel.includes("invokeFuelSalesAsync('save'"));
for (const name of ['consumeProductStockJson','consumeFuelStockJson','publishEventJson','AndroidInterface.publishEvent']) assert.ok(!fuel.includes(name), 'Fuel duplicate mutation: '+name);

const fuelSave = part(main, 'fun saveFuelSaleRecord(jsonData: String): String {', 'fun updateFuelSaleRecord(id: Long, jsonData: String): String');
assert.ok(fuelSave.includes('database.beginTransaction()'));
assert.ok(fuelSave.includes('saleId = db.addFuelSale(input, stationScopeId, currentUserId)'));
assert.ok(fuelSave.includes('database.setTransactionSuccessful()'));
assert.ok(fuelSave.includes('database.endTransaction()'));
assert.ok(!fuelSave.includes('db.publishEvent('), 'Fuel bridge must not enqueue after committing the sale');
const fuelNative = part(db, 'fun addFuelSale(data: JSONObject, stationScopeId: Int, cashierId: Long): Long {', 'fun getSales(stationScopeId: Int): JSONArray');
assert.ok(fuelNative.includes('insertEventOutbox('));
assert.ok(fuelNative.includes('eventType = "fuel_sale_completed"'));
assert.ok(fuelNative.includes('idempotencyKey = "fuel-sale:$saleId"'));
assert.ok(fuelNative.includes('put("pump_id", pumpId ?: 0)'));
assert.ok(fuelNative.includes('put("tank_id", resolvedTankId)'), 'Persist the authoritative tank on the fuel sale row');
assert.ok(db.includes('ensureColumn(db, "fuel_sales", "tank_id", "INTEGER REFERENCES tanks(id)")'),
    'Add tank_id without guessing historical values');
const replay = part(fuelNative, 'val existingSaleId = findFinancialIdempotency', 'require(reserveFinancialIdempotency');
assert.ok(replay.includes('SELECT tank_id, shift_id, pump_id, fuel_type_id, quantity'), 'Replay reads the original sale row');
const replayBranch = replay.slice(0, replay.indexOf('// Resolve the authoritative tank only for a new sale'));
assert.ok(replayBranch.length > 0, 'Replay branch must be isolated before new-sale tank resolution');
assert.ok(!replayBranch.includes('FROM pumps'), 'Replay cannot infer a historical tank from mutable pump mapping');
assert.ok(fuelNative.indexOf('insertEventOutbox(') < fuelNative.indexOf('db.setTransactionSuccessful()'),
    'Fuel event must be persisted before the sale transaction commits');

const posNativeDb = part(db, 'fun completeSale(data: JSONObject, stationScopeId: Int, cashierId: Long): JSONObject {', 'private fun resolveDryStockSelection');
assert.ok(posNativeDb.includes('insertEventOutbox('));
assert.ok(posNativeDb.includes('eventType = "pos_sale_completed"'));
assert.ok(posNativeDb.includes('idempotencyKey = "pos-sale:$saleId"'));
assert.ok(posNativeDb.indexOf('insertEventOutbox(') < posNativeDb.indexOf('db.setTransactionSuccessful()'),
    'POS event must be persisted before the sale transaction commits');

assert.ok(db.includes('private fun insertEventOutbox('));
assert.ok(db.includes('fun claimPendingEvents('));
assert.ok(db.includes("status='PROCESSING'"));
assert.ok(db.includes('lease_until'));
assert.ok(db.includes('fun acknowledgeEvent('));
assert.ok(db.includes('fun failEvent('));

// WebView JavaScript is not a trusted outbox consumer. Keep queue publication,
// leasing and terminal transitions inside native Kotlin code.
const outboxBridge = part(main, '// Phase 3/4: Forecourt, procurement, durable events and local intelligence', '// 9. المخزون');
for (const name of ['publishEvent', 'fetchPendingEvents', 'claimPendingEvents', 'acknowledgeEvent', 'failEvent']) {
    assert.doesNotMatch(outboxBridge, new RegExp('@JavascriptInterface\\s*(?:fun\\s+)?' + name + '\\s*\\('),
        `Outbox lifecycle method must not be exposed to WebView JavaScript: ${name}`);
}

console.log('Canonical POS/fuel transactional outbox contract PASS.');
