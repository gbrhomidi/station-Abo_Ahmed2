const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');

const root = __dirname;
const pagePath = path.join(root, 'app/src/main/assets/screens/vehicle-tracking.html');
const bridgePath = path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const databasePath = path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const rawHtml = fs.readFileSync(pagePath, 'utf8');
const bridgeSource = fs.readFileSync(bridgePath, 'utf8');
const databaseSource = fs.readFileSync(databasePath, 'utf8');
const html = rawHtml.replace(/<script\s+src=[\s\S]*?<\/script>/gi, '');
const calls = [];
const success = data => JSON.stringify({ success: true, data });

const vehicles = [{ id: 101, vehicle_code: 'VEH-101', plate_number: 'A-101', brand: 'Toyota', model: 'Hilux' }];
const locations = [
  { id: 1, vehicle_id: 101, latitude: 15.3694, longitude: 44.1910, speed: 20, location_time: '2026-08-27 09:00:00' },
  { id: 2, vehicle_id: 101, latitude: 15.3700, longitude: 44.1920, speed: 32, location_time: '2026-08-27 09:10:00' },
  { id: 3, vehicle_id: 101, latitude: 120, longitude: 44.1930, speed: 40, location_time: '2026-08-27 09:20:00' }
];
const tracking = [{ vehicle_id: 101, connection_state: 'recent', last_latitude: 15.3700, last_longitude: 44.1920, last_location_time: '2026-08-27 09:10:00' }];

function installBridge(window) {
  window.AndroidInterface = {
    getVehicleLocationRecords(payload) { calls.push(['locations', JSON.parse(payload)]); return success(locations); },
    getVehicleRecords(payload) { calls.push(['vehicles', JSON.parse(payload)]); return success(vehicles); },
    getVehicleTrackingStatus(payload) { calls.push(['tracking', JSON.parse(payload)]); return success(tracking); },
    getVehicleRouteRecords(payload) { calls.push(['route', JSON.parse(payload)]); return success(locations.slice(0, 2)); },
    generateVehicleLocationReport() { return success(locations); },
    goHome() {}
  };
}

async function wait() { await new Promise(resolve => setTimeout(resolve, 30)); }

