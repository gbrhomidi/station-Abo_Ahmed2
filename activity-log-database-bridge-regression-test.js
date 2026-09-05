const fs = require('fs');
const assert = require('assert');

const screen = fs.readFileSync('app/src/main/assets/screens/activity-log.html', 'utf8');

assert.match(
  screen,
  /case 'getActivityLogs':[\s\S]*bridge\.getActivityLogs\(JSON\.stringify\(\{[\s\S]*limit:/,
  'getActivityLogs must pass a JSON request object to MainActivity'
);
assert.match(
  screen,
  /offset:\s*toSafeInt\(p\.offset,\s*0,\s*0,\s*Number\.MAX_SAFE_INTEGER\)/,
  'activity log pagination offset must be forwarded to the database bridge'
);
assert.doesNotMatch(
  screen,
  /case 'getActivityLogs':[\s\S]*bridge\.getActivityLogs\(toSafeInt\(p\.limit/,
  'getActivityLogs must not pass a number to the String JSON bridge method'
);
assert.match(
  screen,
  /apiCall\('getActivityLogs',\s*\{\s*limit:\s*500\s*\}\)/,
  'screen load must use the database-backed activity log API'
);
assert.match(screen, /apiCall\('deleteActivityLog'/, 'deletion must use the database bridge');
assert.match(screen, /apiCall\('cleanupActivityLogs'/, 'cleanup must use the database bridge');

console.log('activity-log database bridge regression: PASS');
