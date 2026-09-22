const fs = require('node:fs');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/screens/employees.html', 'utf8');

assert.equal(
  source.includes("['party_id','number',0,0,1,1,null,1]"),
  false,
  'employees.html must not depend on party_id'
);
assert.equal(
  source.includes('loadEmployeePartyId();'),
  false,
  'opening the employee modal must not trigger the unsupported party lookup'
);
assert.equal(
  source.includes('loadParties();'),
  false,
  'employees.html must not perform an unnecessary startup party lookup'
);
assert.equal(
  source.includes('الطرف المرتبط "الموظفين - EMPLOYEES" غير موجود في قاعدة البيانات'),
  false,
  'employee creation must not be blocked by a missing synthetic party'
);
assert.equal(
  source.includes("['remarks','textarea',0,1,0]"),
  true,
  'the form must submit the backend-supported remarks column'
);

console.log('Employees optional-party regression PASS.');
