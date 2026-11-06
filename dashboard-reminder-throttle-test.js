const assert = require('node:assert/strict');
const fs = require('node:fs');

const source = fs.readFileSync('app/src/main/assets/main.html', 'utf8');

assert.match(source, /const DUE_REMINDER_REFRESH_INTERVAL_MS = 15 \* 60 \* 1000/);
assert.match(source, /function dueReminderRefreshStorageKey\(\)/);
assert.match(source, /mainDueReminderRefreshAt:/);
assert.match(source, /function shouldRefreshMainDueReminders\(now = Date\.now\(\)\)/);
assert.match(source, /localStorage\.getItem\(dueReminderRefreshStorageKey\(\)/);
assert.match(source, /function markMainDueRemindersRefreshed\(now = Date\.now\(\)\)/);
assert.match(source, /localStorage\.setItem\(dueReminderRefreshStorageKey\(\), String\(now\)\)/);
assert.match(source, /if \(await refreshMainDueReminders\(\)\) markMainDueRemindersRefreshed\(reminderCheckAt\)/);
assert.match(source, /once per 15 minutes after the first successful dashboard load/);

const refreshBlock = source.match(/async function refreshMainDueReminders\(\)\s*\{([\s\S]*?)\n\s*\}\n\s*function openMainDueReminderNotice/);
assert.ok(refreshBlock, 'Reminder refresh function must remain present');
assert.match(refreshBlock[1], /return true;/);
assert.match(refreshBlock[1], /return false;/);

console.log('Dashboard debt-reminder 15-minute throttle contract PASS.');
