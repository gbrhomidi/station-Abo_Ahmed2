package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Deterministic pump-meter SMS reconciliation; financial decisions never use AI. */
class PumpMeterSmsRepository(private val context: Context, private val db: DatabaseHelper) {
    companion object {
        private const val TAG = "PumpMeterSms"
        private const val WARNING = "معذرة - القراءة التي ارسلتها لا تتطابق مع القراءة للترات المباعة، لذلك قم بمراجعة الامر مالم سيتم احتساب الفارق على حسابك"
        private val FORMAT = Regex("^\\s*(\\d+)\\s*[-–—]\\s*(\\d+(?:[.,]\\d+)?)\\s*[-–—]\\s*([فخFKfk])\\s*[-–—]\\s*(\\d+)\\s*$")
    }
    data class Parsed(val pumpNumber:Int,val reading:Double,val kind:Char,val nozzleNumber:Int)
    private data class Employee(val id:Long,val code:String,val stationId:Int,val phone:String)

    fun ensureSchema(database: SQLiteDatabase = db.writableDatabase) {
        addColumn(database,"pumps","employee_id INTEGER")
        addColumn(database,"fuel_sales","nozzle_id INTEGER")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_nozzles(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL DEFAULT 0,pump_id INTEGER NOT NULL,nozzle_code TEXT,nozzle_number INTEGER NOT NULL,fuel_type_id INTEGER,is_deleted INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(pump_id,nozzle_number))")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_shift_assignments(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,pump_id INTEGER NOT NULL,employee_id INTEGER NOT NULL,shift_type TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,assigned_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,released_at TEXT,opening_confirmed INTEGER NOT NULL DEFAULT 0,closing_confirmed INTEGER NOT NULL DEFAULT 0,opening_notes TEXT,closing_notes TEXT)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_active_pump_assignment ON pump_shift_assignments(station_id,pump_id) WHERE active=1")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_nozzle_meter_state(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,pump_id INTEGER NOT NULL,nozzle_id INTEGER NOT NULL,employee_id INTEGER,shift_type TEXT,opening_reading REAL,closing_reading REAL,last_approved_reading REAL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(station_id,pump_id,nozzle_id))")
        database.execSQL("CREATE TABLE IF NOT EXISTS employee_deductions(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,employee_id INTEGER NOT NULL,shift_id INTEGER,pump_id INTEGER NOT NULL,nozzle_id INTEGER NOT NULL,sms_message_id INTEGER,shortage_liters REAL NOT NULL,unit_sale_price REAL NOT NULL,amount REAL NOT NULL,status TEXT NOT NULL DEFAULT 'pending_review',notes TEXT,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,approved_by INTEGER,approved_at TEXT)")
        database.execSQL("CREATE TABLE IF NOT EXISTS pump_sms_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,employee_id INTEGER,employee_code TEXT,sender_phone TEXT,pump_id INTEGER,pump_number INTEGER,nozzle_id INTEGER,nozzle_number INTEGER,shift_type TEXT,reading_kind TEXT NOT NULL,sms_reading REAL,previous_reading REAL,sold_liters REAL,expected_reading REAL,difference_liters REAL,unit_sale_price REAL,deduction_amount REAL,decision TEXT NOT NULL,reason TEXT,raw_message TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    }
    private fun addColumn(database:SQLiteDatabase,table:String,definition:String){try{database.execSQL("ALTER TABLE "+table+" ADD COLUMN "+definition)}catch(_:Exception){}}

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
            database.execSQL("UPDATE pumps SET employee_id=? WHERE id=? AND station_id=?",arrayOf(employeeId,pumpId,stationId))
            database.execSQL("UPDATE pump_shift_assignments SET active=0,released_at=CURRENT_TIMESTAMP WHERE station_id=? AND pump_id=? AND active=1",arrayOf(stationId,pumpId))
            database.execSQL("INSERT INTO pump_shift_assignments(station_id,pump_id,employee_id,shift_type,active) VALUES(?,?,?,?,1)",arrayOf(stationId,pumpId,normalizeShift(shiftType),1))
            database.setTransactionSuccessful()
            JSONObject().put("success",true).put("id",pumpId).put("employee_id",employeeId).put("shift_type",normalizeShift(shiftType))
        }finally{database.endTransaction()}
    }

    fun handleIncomingSms(sender:String,body:String):Boolean{
        val parsed=parse(body)?:return false; ensureSchema(); val database=db.writableDatabase
        val employee=findEmployee(database,normalizePhone(sender))?:return false
        val pump=database.rawQuery("SELECT id,pump_number,station_id,employee_id FROM pumps WHERE pump_number=? AND station_id=? AND COALESCE(is_deleted,0)=0 LIMIT 1",arrayOf(parsed.pumpNumber.toString(),employee.stationId.toString())).use{c->if(!c.moveToFirst())null else JSONObject().apply{put("id",c.getLong(0));put("pump_number",c.getInt(1));put("station_id",c.getInt(2));put("employee_id",c.getLong(3))}}?:return false
        if(pump.optLong("employee_id",0L)!=employee.id)return false
        val nozzle=database.rawQuery("SELECT id,nozzle_number,fuel_type_id FROM pump_nozzles WHERE pump_id=? AND station_id=? AND nozzle_number=? AND is_deleted=0 LIMIT 1",arrayOf(pump.getString("id"),employee.stationId.toString(),parsed.nozzleNumber.toString())).use{c->if(!c.moveToFirst())null else JSONObject().apply{put("id",c.getLong(0));put("nozzle_number",c.getInt(1));put("fuel_type_id",if(c.isNull(2))JSONObject.NULL else c.getLong(2))}}?:return audit(database,employee,pump,null,parsed,sender,body,"INVALID_NOZZLE","الفوهة لا تتبع المضخة")
        val shift=currentShift(database,employee.stationId,pump.getLong("id"),employee.id)?:return audit(database,employee,pump,nozzle,parsed,sender,body,"UNAUTHORIZED","لا يوجد تعيين نشط للمضخة لهذا الموظف")
        val state=readState(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id")); val previous=state?.optDouble("last_approved_reading",Double.NaN)?:Double.NaN
        if(parsed.kind.uppercaseChar()=='ف'||parsed.kind.uppercaseChar()=='F'){
            if(!previous.isNaN()&&kotlin.math.abs(parsed.reading-previous)>0.0001){warn(sender,"قراءة الفتح لا تطابق آخر قراءة معتمدة. "+WARNING);return audit(database,employee,pump,nozzle,parsed,sender,body,"OPENING_MISMATCH","قراءة الافتتاح لا تطابق الإغلاق السابق",previous,0.0,previous)}
            upsertState(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id"),employee.id,shift,parsed.reading,null,parsed.reading)
            return audit(database,employee,pump,nozzle,parsed,sender,body,"OPENING_ACCEPTED","تم اعتماد قراءة الافتتاح",previous,0.0,parsed.reading)
        }
        val sold=soldLiters(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id")); val expected=if(previous.isNaN())parsed.reading else previous+sold
        val price=salePrice(database,pump.getLong("id"),nozzle.getLong("id"),nozzle.optLong("fuel_type_id",0),employee.stationId); val diff=parsed.reading-expected
        if(kotlin.math.abs(diff)<0.0001){
            upsertState(database,employee.stationId,pump.getLong("id"),nozzle.getLong("id"),employee.id,shift,state?.optDouble("opening_reading",Double.NaN)?.takeUnless{it.isNaN()},parsed.reading,parsed.reading)
            database.execSQL("UPDATE pumps SET meter_current=? WHERE id=? AND station_id=?",arrayOf(parsed.reading,pump.getLong("id"),employee.stationId))
            audit(database,employee,pump,nozzle,parsed,sender,body,"MATCHED","القراءة تطابق القراءة المتوقعة",previous,sold,expected,0.0,price,0.0); return true
        }
        if(parsed.reading<expected){
            val shortage=expected-parsed.reading; val deduction=shortage*price
            database.execSQL("INSERT INTO employee_deductions(station_id,employee_id,pump_id,nozzle_id,shortage_liters,unit_sale_price,amount,status,notes) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(employee.stationId,employee.id,pump.getLong("id"),nozzle.getLong("id"),shortage,price,deduction,"pending_review","expected="+expected+" sms="+parsed.reading+" sold="+sold))
            audit(database,employee,pump,nozzle,parsed,sender,body,"SHORTAGE_REVIEW","القراءة أقل من القراءة المتوقعة؛ تم إنشاء استقطاع قيد المراجعة",previous,sold,expected,diff,price,deduction)
            warn(sender,WARNING+"\nالمضخة "+parsed.pumpNumber+" / الفوهة "+parsed.nozzleNumber+"\nالفارق المتوقع: "+fmt(shortage)+" لتر"); return true
        }
        audit(database,employee,pump,nozzle,parsed,sender,body,"SURPLUS_REVIEW","القراءة أعلى من القراءة المتوقعة؛ لم يتم اعتمادها تلقائياً",previous,sold,expected,diff,price,0.0)
        warn(sender,"القراءة أعلى من المتوقع؛ لم يتم اعتمادها. يرجى مراجعة قراءة المضخة والمبيعات قبل إرسال القراءة الصحيحة."); return true
    }

    private fun parse(body:String):Parsed?{val m=FORMAT.matchEntire(body.trim())?:return null;return runCatching{Parsed(m.groupValues[1].toInt(),m.groupValues[2].replace(',','.').toDouble(),m.groupValues[3].first(),m.groupValues[4].toInt())}.getOrNull()}
    private fun findEmployee(database:SQLiteDatabase,phone:String):Employee?{database.rawQuery("SELECT id,employee_code,station_id,phone FROM employees WHERE COALESCE(status,'active')='active' AND COALESCE(is_deleted,0)=0",null).use{c->while(c.moveToNext())if(normalizePhone(c.getString(3).orEmpty())==phone)return Employee(c.getLong(0),c.getString(1).orEmpty(),c.getInt(2),c.getString(3).orEmpty())};return null}
    private fun currentShift(database:SQLiteDatabase,station:Int,pump:Long,employee:Long):String?=database.rawQuery("SELECT shift_type FROM pump_shift_assignments WHERE station_id=? AND pump_id=? AND employee_id=? AND active=1 LIMIT 1",arrayOf(station.toString(),pump.toString(),employee.toString())).use{if(it.moveToFirst())it.getString(0)else null}
    private fun readState(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long):JSONObject?=database.rawQuery("SELECT opening_reading,last_approved_reading FROM pump_nozzle_meter_state WHERE station_id=? AND pump_id=? AND nozzle_id=? LIMIT 1",arrayOf(station.toString(),pump.toString(),nozzle.toString())).use{if(!it.moveToFirst())null else JSONObject().apply{put("opening_reading",if(it.isNull(0))JSONObject.NULL else it.getDouble(0));put("last_approved_reading",if(it.isNull(1))JSONObject.NULL else it.getDouble(1))}}
    private fun upsertState(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long,employee:Long,shift:String,opening:Double?,closing:Double?,approved:Double){database.execSQL("INSERT INTO pump_nozzle_meter_state(station_id,pump_id,nozzle_id,employee_id,shift_type,opening_reading,closing_reading,last_approved_reading) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(station_id,pump_id,nozzle_id) DO UPDATE SET employee_id=excluded.employee_id,shift_type=excluded.shift_type,opening_reading=COALESCE(excluded.opening_reading,pump_nozzle_meter_state.opening_reading),closing_reading=COALESCE(excluded.closing_reading,pump_nozzle_meter_state.closing_reading),last_approved_reading=excluded.last_approved_reading,updated_at=CURRENT_TIMESTAMP",arrayOf(station,pump,nozzle,employee,shift,opening,closing,approved))}
    private fun soldLiters(database:SQLiteDatabase,station:Int,pump:Long,nozzle:Long):Double=database.rawQuery("SELECT COALESCE(SUM(quantity),0) FROM fuel_sales WHERE station_id=? AND pump_id=? AND nozzle_id=? AND COALESCE(is_deleted,0)=0 AND COALESCE(status,'completed') NOT IN ('cancelled','deleted')",arrayOf(station.toString(),pump.toString(),nozzle.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}
    private fun salePrice(database:SQLiteDatabase,pump:Long,nozzle:Long,fuelType:Long,station:Int):Double{val sale=database.rawQuery("SELECT price_per_liter FROM fuel_sales WHERE station_id=? AND pump_id=? AND nozzle_id=? AND COALESCE(is_deleted,0)=0 ORDER BY id DESC LIMIT 1",arrayOf(station.toString(),pump.toString(),nozzle.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0};if(sale>0)return sale;if(fuelType<=0)return 0.0;return database.rawQuery("SELECT COALESCE(default_sale_price,0) FROM fuel_types WHERE id=? AND COALESCE(is_deleted,0)=0 LIMIT 1",arrayOf(fuelType.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}}
    private fun audit(database:SQLiteDatabase,e:Employee,p:JSONObject,n:JSONObject?,parsed:Parsed,sender:String,body:String,decision:String,reason:String,previous:Double=Double.NaN,sold:Double=0.0,expected:Double=Double.NaN,diff:Double=0.0,price:Double=0.0,deduction:Double=0.0):Boolean{database.execSQL("INSERT INTO pump_sms_audit(station_id,employee_id,employee_code,sender_phone,pump_id,pump_number,nozzle_id,nozzle_number,shift_type,reading_kind,sms_reading,previous_reading,sold_liters,expected_reading,difference_liters,unit_sale_price,deduction_amount,decision,reason,raw_message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(e.stationId,e.id,e.code,sender,p.optLong("id"),p.optInt("pump_number"),n?.optLong("id"),n?.optInt("nozzle_number"),null,parsed.kind.toString(),parsed.reading,if(previous.isNaN())null else previous,sold,if(expected.isNaN())null else expected,diff,price,deduction,decision,reason,body));return true}
    private fun warn(phone:String,message:String){runCatching{SmsReplyManager(context,db).sendReplyOnce(phone,message,"pump-meter-"+normalizePhone(phone)+"-"+System.currentTimeMillis()/60000)}.onFailure{Log.w(TAG,"Unable to send pump-meter warning",it)}}
    private fun normalizePhone(v:String):String{val d=v.filter{it.isDigit()};return when{d.startsWith("967")&&d.length>=12->d.substring(3);d.startsWith("0")&&d.length>=9->d.substring(1);else->d}}
    private fun normalizeShift(v:String)=if(v.lowercase(Locale.ROOT).contains("even")||v.contains("مساء"))"evening" else "morning"
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v)
}
