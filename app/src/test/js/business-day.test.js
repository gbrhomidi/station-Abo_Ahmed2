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
const arabicDateFormatter = new Intl.DateTimeFormat('ar-EG', {
    weekday: 'long', year: 'numeric', month: 'long', day: 'numeric'
});
const normalizeArabicDigits = value => value.replace(/[٠-٩]/g, digit => String('٠١٢٣٤٥٦٧٨٩'.indexOf(digit)));
const displayedDay = date => normalizeArabicDigits(arabicDateFormatter.formatToParts(date).find(part => part.type === 'day').value);
assert.equal(displayedDay(justBeforeMidnight), '7', 'Arabic date display must show the local day before midnight');
assert.equal(displayedDay(atMidnight), '8', 'Arabic date display must advance at local midnight');
assert.notEqual(arabicDateFormatter.format(justBeforeMidnight), arabicDateFormatter.format(atMidnight));

// Date-only and timezone-less wall-clock values must not be interpreted as UTC.
assert.equal(window.businessDayDate('2026-10-08'), '2026-10-08');
assert.equal(window.businessDayDateTime('2026-10-08 00:00:00'), '2026-10-08 00:00:00');
// An instant around the local boundary resolves to the prior local date before midnight.
assert.equal(window.businessDayDate(new Date('2026-10-08T03:30:00Z')), '2026-10-07');

const observedDayChanges = [];
const watchDashboardDay = window.createBusinessDayWatcher((currentDay, previousDay) => {
    observedDayChanges.push({ currentDay, previousDay });
});
assert.equal(watchDashboardDay(justBeforeMidnight), false, 'initial observation must not trigger a refresh');
assert.equal(watchDashboardDay(new Date(2026, 9, 7, 23, 59, 59)), false, 'same local day must not trigger a refresh');
assert.equal(watchDashboardDay(atMidnight), true, 'local midnight must trigger a dashboard refresh');
assert.equal(watchDashboardDay(new Date(2026, 9, 8, 0, 0, 1)), false, 'one day change must trigger only once');
assert.deepEqual(observedDayChanges, [{ currentDay: '2026-10-08', previousDay: '2026-10-07' }]);

const mainHtmlPath = path.join(__dirname, '../../main/assets/main.html');
const mainHtml = fs.readFileSync(mainHtmlPath, 'utf8');
assert.ok(mainHtml.includes('file:///android_asset/assets-local/js/business-day.js'), 'main.html must load the shared local-day helper');
assert.ok(mainHtml.includes('dashboardBusinessDayWatcher(now);'), 'main.html must inspect the local day while updating its clock');
assert.ok(mainHtml.includes('if (!isSleeping) loadDashboard(true);'), 'a detected day change must reload the dashboard cards');
assert.ok(mainHtml.includes("new Intl.DateTimeFormat('ar-EG'"), 'main.html must render the date using the device locale');
assert.ok(mainHtml.includes('if (forceRefresh) dashboardRefreshQueued = true;'), 'midnight refresh must not be lost during an in-flight dashboard request');

console.log('Business-day and main-dashboard rollover tests passed (America/New_York).');
