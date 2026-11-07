'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const iso = require('./app/src/main/assets/assets-local/js/iso-civil-date.js');

const validDates = ['2024-02-29', '2000-02-29', '0001-01-01', '9999-12-31'];
const invalidDates = [
  '2023-02-29', '1900-02-29', '2024-13-01', '2024-00-10', '2024-04-31',
  '2024-02-31', '2024-01-32', '2024-1-09', '09/01/2024', '2024-01-09x',
  ' 2024-01-09', '2024-01-09 ', '2024-01-09\n', '0000-01-01', '', null, undefined, 20240109
];

for (const value of validDates) assert.equal(iso.isValidIsoDate(value), true, `valid ${String(value)}`);
for (const value of invalidDates) assert.equal(iso.isValidIsoDate(value), false, `invalid ${String(value)}`);

assert.equal(iso.isValidOptionalDate(''), true);
assert.equal(iso.isValidOptionalDate(null), true);
assert.equal(iso.isValidOptionalDate(undefined), true);
assert.equal(iso.isValidOptionalDate(' '), false);
assert.equal(iso.isValidDateRange('', ''), true);
assert.equal(iso.isValidDateRange('2024-01-01', '2024-01-01'), true);
assert.equal(iso.isValidDateRange('2024-01-01', '2024-01-02'), true);
assert.equal(iso.isValidDateRange('2024-01-02', '2024-01-01'), false);
assert.equal(iso.isValidDateRange('2024-02-31', ''), false);
assert.equal(iso.calendarDayDifference('2024-02-28', '2024-03-01'), 2);
assert.equal(iso.calendarDayDifference('0001-01-01', '0001-01-02'), 1);
assert.equal(iso.calendarDayDifference('2024-02-31', '2024-03-01'), null);
assert.equal(iso.datePart('2024-02-29 23:59:59', true), '2024-02-29');
assert.equal(iso.datePart('2024-02-29T23:59:59.123Z', true), '2024-02-29');
assert.equal(iso.datePart('2024-02-29T23:59:59.123+05:30', true), '2024-02-29');
assert.equal(iso.datePart('2024-02-31 10:00:00', true), null);
assert.equal(iso.datePart('2024-01-09x', true), null);
assert.equal(iso.datePart('2024-01-09 24:00:00', true), null);
assert.equal(iso.datePart('2024-01-09T12:00:00+24:00', true), null);

const screens = [
  'customer-debts.html',
  'debt-reminders.html',
  'customer-reports.html',
  'customers.html'
];
const assetRoot = path.join(__dirname, 'app/src/main/assets/screens');
for (const name of screens) {
  const html = fs.readFileSync(path.join(assetRoot, name), 'utf8');
  assert.match(html, /assets-local\/js\/iso-civil-date\.js/, `${name} must load the shared validator`);
  assert.match(html, /IsoCivilDate\./, `${name} must use the shared validator`);
}

console.log(`ISO civil-date regression passed: ${validDates.length + invalidDates.length} date cases, ranges, timestamp extraction, and ${screens.length} screen contracts.`);
