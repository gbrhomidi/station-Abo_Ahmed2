package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.sms.PumpMeterSmsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Offline-first delivery-fee persistence and accounting.
 *
 * Fuel sale totals remain the fuel-sale amount. Delivery fees are saved separately and
 * recognised through a balanced journal. A driver-borne fee is not posted until an
 * actual driver is assigned to the delivery.
 */
object DeliveryFeeAccounting {
    const val EXEMPT = "exempt"
    const val ADD_TO_SALES = "add_to_sales"
    const val DRIVER = "driver"
    const val DEDUCT_FROM_SALES = "deduct_from_sales"

    private data class Account(
        val id: Long,
        val code: String,
        val name: String,
        val arabicName: String,
        val type: String
    ) {
        val allText: String get() = "$code $name $arabicName".lowercase(Locale.ROOT)
        val typeText: String get() = type.lowercase(Locale.ROOT)
    }

    data class FuelSale(
        val id: Long,
        val parentSaleId: Long,
        val stationId: Int,
        val customerId: Long,
        val paymentMethod: String,
        val fuelTypeId: Long,
        val fuelTypeName: String,
        val quantity: Double,
        val amount: Double,
        val deliveryFee: Double,
        val deliveryFeeMode: String,
        val deliveryLocation: String,
        val cashCustomerName: String
    )

    fun normalizeMode(value: String?): String {
        val v = value.orEmpty().trim().lowercase(Locale.ROOT)
        return when (v) {
            "add_to_sales", "added_to_sales", "sales", "add", "تضاف لقيمة المبيعات", "تُضاف لقيمة المبيعات" -> ADD_TO_SALES
            "driver", "on_driver", "driver_account", "على حساب السائق" -> DRIVER
            "deduct_from_sales", "subtract_from_sales", "deduct", "تُنقص من المبيعات", "تنقص من المبيعات" -> DEDUCT_FROM_SALES
            else -> EXEMPT
        }
    }

    fun modeLabel(value: String?): String = when (normalizeMode(value)) {
        ADD_TO_SALES -> "تُضاف لقيمة المبيعات"
        DRIVER -> "على حساب السائق"
        DEDUCT_FROM_SALES -> "تُنقص من المبيعات"
        else -> "معفي منها"
    }

