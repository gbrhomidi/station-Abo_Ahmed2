const fs = require('fs');
const assert = require('assert');

const screenPath = 'app/src/main/assets/screens/activity-log.html';
const mainActivityPath = 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt';
const databaseHelperPath = 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt';

const screen = fs.readFileSync(screenPath, 'utf8');
const mainActivity = fs.readFileSync(mainActivityPath, 'utf8');
const databaseHelper = fs.readFileSync(databaseHelperPath, 'utf8');

function section(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  assert.notStrictEqual(start, -1, `Missing section: ${startMarker}`);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.notStrictEqual(end, -1, `Missing section terminator: ${endMarker}`);
  return source.slice(start, end);
}

const bridgeCase = section(screen, "case 'getActivityLogs':", "break;");
assert.match(bridgeCase, /bridge\.getActivityLogs\(JSON\.stringify\(\{/);
assert.match(bridgeCase, /limit:\s*toSafeInt\(p\.limit,\s*500,\s*1,\s*2000\)/);
assert.match(bridgeCase, /offset:\s*toSafeInt\(p\.offset,\s*0,\s*0,\s*Number\.MAX_SAFE_INTEGER\)/);
assert.doesNotMatch(bridgeCase, /bridge\.getActivityLogs\(toSafeInt\(p\.limit/);

const queryBuilder = section(screen, 'const ACTIVITY_LOG_DB_TYPES', 'let reloadTimer');
assert.match(queryBuilder, /function normalizeActivityLogType\(value\)/);
assert.match(queryBuilder, /normalized === 'all' \|\| normalized === 'ai_suggest'/);
assert.match(queryBuilder, /type:\s*normalizeActivityLogType\(currentFilter\)/);
assert.match(queryBuilder, /throw new Error\('نوع سجل النشاط غير مدعوم:/);

const domInit = section(screen, "document.addEventListener('DOMContentLoaded', function()", "setInterval(() =>");
const applyIndex = domInit.indexOf('applyTimeFilter();');
const loadIndex = domInit.indexOf('loadData();');
assert.ok(applyIndex >= 0 && loadIndex >= 0 && applyIndex < loadIndex);
assert.match(screen, /apiCall\('getActivityLogs',\s*buildActivityQueryParams\(\)\)/);
assert.match(screen, /apiCall\('deleteActivityLog'/);
assert.match(screen, /apiCall\('cleanupActivityLogs'/);
assert.doesNotMatch(screen, /catch \(e\) \{[\s\S]*LocalDB\.get\('logs'/);

const bridgeMethod = section(mainActivity, '@JavascriptInterface\n        fun getActivityLogs', '@JavascriptInterface\n        fun deleteActivityLog');
assert.match(bridgeMethod, /JSONObject\(payload\)/);
assert.match(bridgeMethod, /requireCurrentStationId\(db,\s*userId\)/);
assert.match(bridgeMethod, /db\.getActivityLogs\(params,\s*stationId\.toInt\(\)\)/);
assert.match(bridgeMethod, /catch \(e: IllegalArgumentException\)/);
assert.match(bridgeMethod, /طلب سجل النشاط ليس JSON صالحاً/);

const dbMethod = section(databaseHelper, 'fun getActivityLogs(params: JSONObject, stationId: Int?): JSONArray', '/** حذف سجل من جدول ثابت');
assert.match(dbMethod, /"all", "ai_suggest" -> ""/);
assert.match(databaseHelper, /private val activityLogTypes = setOf\("user_activity", "audit", "system", "sms", "sync"\)/);
assert.match(dbMethod, /نوع سجل النشاط غير مدعوم:/);
assert.match(dbMethod, /حالة سجل النشاط غير مدعومة:/);
assert.match(dbMethod, /validateActivityLogDate\(/);
assert.match(databaseHelper, /private fun validateActivityLogDate[\s\S]*isLenient\s*=\s*false/);
assert.match(dbMethod, /تاريخ البداية يجب أن يسبق أو يساوي تاريخ النهاية/);
assert.doesNotMatch(dbMethod, /require\(type\.isEmpty\(\) \|\| type in setOf/);

for (const table of ['user_activity_log','audit_logs','system_logs','sync_logs','sms_logs']) {
  assert.match(dbMethod, new RegExp(`FROM ${table}\\b`), `activity SQL must read ${table}`);
}
assert.match(dbMethod, /ual\.station_id = \?/);
assert.match(dbMethod, /sl\.station_id = \?/);
assert.match(dbMethod, /au\.station_id = \?/);
assert.match(dbMethod, /sp\.station_id = \? OR su\.station_id = \?/);
assert.match(dbMethod, /sd\.station_id = \?/);
assert.match(dbMethod, /repeat\(6\) \{ args \+= stationId\.toString\(\) \}/);
assert.match(dbMethod, /cursorToJsonArray\(it\)/);

console.log('activity-log database bridge regression: PASS');
