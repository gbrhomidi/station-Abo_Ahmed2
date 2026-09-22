const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');

const root = __dirname;
const pageSource = fs.readFileSync(path.join(root, 'app/src/main/assets/screens/vehicle-expenses.html'), 'utf8');
const bridgeSource = fs.readFileSync(path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'), 'utf8');
const databaseSource = fs.readFileSync(path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const html = pageSource.replace(/<script\s+src=[\s\S]*?<\/script>/gi, '');
const calls = [];
const ok = data => JSON.stringify({ success: true, data });

const row = { id: 501, vehicle_id: 101, vehicle_name: 'Toyota Hilux', plate_number: 'ABC-123', expense_type: 'صيانة', expense_date: '2026-08-26', amount: 3200, currency_id: 1, currency_name: 'ريال يمني', currency_code: 'YER', currency_symbol: 'ر.ي', odometer_reading: 12500, description: 'تبديل زيت المحرك', invoice_path: '/data/user/0/test/files/invoices/501.pdf' };
const workspace = { rows: [row], summary: { record_count: 1, total_expenses: 3200, current_month_expenses: 3200, total_distance_km: 160, cost_per_km: 20, top_vehicle_name: 'Toyota Hilux', top_expense_type: 'صيانة' }, by_vehicle: [{ vehicle_id: 101, vehicle_name: 'Toyota Hilux', plate_number: 'ABC-123', total_expenses: 3200, record_count: 1, distance_km: 160, cost_per_km: 20 }], by_expense_type: [{ expense_type: 'صيانة', total_expenses: 3200, record_count: 1 }], top_expenses: [row], vehicles: [{ id: 101, vehicle_name: 'Toyota Hilux', plate_number: 'ABC-123', vehicle_code: 'VEH-101' }], expense_types: [{ expense_type: 'صيانة' }], currencies: [{ id: 1, currency_name: 'ريال يمني', currency_code: 'YER', symbol: 'ر.ي', is_default: 1 }], has_next: false };

function installBridge(window) {
  window.AndroidInterface = {
    getVehicleExpenseWorkspace(payload) { calls.push(['workspace', JSON.parse(payload)]); return ok(workspace); },
    getVehicleExpenseDetails(id) { calls.push(['details', id]); return ok({ ...row, id }); },
    saveVehicleExpenseRecord(payload) { calls.push(['save', JSON.parse(payload)]); return ok(502); },
    updateVehicleExpenseRecord(id, payload) { calls.push(['update', id, JSON.parse(payload)]); return ok(true); },
    deleteVehicleExpenseRecord(id) { calls.push(['delete', id]); return ok(true); },
    openVehicleExpenseInvoice(id) { calls.push(['invoice', id]); return ok(true); },
    goHome() {},
    printCurrentPage() { return ok(true); }
  };
}

const wait = () => new Promise(resolve => setTimeout(resolve, 35));

(async () => {
  assert.match(bridgeSource, /fun getVehicleExpenseWorkspace\(jsonData: String = "\{\}"\): String \{[\s\S]{0,700}checkPermission\("vehicles", "read"\)[\s\S]{0,700}requireCurrentStationId/, 'expense workspace bridge must check permission and derive station scope natively');
  assert.match(bridgeSource, /fun getVehicleExpenseDetails\(id: Long\): String \{[\s\S]{0,650}checkPermission\("vehicles", "read"\)[\s\S]{0,650}requireCurrentStationId/, 'expense details bridge must be native-scoped');
  assert.match(bridgeSource, /fun openVehicleExpenseInvoice\(id: Long\): String \{[\s\S]{0,2200}FileProvider\.getUriForFile[\s\S]{0,700}FLAG_GRANT_READ_URI_PERMISSION/, 'invoice bridge must use FileProvider and grant read-only URI access');
  assert.match(bridgeSource, /canonicalFile[\s\S]{0,700}roots\.any/, 'invoice bridge must restrict persisted paths to app-controlled roots');
  assert.match(databaseSource, /fun getVehicleExpenseWorkspace[\s\S]{0,11000}JOIN vehicles v[\s\S]{0,4000}LEFT JOIN currencies c/, 'workspace must join actual vehicle and currency data in SQLite');
  assert.match(databaseSource, /fun getVehicleExpenseWorkspace[\s\S]{0,16000}SUM\(t\.distance_km\)[\s\S]{0,4000}cost_per_km/, 'workspace must calculate distance and cost per km in SQLite');
  assert.match(databaseSource, /fun getVehicleExpenseDetails\(expenseId: Long, stationId: Int\)[\s\S]{0,2500}p\.station_id = \?/, 'expense detail must enforce station scope in its SQL');
  assert.doesNotMatch(pageSource, /station_id\s*[:=]/, 'WebView must not select station_id');
  assert.doesNotMatch(pageSource, /Math\.random|fetch\(/, 'screen must not create records or use external data feeds');
  assert.doesNotMatch(pageSource, /window\.confirm\(/, 'screen must use its in-page confirmation modal rather than browser prompts');

  const dom = new JSDOM(html, { runScripts: 'dangerously', url: 'https://vehicle-expenses.test/', beforeParse(window) { installBridge(window); window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {} }); window.HTMLElement.prototype.scrollIntoView = () => {}; } });
  const { window } = dom;
  await wait();
  assert.equal(calls.filter(([name]) => name === 'workspace').length, 1, 'initialization must request the typed SQLite workspace once');
  assert.equal(Object.hasOwn(calls[0][1], 'station_id'), false, 'workspace payload must not include a selectable station');
  assert.match(window.document.getElementById('recordsList').textContent, /Toyota Hilux/, 'cards must show the joined vehicle name');
  assert.match(window.document.getElementById('recordsList').textContent, /صيانة/, 'cards must show the persisted expense type');
  assert.notEqual(window.document.getElementById('kpiPerKm').textContent.trim(), 'غير متاح', 'cost per km must render the SQLite aggregate when distance is valid');

  window.document.getElementById('vehicleFilter').value = '101';
  window.document.getElementById('vehicleFilter').dispatchEvent(new window.Event('change', { bubbles: true }));
  await wait();
  const filterCall = calls.filter(([name]) => name === 'workspace').at(-1)[1];
  assert.equal(filterCall.vehicle_id, 101, 'vehicle filter must send a numeric vehicle identifier');
  assert.equal(Object.hasOwn(filterCall, 'station_id'), false, 'filter payload must retain native station authority');

  window.document.getElementById('addButton').click();
  window.document.getElementById('formVehicle').value = '101';
  window.document.getElementById('formType').value = 'صيانة';
  window.document.getElementById('formDate').value = '2026-08-27';
  window.document.getElementById('formAmount').value = '450.5';
  window.document.getElementById('formCurrency').value = '1';
  window.document.getElementById('formOdometer').value = '12600';
  window.document.getElementById('expenseForm').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  await wait();
  const save = calls.find(([name]) => name === 'save')[1];
  assert.equal(save.vehicle_id, 101, 'save must retain selected vehicle ID internally');
  assert.equal(save.amount, 450.5, 'save must convert amount to number');
  assert.equal(save.currency_id, 1, 'save must send a real selected currency ID');
  assert.equal(Object.hasOwn(save, 'station_id'), false, 'save payload cannot override station authority');

  window.document.querySelector('[data-invoice="501"]').click();
  await wait();
  assert.deepEqual(calls.find(([name]) => name === 'invoice'), ['invoice', 501], 'invoice opening must use its expense ID typed contract rather than exposing path from JavaScript');

  window.AndroidInterface.getVehicleExpenseWorkspace = () => '{not-json';
  window.document.getElementById('refreshButton').click();
  await wait();
  assert.equal(window.document.getElementById('bridgeError').classList.contains('show'), true, 'invalid Android JSON must show an error state');
  assert.match(window.document.getElementById('recordsList').textContent, /لا توجد مصروفات/, 'invalid Android JSON must clear old financial records instead of retaining stale values');
  console.log('Vehicle expenses workspace and Android Bridge regression PASS (24 assertion groups).');
  window.close();
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
