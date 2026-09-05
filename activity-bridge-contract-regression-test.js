const fs = require('fs');
const assert = require('assert');

const activity = fs.readFileSync('app/src/main/assets/screens/activity-log.html', 'utf8');
const tasks = fs.readFileSync('app/src/main/assets/screens/tasks.html', 'utf8');
const audit = fs.readFileSync('app/src/main/assets/screens/audit-logs.html', 'utf8');

for (const [name, source] of [['activity-log', activity], ['tasks', tasks]]) {
  assert.match(
    source,
    /getActivityLogs\(JSON\.stringify\(\{[\s\S]*limit:/,
    `${name}: getActivityLogs must receive a JSON request object`
  );
  assert.doesNotMatch(
    source,
    /getActivityLogs\(Number\(/,
    `${name}: getActivityLogs must not receive a number directly`
  );
}

assert.match(audit, /const invoke=\(method,args=\[\]\)=>/, 'audit-logs must define its bridge invoke helper');
assert.match(
  audit,
  /invoke\(CFG\.listMethod,\[JSON\.stringify\(\{limit:CFG\.listLimit,offset:0\}\)\]\)/,
  'audit-logs must pass getActivityLogs JSON parameters'
);

console.log('activity bridge contract regression: PASS');
