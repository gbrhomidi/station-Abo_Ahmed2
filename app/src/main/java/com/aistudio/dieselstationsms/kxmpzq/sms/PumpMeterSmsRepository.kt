package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.WeakHashMap

/** Deterministic pump-meter SMS reconciliation; financial decisions never use AI. */
class PumpMeterSmsRepository(private val context: Context, private val db: DatabaseHelper) {
    companion object {
        private const val TAG = "PumpMeterSms"
        private const val WARNING = "معذرة - القراءة التي ارسلتها لا تتطابق مع القراءة للترات المباعة، لذلك قم بمراجعة الامر مالم سيتم احتساب الفارق على حسابك"
        private val FORMAT = Regex("^\\s*(\\d+)\\s*[-–—]\\s*(\\d+(?:[.,]\\d+)?)\\s*[-–—]\\s*([فخFKfk])\\s*[-–—]\\s*(\\d+)\\s*$")
        private val schemaLock = Any()
        // Key by the live SQLiteDatabase handle, not path/version: a deleted and
        // recreated database can reuse both while representing a new schema instance.
        private val initializedSchemas = WeakHashMap<SQLiteDatabase, Boolean>()
    }
    data class Parsed(val pumpNumber:Int,val reading:Double,val kind:Char,val nozzleNumber:Int)
    private data class Employee(val id:Long,val code:String,val stationId:Int,val phone:String)

    // These fields are scoped to one repository instance, which is created per
    // incoming message by SmsProcessor. They let every audit row carry the
    // exact source identity without changing every legacy audit call site.
    private var currentMessageId = ""
    private var currentServiceCenter = ""
    private var currentReceivedAt = 0L
    private var currentDedupeKey = ""
    private var pendingWarning: String? = null

    fun ensureSchema(database: SQLiteDatabase = db.writableDatabase) {
        synchronized(schemaLock) {
            if (initializedSchemas[database] != true) {
                ensureSchemaTables(database)
                initializedSchemas[database] = true
            }
        }
    }