    fun ensureSchema(helper: DatabaseHelper) {
        val db = helper.writableDatabase
        addColumn(db, "fuel_sales", "delivery_service_fee", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "fuel_sales", "delivery_fee_mode", "TEXT NOT NULL DEFAULT 'exempt'")
        addColumn(db, "fuel_sales", "delivery_location", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "fuel_sales", "cash_customer_name", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "fuel_sales", "delivery_fee_display_total", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "deliveries", "delivery_service_fee", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "deliveries", "delivery_fee_mode", "TEXT NOT NULL DEFAULT 'exempt'")
        addColumn(db, "deliveries", "delivery_location", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "deliveries", "cash_customer_name", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "deliveries", "delivery_fee_status", "TEXT NOT NULL DEFAULT 'none'")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS delivery_service_fee_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                fuel_sale_id INTEGER NOT NULL DEFAULT 0,
                parent_sale_id INTEGER NOT NULL DEFAULT 0,
                delivery_id INTEGER NOT NULL DEFAULT 0,
                driver_id INTEGER NOT NULL DEFAULT 0,
                amount REAL NOT NULL CHECK(amount >= 0),
                mode TEXT NOT NULL DEFAULT 'exempt',
                status TEXT NOT NULL DEFAULT 'pending',
                journal_entry_id INTEGER,
                settlement_journal_entry_id INTEGER,
                employee_payment_id INTEGER,
                created_by INTEGER NOT NULL DEFAULT 0,
                settled_by INTEGER,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                settled_at TEXT,
                notes TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_fee_station_sale " +
                "ON delivery_service_fee_ledger(station_id,fuel_sale_id) WHERE fuel_sale_id > 0"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_fee_station_delivery " +
                "ON delivery_service_fee_ledger(station_id,delivery_id) WHERE delivery_id > 0"
        )
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        if (!tableExists(db, table)) return emptySet()
        val result = linkedSetOf<String>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        return result
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table))
            .use { it.moveToFirst() }

    private fun addColumn(db: SQLiteDatabase, table: String, name: String, definition: String) {
        if (!tableExists(db, table)) return
        if (name in columns(db, table)) return
        db.execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $definition")
    }

    private fun putIfColumn(db: SQLiteDatabase, table: String, values: ContentValues, name: String, value: Any?) {
        if (name !in columns(db, table)) return
        when (value) {
            null -> values.putNull(name)
            is String -> values.put(name, value)
            is Int -> values.put(name, value)
            is Long -> values.put(name, value)
            is Double -> values.put(name, value)
            is Float -> values.put(name, value)
            is Boolean -> values.put(name, if (value) 1 else 0)
            else -> values.put(name, value.toString())
        }
    }

    private fun findSale(db: SQLiteDatabase, stationId: Int, referenceId: Long): FuelSale? {
        if (referenceId <= 0L || !tableExists(db, "fuel_sales")) return null
        val c = columns(db, "fuel_sales")
        val idCol = "id"
        val stationCol = if ("station_id" in c) "station_id" else return null
        fun one(where: String, args: Array<String>): FuelSale? {
            val nameColumn = when {
                "fuel_type_id" in c -> "fuel_type_id"
                "fuel_id" in c -> "fuel_id"
                else -> "0"
            }
            val parentColumn = if ("sale_id" in c) "COALESCE(sale_id,0)" else "0"
            val customerColumn = if ("customer_id" in c) "COALESCE(customer_id,0)" else "0"
            val paymentColumn = if ("payment_method" in c) "COALESCE(payment_method,'cash')" else "'cash'"
            val quantityColumn = if ("quantity" in c) "COALESCE(quantity,0)" else "0"
            val amountColumn = if ("total_amount" in c) "COALESCE(total_amount,0)" else "0"
            val feeColumn = if ("delivery_service_fee" in c) "COALESCE(delivery_service_fee,0)" else "0"
            val modeColumn = if ("delivery_fee_mode" in c) "COALESCE(delivery_fee_mode,'exempt')" else "'exempt'"
            val locationColumn = if ("delivery_location" in c) "COALESCE(delivery_location,'')" else "''"
            val nameColumnCash = if ("cash_customer_name" in c) "COALESCE(cash_customer_name,'')" else "''"
            return db.rawQuery(
                "SELECT $idCol,$parentColumn,$stationCol,$customerColumn,$paymentColumn,$nameColumn," +
                    "$quantityColumn,$amountColumn,$feeColumn,$modeColumn,$locationColumn,$nameColumnCash " +
                    "FROM fuel_sales WHERE $where LIMIT 1",
                args
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val fuelId = if (cursor.isNull(5)) 0L else cursor.getLong(5)
                    val fuelName = fuelTypeName(db, fuelId)
                    FuelSale(
                        id = cursor.getLong(0),
                        parentSaleId = cursor.getLong(1),
                        stationId = cursor.getInt(2),
                        customerId = cursor.getLong(3),
                        paymentMethod = cursor.getString(4).orEmpty(),
                        fuelTypeId = fuelId,
                        fuelTypeName = fuelName,
                        quantity = cursor.getDouble(6),
                        amount = cursor.getDouble(7),
                        deliveryFee = cursor.getDouble(8),
                        deliveryFeeMode = normalizeMode(cursor.getString(9)),
                        deliveryLocation = cursor.getString(10).orEmpty(),
                        cashCustomerName = cursor.getString(11).orEmpty()
                    )
                }
            }
        }

        // When a form submits fuel_sales.id, this path is exact. When the caller holds
        // the linked sales_transactions.id, try the FK path next.
        return one("id=? AND station_id=?", arrayOf(referenceId.toString(), stationId.toString()))
            ?: if ("sale_id" in c) one("sale_id=? AND station_id=?", arrayOf(referenceId.toString(), stationId.toString())) else null
    }

    private fun fuelTypeName(db: SQLiteDatabase, fuelTypeId: Long): String {
        if (fuelTypeId <= 0L || !tableExists(db, "fuel_types")) return ""
        val cols = columns(db, "fuel_types")
        val nameExpr = when {
            "fuel_name_ar" in cols -> "COALESCE(fuel_name_ar,'')"
            "fuel_name" in cols -> "COALESCE(fuel_name,'')"
            "name" in cols -> "COALESCE(name,'')"
            else -> "''"
        }
        return db.rawQuery("SELECT $nameExpr FROM fuel_types WHERE id=? LIMIT 1", arrayOf(fuelTypeId.toString()))
            .use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
    }

    fun persistFuelSaleFee(
        helper: DatabaseHelper,
        fuelSaleId: Long,
        stationId: Int,
        userId: Long,
        amount: Double,
        rawMode: String?,
        location: String?,
        cashCustomerName: String?,
        paymentMethod: String?
    ): JSONObject {
        ensureSchema(helper)
        val db = helper.writableDatabase
        require(fuelSaleId > 0L && stationId > 0) { "معرف البيع أو المحطة غير صالح" }
        require(amount.isFinite() && amount >= 0.0) { "رسوم خدمة التوصيل غير صالحة" }
        val sale = findSale(db, stationId, fuelSaleId)
            ?: throw IllegalStateException("لم يتم العثور على عملية بيع الوقود في SQLite")
        val mode = normalizeMode(rawMode)
        val cleanLocation = location.orEmpty().trim()
        val cleanCashName = cashCustomerName.orEmpty().trim()
        val salePayment = paymentMethod.orEmpty().ifBlank { sale.paymentMethod }.trim()

        if (amount > 0.0 && mode != EXEMPT && cleanLocation.isBlank()) {
            throw IllegalArgumentException("أدخل موقع التوصيل عند احتساب رسوم خدمة التوصيل")
        }
        if (amount > 0.0 && mode == DEDUCT_FROM_SALES && amount - sale.amount > 0.000001) {
            throw IllegalArgumentException("رسوم خدمة التوصيل لا يمكن أن تتجاوز قيمة المبيعات")
        }

        val values = ContentValues().apply {
            put("delivery_service_fee", amount)
            put("delivery_fee_mode", mode)
            put("delivery_location", cleanLocation)
            put("cash_customer_name", cleanCashName)
            put("delivery_fee_display_total", when (mode) {
                ADD_TO_SALES -> sale.amount + amount
                DEDUCT_FROM_SALES -> (sale.amount - amount).coerceAtLeast(0.0)
                else -> sale.amount
            })
        }
        db.update("fuel_sales", values, "id=? AND station_id=?", arrayOf(fuelSaleId.toString(), stationId.toString()))

        val ledgerId = upsertLedger(
            db, stationId, sale.id, sale.parentSaleId, 0L, 0L, amount, mode, userId,
            "fuel_sale=$fuelSaleId; payment_method=$salePayment"
        )
        val existing = ledgerRow(db, stationId, ledgerId)
        if (amount <= 0.0 || mode == EXEMPT) {
            updateLedger(db, ledgerId, stationId, ContentValues().apply { put("status", "exempt") })
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("journal_entry_id", JSONObject.NULL)
        }

        val existingJournal = existing?.optLong("journal_entry_id", 0L) ?: 0L
        if (existingJournal > 0L) {
            val existingAmount = existing?.optDouble("amount", amount) ?: amount
            val existingMode = normalizeMode(existing?.optString("mode", mode))
            require(abs(existingAmount - amount) < 0.000001 && existingMode == mode) {
                "رسوم التوصيل مسجلة محاسبياً مسبقاً بقيمة أو طريقة مختلفة؛ استخدم قيد تسوية بدلاً من تغيير العملية"
            }
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("journal_entry_id", existingJournal)
        }

        if (mode == DRIVER) {
            updateLedger(db, ledgerId, stationId, ContentValues().apply { put("status", "pending_driver") })
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("pending_driver", true)
        }

        val journalId = when (mode) {
            ADD_TO_SALES -> postFeeJournal(
                helper, db, stationId, userId, ledgerId,
                "رسوم خدمة التوصيل للبيع رقم $fuelSaleId",
                debitAccount = settlementAccount(db, stationId, salePayment),
                creditAccount = serviceRevenueAccount(db),
                amount = amount,
                referenceType = "fuel_sale",
                referenceId = sale.id
            )
            DEDUCT_FROM_SALES -> {
                val salesAccount = salesRevenueAccount(db)
                val settlement = settlementAccount(db, stationId, salePayment)
                val id = postFeeJournal(
                    helper, db, stationId, userId, ledgerId,
                    "خصم رسوم خدمة التوصيل من المبيعات رقم $fuelSaleId",
                    debitAccount = salesAccount,
                    creditAccount = settlement,
                    amount = amount,
                    referenceType = "fuel_sale",
                    referenceId = sale.id
                )
                updateSaleAmountsForDeduction(db, sale, amount)
                id
            }
            else -> 0L
        }
        updateLedger(db, ledgerId, stationId, ContentValues().apply {
            put("journal_entry_id", journalId)
            put("status", "posted")
        })
        return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("journal_entry_id", journalId)
    }

    fun persistDeliveryFee(
        helper: DatabaseHelper,
        deliveryId: Long,
        saleReferenceId: Long,
        stationId: Int,
        userId: Long,
        driverId: Long,
        requestedAmount: Double,
        requestedMode: String?,
        location: String?,
        cashCustomerName: String?
    ): JSONObject {
        ensureSchema(helper)
        val db = helper.writableDatabase
        require(deliveryId > 0L && stationId > 0) { "معرف التوصيل أو المحطة غير صالح" }
        require(requestedAmount.isFinite() && requestedAmount >= 0.0) { "رسوم خدمة التوصيل غير صالحة" }

        val sale = findSale(db, stationId, saleReferenceId)
        val saleFee = sale?.deliveryFee ?: 0.0
        val amount = if (saleFee > 0.0) saleFee else requestedAmount
        val saleMode = sale?.deliveryFeeMode ?: EXEMPT
        val mode = if (saleFee > 0.0 || sale?.deliveryFeeMode != null && sale.deliveryFeeMode != EXEMPT) saleMode else normalizeMode(requestedMode)
        val cleanLocation = location.orEmpty().trim().ifBlank { sale?.deliveryLocation.orEmpty() }
        val cleanCashName = cashCustomerName.orEmpty().trim().ifBlank { sale?.cashCustomerName.orEmpty() }

        if (amount > 0.0 && mode != EXEMPT && cleanLocation.isBlank()) {
            throw IllegalArgumentException("أدخل موقع التوصيل قبل حفظ مهمة التوصيل")
        }
        if (amount > 0.0 && mode == DRIVER && driverId <= 0L) {
            throw IllegalArgumentException("حدد السائق قبل تسجيل رسوم خدمة التوصيل على حسابه")
        }

        val deliveryValues = ContentValues().apply {
            put("delivery_service_fee", amount)
            put("delivery_fee_mode", mode)
            put("delivery_location", cleanLocation)
            put("cash_customer_name", cleanCashName)
            put("delivery_fee_status", if (mode == EXEMPT || amount <= 0.0) "none" else if (mode == DRIVER) "pending" else "posted")
        }
        val deliveryColumns = columns(db, "deliveries")
        if ("location" in deliveryColumns && cleanLocation.isNotBlank()) deliveryValues.put("location", cleanLocation)
        if ("fuel_type" in deliveryColumns && sale != null && sale.fuelTypeName.isNotBlank()) deliveryValues.put("fuel_type", sale.fuelTypeName)
        db.update("deliveries", deliveryValues, "id=? AND station_id=?", arrayOf(deliveryId.toString(), stationId.toString()))

        val fuelSaleId = sale?.id ?: 0L
        val parentSaleId = sale?.parentSaleId ?: saleReferenceId
        val ledgerId = upsertLedger(
            db, stationId, fuelSaleId, parentSaleId, deliveryId, driverId, amount, mode, userId,
            "delivery_id=$deliveryId; sale_reference=$saleReferenceId"
        )
        val existing = ledgerRow(db, stationId, ledgerId)
        if (amount <= 0.0 || mode == EXEMPT) {
            updateLedger(db, ledgerId, stationId, ContentValues().apply {
                put("status", "exempt")
                put("delivery_id", deliveryId)
                put("driver_id", driverId)
            })
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("status", "exempt")
        }

        val existingJournal = existing?.optLong("journal_entry_id", 0L) ?: 0L
        if (existingJournal > 0L) {
            val existingMode = normalizeMode(existing?.optString("mode", mode))
            val existingAmount = existing?.optDouble("amount", amount) ?: amount
            require(existingMode == mode && abs(existingAmount - amount) < 0.000001) {
                "رسوم التوصيل مرتبطة بقيد سابق مختلف؛ لا يمكن تغييرها بعد الترحيل"
            }
            updateLedger(db, ledgerId, stationId, ContentValues().apply {
                put("delivery_id", deliveryId)
                put("driver_id", driverId)
            })
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("journal_entry_id", existingJournal).put("status", existing?.optString("status", "posted"))
        }

        val journalId = when (mode) {
            ADD_TO_SALES -> {
                val account = settlementAccount(db, stationId, sale?.paymentMethod.orEmpty())
                val income = serviceRevenueAccount(db)
                postFeeJournal(helper, db, stationId, userId, ledgerId, "رسوم خدمة التوصيل للتوصيل رقم $deliveryId", account, income, amount, "fuel_sale", fuelSaleId.takeIf { it > 0L } ?: deliveryId)
            }
            DEDUCT_FROM_SALES -> {
                val saleForAdjustment = sale ?: throw IllegalArgumentException("لا يمكن خصم رسوم التوصيل من المبيعات دون بيع وقود أصلي مرتبط")
                require(amount - saleForAdjustment.amount <= 0.000001) { "رسوم خدمة التوصيل لا يمكن أن تتجاوز قيمة المبيعات" }
                val id = postFeeJournal(helper, db, stationId, userId, ledgerId, "خصم رسوم التوصيل للتوصيل رقم $deliveryId", salesRevenueAccount(db), settlementAccount(db, stationId, sale.paymentMethod), amount, "fuel_sale", sale.id)
                updateSaleAmountsForDeduction(db, saleForAdjustment, amount)
                id
            }
            DRIVER -> {
                val id = postFeeJournal(helper, db, stationId, userId, ledgerId, "رسوم خدمة التوصيل على السائق رقم $driverId للتوصيل $deliveryId", driverReceivableAccount(db), serviceRevenueAccount(db), amount, "fuel_sale", fuelSaleId.takeIf { it > 0L } ?: deliveryId)
                val paymentId = createPayrollDeduction(db, stationId, driverId, amount, deliveryId)
                require(paymentId > 0L) {
                    "تعذر ربط السائق بموظف أو تسجيل الخصم في employee_payments؛ أُلغيت العملية لمنع تسجيل عهدة دون خصم راتبي"
                }
                updateLedger(db, ledgerId, stationId, ContentValues().apply { put("employee_payment_id", paymentId) })
                id
            }
            else -> 0L
        }
        updateLedger(db, ledgerId, stationId, ContentValues().apply {
            put("journal_entry_id", journalId)
            put("status", if (mode == DRIVER) "driver_due" else "posted")
            put("driver_id", driverId)
            put("delivery_id", deliveryId)
        })
        return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode).put("journal_entry_id", journalId).put("status", if (mode == DRIVER) "driver_due" else "posted")
    }

    fun settleDriverFee(helper: DatabaseHelper, deliveryId: Long, stationId: Int, userId: Long): JSONObject {
        ensureSchema(helper)
        val db = helper.writableDatabase
        val record = db.rawQuery(
            "SELECT id,amount,mode,status,journal_entry_id,employee_payment_id FROM delivery_service_fee_ledger WHERE delivery_id=? AND station_id=? LIMIT 1",
            arrayOf(deliveryId.toString(), stationId.toString())
        ).use { c ->
            if (!c.moveToFirst()) null else JSONObject().apply {
                put("id", c.getLong(0)); put("amount", c.getDouble(1)); put("mode", c.getString(2))
                put("status", c.getString(3)); put("journal_entry_id", if (c.isNull(4)) 0L else c.getLong(4))
                put("employee_payment_id", if (c.isNull(5)) 0L else c.getLong(5))
            }
        } ?: throw IllegalArgumentException("لا توجد رسوم توصيل مسجلة لهذا الطلب")
        if (normalizeMode(record.optString("mode")) != DRIVER) {
            throw IllegalArgumentException("هذا الطلب ليس على حساب السائق")
        }
        if (record.optString("status") == "settled") {
            return JSONObject().put("success", true).put("already_settled", true).put("message", "تم تسديد رسوم التوصيل مسبقاً")
        }
        require(record.optLong("journal_entry_id", 0L) > 0L) { "لم يتم ترحيل قيد استحقاق السائق بعد" }

        val ledgerId = record.getLong("id")
        val amount = record.getDouble("amount")
        val cash = settlementAccount(db, stationId, "cash")
        val driver = driverReceivableAccount(db)
        val journalId = postFeeJournal(
            helper, db, stationId, userId, ledgerId,
            "تسديد السائق رسوم خدمة التوصيل للطلب رقم $deliveryId",
            debitAccount = cash,
            creditAccount = driver,
            amount = amount,
            referenceType = "delivery_fee_settlement",
            referenceId = deliveryId
        )
        val values = ContentValues().apply {
            put("status", "settled")
            put("settlement_journal_entry_id", journalId)
            put("settled_by", userId)
            put("settled_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        }
        updateLedger(db, ledgerId, stationId, values)
        val deliveryCols = columns(db, "deliveries")
        if ("delivery_fee_status" in deliveryCols) {
            db.update("deliveries", ContentValues().apply { put("delivery_fee_status", "settled") }, "id=? AND station_id=?", arrayOf(deliveryId.toString(), stationId.toString()))
        }
        cancelPayrollDeduction(db, record.optLong("employee_payment_id", 0L))
        return JSONObject().put("success", true).put("journal_entry_id", journalId).put("message", "تم إثبات تسديد رسوم التوصيل وإنقاص مديونية السائق بقيد محاسبي")
    }

    fun enrichSaleContext(helper: DatabaseHelper, stationId: Int, context: JSONObject): JSONObject {
        ensureSchema(helper)
        val saleJson = context.optJSONObject("sale") ?: context
        val reference = saleJson.optLong("sale_id", saleJson.optLong("id", 0L))
        val sale = findSale(helper.readableDatabase, stationId, reference) ?: return context
        saleJson.put("fuel_sale_id", sale.id)
        saleJson.put("fuel_type_id", sale.fuelTypeId)
        saleJson.put("fuel_type_name", sale.fuelTypeName)
        saleJson.put("fuel_type", sale.fuelTypeName)
        saleJson.put("delivery_service_fee", sale.deliveryFee)
        saleJson.put("service_fee", sale.deliveryFee)
        saleJson.put("delivery_fee_mode", sale.deliveryFeeMode)
        saleJson.put("delivery_fee_mode_label", modeLabel(sale.deliveryFeeMode))
        saleJson.put("delivery_location", sale.deliveryLocation)
        saleJson.put("location", sale.deliveryLocation.ifBlank { saleJson.optString("location") })
        saleJson.put("cash_customer_name", sale.cashCustomerName)
        val cash = sale.paymentMethod.trim().lowercase(Locale.ROOT) in setOf("cash", "نقدي", "نقداً")
        saleJson.put("cash_sale", cash)
        saleJson.put("payment_method", sale.paymentMethod)
        if (sale.cashCustomerName.isNotBlank() && cash) {
            saleJson.put("customer_name", sale.cashCustomerName)
            saleJson.put("customer_display", sale.cashCustomerName)
        }
        return context
    }

    fun enrichDeliverySmsPayload(helper: DatabaseHelper, deliveryId: Long, stationId: Int, original: JSONObject): JSONObject {
        ensureSchema(helper)
        val db = helper.readableDatabase
        val dCols = columns(db, "deliveries")
        if (deliveryId <= 0L || !tableExists(db, "deliveries")) return original
        val saleRef = original.optLong("sale_id", 0L).takeIf { it > 0L } ?: run {
            val saleCol = if ("sale_id" in dCols) "COALESCE(sale_id,0)" else "0"
            db.rawQuery("SELECT $saleCol FROM deliveries WHERE id=? AND station_id=? LIMIT 1", arrayOf(deliveryId.toString(), stationId.toString()))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }
        val deliveryRow = db.rawQuery(
            "SELECT * FROM deliveries WHERE id=? AND station_id=? LIMIT 1",
            arrayOf(deliveryId.toString(), stationId.toString())
        ).use { c ->
            if (!c.moveToFirst()) null else cursorObject(c)
        }
        val delivery = deliveryRow ?: return original
        val sale = findSale(db, stationId, saleRef)
        val fuelSaleId = sale?.id ?: 0L

        delivery.optString("location").trim().ifBlank { delivery.optString("delivery_location").trim() }
            .takeIf { it.isNotBlank() }?.let { original.put("location", it); original.put("delivery_location", it) }
        if (sale != null) {
            if (sale.fuelTypeName.isNotBlank()) {
                original.put("fuel_type_name", sale.fuelTypeName)
                original.put("fuel_type", sale.fuelTypeName)
            }
            original.put("sale_kind", "fuel")
            original.put("payment_method", sale.paymentMethod)
            original.put("cash_sale", sale.paymentMethod.trim().lowercase(Locale.ROOT) in setOf("cash", "نقدي", "نقداً"))
            original.put("delivery_service_fee", sale.deliveryFee.takeIf { it > 0.0 } ?: delivery.optDouble("delivery_service_fee", 0.0))
            original.put("delivery_fee_mode", if (sale.deliveryFeeMode != EXEMPT) sale.deliveryFeeMode else delivery.optString("delivery_fee_mode", EXEMPT))
            original.put("cash_customer_name", sale.cashCustomerName.ifBlank { delivery.optString("cash_customer_name") })
            if (sale.cashCustomerName.isNotBlank()) {
                original.put("customer_display", sale.cashCustomerName)
                original.put("customer_name", sale.cashCustomerName)
            }
        } else {
            original.put("sale_kind", delivery.optString("sale_type", original.optString("sale_kind", "fuel")))
            original.put("delivery_service_fee", delivery.optDouble("delivery_service_fee", 0.0))
            original.put("delivery_fee_mode", delivery.optString("delivery_fee_mode", EXEMPT))
            original.put("cash_customer_name", delivery.optString("cash_customer_name"))
        }
        if (delivery.has("quantity") && !delivery.isNull("quantity")) original.put("quantity", delivery.opt("quantity"))
        if (delivery.optString("cash_customer_name").isNotBlank()) original.put("cash_customer_name", delivery.optString("cash_customer_name"))
        if (delivery.optString("delivery_fee_mode").isNotBlank()) original.put("delivery_fee_mode", delivery.optString("delivery_fee_mode"))
        // Keep the bridge's authoritative delivery/customer labels when they already exist.
        original.put("delivery_id", deliveryId)
        original.put("fuel_sale_id", fuelSaleId)
        return original
    }

    private fun cursorObject(cursor: Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until cursor.columnCount) {
            val value: Any? = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> null
                else -> null
            }
            if (value != null) put(cursor.getColumnName(i), value)
        }
    }

    private fun upsertLedger(
        db: SQLiteDatabase,
        stationId: Int,
        fuelSaleId: Long,
        parentSaleId: Long,
        deliveryId: Long,
        driverId: Long,
        amount: Double,
        mode: String,
        userId: Long,
        notes: String
    ): Long {
        val old = when {
            fuelSaleId > 0L -> db.rawQuery(
                "SELECT id,amount,mode,status,journal_entry_id FROM delivery_service_fee_ledger WHERE station_id=? AND fuel_sale_id=? LIMIT 1",
                arrayOf(stationId.toString(), fuelSaleId.toString())
            ).use { if (it.moveToFirst()) cursorObject(it) else null }
            deliveryId > 0L -> db.rawQuery(
                "SELECT id,amount,mode,status,journal_entry_id FROM delivery_service_fee_ledger WHERE station_id=? AND delivery_id=? LIMIT 1",
                arrayOf(stationId.toString(), deliveryId.toString())
            ).use { if (it.moveToFirst()) cursorObject(it) else null }
            else -> null
        }
        if (old != null) {
            val oldId = old.optLong("id")
            val oldJournal = old.optLong("journal_entry_id", 0L)
            val changed = abs(old.optDouble("amount") - amount) > 0.000001 || normalizeMode(old.optString("mode")) != mode
            if (changed && oldJournal > 0L) {
                throw IllegalArgumentException("لا يمكن تغيير رسوم توصيل سبق ترحيلها محاسبياً؛ استخدم قيد تسوية")
            }
            val values = ContentValues().apply {
                put("parent_sale_id", parentSaleId)
                put("delivery_id", deliveryId)
                put("driver_id", driverId)
                put("amount", amount)
                put("mode", mode)
                put("notes", notes)
            }
            db.update("delivery_service_fee_ledger", values, "id=? AND station_id=?", arrayOf(oldId.toString(), stationId.toString()))
            return oldId
        }
        return db.insertOrThrow("delivery_service_fee_ledger", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", stationId)
            put("fuel_sale_id", fuelSaleId)
            put("parent_sale_id", parentSaleId)
            put("delivery_id", deliveryId)
            put("driver_id", driverId)
            put("amount", amount)
            put("mode", mode)
            put("status", if (amount <= 0.0 || mode == EXEMPT) "exempt" else if (mode == DRIVER) "pending_driver" else "pending")
            put("created_by", userId)
            put("notes", notes)
        })
    }

    private fun ledgerRow(db: SQLiteDatabase, stationId: Int, id: Long): JSONObject? =
        db.rawQuery(
            "SELECT * FROM delivery_service_fee_ledger WHERE id=? AND station_id=? LIMIT 1",
            arrayOf(id.toString(), stationId.toString())
        ).use { if (it.moveToFirst()) cursorObject(it) else null }

    private fun updateLedger(db: SQLiteDatabase, id: Long, stationId: Int, values: ContentValues) {
        val changed = db.update("delivery_service_fee_ledger", values, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        check(changed == 1) { "تعذر تحديث سجل رسوم خدمة التوصيل" }
    }

    private fun updateSaleAmountsForDeduction(db: SQLiteDatabase, sale: FuelSale, amount: Double) {
        val net = (sale.amount - amount).coerceAtLeast(0.0)
        val fs = ContentValues()
        val fCols = columns(db, "fuel_sales")
        if ("gross_amount" in fCols) fs.put("gross_amount", sale.amount)
        if ("total_amount" in fCols) fs.put("total_amount", net)
        if ("net_amount" in fCols) fs.put("net_amount", net)
        if ("delivery_fee_display_total" in fCols) fs.put("delivery_fee_display_total", net)
        db.update("fuel_sales", fs, "id=? AND station_id=?", arrayOf(sale.id.toString(), sale.stationId.toString()))

        // Preserve the gross original for audit where supported, while reports and the
        // receivable reflect the net amount after the chosen sales reduction.
        if (tableExists(db, "sales_transactions") && sale.parentSaleId > 0L) {
            val cols = columns(db, "sales_transactions")
            val values = ContentValues()
            if ("gross_amount" in cols) values.put("gross_amount", sale.amount)
            if ("total_amount" in cols) values.put("total_amount", net)
            if ("net_amount" in cols) values.put("net_amount", net)
            if ("delivery_service_fee" in cols) values.put("delivery_service_fee", amount)
            if (values.size() > 0) db.update("sales_transactions", values, "id=? AND station_id=?", arrayOf(sale.parentSaleId.toString(), sale.stationId.toString()))
        }
    }

    private fun postFeeJournal(
        helper: DatabaseHelper,
        db: SQLiteDatabase,
        stationId: Int,
        userId: Long,
        ledgerId: Long,
        description: String,
        debitAccount: Account,
        creditAccount: Account,
        amount: Double,
        referenceType: String,
        referenceId: Long
    ): Long {
        require(amount > 0.0 && amount.isFinite()) { "مبلغ قيد رسوم التوصيل غير صالح" }
        require(debitAccount.id != creditAccount.id) { "حساب المدين والدائن لا يمكن أن يكونا الحساب نفسه" }
        val items = JSONArray()
            .put(JSONObject().put("account_id", debitAccount.id).put("debit", amount).put("credit", 0.0))
            .put(JSONObject().put("account_id", creditAccount.id).put("debit", 0.0).put("credit", amount))
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val payload = JSONObject()
            .put("entry_date", date)
            .put("entry_type", "general")
            .put("description", description)
            .put("reference_type", referenceType)
            .put("reference_id", referenceId)
            .put("items", items)
        val journalId = helper.saveJournalEntry(payload, userId, stationId)
        check(journalId > 0L) { "تعذر إنشاء قيد رسوم خدمة التوصيل" }
        val references = ContentValues().apply {
            put("reference_type", referenceType)
            put("reference_id", referenceId)
            if ("description" in columns(db, "journal_entries")) put("description", description)
        }
        db.update("journal_entries", references, "id=? AND station_id=?", arrayOf(journalId.toString(), stationId.toString()))
        check(helper.postJournalEntry(journalId, userId, stationId) == 1) {
            "تعذر ترحيل قيد رسوم خدمة التوصيل رقم $journalId"
        }
        return journalId
    }

    private fun accounts(db: SQLiteDatabase): List<Account> {
        require(tableExists(db, "accounts")) { "جدول الحسابات غير متاح" }
        val cols = columns(db, "accounts")
        require("id" in cols && "account_code" in cols && "account_name" in cols && "account_type" in cols) {
            "بنية جدول الحسابات لا تسمح بالتحقق الآمن من الحسابات"
        }
        val nameAr = if ("account_name_ar" in cols) "COALESCE(account_name_ar,'')" else "''"
        val active = if ("is_active" in cols) "COALESCE(is_active,1)=1" else "1=1"
        val notDeleted = if ("is_deleted" in cols) "COALESCE(is_deleted,0)=0" else "1=1"
        val sql = "SELECT id,COALESCE(account_code,''),COALESCE(account_name,''),$nameAr,COALESCE(account_type,'') FROM accounts WHERE $active AND $notDeleted"
        return db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    Account(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty())
                )
            }
        }
    }

    private fun chooseAccount(db: SQLiteDatabase, label: String, predicate: (Account) -> Boolean, score: (Account) -> Int): Account {
        val candidates = accounts(db).filter(predicate)
        require(candidates.isNotEmpty()) { "لا يوجد حساب محاسبي نشط مخصص لـ$label في شجرة الحسابات" }
        val ranked = candidates.map { it to score(it) }.sortedByDescending { it.second }
        val bestScore = ranked.first().second
        val best = ranked.filter { it.second == bestScore }
        require(best.size == 1) {
            "توجد عدة حسابات محتملة لـ$label؛ اربط حساباً واحداً صحيحاً في شجرة الحسابات"
        }
        return best.first().first
    }

    private fun serviceRevenueAccount(db: SQLiteDatabase): Account = chooseAccount(
        db, "إيرادات خدمة التوصيل",
        predicate = { a ->
            val txt = a.allText
            val income = a.typeText in setOf("income", "revenue", "revenues", "إيراد", "إيرادات") ||
                txt.contains("إيراد") || txt.contains("revenue")
            val delivery = txt.contains("توصيل") || txt.contains("delivery")
            val service = txt.contains("خدم") || txt.contains("service")
            income && delivery && service
        },
        score = { a -> (if (a.allText.contains("توصيل") || a.allText.contains("delivery")) 4 else 0) + (if (a.allText.contains("خدمة التوصيل") || a.allText.contains("delivery service")) 4 else 0) }
    )

    private fun salesRevenueAccount(db: SQLiteDatabase): Account = chooseAccount(
        db, "إيرادات المبيعات",
        predicate = { a ->
            val txt = a.allText
            val income = a.typeText in setOf("income", "revenue", "revenues", "إيراد", "إيرادات") || txt.contains("إيراد") || txt.contains("revenue")
            val sales = txt.contains("مبيعات") || txt.contains("sales")
            income && sales && !txt.contains("مرتجع") && !txt.contains("مردود") && !txt.contains("return") && !txt.contains("خصم")
        },
        score = { a -> (if (a.allText.contains("وقود") || a.allText.contains("fuel")) 4 else 0) + (if (a.allText.contains("مبيعات الوقود") || a.allText.contains("fuel sales")) 4 else 0) }
    )

    private fun driverReceivableAccount(db: SQLiteDatabase): Account {
        val all = accounts(db)
        val direct = all.filter { a ->
            val t = a.allText
            val asset = a.typeText in setOf("asset", "assets", "أصل", "أصول") || t.contains("ذمم") || t.contains("عهد")
            val driver = t.contains("سائق") || t.contains("driver")
            val receivable = t.contains("ذمم") || t.contains("عهد") || t.contains("سلف") || t.contains("advance") || t.contains("receivable")
            asset && driver && receivable
        }
        val fallback = if (direct.isNotEmpty()) direct else all.filter { a ->
            val t = a.allText
            (a.typeText in setOf("asset", "assets", "أصل", "أصول") || t.contains("ذمم") || t.contains("عهد")) &&
                (t.contains("عهد الموظفين") || t.contains("سلف الموظفين") || t.contains("employee advance") || t.contains("employee receivable"))
        }
        require(fallback.isNotEmpty()) { "لا يوجد حساب عهد/ذمم مدينة نشط للسائقين أو الموظفين" }
        val ranked = fallback.sortedByDescending { a -> (if (a.allText.contains("سائق") || a.allText.contains("driver")) 8 else 0) + (if (a.allText.contains("عهد") || a.allText.contains("advance")) 3 else 0) }
        val topScore = (if (ranked.first().allText.contains("سائق") || ranked.first().allText.contains("driver")) 8 else 0) + (if (ranked.first().allText.contains("عهد") || ranked.first().allText.contains("advance")) 3 else 0)
        require(ranked.count { a -> (if (a.allText.contains("سائق") || a.allText.contains("driver")) 8 else 0) + (if (a.allText.contains("عهد") || a.allText.contains("advance")) 3 else 0) == topScore } == 1) {
            "توجد عدة حسابات محتملة لعهد السائقين؛ اربط حساباً واحداً صحيحاً"
        }
        return ranked.first()
    }

    private fun settlementAccount(db: SQLiteDatabase, stationId: Int, paymentMethod: String): Account {
        val method = paymentMethod.trim().lowercase(Locale.ROOT)
        if (method in setOf("credit", "آجل", "credit_sale", "credit_account")) {
            return chooseAccount(
                db, "الذمم المدينة للعملاء",
                predicate = { a ->
                    val t = a.allText
                    val asset = a.typeText in setOf("asset", "assets", "أصل", "أصول") || t.contains("ذمم")
                    asset && (t.contains("ذمم مدينة") || t.contains("حسابات العملاء") || t.contains("العملاء") ||
                        t.contains("المدينون") || t.contains("accounts receivable") || t.contains("customer receivable")) &&
                        !t.contains("مورد") && !t.contains("supplier")
                },
                score = { a -> (if (a.allText.contains("ذمم مدينة") || a.allText.contains("accounts receivable")) 5 else 0) + (if (a.allText.contains("عملاء") || a.allText.contains("customer")) 2 else 0) }
            )
        }
        if (method in setOf("credit_card", "card", "بطاقة", "بطاقات", "pos")) {
            return chooseAccount(
                db, "حساب تسوية البطاقات البنكية",
                predicate = { a ->
                    val t = a.allText
                    val asset = a.typeText in setOf("asset", "assets", "أصل", "أصول") || t.contains("بنك") || t.contains("bank")
                    val cardOrBank = t.contains("بطاقة") || t.contains("بطاقات") ||
                        t.contains("نقاط البيع") || t.contains("pos") || t.contains("bank") ||
                        t.contains("بنك") || t.contains("تسوية البطاقات") || t.contains("card clearing")
                    asset && cardOrBank && !t.contains("ذمم") && !t.contains("عملاء") &&
                        !t.contains("صندوق") && !t.contains("نقدية") && !t.contains("cash on hand")
                },
                score = { a ->
                    (if (a.allText.contains("تسوية البطاقات") || a.allText.contains("card clearing") || a.allText.contains("نقاط البيع")) 6 else 0) +
                    (if (a.allText.contains("بطاقة") || a.allText.contains("بطاقات") || a.allText.contains("card")) 3 else 0) +
                    (if (a.allText.contains("بنك") || a.allText.contains("bank")) 2 else 0)
                }
            )
        }
        val all = accounts(db)
        val mappedCashbox = findMappedCashboxAccount(db, stationId)
        if (mappedCashbox > 0L) all.firstOrNull { it.id == mappedCashbox }?.let { return it }
        val candidates = all.filter { a ->
            val t = a.allText
            val asset = a.typeText in setOf("asset", "assets", "أصل", "أصول") || t.contains("صندوق") || t.contains("نقد")
            asset && (t.contains("صندوق") || t.contains("نقدية") || t.contains("نقد") || t.contains("cash on hand") || t.contains("cash")) &&
                !t.contains("ذمم") && !t.contains("عملاء") && !t.contains("receivable")
        }
        require(candidates.isNotEmpty()) { "لا يوجد حساب نقد/صندوق نشط مرتبط بالمحطة" }
        val ranked = candidates.sortedByDescending { a -> (if (a.allText.contains("رئيسي") || a.allText.contains("main")) 5 else 0) + (if (a.allText.contains("صندوق") || a.allText.contains("cash on hand")) 2 else 0) }
        val score = (if (ranked.first().allText.contains("رئيسي") || ranked.first().allText.contains("main")) 5 else 0) + (if (ranked.first().allText.contains("صندوق") || ranked.first().allText.contains("cash on hand")) 2 else 0)
        require(ranked.count { a -> (if (a.allText.contains("رئيسي") || a.allText.contains("main")) 5 else 0) + (if (a.allText.contains("صندوق") || a.allText.contains("cash on hand")) 2 else 0) == score } == 1) {
            "توجد عدة حسابات نقدية محتملة؛ حدد الصندوق المحاسبي الافتراضي للمحطة"
        }
        return ranked.first()
    }

    private fun findMappedCashboxAccount(db: SQLiteDatabase, stationId: Int): Long {
        if (!tableExists(db, "cashboxes")) return 0L
        val c = columns(db, "cashboxes")
        val accountCol = listOf("account_id", "chart_account_id", "accounting_account_id").firstOrNull { it in c } ?: return 0L
        val stationCol = if ("station_id" in c) "station_id" else return 0L
        val active = if ("is_active" in c) "AND COALESCE(is_active,1)=1" else ""
        val notDeleted = if ("is_deleted" in c) "AND COALESCE(is_deleted,0)=0" else ""
        val defaultOrder = listOf("is_default", "is_primary", "is_main").firstOrNull { it in c }
            ?.let { " ORDER BY COALESCE($it,0) DESC,id ASC" } ?: " ORDER BY id ASC"
        return db.rawQuery(
            "SELECT $accountCol FROM cashboxes WHERE $stationCol=? $active $notDeleted AND COALESCE($accountCol,0)>0 $defaultOrder LIMIT 2",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) 0L else {
                val first = cursor.getLong(0)
                if (cursor.moveToNext() && (c.none { it in setOf("is_default", "is_primary", "is_main") } || cursor.getLong(0) == first)) 0L else first
            }
        }
    }

    private fun createPayrollDeduction(db: SQLiteDatabase, stationId: Int, driverId: Long, amount: Double, deliveryId: Long): Long {
        if (!tableExists(db, "drivers") || !tableExists(db, "employees") || !tableExists(db, "employee_payments")) return 0L
        val driverCols = columns(db, "drivers")
        if ("employee_id" !in driverCols || "station_id" !in driverCols) return 0L
        val employeeId = db.rawQuery(
            "SELECT COALESCE(employee_id,0) FROM drivers WHERE id=? AND station_id=? LIMIT 1",
            arrayOf(driverId.toString(), stationId.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (employeeId <= 0L) return 0L
        val employeeCols = columns(db, "employees")
        if ("station_id" in employeeCols) {
            val exists = db.rawQuery("SELECT 1 FROM employees WHERE id=? AND station_id=? LIMIT 1", arrayOf(employeeId.toString(), stationId.toString())).use { it.moveToFirst() }
            if (!exists) return 0L
        }
        val payCols = columns(db, "employee_payments")
        if (!setOf("employee_id", "station_id", "amount", "type").all { it in payCols }) return 0L
        val values = ContentValues()
        putIfColumn(db, "employee_payments", values, "uuid", UUID.randomUUID().toString())
        putIfColumn(db, "employee_payments", values, "employee_id", employeeId)
        putIfColumn(db, "employee_payments", values, "station_id", stationId)
        putIfColumn(db, "employee_payments", values, "amount", amount)
        putIfColumn(db, "employee_payments", values, "type", "deduction")
        putIfColumn(db, "employee_payments", values, "status", "pending")
        putIfColumn(db, "employee_payments", values, "is_deleted", 0)
        val memo = "رسوم خدمة التوصيل رقم $deliveryId"
        listOf("notes", "description", "remarks", "reference").firstOrNull { it in payCols }?.let { putIfColumn(db, "employee_payments", values, it, memo) }
        return runCatching { db.insertOrThrow("employee_payments", null, values) }.getOrDefault(0L)
    }

    private fun cancelPayrollDeduction(db: SQLiteDatabase, paymentId: Long) {
        if (paymentId <= 0L || !tableExists(db, "employee_payments")) return
        val cols = columns(db, "employee_payments")
        val values = ContentValues()
        if ("is_deleted" in cols) values.put("is_deleted", 1)
        if ("status" in cols) values.put("status", "cancelled")
        if (values.size() > 0) db.update("employee_payments", values, "id=?", arrayOf(paymentId.toString()))
    }
}
