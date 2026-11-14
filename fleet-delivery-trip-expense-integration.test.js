'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = __dirname;
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const main = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const deliveries = read('app/src/main/assets/screens/deliveries.html');
const trips = read('app/src/main/assets/screens/trips.html');
const expenses = read('app/src/main/assets/screens/vehicle-expenses.html');
const routes = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/StationRoutesClient.kt');

assert.match(main, /fun saveDeliveryRecord\(jsonData: String\): String/);
assert.match(main, /fun saveDeliveryRecord[\s\S]{0,2200}requireCurrentStationId/);
assert.match(db, /private fun requireDeliveryRelationsInStation[\s\S]{0,7000}vehicle_id[\s\S]{0,1500}driver_id/);
assert.match(deliveries, /id="f_vehicle_id"/);
assert.match(deliveries, /id="f_driver_id"/);
assert.match(deliveries, /vehicle_id/);
assert.match(deliveries, /driver_id/);

assert.match(trips, /saveVehicleTripRecord/);
assert.match(trips, /vehicle_id/);
assert.match(trips, /driver_id/);
assert.match(main, /fun saveVehicleTripRecord\(jsonData: String\) = operationalSave\("vehicles", "vehicle_trips", jsonData\)/);
const tripSpec = db.slice(db.indexOf('"vehicle_trips" -> OperationalTableSpec'), db.indexOf('"vehicle_expenses" -> OperationalTableSpec'));
for (const field of ['start_latitude','start_longitude','end_latitude','end_longitude','route_distance_km','route_duration_seconds','route_provider','route_encoded_polyline','route_calculated_at']) {
  assert.ok(tripSpec.includes('"'+field+'"'), 'trip persistence spec must include '+field);
}
assert.match(db, /trip_status = 'completed'/);
assert.match(db, /validateFleetNumber\(data, "start_longitude", -180.0, 180.0\)/);
assert.match(db, /route_distance_km/);

assert.match(expenses, /id="formTrip"/);
assert.match(expenses, /id="formSourceDistance"/);
assert.match(expenses, /trip_id:tripId\|\|null/);
assert.match(expenses, /trip_status!=='completed'/);
assert.match(expenses, /route_distance_km\?\?row\.distance_km/);
assert.match(expenses, /payload\.idempotency_key\s*=\s*state\.pendingExpenseKey/);
assert.match(main, /fun saveVehicleExpenseRecord\(jsonData: String\) = operationalSave\("vehicles", "vehicle_expenses", jsonData\)/);
const expenseSpec = db.slice(db.indexOf('"vehicle_expenses" -> OperationalTableSpec'), db.indexOf('"vehicle_maintenance" -> OperationalTableSpec'));
for (const field of ['trip_id','source_distance_km','idempotency_key']) {
  assert.ok(expenseSpec.includes('"'+field+'"'), 'expense persistence spec must include '+field);
}
assert.match(db, /CREATE UNIQUE INDEX IF NOT EXISTS idx_vehicle_expenses_idempotency_key/);
assert.match(db, /WHERE t\.id = \? AND t\.vehicle_id = \? AND t\.trip_status = 'completed'/);
assert.match(db, /data\.put\("source_distance_km", sourceDistance\)/);
assert.match(db, /e\.trip_id, e\.source_distance_km/);

assert.match(routes, /callTimeout\(20, TimeUnit\.SECONDS\)/);
assert.match(routes, /X-Firebase-AppCheck/);
assert.match(routes, /if \(!response\.isSuccessful\)/);
assert.match(routes, /JSONObject\(responseText\)/);
assert.match(routes, /encodedPolyline\.isBlank\(\)/);
assert.match(routes, /getAppCheckToken\(false\)/);
assert.match(routes, /401 ->/);
assert.match(routes, /502, 503, 504 ->/);
console.log('Fleet delivery → trip/route → expense persistence integration contract PASS (32+ assertions).');
