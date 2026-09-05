const fs = require('fs');
const assert = require('assert');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync('app/src/main/assets/screens/shifts.html', 'utf8');
const shiftTypes = [
  { value: 'morning', label: 'صباحية', start: '06:00' },
  { value: 'evening', label: 'مسائية', start: '14:00' },
  { value: 'full_day', label: 'يوم كامل', start: '06:00' }
];

const context = {
  station: { id: 7, name: 'محطة الاختبار' },
  branch: { id: 3, name: 'الفرع الرئيسي' },
  current_user: { id: 11, station_id: 7, branch_id: 3 },
  managers: [{ id: 41, full_name_ar: 'مدير الاختبار', job_title_ar: 'مدير المحطة' }],
  cashiers: [{ id: 21, full_name_ar: 'أمين صندوق الاختبار' }],
  attendants: [{ id: 31, full_name_ar: 'عامل الاختبار' }]
};

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function runShiftTypeTest(shiftType) {
  const calls = [];
  const bridge = {
    getShiftRecordsTyped: () => JSON.stringify([]),
    getShiftFormContext: () => JSON.stringify(context),
    saveShiftRecordTyped: (...args) => {
      calls.push({ method: 'saveShiftRecordTyped', args });
      return JSON.stringify({ success: true, id: 9001 });
    },
    updateShiftRecord: () => JSON.stringify({ success: true }),
    deleteShiftRecord: () => JSON.stringify({ success: true }),
    generateShiftReport: () => JSON.stringify({ success: true, rows: [] })
  };

  const dom = new JSDOM(html, {
    url: 'http://localhost/screens/shifts.html',
    runScripts: 'dangerously',
    beforeParse(window) {
      window.AndroidInterface = bridge;
      window.confirm = () => true;
      window.prompt = () => '';
      window.print = () => {};
    }
  });

  try {
    await sleep(30);
    const document = dom.window.document;
    const type = document.querySelector('#shift_type');
    assert(type, `${shiftType.value}: shift_type select exists`);
    assert([...type.options].some(option => option.value === shiftType.value), `${shiftType.value}: option exists`);

    dom.window.openForm();
    await sleep(30);
    type.value = shiftType.value;
    document.querySelector('#shift_date').value = '2026-09-05';
    document.querySelector('#start_time').value = `2026-09-05T${shiftType.start}`;
    document.querySelector('#cashier_id').value = '21';
    document.querySelector('#manager_id').value = '41';
    document.querySelector('#opening_cash').value = '1000';
    document.querySelector('#opening_bank').value = '0';
    document.querySelector('#opening_credit').value = '0';

    await dom.window.saveForm();

    assert.strictEqual(calls.length, 1, `${shiftType.value}: save bridge called once`);
    assert.strictEqual(calls[0].method, 'saveShiftRecordTyped', `${shiftType.value}: typed bridge used`);
    assert.strictEqual(calls[0].args[2], shiftType.value, `${shiftType.value}: shift_type sent correctly`);
    assert.strictEqual(calls[0].args[0], 7, `${shiftType.value}: station scope sent`);
    assert.strictEqual(calls[0].args[1], 3, `${shiftType.value}: branch scope sent`);
    assert.strictEqual(calls[0].args[5], 21, `${shiftType.value}: cashier sent`);
    assert.match(document.querySelector('#toast').textContent, /تم حفظ الوردية/);

    console.log(`PASS: ${shiftType.label} (${shiftType.value}) UI save flow`);
  } finally {
    dom.window.close();
  }
}

(async () => {
  for (const shiftType of shiftTypes) await runShiftTypeTest(shiftType);
  console.log(`PASS: all ${shiftTypes.length} shift type UI save flows`);
})().catch(error => {
  console.error('FAIL:', error.stack || error.message);
  process.exitCode = 1;
});
