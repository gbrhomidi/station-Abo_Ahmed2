const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = (...p) => fs.readFileSync(path.join(__dirname, ...p), 'utf8');
const main = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const pos = read('app/src/main/assets/screens/pos.html');
const fuel = read('app/src/main/assets/screens/fuel-sales.html');
function part(s, a, b) { const i=s.indexOf(a); assert.notEqual(i,-1,a); const j=s.indexOf(b,i+a.length); assert.notEqual(j,-1,b); return s.slice(i,j); }

const posUi = part(pos, 'async function completeSale()', '// 11. عرض الفاتورة');
assert.equal((posUi.match(/apiCall\('completeSale'/g)||[]).length,1);
assert.ok(posUi.includes('idempotency_key: activeSaleOperationKey'));
for (const name of ['consumeProductStockJson','consumeFuelStockJson','publishEventJson','AndroidInterface.publishEvent']) assert.ok(!pos.includes(name), 'POS duplicate mutation: '+name);

const posBridge = part(main, 'fun completeSale(jsonData: String): String {', 'fun getNextInvoiceNumber(): String');
assert.ok(posBridge.includes('db.completeSale(data, stationId, activity.currentUserId)'));
assert.ok(posBridge.includes('if (result.optBoolean("success", false))'));
assert.ok(posBridge.includes('"idempotency_key", "pos-sale:$saleId"'));
assert.ok(posBridge.includes('"consumer", "stock-levels"'));
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
assert.ok(fuelSave.includes('"event_type", "fuel_sale_completed"'));
assert.ok(fuelSave.includes('"consumer", "stock-levels"'));
assert.ok(fuelSave.includes('"idempotency_key", "fuel-sale:$saleId"'));
assert.ok(fuelSave.includes('onFailure { DebugLogger.logException("FuelSaleEvent", it) }'),
    'post-commit outbox failures must be visible in diagnostics');
assert.ok(fuelSave.includes('put("pump_id", input.optLong("pump_id", 0L))'));
console.log('Canonical POS/fuel SQLite stock-event contract PASS.');
