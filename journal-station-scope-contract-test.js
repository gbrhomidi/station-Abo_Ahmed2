const fs = require('fs');
const path = require('path');

const db = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const bridge = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

const schemaVersion = Number((db.match(/const val VERSION = (\d+)/) || [])[1]);
expect(schemaVersion >= 30, 'يلزم بقاء إصدار مخطط SQLite عند V30 أو أحدث');
expect(db.includes('27 -> migrateV27ToV28(db)'), 'يلزم تشغيل ترحيل V27 إلى V28');
expect(db.includes('ensureJournalStationScopeSchema(db)'), 'يلزم ضمان مخطط عزل القيود عند فتح القاعدة');
expect(db.includes('station_id INTEGER NOT NULL DEFAULT 0,'), 'يلزم أن يحمل جدول journal_entries نطاق محطة fail-closed');
expect(db.includes('idx_journal_entries_station_status_date'), 'يلزم فهرس القراءة المقيدة للمحطة');
expect(db.includes('fun getJournalEntries(params: JSONObject = JSONObject(), stationScopeId: Int)'), 'يلزم تمرير نطاق موثوق لاستعلام القيود');
expect(db.includes('fun saveJournalEntry(data: JSONObject, userId: Long, stationScopeId: Int)'), 'يلزم تمرير نطاق موثوق لحفظ القيد');
expect(db.includes('fun getChartTrialBalance(fromDate: String?, toDate: String?, stationScopeId: Int)'), 'يلزم عزل ميزان المراجعة بحسب المحطة');
expect(db.includes('je.station_id = ? AND je.is_deleted = 0'), 'يلزم أن يبدأ استعلام القيود بعقد نطاق المحطة');
[
  'fun getJournalItems(stationScopeId: Int)',
  'fun saveJournalEntry(data: JSONObject, userId: Long, stationScopeId: Int)',
  'fun deleteJournalEntry(id: Long, userId: Long, stationScopeId: Int)',
  'fun postJournalEntry(id: Long, userId: Long, stationScopeId: Int)',
  'fun reverseJournalEntry(id: Long, reason: String, userId: Long, stationScopeId: Int)',
  'fun getJournalEntryDetails(id: Long, stationScopeId: Int)',
  'fun getLedgerStats(stationScopeId: Int)',
  'fun getLedgerEntries(params: JSONObject, stationScopeId: Int)'
].forEach(signature => expect(db.includes(signature), `يجب ألا تبقى واجهة قيود تشغيلية بدون نطاق: ${signature}`));
expect(bridge.includes('db.getJournalEntries(params, stationId)'), 'يجب أن يمرر Kotlin محطة الجلسة إلى قائمة القيود');
expect(bridge.includes('db.saveJournalEntry(payload, activity.currentUserId, requireCurrentStationId'), 'يجب أن يشتق حفظ القيد نطاقه من الجلسة');
expect(bridge.includes('db.getChartTrialBalance(fromDate, toDate, requireCurrentStationId'), 'يجب أن يشتق ميزان المراجعة نطاقه من الجلسة');
expect(!bridge.includes('payload.optInt("station_id"'), 'لا يجوز أن يثق جسر القيود في station_id القادم من JavaScript');

console.log('Journal station authority contract PASS.');
