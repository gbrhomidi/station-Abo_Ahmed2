'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Deliberately use a zone that is not UTC+3 to catch a fixed Riyadh offset.
process.env.TZ = 'America/New_York';
const sourcePath = path.join(__dirname, '../../main/assets/assets-local/js/business-day.js');
const source = fs.readFileSync(sourcePath, 'utf8');
const window = {};
vm.runInNewContext(source, { window, Date, Intl });

const justBeforeMidnight = new Date(2026, 9, 7, 23, 59, 59);
const atMidnight = new Date(2026, 9, 8, 0, 0, 0);
assert.equal(window.businessDayTimeZone, 'America/New_York');
assert.equal(window.businessDayDate(justBeforeMidnight), '2026-10-07');
assert.equal(window.businessDayDate(atMidnight), '2026-10-08');
assert.equal(window.businessDayDateTime(atMidnight), '2026-10-08 00:00:00');

// Date-only and timezone-less wall-clock values must not be interpreted as UTC.
assert.equal(window.businessDayDate('2026-10-08'), '2026-10-08');
assert.equal(window.businessDayDateTime('2026-10-08 00:00:00'), '2026-10-08 00:00:00');
// An instant around the local boundary resolves to the prior local date before midnight.
assert.equal(window.businessDayDate(new Date('2026-10-08T03:30:00Z')), '2026-10-07');

console.log('Business-day JavaScript local-midnight tests passed (America/New_York).');
