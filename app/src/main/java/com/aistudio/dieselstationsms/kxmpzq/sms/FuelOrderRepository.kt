package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** مصدر الحقيقة للطلبات التجارية؛ لا يسجل Sale نهائيًا عند إنشاء الطلب. */
class FuelOrderRepository(private val db: DatabaseHelper) {
    fun createDraft(
        customerId: Long,
        phone: String,
        fuelTypeId: Long,
        quantity: Double,
        unit: String,
        liters: Double,
        unitPrice: Double,
        paymentMode: String,
        locationOriginal: String? = null,
        requestedDeliveryAt: Long? = null,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): FuelOrderDraft {
        require(customerId > 0 && fuelTypeId > 0) { "Invalid customer or fuel type" }
        require(quantity.isFinite() && quantity > 0 && liters.isFinite() && liters > 0) { "Invalid quantity" }
        require(unitPrice.isFinite() && unitPrice >= 0) { "Invalid unit price" }
        require(paymentMode in setOf("PREPAID", "CREDIT")) { "Unsupported payment mode" }
        val location = LocationResolver.normalize(locationOriginal)
        val now = System.currentTimeMillis()
        val orderId = "FO-${UUID.randomUUID()}"
        val order = FuelOrderDraft(orderId, customerId, phone, fuelTypeId, quantity, unit, liters, unitPrice, liters * unitPrice, paymentMode = paymentMode, deliveryLocation = location, deliveryLocationOriginal = locationOriginal, requestedDeliveryAt = requestedDeliveryAt, createdAt = now, expiresAt = now + 30 * 60 * 1000L, idempotencyKey = idempotencyKey)
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val existing = database.rawQuery("SELECT order_id FROM fuel_orders WHERE idempotency_key = ? LIMIT 1", arrayOf(idempotencyKey)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (existing != null) {
                database.setTransactionSuccessful()
                return get(existing) ?: error("Existing order disappeared")
            }
            database.insertOrThrow("fuel_orders", null, orderValues(order))
            appendEvent(database, order.orderId, "FUEL_ORDER_CREATED", JSONObject().put("phone", phone).put("liters", liters).put("total_amount", order.totalAmount))
            database.setTransactionSuccessful()
            return order
        } finally { database.endTransaction() }
    }

