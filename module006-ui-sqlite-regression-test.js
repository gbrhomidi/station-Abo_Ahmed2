const fs = require('fs');
const assert = require('assert');
const root = 'app/src/main/assets/screens/';
const screens = {
  'tanks.html': ['getTankRecords', 'saveTankRecord', 'operational:Tank'],
  'pumps.html': ['getPumpRecords', 'savePumpRecord', 'generatePumpReport', "['id', 'pump_id', 'tank_id', 'nozzle_id', '_id']"],
  'meter-readings.html': ['getMeterReadingRecords', 'saveMeterReadingRecord', 'operational:MeterReading'],
  'tank-filling.html': ['getTankLevelRecords', 'saveTankLevelRecord', 'operational:TankLevel'],
  'quality-checks.html': ['getFuelQualityRecords', 'saveFuelQualityRecord', 'operational:FuelQuality'],
  'equipment-calibration.html': ['getCalibrationRecords', 'saveCalibrationRecord', 'operational:Calibration']
};
for (const [file, contracts] of Object.entries(screens)) {
  const source = fs.readFileSync(root + file, 'utf8');
  for (const contract of contracts) assert(source.includes(contract), `${file}: missing ${contract}`);
  assert(source.includes("invoke('generate' + suffix + 'Report'") || file === 'pumps.html', `${file}: dynamic report bridge missing`);
  assert(source.includes("document.querySelector('.modal-overlay.show .modal-body')"), `${file}: modal-local notification routing missing`);
  assert(source.includes('.modal-notice'), `${file}: modal notice styling/element missing`);
}
const main = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');
for (const method of ['getTankRecords', 'getPumpRecords', 'getMeterReadingRecords', 'getTankLevelRecords', 'getFuelQualityRecords', 'getCalibrationRecords', 'generateTankReport', 'generatePumpReport', 'generateMeterReadingReport', 'generateTankLevelReport', 'generateFuelQualityReport', 'generateCalibrationReport']) {
  assert(main.includes(`fun ${method}`), `MainActivity bridge missing ${method}`);
}
const db = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');
for (const table of ['tanks', 'pumps', 'meter_readings', 'tank_level_log', 'fuel_quality_tests', 'calibration_records']) {
  assert(db.includes(`"${table}" -> OperationalTableSpec`) || db.includes(`"${table}" ->`), `SQLite operational spec missing ${table}`);
}
assert(db.includes('"pumps" -> if (data.has("tank_id"))'), 'pump/tank station relation validation missing');
console.log('Module-006 UI/SQLite binding, report, lookup, and modal notification contract PASS.');
