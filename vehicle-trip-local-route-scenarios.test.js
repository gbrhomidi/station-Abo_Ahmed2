const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/trips.html'), 'utf8');
const calls = [];
const rows = [{ id: 701, vehicle_id: 11, driver_id: 22, trip_date: '2026-11-16', trip_status: 'scheduled', vehicle_code: 'TEST-11', driver_code: 'DRV-22', start_location: 'المحطة', end_location: 'العميل' }];
const workspace = { rows, vehicles: [{ id: 11, vehicle_code: 'TEST-11', current_odometer: 100 }], drivers: [{ id: 22, driver_code: 'DRV-22', driver_name: 'سائق اختبار', vehicle_id: 11 }], statistics: { total_trips: 1, total_distance_km: 0, total_fuel_consumed: 0, total_fuel_cost: 0, average_efficiency: 0 }, total_count: 1, page: 1, page_size: 20, total_pages: 1 };
function response(data) { return JSON.stringify({ success: true, data }); }
function installBridge(window) {
  window.AndroidInterface = {
    getVehicleTripWorkspace() { return response(workspace); },
    openNativeMapPicker(lat, lon) { calls.push(['picker', lat, lon]); return JSON.stringify({ success: true }); },
    calculateStationRoute(a, b, c, d, requestId) { calls.push(['route', a, b, c, d, requestId]); return JSON.stringify({ success: true, message: 'بدأ الحساب المحلي' }); },
    saveVehicleTripRecord(payload) { calls.push(['save', JSON.parse(payload)]); return response(702); },
    generateVehicleTripReport() { return response({ rows }); },
    getVehicleTripDetails() { return response(rows[0]); },
    getVehicleTripTimeline() { return response([]); },
    updateVehicleTripRecord() { return response(true); },
    updateVehicleTripStatus() { return response(true); },
    deleteVehicleTripRecord() { return response(true); }
  };
  window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {} });
  window.requestAnimationFrame = callback => { callback(); return 1; };
}
(async () => {
  const dom = new JSDOM(html, { runScripts: 'dangerously', url: 'https://local-route.test/', beforeParse: installBridge });
  const { window } = dom;
  await new Promise(resolve => setTimeout(resolve, 30));
  window.document.getElementById('newTripButton').click();

  // Scenario 1: calculation is blocked until both map points exist.
  window.document.getElementById('calculateTripRoute').click();
  assert.match(window.document.getElementById('toastText').textContent, /حدد نقطتي/);
  assert.strictEqual(calls.filter(call => call[0] === 'route').length, 0);

  // Scenario 2: an offline/local estimate is accepted and persisted with its provider.
  window.document.getElementById('pickTripOrigin').click();
  window.onNativeMapLocationSelected(15.3694, 44.191);
  window.document.getElementById('pickTripDestination').click();
  window.onNativeMapLocationSelected(15.40, 44.22);
  window.document.getElementById('calculateTripRoute').click();
  const route = calls.find(call => call[0] === 'route');
  window.onNativeRouteResult(route[5], JSON.stringify({ success: true, provider: 'OFFLINE_ESTIMATE', distanceKm: 4.27, durationSeconds: 440, encodedPolyline: 'local-polyline' }));
  assert.strictEqual(window.document.getElementById('distanceKm').value, '4.27');
  assert.match(window.document.getElementById('routeEstimateStatus').textContent, /دقيقة/);
  window.document.getElementById('tripVehicleId').value = '11';
  window.document.getElementById('tripDate').value = '2026-11-16';
  window.document.getElementById('tripForm').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  await new Promise(resolve => setTimeout(resolve, 20));
  const saved = calls.find(call => call[0] === 'save');
  assert.strictEqual(saved[1].route_provider, 'OFFLINE_ESTIMATE');
  assert.strictEqual(saved[1].route_duration_seconds, 440);

  // Scenario 3: changing an endpoint invalidates the old local result.
  window.document.getElementById('newTripButton').click();
  window.document.getElementById('pickTripOrigin').click();
  window.onNativeMapLocationSelected(15.37, 44.20);
  assert.strictEqual(window.document.getElementById('distanceKm').value, '');
  assert.match(window.document.getElementById('routeEstimateStatus').textContent, /أعد حساب/);
  dom.window.close();
  console.log('vehicle-trip local route scenarios: PASS (3 scenarios)');
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
