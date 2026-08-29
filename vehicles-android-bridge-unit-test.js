const fs = require('fs');
const assert = require('assert');
const { JSDOM } = require('jsdom');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/vehicles.html'), 'utf8').replace(/<script\s+src=[\s\S]*?<\/script>/gi, '');
const calls = [];
const vehicleRows = [{ id: 101, vehicle_code: 'VEH-101', party_id: 7, plate_number: 'ABC-101', vehicle_type: 'truck', brand: 'Toyota', model: 'Hilux', year: 2024, current_odometer: 1200, status: 'active', registration_expiry: '2027-01-01', insurance_expiry: '2027-02-01', is_deleted: 0 }];
const driverRows = [{ id: 201, driver_code: 'DRV-201', full_name: 'Driver One', full_name_ar: 'السائق الأول', phone: '0500000000', vehicle_id: 101, status: 'active', is_deleted: 0 }];
const trips = [{ id: 301, vehicle_id: 101, driver_id: 201, trip_date: '2026-08-20', start_location: 'المحطة', end_location: 'العميل', distance_km: 42 }];
const locations = [{ id: 401, vehicle_id: 101, latitude: 15.3, longitude: 44.2, speed: 30, location_time: '2026-08-26 10:00:00' }];
const expenses = [{ id: 501, vehicle_id: 101, expense_type: 'fuel', expense_date: '2026-08-21', amount: 80 }];
const maintenance = [{ id: 601, vehicle_id: 101, maintenance_type: 'oil', maintenance_date: '2026-08-22', cost: 50, next_due_date: '2026-11-22', is_deleted: 0 }];
const insurance = [{ id: 701, vehicle_id: 101, insurance_company: 'Safe Co', policy_number: 'POL-1', start_date: '2026-01-01', end_date: '2027-01-01', premium: 300, is_active: 1, is_deleted: 0 }];
const json = data => JSON.stringify({ success: true, data });

function installBridge(window) {
  window.AndroidInterface = {
    getVehicleRecords: payload => { calls.push(['getVehicleRecords', JSON.parse(payload)]); return json(vehicleRows); },
    getDriverRecords: payload => { calls.push(['getDriverRecords', JSON.parse(payload)]); return json(driverRows); },
    getVehicleTripRecords: payload => { calls.push(['getVehicleTripRecords', JSON.parse(payload)]); return json(trips); },
    getVehicleLocationRecords: payload => { calls.push(['getVehicleLocationRecords', JSON.parse(payload)]); return json(locations); },
    getVehicleExpenseRecords: payload => { calls.push(['getVehicleExpenseRecords', JSON.parse(payload)]); return json(expenses); },
    getVehicleMaintenanceRecords: payload => { const p = JSON.parse(payload); calls.push(['getVehicleMaintenanceRecords', p]); return json(p.include_deleted ? maintenance : maintenance.filter(x => !x.is_deleted)); },
    getVehicleInsuranceRecords: payload => { const p = JSON.parse(payload); calls.push(['getVehicleInsuranceRecords', p]); return json(p.include_deleted ? insurance : insurance.filter(x => !x.is_deleted)); },
    saveVehicleRecord: payload => { calls.push(['saveVehicleRecord', JSON.parse(payload)]); return json(102); },
    updateVehicleRecord: (id, payload) => { calls.push(['updateVehicleRecord', id, JSON.parse(payload)]); return json(true); },
    updateDriverRecord: (id, payload) => { calls.push(['updateDriverRecord', id, JSON.parse(payload)]); return json(true); },
    deleteVehicleRecord: id => { calls.push(['deleteVehicleRecord', id]); return json(true); },
    restoreVehicleRecord: id => { calls.push(['restoreVehicleRecord', id]); return json(true); },
    generateVehicleReport: payload => { calls.push(['generateVehicleReport', JSON.parse(payload)]); return json(vehicleRows); },
    generateVehicleTripReport: payload => { calls.push(['generateVehicleTripReport', JSON.parse(payload)]); return json(trips); },
    generateVehicleLocationReport: payload => { calls.push(['generateVehicleLocationReport', JSON.parse(payload)]); return json(locations); },
    generateVehicleExpenseReport: payload => { calls.push(['generateVehicleExpenseReport', JSON.parse(payload)]); return json(expenses); },
    saveVehicleMaintenanceRecord: payload => { calls.push(['saveVehicleMaintenanceRecord', JSON.parse(payload)]); return json(602); },
    updateVehicleMaintenanceRecord: (id, payload) => { calls.push(['updateVehicleMaintenanceRecord', id, JSON.parse(payload)]); return json(true); },
    deleteVehicleMaintenanceRecord: id => { maintenance[0].is_deleted = 1; calls.push(['deleteVehicleMaintenanceRecord', id]); return json(true); },
    restoreVehicleMaintenanceRecord: id => { maintenance[0].is_deleted = 0; calls.push(['restoreVehicleMaintenanceRecord', id]); return json(true); },
    generateVehicleMaintenanceReport: payload => { calls.push(['generateVehicleMaintenanceReport', JSON.parse(payload)]); return json(maintenance); },
    saveVehicleInsuranceRecord: payload => { calls.push(['saveVehicleInsuranceRecord', JSON.parse(payload)]); return json(702); },
    updateVehicleInsuranceRecord: (id, payload) => { calls.push(['updateVehicleInsuranceRecord', id, JSON.parse(payload)]); return json(true); },
    deleteVehicleInsuranceRecord: id => { insurance[0].is_deleted = 1; calls.push(['deleteVehicleInsuranceRecord', id]); return json(true); },
    restoreVehicleInsuranceRecord: id => { insurance[0].is_deleted = 0; calls.push(['restoreVehicleInsuranceRecord', id]); return json(true); },
    generateVehicleInsuranceReport: payload => { calls.push(['generateVehicleInsuranceReport', JSON.parse(payload)]); return json(insurance); }
  };
}