(async () => {
  assert.match(bridgeSource, /fun getVehicleTrackingStatus\(jsonData: String = "\{\}"\): String \{[\s\S]{0,450}checkPermission\("vehicles", "read"\)[\s\S]{0,450}operationalScopedJson\(jsonData\)/, 'tracking contract must enforce permission and derive station scope natively');
  assert.match(bridgeSource, /fun getVehicleRouteRecords\(jsonData: String = "\{\}"\): String \{[\s\S]{0,450}checkPermission\("vehicles", "read"\)[\s\S]{0,450}operationalScopedJson\(jsonData\)/, 'route contract must enforce permission and derive station scope natively');
  assert.match(databaseSource, /fun getVehicleRouteRecords[\s\S]{0,1400}requireVehicleInStation\(db, vehicleId, stationId\)/, 'route query must validate the selected vehicle against the persisted station scope');
  assert.match(databaseSource, /fun getVehicleTrackingStatus[\s\S]{0,4200}p_scope\.station_id = \?/, 'tracking query must scope visible vehicles to the authenticated station');
  const routeFunction = databaseSource.slice(databaseSource.indexOf('fun getVehicleRouteRecords'), databaseSource.indexOf('fun getVehicleTripWorkspace'));
  assert.match(routeFunction, /location_time >= \?/, 'route date lower bound must be index-friendly');
  assert.match(routeFunction, /location_time < \?/, 'route date upper bound must use a precomputed exclusive day boundary without wrapping indexed values');
  assert.match(routeFunction, /ORDER BY location_time ASC, id ASC/, 'route order must follow the composite vehicle-time index');
  assert.doesNotMatch(routeFunction, /date\(location_time\)|datetime\(location_time\)/, 'route query must not wrap the indexed timestamp column');
  assert.match(databaseSource, /migrateV31ToV32[\s\S]{0,500}replace\(location_time, 'T', ' '\)/, 'V32 must normalize older WebView timestamp values before index ordering is used');
  assert.doesNotMatch(rawHtml, /station_id\s*[:=]/, 'WebView must not select the operational station');
  assert.doesNotMatch(rawHtml, /Math\.random|fetch\(|https?:\/\/.*(?:tiles|maps)/i, 'map must not use invented positions or an external live-map feed');

  const dom = new JSDOM(html, {
    runScripts: 'dangerously', url: 'https://vehicle-tracking.test/',
    beforeParse(window) {
      installBridge(window);
      window.confirm = () => true;
      window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {} });
      window.HTMLElement.prototype.scrollIntoView = () => {};
      window.URL.createObjectURL = () => 'blob:test';
      window.URL.revokeObjectURL = () => {};
    }
  });
  const { window } = dom;
  await wait();

  assert.deepEqual(calls.map(call => call[0]).sort(), ['locations', 'tracking', 'vehicles'], 'screen initialization must request locations, fleet records, and tracking status from Android');
  calls.forEach(([, payload]) => assert.equal(Object.hasOwn(payload, 'station_id'), false, 'WebView payload must not carry a selectable station id'));
  assert.equal(window.document.querySelectorAll('#trackingMap circle.map-point').length, 2, 'map must ignore invalid persisted coordinates');
  assert.equal(window.document.querySelectorAll('#trackingMap path.map-route').length, 1, 'map must draw a path only from two valid persisted points');
  assert.match(window.document.getElementById('connectionTitle').textContent, /بيانات GPS حديثة/, 'GPS status must reflect Android tracking response');

  const vehicleFilter = window.document.getElementById('vehicleFilter');
  vehicleFilter.value = '101';
  vehicleFilter.dispatchEvent(new window.Event('change', { bubbles: true }));
  await wait();
  const routeCall = calls.find(call => call[0] === 'route');
  assert.ok(routeCall, 'selecting a vehicle must request its persisted route through Android');
  assert.equal(routeCall[1].vehicle_id, 101, 'route identifier must be numeric');
  assert.equal(Object.hasOwn(routeCall[1], 'station_id'), false, 'route station scope must remain native');
  assert.equal(window.document.getElementById('mapEmpty').classList.contains('show'), false, 'valid route records must not render the empty map state');

  window.AndroidInterface.getVehicleLocationRecords = () => success([]);
  window.AndroidInterface.getVehicleTrackingStatus = () => success([]);
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.equal(window.document.getElementById('mapEmpty').classList.contains('show'), true, 'empty SQLite results must show an honest empty map state');

  window.AndroidInterface.getVehicleLocationRecords = () => '{not-json';
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.equal(window.document.getElementById('bridgeError').classList.contains('show'), true, 'invalid Android response must expose a bridge error instead of retaining map data');
  assert.equal(window.document.querySelectorAll('#trackingMap circle.map-point').length, 0, 'failed Android response must clear previously rendered coordinates');

  window.AndroidInterface.getVehicleLocationRecords = () => success(locations.slice(0, 2));
  window.AndroidInterface.getVehicleTrackingStatus = () => success([{ ...tracking[0], connection_state: 'stale', last_location_time: '2025-01-01 00:00:00' }]);
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.match(window.document.getElementById('connectionTitle').textContent, /بيانات GPS قديمة/, 'stale persisted GPS status must be displayed honestly');
  assert.equal(window.document.getElementById('connectionBadge').textContent, 'قديمة', 'stale status must not be presented as connected');

  window.AndroidInterface.getVehicleTrackingStatus = () => JSON.stringify({ success: false, error: 'GPS غير متصل' });
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.match(window.document.getElementById('connectionTitle').textContent, /تعذر قراءة حالة اتصال GPS/, 'tracking failure must be distinguished from a valid no-data state');
  assert.equal(window.document.getElementById('bridgeError').classList.contains('show'), false, 'tracking-only failure must not hide valid persisted locations');
  assert.equal(window.document.querySelectorAll('#trackingMap circle.map-point').length, 2, 'tracking-only failure must preserve valid persisted route points');

  window.AndroidInterface.getVehicleTrackingStatus = () => success([{ ...tracking[0], connection_state: 'connected', device_name: 'GPS-101', last_communication: '2026-08-27 09:12:00' }]);
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.match(window.document.getElementById('connectionTitle').textContent, /اتصال حديث محفوظ/, 'a current IoT device must be described as stored recency rather than a guaranteed live connection');
  assert.equal(window.document.getElementById('connectionBadge').textContent, 'حديثة', 'connected state must be visibly distinct from recent location-only data');

  window.AndroidInterface.getVehicleTrackingStatus = () => success([]);
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.match(window.document.getElementById('connectionBadge').textContent, /لا بيانات/, 'no device and no status must not be described as an active connection');

  window.AndroidInterface.getVehicleTrackingStatus = () => '{not-json';
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.match(window.document.getElementById('connectionTitle').textContent, /تعذر قراءة حالة اتصال GPS/, 'malformed tracking JSON must not be silently rendered as a valid disconnected state');
  assert.equal(window.document.querySelectorAll('#trackingMap circle.map-point').length, 2, 'malformed tracking JSON must preserve independently loaded SQLite locations');

  window.AndroidInterface.getVehicleTrackingStatus = () => success(tracking);
  window.AndroidInterface.getVehicleRouteRecords = () => JSON.stringify({ success: false, error: 'تعذر قراءة مسار GPS' });
  vehicleFilter.value = '101';
  vehicleFilter.dispatchEvent(new window.Event('change', { bubbles: true }));
  await wait();
  assert.equal(window.document.getElementById('bridgeError').classList.contains('show'), false, 'route failure must remain local to the route request');
  assert.equal(window.document.querySelectorAll('#trackingMap circle.map-point').length, 2, 'route failure must retain valid persisted location points as the fallback view');

  window.AndroidInterface.getVehicleRouteRecords = () => success([]);
  vehicleFilter.value = '';
  vehicleFilter.dispatchEvent(new window.Event('change', { bubbles: true }));
  vehicleFilter.value = '101';
  vehicleFilter.dispatchEvent(new window.Event('change', { bubbles: true }));
  await wait();
  assert.equal(window.document.getElementById('mapEmpty').classList.contains('show'), false, 'an empty selected route must not erase independently available persisted locations');

  console.log('Vehicle tracking map and Android Bridge regression PASS (32 assertion groups).');
  window.close();
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
