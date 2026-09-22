const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const root = __dirname;
const htmlPath = path.join(root, 'app/src/main/assets/screens/trips.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const mainActivity = fs.readFileSync(path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'), 'utf8');
const databaseHelper = fs.readFileSync(path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const calls = [];
const rows = [{
  id: 301, vehicle_id: 101, driver_id: 201, trip_date: '2026-08-26',
  start_location: '<img src=x onerror=alert(1)> المحطة', end_location: 'موقع العميل',
  distance_km: 42.5, fuel_consumed: 8.5, fuel_cost: 7600, start_odometer: 1200,
  end_odometer: 1242.5, trip_purpose: 'توصيل وقود', notes: 'سجل تجريبي',
  vehicle_code: 'VEH-101', plate_number: 'صنعاء-101', driver_code: 'DRV-201',
  driver_name: 'السائق الأول', vehicle_current_odometer: 1242.5,
  last_latitude: 15.3694, last_longitude: 44.191, last_speed: 30,
  last_odometer: 1242.5, last_location_time: '2026-08-26 10:30:00',
  efficiency_km_per_liter: 5, odometer_delta: 42.5, trip_status: 'scheduled', scheduled_at: '2026-08-26 08:00:00'
}];
const workspace = payload => ({
  rows,
  vehicles: [{ id: 101, vehicle_code: 'VEH-101', plate_number: 'صنعاء-101', current_odometer: 1242.5 }],
  drivers: [{ id: 201, driver_code: 'DRV-201', driver_name: 'السائق الأول', vehicle_id: 101 }],
  statistics: { total_trips: 1, scheduled_count: rows.filter(row => row.trip_status === 'scheduled').length, active_count: rows.filter(row => row.trip_status === 'active').length, completed_count: rows.filter(row => row.trip_status === 'completed').length, cancelled_count: rows.filter(row => row.trip_status === 'cancelled').length, total_distance_km: 42.5, total_fuel_consumed: 8.5, total_fuel_cost: 7600, average_efficiency: 5 },
  total_count: 1, page: Math.floor((payload.offset || 0) / (payload.limit || 20)) + 1,
  page_size: payload.limit || 20, total_pages: 1, has_next: false, has_previous: false, source: 'vehicle_trips'
});
const response = data => JSON.stringify({ success: true, data });

function installBridge(window) {
  window.AndroidInterface = {
    getVehicleTripWorkspace(payload) {
      const parsed = JSON.parse(payload); calls.push(['getVehicleTripWorkspace', parsed]);
      assert.strictEqual(parsed.station_id, undefined, 'WebView must never supply station_id');
      return response(workspace(parsed));
    },
    saveVehicleTripRecord(payload) { calls.push(['saveVehicleTripRecord', JSON.parse(payload)]); return response(302); },
    updateVehicleTripRecord(id, payload) { calls.push(['updateVehicleTripRecord', id, JSON.parse(payload)]); return response(true); },
    deleteVehicleTripRecord(id) { calls.push(['deleteVehicleTripRecord', id]); return response(true); },
    generateVehicleTripReport(payload) { calls.push(['generateVehicleTripReport', JSON.parse(payload)]); return response({ rows, total_count: rows.length }); },
    getVehicleTripDetails(id) { calls.push(['getVehicleTripDetails', id]); return response({ ...rows[0] }); },
    getVehicleTripTimeline(id) { calls.push(['getVehicleTripTimeline', id]); return response([{ id: 1, event_type: 'created', event_note: 'تم إنشاء سجل الرحلة', occurred_at: '2026-08-26 08:00:00' }]); },
    updateVehicleTripStatus(id, status, note) { calls.push(['updateVehicleTripStatus', id, status, note]); rows[0].trip_status = status; return response({ id, trip_status: status }); }
  };
  window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {} });
  window.requestAnimationFrame = callback => { callback(); return 1; };
  window.alert = () => { throw new Error('native alert must not be used'); };
}

