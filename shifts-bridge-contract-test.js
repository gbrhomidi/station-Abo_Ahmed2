const fs = require('fs');
const assert = require('assert');

const main = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');
const database = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');
const screen = fs.readFileSync('app/src/main/assets/screens/shifts.html', 'utf8');

assert(main.includes('fun saveShiftRecordTyped('), 'MainActivity exposes typed shift save bridge');
assert(main.includes('shiftType = shiftType'), 'Bridge passes shiftType to DatabaseHelper');
assert(database.includes('shiftType in setOf('), 'Database validates shift type domain');
for (const value of ['morning', 'evening', 'night', 'full_day']) {
  assert(database.includes(`"${value}"`), `Database allows ${value}`);
}
assert(database.includes('put(\n                        "shift_type", shiftType'), 'Database persists shift_type column');
assert(screen.includes('saveShiftRecordTyped'), 'UI uses typed save bridge');
assert(screen.includes('<option value="full_day">'), 'UI exposes full_day option');

console.log('PASS: Android Bridge shift_type contract');