(async () => {
  const dom = new JSDOM(html, { runScripts: 'dangerously', url: 'https://vehicles.test/', beforeParse(window) {
    installBridge(window);
    window.confirm = () => true;
    window.prompt = () => '';
    window.alert = () => {};
    window.open = () => ({ document: { write() {}, close() {} } });
    window.requestAnimationFrame = callback => { callback(); return 1; };
    window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {} });
    window.CSS = { escape: value => String(value).replace(/[^a-zA-Z0-9_-]/g, '\\$&') };
    window.URL.createObjectURL = () => 'blob:test';
    window.URL.revokeObjectURL = () => {};
  }});
  const { window } = dom;
  await new Promise(resolve => setTimeout(resolve, 20));
  await window.loadVehicles();
  assert.ok(calls.some(c => c[0] === 'getVehicleRecords'), 'vehicle list must use getVehicleRecords');
  assert.ok(calls.some(c => c[0] === 'getDriverRecords'), 'vehicle screen must load real drivers');
  assert.strictEqual(window.document.querySelectorAll('.vehicle-card').length, 1, 'vehicle card should render');
  assert.ok(window.document.querySelector('.vehicle-card').textContent.includes('السائق الأول'), 'linked driver should render');

  window.openForm();
  window.document.getElementById('plate_number').value = 'NEW-102';
  window.document.getElementById('party_id').value = '8';
  window.document.getElementById('vehicle_type').value = 'car';
  window.document.getElementById('year').value = '2025';
  await window.saveForm({ preventDefault() {} });
  const add = calls.find(c => c[0] === 'saveVehicleRecord');
  assert.ok(add, 'saveVehicleRecord must be called');
  assert.strictEqual(add[1].vehicle_code, undefined, 'vehicle code must be generated by SQLite');
  assert.strictEqual(add[1].party_id, 8, 'party_id must be numeric');
  assert.strictEqual(add[1].year, 2025, 'numeric vehicle fields must be numbers');

  window.openForm(vehicleRows[0]);
  window.document.getElementById('current_odometer').value = '1300';
  await window.saveForm({ preventDefault() {} });
  const update = calls.find(c => c[0] === 'updateVehicleRecord');
  assert.ok(update, 'updateVehicleRecord must be called');
  assert.strictEqual(update[1], 101);
  assert.strictEqual(update[2].current_odometer, 1300);

  await window.archiveOrRestore(vehicleRows[0]);
  assert.ok(calls.some(c => c[0] === 'deleteVehicleRecord' && c[1] === 101), 'archive must use deleteVehicleRecord');
  await window.archiveOrRestore({ ...vehicleRows[0], is_deleted: 1 });
  assert.ok(calls.some(c => c[0] === 'restoreVehicleRecord' && c[1] === 101), 'restore must use restoreVehicleRecord');

  window.openAssign(101);
  window.document.getElementById('assignDriver').value = '';
  await window.confirmAssign();
  const unbind = calls.find(c => c[0] === 'updateDriverRecord' && c[1] === 201);
  assert.ok(unbind && unbind[2].vehicle_id === null, 'unbinding must send explicit NULL vehicle_id');
  window.openAssign(101);
  window.document.getElementById('assignDriver').value = '201';
  await window.confirmAssign();
  const bind = calls.filter(c => c[0] === 'updateDriverRecord' && c[1] === 201).at(-1);
  assert.strictEqual(bind[2].vehicle_id, 101, 'binding must send numeric vehicle_id');

  window.openDetails(101);
  window.document.querySelector('[data-tab="trips"]').click();
  assert.ok(window.document.getElementById('detailsContent').textContent.includes('المحطة'), 'linked trips should render');
  window.document.querySelector('[data-tab="locations"]').click();
  assert.ok(window.document.getElementById('detailsContent').textContent.includes('15.3'), 'real GPS rows should render');
  window.document.querySelector('[data-tab="expenses"]').click();
  assert.ok(window.document.getElementById('detailsContent').textContent.includes('fuel'), 'real expense rows should render');
  window.document.querySelector('[data-tab="maintenance"]').click();
  assert.ok(window.document.getElementById('detailsContent').textContent.includes('oil'), 'real maintenance rows should render');
  window.document.querySelector('[data-tab="insurance"]').click();
  assert.ok(window.document.getElementById('detailsContent').textContent.includes('Safe Co'), 'real insurance rows should render');
  window.openMaintenanceForm();
  window.document.getElementById('maintenance_type').value = 'brakes';
  window.document.getElementById('maintenance_date').value = '2026-08-26';
  const due = window.document.getElementById('next_due_date'); due.value = '2026-12-26';
  await window.saveMaintenance({ preventDefault() {} });
  const savedMaintenance = calls.find(c => c[0] === 'saveVehicleMaintenanceRecord');
  assert.strictEqual(savedMaintenance[1].vehicle_id, 101, 'maintenance vehicle_id must be numeric');
  assert.strictEqual(savedMaintenance[1].maintenance_date, '2026-08-26');
  window.openInsuranceForm();
  window.document.getElementById('start_date').value = '2026-08-26';
  window.document.getElementById('end_date').value = '2027-08-26';
  await window.saveInsurance({ preventDefault() {} });
  const savedInsurance = calls.find(c => c[0] === 'saveVehicleInsuranceRecord');
  assert.strictEqual(savedInsurance[1].vehicle_id, 101, 'insurance vehicle_id must be numeric');
  assert.strictEqual(savedInsurance[1].is_active, 1);
  await window.toggleRelatedArchive('maintenance', 601);
  assert.ok(calls.some(c => c[0] === 'deleteVehicleMaintenanceRecord' && c[1] === 601), 'maintenance archive must use delete contract');
  window.document.getElementById('archiveChip').click();
  await new Promise(resolve => setTimeout(resolve, 10));
  window.openDetails(101);
  window.document.querySelector('[data-tab="maintenance"]').click();
  await window.toggleRelatedArchive('maintenance', 601);
  assert.ok(calls.some(c => c[0] === 'restoreVehicleMaintenanceRecord' && c[1] === 601), 'maintenance restore must use restore contract');
  await window.toggleRelatedArchive('insurance', 701);
  assert.ok(calls.some(c => c[0] === 'deleteVehicleInsuranceRecord' && c[1] === 701), 'insurance archive must use delete contract');

  window.openReport();
  await window.generateReport();
  assert.ok(calls.some(c => c[0] === 'generateVehicleReport'), 'vehicle report must use generateVehicleReport');
  window.document.getElementById('reportType').value = 'trips';
  await window.generateReport();
  assert.ok(calls.some(c => c[0] === 'generateVehicleTripReport'), 'trip report must use generateVehicleTripReport');
  window.document.getElementById('reportType').value = 'maintenance';
  await window.generateReport();
  assert.ok(calls.some(c => c[0] === 'generateVehicleMaintenanceReport'), 'maintenance report must use generateVehicleMaintenanceReport');
  window.document.getElementById('reportType').value = 'insurance';
  await window.generateReport();
  assert.ok(calls.some(c => c[0] === 'generateVehicleInsuranceReport'), 'insurance report must use generateVehicleInsuranceReport');

  const select = window.document.querySelector('.vehicle-select');
  assert.ok(select, 'vehicle selection checkbox must render');
  select.click();
  await window.bulkArchive();
  assert.ok(calls.filter(c => c[0] === 'deleteVehicleRecord' && c[1] === 101).length >= 2, 'bulk archive must call vehicle delete contract');

  console.log('vehicles Android Bridge unit tests: PASS (17 assertion groups)');
  dom.window.close();
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
