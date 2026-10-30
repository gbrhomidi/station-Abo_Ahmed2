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
    private val ensuredSchemaVersions = mutableSetOf<String>()

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
        val cashCustomerName: String,
        val grossAmount: Double = amount,
        val isFuelSale: Boolean = true
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

    @Synchronized
    fun ensureSchema(helper: DatabaseHelper) {
        val db = helper.writableDatabase
        val schemaKey = "${db.path}:${db.version}"
        if (schemaKey in ensuredSchemaVersions) return
        addColumn(db, "fuel_sales", "delivery_service_fee", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "fuel_sales", "delivery_fee_mode", "TEXT NOT NULL DEFAULT 'exempt'")
        addColumn(db, "fuel_sales", "delivery_location", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "fuel_sales", "cash_customer_name", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "fuel_sales", "delivery_fee_display_total", "REAL NOT NULL DEFAULT 0")
        // Preserve the original fuel quantity/value for auditing and fee reconciliation.
        addColumn(db, "fuel_sales", "gross_amount", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "fuel_sales", "gross_quantity", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "sales_transactions", "delivery_service_fee", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "sales_transactions", "delivery_fee_mode", "TEXT NOT NULL DEFAULT 'exempt'")
        addColumn(db, "sales_transactions", "delivery_location", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "sales_transactions", "cash_customer_name", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "sales_transactions", "delivery_fee_display_total", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "sales_transactions", "gross_amount", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "sales_transactions", "gross_quantity", "REAL NOT NULL DEFAULT 0")
        addColumn(db, "deliveries", "sale_kind", "TEXT NOT NULL DEFAULT 'fuel'")
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
        ensuredSchemaVersions += schemaKey
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

    private fun findSale(
        db: SQLiteDatabase,
        stationId: Int,
        referenceId: Long,
        saleKind: String? = null
    ): FuelSale? {
        if (referenceId <= 0L) return null
        val requestedKind = saleKind.orEmpty().trim().lowercase(Locale.ROOT)
        if (requestedKind in setOf("product", "products", "منتجات", "منتج")) {
            return findProductSale(db, stationId, referenceId)
        }
        if (requestedKind in setOf("fuel", "fuel_sale", "وقود")) {
            return findFuelSale(db, stationId, referenceId)
        }
        return findFuelSale(db, stationId, referenceId) ?: findProductSale(db, stationId, referenceId)
    }

    private fun findFuelSale(db: SQLiteDatabase, stationId: Int, referenceId: Long): FuelSale? {
        if (!tableExists(db, "fuel_sales")) return null
        val c = columns(db, "fuel_sales")
        val stationCol = if ("station_id" in c) "station_id" else return null
        fun one(where: String, args: Array<String>): FuelSale? {
            val fuelIdExpr = when {
                "fuel_type_id" in c -> "fuel_type_id"
                "fuel_id" in c -> "fuel_id"
                else -> "0"
            }
            val parentColumn = if ("sale_id" in c) "COALESCE(sale_id,0)" else "0"
            val customerColumn = when {
                "customer_party_id" in c -> "COALESCE(customer_party_id,0)"
                "customer_id" in c -> "COALESCE(customer_id,0)"
                else -> "0"
            }
            val paymentColumn = if ("payment_method" in c) "COALESCE(payment_method,'cash')" else "'cash'"
            val quantityColumn = if ("quantity" in c) "COALESCE(quantity,0)" else "0"
            val amountColumn = when {
                "total_amount" in c -> "COALESCE(total_amount,0)"
                "net_amount" in c -> "COALESCE(net_amount,0)"
                else -> "0"
            }
            val feeColumn = if ("delivery_service_fee" in c) "COALESCE(delivery_service_fee,0)" else "0"
            val modeColumn = if ("delivery_fee_mode" in c) "COALESCE(delivery_fee_mode,'exempt')" else "'exempt'"
            val locationColumn = if ("delivery_location" in c) "COALESCE(delivery_location,'')" else "''"
            val cashNameColumn = if ("cash_customer_name" in c) "COALESCE(cash_customer_name,'')" else "''"
            val grossColumn = if ("gross_amount" in c) "COALESCE(NULLIF(gross_amount,0),$amountColumn)" else amountColumn
            return db.rawQuery(
                "SELECT id,$parentColumn,$stationCol,$customerColumn,$paymentColumn,$fuelIdExpr," +
                    "$quantityColumn,$amountColumn,$feeColumn,$modeColumn,$locationColumn,$cashNameColumn,$grossColumn " +
                    "FROM fuel_sales WHERE $where LIMIT 1",
                args
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val fuelId = if (cursor.isNull(5)) 0L else cursor.getLong(5)
                    FuelSale(
                        id = cursor.getLong(0),
                        parentSaleId = cursor.getLong(1),
                        stationId = cursor.getInt(2),
                        customerId = cursor.getLong(3),
                        paymentMethod = cursor.getString(4).orEmpty(),
                        fuelTypeId = fuelId,
                        fuelTypeName = fuelTypeName(db, fuelId),
                        quantity = cursor.getDouble(6),
                        amount = cursor.getDouble(7),
                        deliveryFee = cursor.getDouble(8),
                        deliveryFeeMode = normalizeMode(cursor.getString(9)),
                        deliveryLocation = cursor.getString(10).orEmpty(),
                        cashCustomerName = cursor.getString(11).orEmpty(),
                        grossAmount = cursor.getDouble(12),
                        isFuelSale = true
                    )
                }
            }
        }
        return one("id=? AND $stationCol=?", arrayOf(referenceId.toString(), stationId.toString()))
            ?: if ("sale_id" in c) one("sale_id=? AND $stationCol=?", arrayOf(referenceId.toString(), stationId.toString())) else null
    }

    private fun findProductSale(db: SQLiteDatabase, stationId: Int, referenceId: Long): FuelSale? {
        if (!tableExists(db, "sales_transactions")) return null
        val c = columns(db, "sales_transactions")
        if (!setOf("id", "station_id").all { it in c }) return null
        val productTypeColumn = when {
            "order_type" in c -> "order_type"
            "sale_type" in c -> "sale_type"
            "transaction_type" in c -> "transaction_type"
            else -> return null
        }
        val customerColumn = when {
            "customer_party_id" in c -> "COALESCE(customer_party_id,0)"
            "customer_id" in c -> "COALESCE(customer_id,0)"
            else -> "0"
        }
        val paymentColumn = if ("payment_method" in c) "COALESCE(payment_method,'cash')" else "'cash'"
        val quantityColumn = when {
            "quantity" in c -> "COALESCE(quantity,0)"
            "liters" in c -> "COALESCE(liters,0)"
            else -> "0"
        }
        val amountColumn = when {
            "net_amount" in c -> "COALESCE(net_amount,0)"
            "total_amount" in c -> "COALESCE(total_amount,0)"
            else -> "0"
        }
        val feeColumn = if ("delivery_service_fee" in c) "COALESCE(delivery_service_fee,0)" else "0"
        val modeColumn = if ("delivery_fee_mode" in c) "COALESCE(delivery_fee_mode,'exempt')" else "'exempt'"
        val locationColumn = if ("delivery_location" in c) "COALESCE(delivery_location,'')" else "''"
        val cashNameColumn = if ("cash_customer_name" in c) "COALESCE(cash_customer_name,'')" else "''"
        val grossColumn = if ("gross_amount" in c) "COALESCE(NULLIF(gross_amount,0),$amountColumn)" else amountColumn
        val activeClause = if ("is_deleted" in c) "AND COALESCE(is_deleted,0)=0" else ""
        return db.rawQuery(
            "SELECT station_id,$customerColumn,$paymentColumn,$quantityColumn,$amountColumn,$feeColumn," +
                "$modeColumn,$locationColumn,$cashNameColumn,$grossColumn FROM sales_transactions " +
                "WHERE id=? AND station_id=? AND LOWER(COALESCE($productTypeColumn,'')) IN ('product','products') $activeClause LIMIT 1",
            arrayOf(referenceId.toString(), stationId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            FuelSale(
                id = 0L,
                parentSaleId = referenceId,
                stationId = cursor.getInt(0),
                customerId = cursor.getLong(1),
                paymentMethod = cursor.getString(2).orEmpty(),
                fuelTypeId = 0L,
                fuelTypeName = "",
                quantity = cursor.getDouble(3),
                amount = cursor.getDouble(4),
                deliveryFee = cursor.getDouble(5),
                deliveryFeeMode = normalizeMode(cursor.getString(6)),
                deliveryLocation = cursor.getString(7).orEmpty(),
                cashCustomerName = cursor.getString(8).orEmpty(),
                grossAmount = cursor.getDouble(9),
                isFuelSale = false
            )
        }
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
        paymentMethod: String?,
        originalQuantity: Double? = null,
        originalAmount: Double? = null,
        schemaAlreadyEnsured: Boolean = false
    ): JSONObject {
        if (!schemaAlreadyEnsured) ensureSchema(helper)
        val db = helper.writableDatabase
        require(fuelSaleId > 0L && stationId > 0) { "معرف البيع أو المحطة غير صالح" }
        require(amount.isFinite() && amount >= 0.0) { "رسوم خدمة التوصيل غير صالحة" }

        if (originalAmount != null || originalQuantity != null) {
            val cols = columns(db, "fuel_sales")
            val grossValues = ContentValues()
            if (originalAmount != null && originalAmount.isFinite() && "gross_amount" in cols) grossValues.put("gross_amount", originalAmount)
            if (originalQuantity != null && originalQuantity.isFinite() && "gross_quantity" in cols) grossValues.put("gross_quantity", originalQuantity)
            if (grossValues.size() > 0) db.update("fuel_sales", grossValues, "id=? AND station_id=?", arrayOf(fuelSaleId.toString(), stationId.toString()))
        }

        val sale = findSale(db, stationId, fuelSaleId)
            ?: throw IllegalStateException("لم يتم العثور على عملية بيع الوقود في SQLite")
        val mode = normalizeMode(rawMode)
        val feeAmount = if (mode == EXEMPT) 0.0 else amount
        val grossAmount = (originalAmount ?: sale.grossAmount).takeIf { it.isFinite() && it > 0.0 } ?: sale.amount
        val grossQuantity = (originalQuantity ?: sale.quantity).takeIf { it.isFinite() && it > 0.0 } ?: sale.quantity
        val cleanLocation = location.orEmpty().trim()
        val cleanCashName = cashCustomerName.orEmpty().trim()
        val salePayment = paymentMethod.orEmpty().ifBlank { sale.paymentMethod }.trim()

        if (feeAmount > 0.0 && cleanLocation.isBlank()) throw IllegalArgumentException("أدخل موقع التوصيل عند احتساب رسوم خدمة التوصيل")
        if (feeAmount > 0.0 && mode == DEDUCT_FROM_SALES && feeAmount >= grossAmount - 0.000001) {
            throw IllegalArgumentException("رسوم خدمة التوصيل يجب أن تكون أقل من قيمة الوقود كي تبقى كمية موجبة للبيع")
        }

        val invoiceTotal = when (mode) {
            ADD_TO_SALES, DRIVER -> grossAmount + feeAmount
            DEDUCT_FROM_SALES, EXEMPT -> grossAmount
            else -> grossAmount
        }
        val fuelColumns = columns(db, "fuel_sales")
        val fuelValues = ContentValues().apply {
            put("delivery_service_fee", feeAmount)
            put("delivery_fee_mode", mode)
            put("delivery_location", cleanLocation)
            put("cash_customer_name", cleanCashName)
            put("delivery_fee_display_total", invoiceTotal)
            if ("gross_amount" in fuelColumns) put("gross_amount", grossAmount)
            if ("gross_quantity" in fuelColumns) put("gross_quantity", grossQuantity)
        }
        db.update("fuel_sales", fuelValues, "id=? AND station_id=?", arrayOf(fuelSaleId.toString(), stationId.toString()))

        // Parent transaction is the customer invoice; fuel_sales retains the fuel-only value.
        if (sale.parentSaleId > 0L && tableExists(db, "sales_transactions")) {
            val transactionColumns = columns(db, "sales_transactions")
            val transactionValues = ContentValues()
            if ("delivery_service_fee" in transactionColumns) transactionValues.put("delivery_service_fee", feeAmount)
            if ("delivery_fee_mode" in transactionColumns) transactionValues.put("delivery_fee_mode", mode)
            if ("delivery_location" in transactionColumns) transactionValues.put("delivery_location", cleanLocation)
            if ("cash_customer_name" in transactionColumns) transactionValues.put("cash_customer_name", cleanCashName)
            if ("delivery_fee_display_total" in transactionColumns) transactionValues.put("delivery_fee_display_total", invoiceTotal)
            if ("total_amount" in transactionColumns) transactionValues.put("total_amount", invoiceTotal)
            if ("net_amount" in transactionColumns) transactionValues.put("net_amount", invoiceTotal)
            if ("gross_amount" in transactionColumns) transactionValues.put("gross_amount", grossAmount)
            if ("gross_quantity" in transactionColumns) transactionValues.put("gross_quantity", grossQuantity)
            if (transactionValues.size() > 0) db.update("sales_transactions", transactionValues, "id=? AND station_id=?", arrayOf(sale.parentSaleId.toString(), stationId.toString()))
        }

        val ledgerId = upsertLedger(db, stationId, sale.id, sale.parentSaleId, 0L, 0L, feeAmount, mode, userId,
            "fuel_sale=$fuelSaleId; payment_method=$salePayment")
        val existing = ledgerRow(db, stationId, ledgerId)
        if (feeAmount <= 0.0 || mode == EXEMPT) {
            updateLedger(db, ledgerId, stationId, ContentValues().apply { put("status", "exempt") })
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode)
                .put("journal_entry_id", JSONObject.NULL).put("invoice_total", invoiceTotal)
                .put("fuel_amount", sale.amount).put("fuel_quantity", sale.quantity).put("service_fee", 0.0)
        }

        val existingJournal = existing?.optLong("journal_entry_id", 0L) ?: 0L
        if (existingJournal > 0L) {
            val existingAmount = existing?.optDouble("amount", feeAmount) ?: feeAmount
            val existingMode = normalizeMode(existing?.optString("mode", mode))
            require(abs(existingAmount - feeAmount) < 0.000001 && existingMode == mode) {
                "رسوم التوصيل مسجلة محاسبياً مسبقاً بقيمة أو طريقة مختلفة؛ استخدم قيد تسوية بدلاً من تغيير العملية"
            }
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode)
                .put("journal_entry_id", existingJournal).put("invoice_total", invoiceTotal)
                .put("fuel_amount", sale.amount).put("fuel_quantity", sale.quantity).put("service_fee", feeAmount)
        }

        if (mode == DRIVER) {
            updateLedger(db, ledgerId, stationId, ContentValues().apply { put("status", "pending_driver") })
            return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode)
                .put("pending_driver", true).put("invoice_total", invoiceTotal)
                .put("fuel_amount", sale.amount).put("fuel_quantity", sale.quantity).put("service_fee", feeAmount)
        }

        val journalId = when (mode) {
            ADD_TO_SALES -> postFeeJournal(
                helper, db, stationId, userId, ledgerId,
                "رسوم خدمة التوصيل للبيع رقم $fuelSaleId",
                debitAccount = settlementAccount(db, stationId, salePayment),
                creditAccount = serviceRevenueAccount(helper, db, stationId, userId),
                amount = feeAmount, referenceType = "fuel_sale", referenceId = sale.id
            )
            DEDUCT_FROM_SALES -> postFeeJournal(
                helper, db, stationId, userId, ledgerId,
                "إعادة تصنيف قيمة الوقود المحولة إلى إيرادات خدمة التوصيل للبيع رقم $fuelSaleId",
                // The fuel component was reduced before stock posting; debit the settlement
                // account for the service component without deducting the settlement twice.
                debitAccount = settlementAccount(db, stationId, salePayment),
                creditAccount = serviceRevenueAccount(helper, db, stationId, userId),
                amount = feeAmount, referenceType = "fuel_sale", referenceId = sale.id
            )
            else -> 0L
        }
        updateLedger(db, ledgerId, stationId, ContentValues().apply {
            put("journal_entry_id", journalId)
            put("status", "posted")
        })
        return JSONObject().put("success", true).put("ledger_id", ledgerId).put("mode", mode)
            .put("journal_entry_id", journalId).put("invoice_total", invoiceTotal)
            .put("fuel_amount", sale.amount).put("fuel_quantity", sale.quantity).put("service_fee", feeAmount)
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
        cashCustomerName: String?,
        saleKind: String? = null
    ): JSONObject {
        ensureSchema(helper)
        val db = helper.writableDatabase
        require(deliveryId > 0L && stationId > 0) { "معرف التوصيل أو المحطة غير صالح" }
        require(requestedAmount.isFinite() && requestedAmount >= 0.0) { "رسوم خدمة التوصيل غير صالحة" }

        val sale = findSale(db, stationId, saleReferenceId, saleKind)
        // Original SQLite sale values win even when the fee is zero or exempt.
        val amount = if (sale != null) sale.deliveryFee else requestedAmount
        val mode = if (sale != null) sale.deliveryFeeMode else normalizeMode(requestedMode)
        val cleanLocation = if (sale != null) sale.deliveryLocation.trim() else location.orEmpty().trim()
        val cleanCashName = if (sale != null) sale.cashCustomerName.trim() else cashCustomerName.orEmpty().trim()
        if (amount > 0.0 && mode != EXEMPT) {
            require(sale != null) { "تعذر ربط رسوم التوصيل بعملية بيع أصلية صالحة في SQLite؛ لم يتم تسجيل رسوم أو قيد غير مرتبط" }
        }

        if (amount > 0.0 && mode != EXEMPT && cleanLocation.isBlank()) {
            throw IllegalArgumentException("أدخل موقع التوصيل قبل حفظ مهمة التوصيل")
        }
        if (amount > 0.0 && mode == DRIVER && driverId <= 0L) {
            throw IllegalArgumentException("حدد السائق قبل تسجيل رسوم خدمة التوصيل على حسابه")
        }

        val effectiveSaleKind = if (sale != null) (if (sale.isFuelSale) "fuel" else "product") else
            (saleKind.orEmpty().trim().lowercase(Locale.ROOT).let { if (it in setOf("product","products")) "product" else "fuel" })
        val deliveryValues = ContentValues().apply {
            put("sale_kind", effectiveSaleKind)
            put("delivery_service_fee", amount)
            put("delivery_fee_mode", mode)
            put("delivery_location", cleanLocation)
            put("cash_customer_name", cleanCashName)
            put("delivery_fee_status", if (mode == EXEMPT || amount <= 0.0) "none" else if (mode == DRIVER) "pending" else "posted")
        }
        val deliveryColumns = columns(db, "deliveries")
        if ("location" in deliveryColumns) deliveryValues.put("location", cleanLocation)
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
                val income = serviceRevenueAccount(helper, db, stationId, userId)
                val referenceType = if (sale?.isFuelSale == false) "sales_transaction" else "fuel_sale"
                val referenceId = if (sale?.isFuelSale == true) sale.id else (sale?.parentSaleId ?: deliveryId)
                postFeeJournal(helper, db, stationId, userId, ledgerId, "رسوم خدمة التوصيل للتوصيل رقم $deliveryId", account, income, amount, referenceType, referenceId)
            }
            DEDUCT_FROM_SALES -> {
                val saleForAdjustment = sale ?: throw IllegalArgumentException("لا يمكن خصم رسوم التوصيل دون بيع أصلي مرتبط")
                require(amount > 0.0 && amount.isFinite()) { "رسوم خدمة التوصيل غير صالحة" }
                val gross = saleForAdjustment.grossAmount.takeIf { it.isFinite() && it > 0.0 } ?: saleForAdjustment.amount
                require(amount < gross) { "رسوم خدمة التوصيل يجب أن تكون أقل من قيمة المبيعات" }
                val referenceType = if (saleForAdjustment.isFuelSale) "fuel_sale" else "sales_transaction"
                val referenceId = if (saleForAdjustment.isFuelSale) saleForAdjustment.id else saleForAdjustment.parentSaleId
                val id = if (saleForAdjustment.isFuelSale) {
                    postFeeJournal(helper, db, stationId, userId, ledgerId,
                        "إعادة تصنيف قيمة الوقود المحولة إلى إيرادات خدمة التوصيل للتوصيل رقم $deliveryId",
                        salesRevenueAccount(db), serviceRevenueAccount(helper, db, stationId, userId),
                        amount, referenceType, referenceId)
                } else {
                    // Product-sale logic remains unchanged; the litre conversion is fuel-only.
                    postFeeJournal(helper, db, stationId, userId, ledgerId,
                        "خصم رسوم التوصيل للتوصيل رقم $deliveryId",
                        salesRevenueAccount(db), settlementAccount(db, stationId, saleForAdjustment.paymentMethod),
                        amount, referenceType, referenceId)
                }
                updateSaleAmountsForDeduction(db, saleForAdjustment, amount)
                id
            }
            DRIVER -> {
                val referenceType = if (sale?.isFuelSale == false) "sales_transaction" else "fuel_sale"
                val referenceId = if (sale?.isFuelSale == true) sale.id else (sale?.parentSaleId ?: deliveryId)
                val id = postFeeJournal(helper, db, stationId, userId, ledgerId, "رسوم خدمة التوصيل على السائق رقم $driverId للتوصيل $deliveryId", driverReceivableAccount(db), serviceRevenueAccount(helper, db, stationId, userId), amount, referenceType, referenceId)
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

    fun latestFuelSaleForCustomer(helper: DatabaseHelper, partyId: Long, stationId: Int): JSONObject {
        require(partyId > 0L) { "معرف العميل غير صالح" }
        require(stationId > 0) { "معرف المحطة غير صالح" }
        ensureSchema(helper)
        val db = helper.readableDatabase
        if (!tableExists(db, "fuel_sales")) {
            return JSONObject().put("found", false).put("message", "جدول مبيعات الوقود غير متاح")
        }
        val saleColumns = columns(db, "fuel_sales")
        if (!setOf("id", "station_id").all { it in saleColumns }) {
            return JSONObject().put("found", false).put("message", "بنية جدول مبيعات الوقود غير مكتملة")
        }
        val customerExpression = when {
            "customer_party_id" in saleColumns && "customer_id" in saleColumns ->
                "COALESCE(NULLIF(customer_party_id,0),customer_id,0)"
            "customer_party_id" in saleColumns -> "COALESCE(customer_party_id,0)"
            "customer_id" in saleColumns -> "COALESCE(customer_id,0)"
            else -> return JSONObject().put("found", false).put("message", "لا يوجد حقل يربط مبيعات الوقود بالعميل")
        }
        val predicates = mutableListOf("station_id=?", "$customerExpression=?")
        if ("is_deleted" in saleColumns) predicates += "COALESCE(CAST(is_deleted AS INTEGER),0)=0"
        if ("deleted_at" in saleColumns) predicates += "(deleted_at IS NULL OR TRIM(CAST(deleted_at AS TEXT))='')"
        val ordering = buildList {
            if ("sale_date" in saleColumns) add("COALESCE(sale_date,'') DESC")
            if ("sale_time" in saleColumns) add("COALESCE(sale_time,'') DESC")
            if ("transaction_time" in saleColumns) add("COALESCE(transaction_time,'') DESC")
            if ("created_at" in saleColumns) add("COALESCE(created_at,'') DESC")
            add("id DESC")
        }.distinct()
        val sale = db.rawQuery(
            "SELECT * FROM fuel_sales WHERE ${predicates.joinToString(" AND ")} ORDER BY ${ordering.joinToString(",")} LIMIT 1",
            arrayOf(stationId.toString(), partyId.toString())
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursorObject(cursor) }
            ?: return JSONObject().put("found", false).put("message", "لا توجد عملية بيع وقود سابقة مرتبطة بهذا العميل في المحطة الحالية")
        val fuelSaleId = sale.optLong("id", sale.optLong("sale_id", 0L))
        if (fuelSaleId <= 0L) return JSONObject().put("found", false).put("message", "تعذر تحديد المعرف الفعلي لعملية بيع الوقود")
        sale.put("sale_id", fuelSaleId)
        sale.put("fuel_sale_id", fuelSaleId)
        sale.put("customer_party_id", partyId)
        sale.put("party_id", partyId)
        sale.put("sale_kind", "fuel")
        sale.put("sale_type", "fuel")
        return JSONObject().put("found", true).put("sale", sale)
    }

    fun savedDeliveryLocations(helper: DatabaseHelper, stationId: Int): JSONArray {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        ensureSchema(helper)
        val db = helper.readableDatabase
        val selects = mutableListOf<String>()
        val arguments = mutableListOf<String>()

        // Include every persisted source used by the fuel-sales and delivery workflows.
        // Tables and column names are an explicit allowlist; only values are bound parameters.
        for (table in listOf("fuel_sales", "sales_transactions", "deliveries")) {
            if (!tableExists(db, table)) continue
            val tableColumns = columns(db, table)
            if ("station_id" !in tableColumns) continue

            for (locationColumn in listOf("delivery_location", "location")) {
                if (locationColumn !in tableColumns) continue
                val predicates = mutableListOf(
                    "station_id=?",
                    "`$locationColumn` IS NOT NULL",
                    "TRIM(CAST(`$locationColumn` AS TEXT))<>''"
                )
                if ("is_deleted" in tableColumns) {
                    predicates += "COALESCE(CAST(is_deleted AS INTEGER),0)=0"
                }
                if ("deleted_at" in tableColumns) {
                    predicates += "(deleted_at IS NULL OR TRIM(CAST(deleted_at AS TEXT))='')"
                }
                selects += "SELECT TRIM(CAST(`$locationColumn` AS TEXT)) AS location " +
                    "FROM `$table` WHERE ${predicates.joinToString(" AND ")}"
                arguments += stationId.toString()
            }
        }

        if (selects.isEmpty()) return JSONArray()
        val sql = selects.joinToString(" UNION ") + " ORDER BY location COLLATE NOCASE"
        return JSONArray().also { locations ->
            db.rawQuery(sql, arguments.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val value = cursor.getString(0)?.trim().orEmpty()
                    if (value.isNotEmpty()) locations.put(value)
                }
            }
        }
    }

    fun recentFuelSaleCustomerIds(helper: DatabaseHelper, stationId: Int): JSONArray {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        val db = helper.readableDatabase
        if (!tableExists(db, "fuel_sales")) return JSONArray()
        val saleColumns = columns(db, "fuel_sales")
        if (!setOf("id", "station_id").all { it in saleColumns }) return JSONArray()

        val customerExpression = when {
            "customer_party_id" in saleColumns && "customer_id" in saleColumns ->
                "COALESCE(NULLIF(customer_party_id,0),customer_id,0)"
            "customer_party_id" in saleColumns -> "COALESCE(customer_party_id,0)"
            "customer_id" in saleColumns -> "COALESCE(customer_id,0)"
            else -> return JSONArray()
        }
        val timeParts = mutableListOf<String>()
        if ("transaction_time" in saleColumns) timeParts += "COALESCE(transaction_time,'')"
        if ("sale_date" in saleColumns) timeParts += "COALESCE(sale_date,'')"
        if ("sale_time" in saleColumns) timeParts += "COALESCE(sale_time,'')"
        if ("created_at" in saleColumns) timeParts += "COALESCE(created_at,'')"
        val sortExpression = if (timeParts.isEmpty()) "CAST(id AS TEXT)" else timeParts.joinToString(" || ' ' || ")
        val predicates = mutableListOf(
            "station_id=?",
            "$customerExpression>0"
        )
        if ("is_deleted" in saleColumns) predicates += "COALESCE(CAST(is_deleted AS INTEGER),0)=0"
        if ("deleted_at" in saleColumns) {
            predicates += "(deleted_at IS NULL OR TRIM(CAST(deleted_at AS TEXT))='')"
        }

        val sql = "SELECT $customerExpression AS party_id, MAX($sortExpression) AS last_sale, " +
            "MAX(id) AS latest_sale_id FROM fuel_sales WHERE ${predicates.joinToString(" AND ")} " +
            "GROUP BY party_id ORDER BY last_sale DESC, latest_sale_id DESC"
        return JSONArray().also { ids ->
            db.rawQuery(sql, arrayOf(stationId.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val partyId = cursor.getLong(0)
                    if (partyId > 0L) ids.put(partyId)
                }
            }
        }
    }

    fun enrichSaleContext(helper: DatabaseHelper, stationId: Int, context: JSONObject): JSONObject {
        ensureSchema(helper)
        val saleJson = context.optJSONObject("sale") ?: context
        val reference = saleJson.optLong("sale_id", saleJson.optLong("id", 0L))
        val kind = listOf(
            saleJson.optString("sale_kind"),
            saleJson.optString("order_type"),
            saleJson.optString("sale_type")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val sale = findSale(helper.readableDatabase, stationId, reference, kind) ?: return context
        saleJson.put("fuel_sale_id", if (sale.isFuelSale) sale.id else JSONObject.NULL)
        saleJson.put("sale_kind", if (sale.isFuelSale) "fuel" else "product")
        saleJson.put("fuel_type_id", if (sale.isFuelSale) sale.fuelTypeId else JSONObject.NULL)
        if (sale.isFuelSale && sale.fuelTypeName.isNotBlank()) {
            saleJson.put("fuel_type_name", sale.fuelTypeName)
            saleJson.put("fuel_type", sale.fuelTypeName)
        }
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
        val originalKind = listOf(original.optString("sale_kind"), original.optString("sale_type"))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val storedKind = listOf(delivery.optString("sale_kind"), delivery.optString("sale_type"))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val kind = when {
            originalKind.equals("product", true) || originalKind.equals("products", true) ||
                originalKind.equals("منتجات", true) -> "product"
            storedKind.equals("product", true) || storedKind.equals("products", true) ||
                storedKind.equals("منتجات", true) -> "product"
            else -> "fuel"
        }
        val sale = findSale(db, stationId, saleRef, kind)
        val fuelSaleId = sale?.id?.takeIf { sale.isFuelSale } ?: 0L

        val rowLocation = delivery.optString("location").trim().ifBlank { delivery.optString("delivery_location").trim() }
        if (sale == null) {
            rowLocation.takeIf { it.isNotBlank() }?.let { original.put("location", it); original.put("delivery_location", it) }
        }
        if (sale != null) {
            if (sale.fuelTypeName.isNotBlank()) {
                original.put("fuel_type_name", sale.fuelTypeName)
                original.put("fuel_type", sale.fuelTypeName)
            }
            original.put("sale_kind", if (sale.isFuelSale) "fuel" else "product")
            original.put("payment_method", sale.paymentMethod)
            original.put("cash_sale", sale.paymentMethod.trim().lowercase(Locale.ROOT) in setOf("cash", "نقدي", "نقداً"))
            original.put("delivery_service_fee", sale.deliveryFee)
            original.put("delivery_fee_mode", sale.deliveryFeeMode)
            original.put("cash_customer_name", sale.cashCustomerName)
            original.put("location", sale.deliveryLocation)
            original.put("delivery_location", sale.deliveryLocation)
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
        if (sale == null && delivery.optString("cash_customer_name").isNotBlank()) original.put("cash_customer_name", delivery.optString("cash_customer_name"))
        if (sale == null && delivery.optString("delivery_fee_mode").isNotBlank()) original.put("delivery_fee_mode", delivery.optString("delivery_fee_mode"))
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
        require(amount.isFinite() && amount > 0.0) { "مبلغ خصم رسوم التوصيل غير صالح" }
        if (!sale.isFuelSale) {
            val net = (sale.amount - amount).coerceAtLeast(0.0)
            if (tableExists(db, "sales_transactions") && sale.parentSaleId > 0L) {
                val cols = columns(db, "sales_transactions")
                val values = ContentValues()
                if ("total_amount" in cols) values.put("total_amount", net)
                if ("net_amount" in cols) values.put("net_amount", net)
                if ("delivery_service_fee" in cols) values.put("delivery_service_fee", amount)
                db.update("sales_transactions", values, "id=? AND station_id=?", arrayOf(sale.parentSaleId.toString(), sale.stationId.toString()))
            }
            return
        }

        val gross = sale.grossAmount.takeIf { it.isFinite() && it > 0.0 } ?: sale.amount
        val originalQuantity = sale.quantity
        require(originalQuantity.isFinite() && originalQuantity > 0.0 && gross > amount) {
            "تعذر خصم رسوم التوصيل لأن قيمة الوقود أو كميته الأصلية غير صالحة"
        }
        val unitPrice = gross / originalQuantity
        require(unitPrice.isFinite() && unitPrice > 0.0) { "تعذر تحديد سعر اللتر لتحويل رسوم التوصيل إلى كمية وقود" }
        val net = (gross - amount).coerceAtLeast(0.0)
        val reducedQuantity = net / unitPrice
        require(reducedQuantity.isFinite() && reducedQuantity > 0.0 && reducedQuantity < originalQuantity) {
            "رسوم التوصيل ستستهلك كامل كمية الوقود؛ راجع المبلغ"
        }

        val fuelCols = columns(db, "fuel_sales")
        val fuelValues = ContentValues()
        if ("gross_amount" in fuelCols) fuelValues.put("gross_amount", gross)
        if ("gross_quantity" in fuelCols) fuelValues.put("gross_quantity", originalQuantity)
        if ("quantity" in fuelCols) fuelValues.put("quantity", reducedQuantity)
        if ("total_amount" in fuelCols) fuelValues.put("total_amount", net)
        if ("net_amount" in fuelCols) fuelValues.put("net_amount", net)
        if ("delivery_fee_display_total" in fuelCols) fuelValues.put("delivery_fee_display_total", gross)
        db.update("fuel_sales", fuelValues, "id=? AND station_id=?", arrayOf(sale.id.toString(), sale.stationId.toString()))

        if (tableExists(db, "sales_transactions") && sale.parentSaleId > 0L) {
            val cols = columns(db, "sales_transactions")
            val values = ContentValues()
            if ("gross_amount" in cols) values.put("gross_amount", gross)
            if ("gross_quantity" in cols) values.put("gross_quantity", originalQuantity)
            if ("quantity" in cols) values.put("quantity", reducedQuantity)
            if ("liters" in cols) values.put("liters", reducedQuantity)
            if ("total_amount" in cols) values.put("total_amount", gross)
            if ("net_amount" in cols) values.put("net_amount", gross)
            if ("delivery_fee_display_total" in cols) values.put("delivery_fee_display_total", gross)
            if ("delivery_service_fee" in cols) values.put("delivery_service_fee", amount)
            db.update("sales_transactions", values, "id=? AND station_id=?", arrayOf(sale.parentSaleId.toString(), sale.stationId.toString()))
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

    private fun serviceRevenueAccount(
        helper: DatabaseHelper,
        db: SQLiteDatabase,
        stationId: Int,
        userId: Long
    ): Account {
        fun isIncome(account: Account): Boolean =
            account.typeText in setOf("income", "revenue", "revenues", "إيراد", "إيرادات") ||
                account.allText.contains("إيراد") || account.allText.contains("revenue")
        fun isService(account: Account): Boolean =
            account.allText.contains("خدم") || account.allText.contains("service")
        fun isDeliveryService(account: Account): Boolean =
            isIncome(account) && isService(account) &&
                (account.allText.contains("توصيل") || account.allText.contains("delivery"))

        val availableAccounts = accounts(db)
        if (availableAccounts.any { isDeliveryService(it) }) {
            return chooseAccount(db, "إيرادات خدمة التوصيل",
                predicate = { isDeliveryService(it) },
                score = { account ->
                    (if (account.allText.contains("خدمة التوصيل") || account.allText.contains("delivery service")) 8 else 0) +
                        (if (account.allText.contains("توصيل") || account.allText.contains("delivery")) 4 else 0)
                })
        }

        val genericServiceAccounts = availableAccounts.filter { isIncome(it) && isService(it) }
        val cols = columns(db, "accounts")
        val treeSupported = cols.containsAll(setOf("id", "account_code", "account_name", "account_type", "parent_account_id", "level"))
        if (treeSupported) {
            val typeClause = "LOWER(COALESCE(account_type,'')) IN ('income','revenue','revenues','إيراد','إيرادات')"
            val activeClause = if ("is_active" in cols) "AND COALESCE(is_active,1)=1" else ""
            val deletedClause = if ("is_deleted" in cols) "AND COALESCE(is_deleted,0)=0" else ""
            val stationClause = if ("station_id" in cols) "AND (COALESCE(station_id,0)=0 OR station_id=?)" else ""
            val levelExpr = "COALESCE(level,1)"
            val arabicNameExpr = if ("account_name_ar" in cols) "COALESCE(account_name_ar,'')" else "''"
            val sql = "SELECT id,COALESCE(account_code,''),$levelExpr,$arabicNameExpr,COALESCE(account_name,'') " +
                "FROM accounts WHERE $typeClause $activeClause $deletedClause AND COALESCE(parent_account_id,0)=0 " +
                "AND $levelExpr<5 $stationClause ORDER BY account_code"
            val roots = ArrayList<JSONObject>()
            db.rawQuery(sql, if ("station_id" in cols) arrayOf(stationId.toString()) else null).use { cursor ->
                while (cursor.moveToNext()) {
                    roots.add(JSONObject().put("id", cursor.getLong(0)).put("code", cursor.getString(1).orEmpty())
                        .put("level", cursor.getInt(2))
                        .put("name", (cursor.getString(3).orEmpty() + " " + cursor.getString(4).orEmpty()).trim()))
                }
            }
            if (roots.isNotEmpty()) {
                fun rootScore(root: JSONObject): Int {
                    val name = root.optString("name").trim().lowercase(Locale.ROOT)
                    val code = root.optString("code").trim()
                    val hasChildren = db.rawQuery("SELECT 1 FROM accounts WHERE parent_account_id=? LIMIT 1",
                        arrayOf(root.optLong("id").toString())).use { it.moveToFirst() }
                    return (if (name in setOf("الإيرادات", "الايرادات", "revenues", "revenue", "income")) 10 else 0) +
                        (if (name.contains("إيراد") || name.contains("revenue") || name.contains("income")) 4 else 0) +
                        (if (code == "4") 3 else 0) + (if (hasChildren) 4 else 0)
                }
                val bestScore = roots.maxOf { rootScore(it) }
                val bestRoots = roots.filter { rootScore(it) == bestScore }
                if (bestRoots.size == 1) {
                    val parent = bestRoots.first()
                    val parentId = parent.optLong("id")
                    val parentCode = parent.optString("code").trim()
                    val level = parent.optInt("level", 1) + 1
                    require(parentId > 0L && parentCode.isNotBlank() && level in 2..5) {
                        "تعذر إنشاء حساب إيرادات خدمة التوصيل بسبب عدم صلاحية الحساب الرئيسي"
                    }
                    val usedCodes = HashSet<String>()
                    db.rawQuery("SELECT COALESCE(account_code,'') FROM accounts", null).use { cursor ->
                        while (cursor.moveToNext()) usedCodes.add(cursor.getString(0).orEmpty())
                    }
                    var sequence = 1
                    var code: String
                    do {
                        code = parentCode.trimEnd('.') + "." + sequence.toString().padStart(2, '0')
                        sequence++
                    } while (usedCodes.contains(code))
                    val payload = JSONObject()
                        .put("id", 0)
                        .put("account_code", code)
                        .put("account_name_ar", "إيرادات خدمة التوصيل")
                        .put("account_name", "Delivery Service Revenue")
                        .put("account_type", "revenue")
                        .put("parent_account_id", parentId)
                        .put("level", level)
                        .put("normal_balance", "credit")
                        .put("opening_balance", 0.0)
                        .put("current_balance", 0.0)
                        .put("is_bank_account", 0)
                        .put("is_cash_account", 0)
                        .put("is_control_account", 0)
                        .put("is_active", 1)
                        .put("remarks", "حساب أنشئ تلقائياً عند ترحيل رسوم خدمة التوصيل؛ لا يغيّر أرصدة الحسابات الأخرى")
                    val createdId = helper.saveChartAccount(payload, userId)
                    check(createdId > 0L) { "تعذر إنشاء حساب إيرادات خدمة التوصيل في شجرة الحسابات" }
                    return accounts(db).firstOrNull { it.id == createdId }
                        ?: throw IllegalStateException("تعذر إعادة قراءة حساب إيرادات خدمة التوصيل من SQLite")
                }
            }
        }
        if (genericServiceAccounts.isNotEmpty()) {
            return chooseAccount(db, "إيرادات الخدمات",
                predicate = { isIncome(it) && isService(it) },
                score = { account ->
                    if (account.allText.contains("إيرادات الخدمات") || account.allText.contains("service revenue")) 5 else 0
                })
        }
        throw IllegalStateException("لا يوجد حساب إيرادات خدمة التوصيل، وتعذر إنشاء حساب فرعي لأن شجرة الحسابات لا تحتوي على حساب إيرادات رئيسياً واضحاً")
    }

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
