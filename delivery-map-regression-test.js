const fs = require('node:fs');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/screens/deliveries.html', 'utf8');
const fillStart = source.indexOf('function fillForm(row)');
const fillEnd = source.indexOf('function toLocalInput', fillStart);
assert.ok(fillStart >= 0 && fillEnd > fillStart, 'delivery edit form must expose a bounded fillForm implementation');
const fillForm = source.slice(fillStart, fillEnd);
assert.match(fillForm, /row\.latitude/);
assert.match(fillForm, /row\.longitude/);
assert.match(fillForm, /row\.location_source/);
assert.match(fillForm, /deliveryCoordinatesStatus/);

const resetStart = source.indexOf('function resetForm()');
const resetEnd = source.indexOf('function clearFieldWarnings', resetStart);
const resetForm = source.slice(resetStart, resetEnd);
assert.match(resetForm, /f_latitude'\)\.dataset\.locationSource = ''/);
assert.match(resetForm, /f_longitude'\)\.dataset\.locationSource = ''/);

const mapStart = source.indexOf('window.onNativeMapLocationSelected = function');
const mapEnd = source.indexOf('function pickDeliveryLocation', mapStart);
const mapCallback = source.slice(mapStart, mapEnd);
assert.match(mapCallback, /dataset\.locationSource = 'map_picker'/);

const payloadStart = source.indexOf('function buildPayload()');
const payloadEnd = source.indexOf('/* ================================================================\n       DELETE', payloadStart);
const payload = source.slice(payloadStart, payloadEnd);
assert.match(payload, /latitudeText/);
assert.match(payload, /longitudeText/);
assert.match(payload, /إحداثيات موقع التوصيل غير صالحة/);
assert.match(payload, /location_source: locationSource/);
assert.doesNotMatch(payload, /location_source:\s*'map_picker'/);

console.log('Delivery map persistence regression tests: PASS');