    private fun ensureSchemaTables(database: SQLiteDatabase) {
        addColumn(database,"pumps","employee_id INTEGER")
        addColumn(database,"fuel_sales","nozzle_id INTEGER")
        addColumn(database,"pump_nozzles","station_id INTEGER NOT NULL DEFAULT 0")
        addColumn(database,"pump_nozzles","nozzle_code TEXT")
        addColumn(database,"pump_nozzles","nozzle_number INTEGER NOT NULL DEFAULT 0")
        addColumn(database,"pump_nozzles","fuel_type_id INTEGER")
        addColumn(database,"pump_nozzles","is_deleted INTEGER NOT NULL DEFAULT 0")
        addColumn(database,"pump_nozzles","status TEXT DEFAULT 'active'")
        addColumn(database,"pump_nozzles","updated_at TEXT")
        addColumn(database,"pump_nozzles","deleted_at TEXT")
        try { database.execSQL("UPDATE pump_nozzles SET station_id=(SELECT station_id FROM pumps WHERE pumps.id=pump_nozzles.pump_id) WHERE COALESCE(station_id,0)=0") } catch (_: Exception) {}
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_nozzles(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT UNIQUE,nozzle_code TEXT,nozzle_number TEXT NOT NULL,station_id INTEGER NOT NULL DEFAULT 0,pump_id INTEGER NOT NULL,fuel_type_id INTEGER,meter_start REAL DEFAULT 0,meter_current REAL DEFAULT 0,status TEXT DEFAULT 'active',is_deleted INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT,deleted_at TEXT,UNIQUE(pump_id,nozzle_number))")
        ensureTwoNozzles(database)
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_active_pump_nozzle_number ON pump_nozzles(pump_id,nozzle_number) WHERE COALESCE(is_deleted,0)=0")
        database.execSQL("CREATE TRIGGER IF NOT EXISTS trg_pump_nozzle_insert_limit BEFORE INSERT ON pump_nozzles WHEN COALESCE(NEW.is_deleted,0)=0 AND (NEW.nozzle_number NOT IN ('1','2') OR (SELECT COUNT(*) FROM pump_nozzles WHERE pump_id=NEW.pump_id AND COALESCE(is_deleted,0)=0)>=2) BEGIN SELECT RAISE(ABORT, 'كل مضخة يجب أن تحتوي على فوهتين فقط'); END")
        database.execSQL("CREATE TRIGGER IF NOT EXISTS trg_pump_nozzle_update_limit BEFORE UPDATE OF nozzle_number,is_deleted,status ON pump_nozzles WHEN COALESCE(NEW.is_deleted,0)=0 AND NEW.nozzle_number NOT IN ('1','2') BEGIN SELECT RAISE(ABORT, 'رقم الفوهة يجب أن يكون 1 أو 2'); END")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_shift_assignments(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,pump_id INTEGER NOT NULL,employee_id INTEGER NOT NULL,shift_type TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,assigned_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,released_at TEXT,opening_confirmed INTEGER NOT NULL DEFAULT 0,closing_confirmed INTEGER NOT NULL DEFAULT 0,opening_notes TEXT,closing_notes TEXT)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_active_pump_assignment ON pump_shift_assignments(station_id,pump_id) WHERE active=1")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_nozzle_meter_state(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,pump_id INTEGER NOT NULL,nozzle_id INTEGER NOT NULL,employee_id INTEGER,shift_type TEXT,opening_reading REAL,closing_reading REAL,last_approved_reading REAL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(station_id,pump_id,nozzle_id))")
        database.execSQL("CREATE TABLE IF NOT EXISTS employee_deductions(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,employee_id INTEGER NOT NULL,shift_id INTEGER,pump_id INTEGER NOT NULL,nozzle_id INTEGER NOT NULL,sms_message_id INTEGER,shortage_liters REAL NOT NULL,unit_sale_price REAL NOT NULL,amount REAL NOT NULL,status TEXT NOT NULL DEFAULT 'pending_review',notes TEXT,dedupe_key TEXT,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,approved_by INTEGER,approved_at TEXT)")
        addColumn(database,"employee_deductions","dedupe_key TEXT")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_employee_deductions_dedupe ON employee_deductions(dedupe_key) WHERE dedupe_key IS NOT NULL")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_sms_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,employee_id INTEGER,employee_code TEXT,sender_phone TEXT,pump_id INTEGER,pump_number INTEGER,nozzle_id INTEGER,nozzle_number INTEGER,shift_type TEXT,reading_kind TEXT NOT NULL,sms_reading REAL,previous_reading REAL,sold_liters REAL,expected_reading REAL,difference_liters REAL,unit_sale_price REAL,deduction_amount REAL,decision TEXT NOT NULL,reason TEXT,raw_message TEXT NOT NULL,dedupe_key TEXT,message_id TEXT,service_center TEXT,received_at INTEGER,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        addColumn(database,"pump_sms_audit","dedupe_key TEXT")
        addColumn(database,"pump_sms_audit","message_id TEXT")
        addColumn(database,"pump_sms_audit","service_center TEXT")
        addColumn(database,"pump_sms_audit","received_at INTEGER")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_sms_receipts(id INTEGER PRIMARY KEY AUTOINCREMENT,dedupe_key TEXT NOT NULL UNIQUE,station_id INTEGER NOT NULL DEFAULT 0,sender_phone TEXT NOT NULL,message_id TEXT,service_center TEXT,received_at INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'CLAIMED',created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_sms_replay_events(id INTEGER PRIMARY KEY AUTOINCREMENT,receipt_id INTEGER,dedupe_key TEXT NOT NULL,sender_phone TEXT NOT NULL,message_id TEXT,received_at INTEGER NOT NULL,reason TEXT NOT NULL DEFAULT 'DUPLICATE_DELIVERY',created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_pump_sms_replay_key ON pump_sms_replay_events(dedupe_key)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_pump_sms_audit_dedupe ON pump_sms_audit(dedupe_key) WHERE dedupe_key IS NOT NULL")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_pump_sms_audit_station_decision ON pump_sms_audit(station_id,decision,id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_employee_deductions_station_status ON employee_deductions(station_id,status,id)")
    }
    private fun addColumn(database: SQLiteDatabase, table: String, definition: String) {
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid SQLite table name: $table" }
        val columnName = definition.substringBefore(' ').trim()
        require(columnName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Invalid SQLite column definition: $definition"
        }
        val exists = database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            database.execSQL("ALTER TABLE `$table` ADD COLUMN $definition")
        }
    }
    private fun ensureTwoNozzles(database: SQLiteDatabase) {
        val ownTransaction = !database.inTransaction()
        if (ownTransaction) database.beginTransaction()
        try {
            database.rawQuery("SELECT p.id,p.station_id,p.pump_number,p.meter_start,p.meter_current,COALESCE(t.fuel_type_id,0) FROM pumps p LEFT JOIN tanks t ON t.id=p.tank_id WHERE COALESCE(p.is_deleted,0)=0", null).use { pumps ->
                while (pumps.moveToNext()) {
                    val pumpId=pumps.getLong(0); val stationId=pumps.getLong(1); val pumpNumber=pumps.getString(2)
                    val meterStart=pumps.getDouble(3); val meterCurrent=pumps.getDouble(4); val fuelType=pumps.getLong(5)
                    val ids=mutableListOf<Long>()
                    database.rawQuery("SELECT id FROM pump_nozzles WHERE pump_id=? AND COALESCE(is_deleted,0)=0 ORDER BY id", arrayOf(pumpId.toString())).use { c -> while(c.moveToNext()) ids += c.getLong(0) }
                    if (ids.size > 2) ids.drop(2).forEach { id -> database.execSQL("UPDATE pump_nozzles SET is_deleted=1,status='retired',deleted_at=CURRENT_TIMESTAMP WHERE id=?", arrayOf(id)) }
                    val active=ids.take(2).toMutableList()
                    for (number in 1..2) {
                        val existing=if(number<=active.size) active[number-1] else 0L
                        if(existing>0) database.execSQL("UPDATE pump_nozzles SET station_id=?,nozzle_number=?,nozzle_code=COALESCE(NULLIF(nozzle_code,''),?),fuel_type_id=COALESCE(fuel_type_id,?),updated_at=CURRENT_TIMESTAMP WHERE id=?", arrayOf(stationId,number,"NZ-$pumpNumber-$number",fuelType,existing))
                        else database.execSQL("INSERT INTO pump_nozzles(uuid,station_id,nozzle_code,nozzle_number,pump_id,fuel_type_id,meter_start,meter_current,status,is_deleted) VALUES(?,?,?,?,?,?,?,?,?,0)", arrayOf("NZ-${pumpId}-${number}",stationId,"NZ-$pumpNumber-$number",number.toString(),pumpId,fuelType,meterStart,meterCurrent,"active"))
                    }
                }
            }
            if (ownTransaction) database.setTransactionSuccessful()
        } finally { if (ownTransaction) database.endTransaction() }
    }


    fun listNozzles(stationId:Int,pumpId:Int=0):JSONArray{
        ensureSchema(); val out=JSONArray()
        val sql=if(pumpId>0)"SELECT id,pump_id,nozzle_code,nozzle_number,fuel_type_id FROM pump_nozzles WHERE station_id=? AND pump_id=? AND is_deleted=0 ORDER BY nozzle_number" else "SELECT id,pump_id,nozzle_code,nozzle_number,fuel_type_id FROM pump_nozzles WHERE station_id=? AND is_deleted=0 ORDER BY pump_id,nozzle_number"
        val args=if(pumpId>0)arrayOf(stationId.toString(),pumpId.toString())else arrayOf(stationId.toString())
        db.readableDatabase.rawQuery(sql,args).use{c->while(c.moveToNext())out.put(JSONObject().apply{put("id",c.getLong(0));put("pump_id",c.getLong(1));put("nozzle_code",c.getString(2));put("nozzle_number",c.getInt(3));put("fuel_type_id",if(c.isNull(4))JSONObject.NULL else c.getLong(4))})}
        return out
    }

    fun savePumpEmployee(stationId:Int,pumpId:Long,employeeId:Long,shiftType:String):JSONObject{
        ensureSchema(); val database=db.writableDatabase; database.beginTransaction()
        return try{
            val valid=database.rawQuery("SELECT id FROM employees WHERE id=? AND station_id=? AND COALESCE(status,'active')='active' AND COALESCE(is_deleted,0)=0 LIMIT 1",arrayOf(employeeId.toString(),stationId.toString())).use{it.moveToFirst()}
            require(valid){"الموظف غير موجود أو غير نشط في المحطة"}
            database.execSQL("UPDATE pumps SET employee_id=? WHERE id=? AND station_id=?",arrayOf<Any?>(employeeId,pumpId,stationId))
            database.execSQL("UPDATE pump_shift_assignments SET active=0,released_at=CURRENT_TIMESTAMP WHERE station_id=? AND pump_id=? AND active=1",arrayOf<Any?>(stationId,pumpId))
            database.execSQL("INSERT INTO pump_shift_assignments(station_id,pump_id,employee_id,shift_type,active) VALUES(?,?,?,?,1)",arrayOf<Any?>(stationId,pumpId,employeeId,normalizeShift(shiftType)))
            database.setTransactionSuccessful()
            JSONObject().put("success",true).put("id",pumpId).put("employee_id",employeeId).put("shift_type",normalizeShift(shiftType))
        }finally{database.endTransaction()}
    }

    suspend fun handleIncomingSms(sender:String,body:String,messageId:String="",serviceCenter:String=""):Boolean {
        val parsed=parse(body) ?: return false
        ensureSchema()
        val database=db.writableDatabase
        currentMessageId=messageId.trim().take(128)
        currentServiceCenter=serviceCenter.trim().take(64)
        currentReceivedAt=System.currentTimeMillis()
        currentDedupeKey=fingerprint(sender, body, currentMessageId, currentServiceCenter)
        pendingWarning=null
        database.beginTransaction()
        val result: Boolean
        try {
            val receipt=ContentValues().apply {
                put("dedupe_key",currentDedupeKey); put("station_id",0);
                put("sender_phone",normalizePhone(sender)); put("message_id",currentMessageId)
                put("service_center",currentServiceCenter); put("received_at",currentReceivedAt)
            }
            val inserted=database.insertWithOnConflict("pump_sms_receipts",null,receipt,SQLiteDatabase.CONFLICT_IGNORE)
            if(inserted==-1L){
                database.execSQL("INSERT INTO pump_sms_replay_events(receipt_id,dedupe_key,sender_phone,message_id,received_at,reason) SELECT id,dedupe_key,?,?,?,? FROM pump_sms_receipts WHERE dedupe_key=?",arrayOf(normalizePhone(sender),currentMessageId,currentReceivedAt,"DUPLICATE_DELIVERY",currentDedupeKey))
                database.setTransactionSuccessful()
                return true
            }
            result=handleIncomingSmsInternal(sender, body, parsed, database, currentDedupeKey)
            database.execSQL("UPDATE pump_sms_receipts SET status='PROCESSED' WHERE dedupe_key=?",arrayOf(currentDedupeKey))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        pendingWarning?.let { sendWarning(sender,it) }
        return result
    }

    private suspend fun handleIncomingSmsInternal(sender:String,body:String,parsed:Parsed,database:SQLiteDatabase,key:String):Boolean{
        val employee=findEmployee(database,normalizePhone(sender))
        if(employee!=null) {
            database.execSQL("UPDATE pump_sms_receipts SET station_id=? WHERE dedupe_key=?",arrayOf(employee.stationId,currentDedupeKey))
        }
        if(employee==null){ auditRaw(database,sender,body,parsed,"UNAUTHORIZED","رقم المرسل غير مرتبط بموظف نشط"); return true }
        val pump=database.rawQuery("SELECT id,pump_number,station_id,employee_id FROM pumps WHERE pump_number=? AND station_id=? AND COALESCE(is_deleted,0)=0 LIMIT 1",arrayOf(parsed.pumpNumber.toString(),employee.stationId.toString())).use{c->if(!c.moveToFirst())null else JSONObject().apply{put("id",c.getLong(0));put("pump_number",c.getInt(1));put("station_id",c.getInt(2));put("employee_id",c.getLong(3))}}
            ?: return auditRawEmployee(database,employee,sender,body,parsed,"INVALID_PUMP","رقم المضخة غير موجود في محطة الموظف")
        if(pump.optLong("employee_id",0L)!=employee.id){auditRawEmployee(database,employee,sender,body,parsed,"UNAUTHORIZED","المضخة ليست مسندة إلى هذا الموظف");return true}
        val nozzle=database.rawQuery("SELECT id,nozzle_number,fuel_type_id FROM pump_nozzles WHERE pump_id=? AND station_id=? AND nozzle_number=? AND is_deleted=0 LIMIT 1",arrayOf(pump.getString("id"),employee.stationId.toString(),parsed.nozzleNumber.toString())).use{c->if(!c.moveToFirst())null else JSONObject().apply{put("id",c.getLong(0));put("nozzle_number",c.getInt(1));put("fuel_type_id",if(c.isNull(2))JSONObject.NULL else c.getLong(2))}}?:return audit(database,employee,pump,null,parsed,sender,body,"INVALID_NOZZLE","الفوهة لا تتبع المضخة")
        val shift=currentShift(database,employee.stationId,pump.getLong("id"),employee.id)?:return audit(database,employee,pump,nozzle,parsed,sender,body,"UNAUTHORIZED","لا يوجد تعيين نشط للمضخة لهذا الموظف")
        val shiftId=currentShiftId(database,employee.stationId,shift) ?: return audit(database,employee,pump,nozzle,parsed,sender,body,"UNAUTHORIZED","لا توجد وردية مفتوحة من نوع "+shift)
        val state=readState(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id")); val previous=state?.optDouble("last_approved_reading",Double.NaN)?:Double.NaN
        if(parsed.kind.uppercaseChar()=='ف'||parsed.kind.uppercaseChar()=='F'){
            if(!previous.isNaN()&&kotlin.math.abs(parsed.reading-previous)>0.0001){warn(sender,"قراءة الفتح لا تطابق آخر قراءة معتمدة. "+WARNING);return audit(database,employee,pump,nozzle,parsed,sender,body,"OPENING_MISMATCH","قراءة الافتتاح لا تطابق الإغلاق السابق",previous,0.0,previous)}
            upsertState(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id"),employee.id,shift,parsed.reading,null,parsed.reading)
            return audit(database,employee,pump,nozzle,parsed,sender,body,"OPENING_ACCEPTED","تم اعتماد قراءة الافتتاح",previous,0.0,parsed.reading)
        }
        val sold=soldLiters(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id"),shiftId); val expected=if(previous.isNaN())parsed.reading else previous+sold
        val price=salePrice(database,pump.getLong("id"),nozzle.getLong("id"),nozzle.optLong("fuel_type_id",0),employee.stationId); val diff=parsed.reading-expected
        if(kotlin.math.abs(diff)<0.0001){
            upsertState(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id"),employee.id,shift,state?.optDouble("opening_reading",Double.NaN)?.takeUnless{it.isNaN()},parsed.reading,parsed.reading)
            database.execSQL("UPDATE pumps SET meter_current=? WHERE id=? AND station_id=?",arrayOf<Any?>(parsed.reading,pump.getLong("id"),employee.stationId))
            recordApprovedMeterReading(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id"),employee.id,previous,parsed.reading,sold)
            audit(database,employee,pump,nozzle,parsed,sender,body,"MATCHED","القراءة تطابق القراءة المتوقعة",previous,sold,expected,0.0,price,0.0); return true
        }
        if(parsed.reading<expected){
            val shortage=expected-parsed.reading; val deduction=shortage*price
            database.execSQL("INSERT INTO employee_deductions(station_id,employee_id,pump_id,nozzle_id,shortage_liters,unit_sale_price,amount,status,notes,dedupe_key,sms_message_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(employee.stationId,employee.id,pump.getLong("id"),nozzle.getLong("id"),shortage,price,deduction,"pending_review","expected="+expected+" sms="+parsed.reading+" sold="+sold,currentDedupeKey,null))
            audit(database,employee,pump,nozzle,parsed,sender,body,"SHORTAGE_REVIEW","القراءة أقل من القراءة المتوقعة؛ تم إنشاء استقطاع قيد المراجعة",previous,sold,expected,diff,price,deduction)
            warn(sender,WARNING+"\nالمضخة "+parsed.pumpNumber+" / الفوهة "+parsed.nozzleNumber+"\nالفارق المتوقع: "+fmt(shortage)+" لتر"); return true
        }
        audit(database,employee,pump,nozzle,parsed,sender,body,"SURPLUS_REVIEW","القراءة أعلى من القراءة المتوقعة؛ لم يتم اعتمادها تلقائياً",previous,sold,expected,diff,price,0.0)
        warn(sender,"القراءة أعلى من المتوقع؛ لم يتم اعتمادها. يرجى مراجعة قراءة المضخة والمبيعات قبل إرسال القراءة الصحيحة."); return true
    }

    fun listAudit(stationId:Int, limit:Int=50, offset:Int=0):JSONArray { ensureSchema(); val out=JSONArray(); val l=limit.coerceIn(1,200); val o=offset.coerceAtLeast(0); db.readableDatabase.rawQuery("SELECT * FROM pump_sms_audit WHERE station_id=? ORDER BY id DESC LIMIT ? OFFSET ?", arrayOf(stationId.toString(),l.toString(),o.toString())).use { c -> while(c.moveToNext()) out.put(cursorJson(c)) }; return out }
    fun listDeductions(stationId:Int, limit:Int=50, offset:Int=0):JSONArray { ensureSchema(); val out=JSONArray(); val l=limit.coerceIn(1,200); val o=offset.coerceAtLeast(0); db.readableDatabase.rawQuery("SELECT * FROM employee_deductions WHERE station_id=? ORDER BY id DESC LIMIT ? OFFSET ?", arrayOf(stationId.toString(),l.toString(),o.toString())).use { c -> while(c.moveToNext()) out.put(cursorJson(c)) }; return out }
    fun resolveDeduction(stationId:Int, id:Long, approved:Boolean, actorId:Long, note:String):JSONObject { ensureSchema(); require(id>0 && actorId>0); val d=db.writableDatabase; d.beginTransaction(); return try { val changed=d.update("employee_deductions", ContentValues().apply { put("status",if(approved) "approved" else "rejected"); put("approved_by",actorId); put("approved_at",SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date())); put("notes",note) }, "id=? AND station_id=? AND status='pending_review'", arrayOf(id.toString(),stationId.toString())); require(changed==1) { "الاستقطاع غير موجود أو تمت مراجعته مسبقاً" }; d.setTransactionSuccessful(); JSONObject().put("success",true).put("status",if(approved) "approved" else "rejected") } finally { d.endTransaction() } }
    private fun assignedShift(database:SQLiteDatabase,station:Int,pump:Long,employee:Long):String? = database.rawQuery("SELECT shift_type FROM pump_shift_assignments WHERE station_id=? AND pump_id=? AND employee_id=? AND active=1 LIMIT 1", arrayOf(station.toString(),pump.toString(),employee.toString())).use { if(it.moveToFirst()) it.getString(0) else null }
    private fun cursorJson(c:android.database.Cursor):JSONObject { val o=JSONObject(); for(i in 0 until c.columnCount) { val n=c.getColumnName(i); if(c.isNull(i)) o.put(n,JSONObject.NULL) else when(c.getType(i)) { android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(n,c.getLong(i)); android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(n,c.getDouble(i)); else -> o.put(n,c.getString(i)) } }; return o }
    private fun fingerprint(sender:String, body:String, messageId:String=currentMessageId, serviceCenter:String=currentServiceCenter):String {
        val normalizedBody=body.trim().replace(Regex("\\s+")," ")
        val raw=listOf("v2",normalizePhone(sender),normalizePhone(serviceCenter),messageId.trim(),normalizedBody).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun parse(body:String):Parsed?{val m=FORMAT.matchEntire(body.trim())?:return null;return runCatching{Parsed(m.groupValues[1].toInt(),m.groupValues[2].replace(',','.').toDouble(),m.groupValues[3].first(),m.groupValues[4].toInt())}.getOrNull()}
    private fun findEmployee(database:SQLiteDatabase,phone:String):Employee?{database.rawQuery("SELECT id,employee_code,station_id,phone FROM employees WHERE COALESCE(status,'active')='active' AND COALESCE(is_deleted,0)=0",null).use{c->while(c.moveToNext())if(normalizePhone(c.getString(3).orEmpty())==phone)return Employee(c.getLong(0),c.getString(1).orEmpty(),c.getInt(2),c.getString(3).orEmpty())};return null}
    private fun currentShift(database:SQLiteDatabase,station:Int,pump:Long,employee:Long):String?=database.rawQuery("SELECT shift_type FROM pump_shift_assignments WHERE station_id=? AND pump_id=? AND employee_id=? AND active=1 LIMIT 1",arrayOf(station.toString(),pump.toString(),employee.toString())).use{if(it.moveToFirst())it.getString(0)else null}
    private fun readState(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long):JSONObject?=database.rawQuery("SELECT opening_reading,last_approved_reading FROM pump_nozzle_meter_state WHERE station_id=? AND pump_id=? AND nozzle_id=? LIMIT 1",arrayOf(station.toString(),pump.toString(),nozzle.toString())).use{if(!it.moveToFirst())null else JSONObject().apply{put("opening_reading",if(it.isNull(0))JSONObject.NULL else it.getDouble(0));put("last_approved_reading",if(it.isNull(1))JSONObject.NULL else it.getDouble(1))}}
    private fun upsertState(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long,employee:Long,shift:String,opening:Double?,closing:Double?,approved:Double){database.execSQL("INSERT INTO pump_nozzle_meter_state(station_id,pump_id,nozzle_id,employee_id,shift_type,opening_reading,closing_reading,last_approved_reading) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(station_id,pump_id,nozzle_id) DO UPDATE SET employee_id=excluded.employee_id,shift_type=excluded.shift_type,opening_reading=COALESCE(excluded.opening_reading,pump_nozzle_meter_state.opening_reading),closing_reading=COALESCE(excluded.closing_reading,pump_nozzle_meter_state.closing_reading),last_approved_reading=excluded.last_approved_reading,updated_at=CURRENT_TIMESTAMP",arrayOf<Any?>(station,pump,nozzle,employee,shift,opening,closing,approved))}
    private fun currentShiftId(database:SQLiteDatabase,station:Int,shift:String):Long?=database.rawQuery("SELECT id FROM shifts WHERE station_id=? AND shift_type=? AND status='open' ORDER BY id DESC LIMIT 1",arrayOf(station.toString(),shift)).use{if(it.moveToFirst())it.getLong(0)else null}
    private fun soldLiters(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long,shiftId:Long):Double=database.rawQuery("SELECT COALESCE(SUM(fs.quantity),0) FROM fuel_sales fs JOIN sales_transactions st ON st.id=fs.sale_id WHERE st.station_id=? AND st.is_deleted=0 AND fs.shift_id=? AND fs.pump_id=? AND fs.nozzle_id=? AND fs.is_deleted=0",arrayOf(station.toString(),shiftId.toString(),pump.toString(),nozzle.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}
    private fun salePrice(database:SQLiteDatabase,pump:Long,nozzle:Long,fuelType:Long,station:Int):Double{val sale=database.rawQuery("SELECT fs.price_per_liter FROM fuel_sales fs JOIN sales_transactions st ON st.id=fs.sale_id WHERE st.station_id=? AND st.is_deleted=0 AND fs.pump_id=? AND fs.nozzle_id=? AND fs.is_deleted=0 ORDER BY fs.id DESC LIMIT 1",arrayOf(station.toString(),pump.toString(),nozzle.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0};if(sale>0)return sale;if(fuelType<=0)return 0.0;return database.rawQuery("SELECT COALESCE(default_sale_price,0) FROM fuel_types WHERE id=? AND COALESCE(is_deleted,0)=0 LIMIT 1",arrayOf(fuelType.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}}
    private fun recordApprovedMeterReading(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long,employee:Long,opening:Double,closing:Double,sold:Double){
        val code="SMS-"+currentDedupeKey.take(20)+"-"+pump+"-"+nozzle
        database.execSQL("INSERT INTO meter_readings(uuid,reading_code,pump_id,nozzle_id,station_id,reading_date,opening_reading,closing_reading,sold_liters,status,is_deleted) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,?,?,?,?,0)",arrayOf<Any?>("SMS-UUID-"+code,code,pump,nozzle,station,opening,closing,sold,"approved"))
        database.execSQL("UPDATE meter_readings SET read_by=? WHERE reading_code=?",arrayOf<Any?>(employee,code))
        database.execSQL("UPDATE meter_readings SET system_sold_liters=?,difference=? WHERE reading_code=?",arrayOf<Any?>(sold,0.0,code))
    }
    private fun auditRaw(database:SQLiteDatabase,sender:String,body:String,parsed:Parsed,decision:String,reason:String):Boolean{
        database.execSQL("INSERT INTO pump_sms_audit(station_id,sender_phone,pump_number,nozzle_number,reading_kind,sms_reading,decision,reason,raw_message,dedupe_key) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(0,sender,parsed.pumpNumber,parsed.nozzleNumber,parsed.kind.toString(),parsed.reading,decision,reason,body,currentDedupeKey))
        stampLastAudit(database)
        return true
    }
    private fun auditRawEmployee(database:SQLiteDatabase,e:Employee,sender:String,body:String,parsed:Parsed,decision:String,reason:String):Boolean{
        database.execSQL("INSERT INTO pump_sms_audit(station_id,employee_id,employee_code,sender_phone,pump_number,nozzle_number,reading_kind,sms_reading,decision,reason,raw_message,dedupe_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(e.stationId,e.id,e.code,sender,parsed.pumpNumber,parsed.nozzleNumber,parsed.kind.toString(),parsed.reading,decision,reason,body,currentDedupeKey))
        stampLastAudit(database)
        return true
    }
    private fun audit(database:SQLiteDatabase,e:Employee,p:JSONObject,n:JSONObject?,parsed:Parsed,sender:String,body:String,decision:String,reason:String,previous:Double=Double.NaN,sold:Double=0.0,expected:Double=Double.NaN,diff:Double=0.0,price:Double=0.0,deduction:Double=0.0):Boolean{database.execSQL("INSERT INTO pump_sms_audit(station_id,employee_id,employee_code,sender_phone,pump_id,pump_number,nozzle_id,nozzle_number,shift_type,reading_kind,sms_reading,previous_reading,sold_liters,expected_reading,difference_liters,unit_sale_price,deduction_amount,decision,reason,raw_message,dedupe_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(e.stationId,e.id,e.code,sender,p.optLong("id"),p.optInt("pump_number"),n?.optLong("id"),n?.optInt("nozzle_number"),assignedShift(database,e.stationId,p.optLong("id"),e.id),parsed.kind.toString(),parsed.reading,if(previous.isNaN())null else previous,sold,if(expected.isNaN())null else expected,diff,price,deduction,decision,reason,body,currentDedupeKey));stampLastAudit(database);return true}
    private fun stampLastAudit(database:SQLiteDatabase){
        database.execSQL("UPDATE pump_sms_audit SET message_id=?,service_center=?,received_at=? WHERE id=last_insert_rowid()",arrayOf(currentMessageId,currentServiceCenter,currentReceivedAt))
    }
    private fun warn(phone:String,message:String){ pendingWarning=message }
    private suspend fun sendWarning(phone:String,message:String){try{SmsReplyManager(context,db).sendReplyOnce(phone,message,"pump-meter-"+normalizePhone(phone)+"-"+currentDedupeKey)}catch(e:Exception){Log.w(TAG,"Unable to send pump-meter warning",e)}}
    private fun normalizePhone(v:String):String{val d=v.filter{it.isDigit()};return when{d.startsWith("967")&&d.length>=12->d.substring(3);d.startsWith("0")&&d.length>=9->d.substring(1);else->d}}
    private fun normalizeShift(v:String)=if(v.lowercase(Locale.ROOT).contains("even")||v.contains("مساء"))"evening" else "morning"
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v)
}