    fun createImmutableQuote(orderId: String, discount: Double = 0.0, deliveryFee: Double = 0.0, currency: String = "YER", priceVersion: String = "CURRENT"): FuelQuote {
        val order = get(orderId) ?: error("Order not found")
        require(order.status == FuelOrderStatus.DRAFT || order.status == FuelOrderStatus.QUOTED) { "Order cannot be quoted in ${order.status}" }
        val quote = FuelQuote("Q-${UUID.randomUUID()}", order.orderId, order.fuelTypeId, order.quantity, order.liters, order.unitPrice, discount.coerceAtLeast(0.0), deliveryFee.coerceAtLeast(0.0), (order.totalAmount - discount + deliveryFee).coerceAtLeast(0.0), currency, priceVersion, minOf(order.expiresAt, System.currentTimeMillis() + 30 * 60 * 1000L))
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.insertOrThrow("fuel_quotes", null, ContentValues().apply {
                put("quote_id", quote.quoteId); put("order_id", quote.orderId); put("fuel_type_id", quote.fuelTypeId); put("quantity", quote.quantity); put("liters", quote.liters); put("unit_price", quote.unitPrice); put("discount", quote.discount); put("delivery_fee", quote.deliveryFee); put("total", quote.total); put("currency", quote.currency); put("price_version", quote.priceVersion); put("expires_at", quote.expiresAt); put("created_at", quote.createdAt)
            })
            transitionInTransaction(database, order, FuelOrderStatus.QUOTED, JSONObject().put("quote_id", quote.quoteId).put("total", quote.total))
            database.update("fuel_orders", ContentValues().apply { put("quote_id", quote.quoteId); put("total_amount", quote.total) }, "order_id = ?", arrayOf(orderId))
            database.setTransactionSuccessful()
            return quote
        } finally { database.endTransaction() }
    }

    fun transition(orderId: String, target: FuelOrderStatus, metadata: JSONObject = JSONObject()): Boolean {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val order = get(orderId) ?: return false
            val changed = transitionInTransaction(database, order, target, metadata)
            database.setTransactionSuccessful()
            return changed
        } finally { database.endTransaction() }
    }

    fun reserve(orderId: String): Boolean {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val order = get(orderId) ?: return false
            val inserted = database.insertWithOnConflict("fuel_reservations", null, ContentValues().apply {
                put("reservation_id", "FR-${UUID.randomUUID()}"); put("order_id", order.orderId); put("fuel_type_id", order.fuelTypeId); put("liters", order.liters); put("status", "RESERVED"); put("created_at", System.currentTimeMillis())
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted == -1L) { database.setTransactionSuccessful(); return true }
            appendEvent(database, orderId, "FUEL_STOCK_RESERVED", JSONObject().put("liters", order.liters))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    fun getOrdersForPhone(phone: String, limit: Int = 50): JSONArray {
        val result = JSONArray()
        db.readableDatabase.rawQuery("SELECT order_id, customer_id, phone, fuel_type_id, quantity, unit, liters, unit_price, total_amount, quote_id, payment_mode, payment_status, delivery_location, delivery_location_original, requested_delivery_at, driver_id, vehicle_id, status, created_at, expires_at FROM fuel_orders WHERE phone = ? ORDER BY created_at DESC LIMIT ?", arrayOf(phone, limit.coerceIn(1, 200).toString())).use { c ->
            while (c.moveToNext()) {
                result.put(JSONObject().apply { for (i in 0 until c.columnCount) put(c.getColumnName(i), if (c.isNull(i)) JSONObject.NULL else c.getString(i)) })
            }
        }
        return result
    }

    fun timeline(orderId: String): JSONArray {
        val result = JSONArray()
        db.readableDatabase.rawQuery("SELECT event_id, event_type, payload_json, created_at FROM sms_business_events WHERE aggregate_type = 'FUEL_ORDER' AND aggregate_id = ? ORDER BY created_at ASC", arrayOf(orderId)).use { c ->
            while (c.moveToNext()) result.put(JSONObject().put("event_id", c.getString(0)).put("event_type", c.getString(1)).put("payload_json", c.getString(2)).put("created_at", c.getLong(3)))
        }
        return result
    }

    fun get(orderId: String): FuelOrderDraft? {
        return db.readableDatabase.rawQuery("SELECT * FROM fuel_orders WHERE order_id = ? LIMIT 1", arrayOf(orderId)).use { c ->
            if (!c.moveToFirst()) null else FuelOrderDraft(c.getString(c.getColumnIndexOrThrow("order_id")), c.getLong(c.getColumnIndexOrThrow("customer_id")), c.getString(c.getColumnIndexOrThrow("phone")), c.getLong(c.getColumnIndexOrThrow("fuel_type_id")), c.getDouble(c.getColumnIndexOrThrow("quantity")), c.getString(c.getColumnIndexOrThrow("unit")), c.getDouble(c.getColumnIndexOrThrow("liters")), c.getDouble(c.getColumnIndexOrThrow("unit_price")), c.getDouble(c.getColumnIndexOrThrow("total_amount")), c.getString(c.getColumnIndexOrThrow("quote_id")), c.getString(c.getColumnIndexOrThrow("payment_mode")), c.getString(c.getColumnIndexOrThrow("payment_status")), c.getString(c.getColumnIndexOrThrow("delivery_location")), c.getString(c.getColumnIndexOrThrow("delivery_location_original")), c.getLong(c.getColumnIndexOrThrow("requested_delivery_at")).takeIf { !c.isNull(c.getColumnIndexOrThrow("requested_delivery_at")) }, c.getLong(c.getColumnIndexOrThrow("driver_id")).takeIf { !c.isNull(c.getColumnIndexOrThrow("driver_id")) }, c.getLong(c.getColumnIndexOrThrow("vehicle_id")).takeIf { !c.isNull(c.getColumnIndexOrThrow("vehicle_id")) }, FuelOrderStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))), c.getLong(c.getColumnIndexOrThrow("created_at")), c.getLong(c.getColumnIndexOrThrow("expires_at")), c.getString(c.getColumnIndexOrThrow("idempotency_key")))
        }
    }

    private fun orderValues(o: FuelOrderDraft) = ContentValues().apply {
        put("order_id", o.orderId); put("customer_id", o.customerId); put("phone", o.phone); put("fuel_type_id", o.fuelTypeId); put("quantity", o.quantity); put("unit", o.unit); put("liters", o.liters); put("unit_price", o.unitPrice); put("total_amount", o.totalAmount); put("quote_id", o.quoteId); put("payment_mode", o.paymentMode); put("payment_status", o.paymentStatus); put("delivery_location", o.deliveryLocation); put("delivery_location_original", o.deliveryLocationOriginal); put("requested_delivery_at", o.requestedDeliveryAt); put("driver_id", o.driverId); put("vehicle_id", o.vehicleId); put("status", o.status.name); put("created_at", o.createdAt); put("expires_at", o.expiresAt); put("idempotency_key", o.idempotencyKey)
    }

    private fun transitionInTransaction(database: android.database.sqlite.SQLiteDatabase, order: FuelOrderDraft, target: FuelOrderStatus, metadata: JSONObject): Boolean {
        FuelOrderStateMachine.requireTransition(order.status, target)
        if (order.status == target) return true
        val changed = database.update("fuel_orders", ContentValues().apply { put("status", target.name); if (target == FuelOrderStatus.PAYMENT_VERIFIED) put("payment_status", "VERIFIED"); if (target == FuelOrderStatus.PAYMENT_FAILED) put("payment_status", "FAILED"); if (target == FuelOrderStatus.PAYMENT_MISMATCH) put("payment_status", "MISMATCH") }, "order_id = ? AND status = ?", arrayOf(order.orderId, order.status.name))
        if (changed == 1) appendEvent(database, order.orderId, target.name, metadata)
        return changed == 1
    }

    private fun appendEvent(database: android.database.sqlite.SQLiteDatabase, orderId: String, type: String, payload: JSONObject) {
        database.insertWithOnConflict("sms_business_events", null, ContentValues().apply { put("event_id", "EV-${UUID.randomUUID()}"); put("conversation_id", orderId); put("event_type", type); put("aggregate_type", "FUEL_ORDER"); put("aggregate_id", orderId); put("payload_json", payload.toString()); put("created_at", System.currentTimeMillis()) }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
    }
}