(async () => {
  assert.match(mainActivity, /fun getVehicleTripWorkspace\(jsonData: String = "\{\}"\)/, 'MainActivity must expose the dedicated workspace bridge contract');
  assert.match(mainActivity, /checkPermission\("vehicles", "read"\)/, 'workspace bridge must require fleet read permission');
  assert.match(mainActivity, /requireCurrentStationId\(db, activity\.currentUserId\)/, 'workspace bridge must resolve station authority natively');
  assert.match(databaseHelper, /fun getVehicleTripWorkspace\(params: JSONObject = JSONObject\(\), stationId: Int\)/, 'DatabaseHelper must own the workspace query');
  assert.match(databaseHelper, /JOIN parties station_party[\s\S]*station_party\.station_id = \?/, 'workspace rows must scope vehicle ownership through station parties');
  assert.match(databaseHelper, /LEFT JOIN vehicle_locations latest/, 'workspace may only expose persisted location readings');
  assert.match(databaseHelper, /allowedSorts = mapOf/, 'workspace dynamic ordering must be allow-listed');
  assert.match(mainActivity, /fun getVehicleTripDetails\(id: Long\)/, 'details must be a native station-scoped bridge contract');
  assert.match(mainActivity, /fun getVehicleTripTimeline\(id: Long\)/, 'timeline must be a native station-scoped bridge contract');
  assert.match(mainActivity, /fun updateVehicleTripStatus\(id: Long, status: String, note: String = ""\)/, 'status updates must be a native bridge contract');
  assert.match(mainActivity, /fun getVehicleTripStatistics\(jsonData: String = "\{\}"\)/, 'statistics must be a native bridge contract');
  assert.match(mainActivity, /fun getVehicles\(\): String \{[\s\S]{0,700}checkPermission\("vehicles", "read"\)[\s\S]{0,700}getOperationalRows\("vehicles", operationalScopedJson/, 'getVehicles must be permission-protected and station-scoped');
  assert.match(mainActivity, /fun getDrivers\(\): String \{[\s\S]{0,700}checkPermission\("vehicles", "read"\)[\s\S]{0,700}getOperationalRows\("drivers", operationalScopedJson/, 'getDrivers must expose fleet drivers within the native station scope');
  assert.match(databaseHelper, /CREATE TABLE IF NOT EXISTS vehicle_trip_events/, 'timeline events must have actual SQLite storage');
  assert.match(databaseHelper, /trip_status TEXT NOT NULL DEFAULT 'scheduled'/, 'trip status must be persisted in SQLite');
  assert.match(databaseHelper, /fun getVehicleTripStatistics[\s\S]{0,4000}SELECT COUNT\(\*\) AS total_trips/, 'statistics must use a dedicated aggregate SQLite query');
  assert.ok(!/fun getVehicleTripStatistics[\s\S]{0,400}getVehicleTripWorkspace\(params, stationId\)/.test(databaseHelper), 'statistics must not reload workspace rows to compute aggregates');
  assert.ok(!/leaflet|google\.maps|openstreetmap/i.test(html), 'screen must not invent or embed an unsupported live map');
  assert.match(html, /آخر قراءة موقع محفوظة/, 'screen must distinguish persisted location from a live position');

  const dom = new JSDOM(html, { runScripts: 'dangerously', url: 'https://trips.test/', beforeParse: installBridge });
  const { window } = dom;
  await new Promise(resolve => setTimeout(resolve, 35));
  assert.ok(calls.some(call => call[0] === 'getVehicleTripWorkspace'), 'screen must load the dedicated SQLite workspace');
  assert.strictEqual(window.document.querySelectorAll('.trip-card').length, 1, 'one SQLite trip should be rendered');
  assert.strictEqual(window.document.querySelector('.trip-card img'), null, 'untrusted route text must be escaped before DOM rendering');
  assert.match(window.document.querySelector('.trip-card').textContent, /آخر موقع مسجل/, 'persisted telemetry must be explicitly labelled');
  assert.notStrictEqual(window.document.getElementById('statDistance').textContent, '—', 'statistics must come from the workspace response');
  assert.match(window.document.getElementById('statDistance').textContent, /كم/, 'distance statistic must retain its real unit after Arabic number formatting');

  window.document.getElementById('vehicleFilter').value = '101';
  window.document.getElementById('vehicleFilter').dispatchEvent(new window.Event('change', { bubbles: true }));
  await new Promise(resolve => setTimeout(resolve, 20));
  const filteredLoad = calls.filter(call => call[0] === 'getVehicleTripWorkspace').at(-1)[1];
  assert.strictEqual(filteredLoad.vehicle_id, 101, 'vehicle filter must be sent to the SQLite workspace query');

  window.document.getElementById('newTripButton').click();
  window.document.getElementById('tripVehicleId').value = '101';
  window.document.getElementById('tripDate').value = '2026-08-27';
  window.document.getElementById('distanceKm').value = '17.25';
  window.document.getElementById('startOdometer').value = '1242.5';
  window.document.getElementById('endOdometer').value = '1259.75';
  window.document.getElementById('tripForm').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  await new Promise(resolve => setTimeout(resolve, 25));
  const saved = calls.find(call => call[0] === 'saveVehicleTripRecord');
  assert.ok(saved, 'new trip must call saveVehicleTripRecord');
  assert.strictEqual(saved[1].vehicle_id, 101, 'saved vehicle ID must be numeric');
  assert.strictEqual(saved[1].station_id, undefined, 'saved payload must not carry a station_id');
  assert.strictEqual(saved[1].created_by, undefined, 'saved payload must not carry user identity');
  assert.strictEqual(saved[1].end_odometer, 1259.75, 'numeric odometer must be preserved');

  window.document.querySelector('[data-action="edit"]').click();
  window.document.getElementById('tripNotes').value = 'تعديل فعلي';
  window.document.getElementById('tripForm').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  await new Promise(resolve => setTimeout(resolve, 25));
  const updated = calls.find(call => call[0] === 'updateVehicleTripRecord');
  assert.ok(updated, 'editing must call updateVehicleTripRecord');
  assert.strictEqual(updated[1], 301, 'update must send the record ID as first native argument');
  assert.strictEqual(updated[2].notes, 'تعديل فعلي', 'update payload must be serialized as second native argument');

  window.document.querySelector('[data-action="details"]').click();
  assert.match(window.document.getElementById('detailsContent').textContent, /لا تحمل رابط رحلة/, 'details must state the limitation of vehicle-level location readings');
  assert.ok(calls.some(call => call[0] === 'getVehicleTripDetails' && call[1] === 301), 'details must request a numeric native trip ID');
  assert.ok(calls.some(call => call[0] === 'getVehicleTripTimeline' && call[1] === 301), 'timeline must request a numeric native trip ID');
  assert.match(window.document.getElementById('tripTimeline').textContent, /تم الإنشاء/, 'timeline must display SQLite event labels');
  window.document.querySelector('[data-action="status"]').click();
  window.document.getElementById('statusNote').value = 'بدأت الرحلة';
  window.document.getElementById('statusForm').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  await new Promise(resolve => setTimeout(resolve, 25));
  const statusUpdate = calls.find(call => call[0] === 'updateVehicleTripStatus');
  assert.ok(statusUpdate, 'status transition must call updateVehicleTripStatus');
  assert.deepStrictEqual(statusUpdate.slice(1), [301, 'active', 'بدأت الرحلة'], 'status bridge arguments must preserve numeric ID, allowed state, and note');
  window.document.querySelector('[data-action="delete"]').click();
  window.document.getElementById('confirmDeleteButton').click();
  await new Promise(resolve => setTimeout(resolve, 25));
  const deleted = calls.find(call => call[0] === 'deleteVehicleTripRecord');
  assert.ok(deleted, 'delete must call deleteVehicleTripRecord');
  assert.strictEqual(deleted[1], 301, 'delete must send a numeric native record ID');

  window.document.getElementById('reportButton').click();
  window.document.getElementById('generateReportButton').click();
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.ok(calls.some(call => call[0] === 'generateVehicleTripReport'), 'report must use existing Android report contract');
  assert.ok(!window.document.querySelector('#reportContent img'), 'report output must escape untrusted route text');
  console.log('trips workspace bridge regression tests: PASS (25 assertion groups)');
  dom.window.close();
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
