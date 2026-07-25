package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════
 * مدير المحادثات والتفضيلات - SmsConversationManager
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. حفظ/استرجاع سياق المحادثة من SQLite
 * 2. حفظ/استرجاع تفضيلات العميل من SQLite
 * 3. حفظ/استرجاع سجل التفاعلات من SQLite
 * 4. إدارة الطلبات المسودة (Order Drafts)
 * 5. إدارة الطلبات المتكررة
 */
class SmsConversationManager(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsConversationManager"
        private const val CONTEXT_TIMEOUT_MS = 600000L
        private const val MAX_HISTORY_SIZE = 50
    }

    data class OrderDraft(
        var product: String = "",
        var quantityLiters: Double = 0.0,
        var quantityDabbas: Double = 0.0,
        var deliveryLocation: String = "",
        var deliveryTime: String = "",
        var deliveryTimestamp: Long = 0,
        var unitPrice: Double = 0.0,
        var totalAmount: Double = 0.0,
        var status: String = "draft",
        var step: Int = 0,
        var createdAt: Long = System.currentTimeMillis()
    )

    data class ConversationContext(
        var lastTopic: String = "",
        var lastIntent: String = "",
        var timestamp: Long = System.currentTimeMillis(),
        var pendingAction: String = "",
        var awaitingResponse: Boolean = false,
        var data: MutableMap<String, String> = mutableMapOf()
    )

    data class CustomerPreferences(
        var preferredQuantity: Double = 0.0,
        var preferredLocation: String = "",
        var preferredTime: String = "",
        var lastOrderDate: Long = 0,
        var orderCount: Int = 0,
        var language: String = "ar"
    )

    data class InteractionRecord(
        val timestamp: Long,
        val intent: String,
        val message: String
    )

    data class RecurringOrder(
        val customerId: String,
        val quantity: Double,
        val location: String,
        val schedule: String,
        val nextDelivery: Long
    )

    private val activeOrdersCache = java.util.concurrent.ConcurrentHashMap<String, OrderDraft>()
    private val contextCache = java.util.concurrent.ConcurrentHashMap<String, ConversationContext>()
    private val prefsCache = java.util.concurrent.ConcurrentHashMap<String, CustomerPreferences>()

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. إدارة سياق المحادثة (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getOrCreateContext(phone: String): ConversationContext = withContext(Dispatchers.IO) {
        val cached = contextCache[phone]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CONTEXT_TIMEOUT_MS) {
            return@withContext cached
        }

        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM sms_conversation_context WHERE phone = ? LIMIT 1",
            arrayOf(phone)
        )

        val ctx = cursor.use {
            if (it.moveToFirst()) {
                ConversationContext(
                    lastTopic = it.getString(it.getColumnIndexOrThrow("last_topic")),
                    lastIntent = it.getString(it.getColumnIndexOrThrow("last_intent")),
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    pendingAction = it.getString(it.getColumnIndexOrThrow("pending_action")),
                    awaitingResponse = it.getInt(it.getColumnIndexOrThrow("awaiting_response")) == 1
                ).apply {
                    val dataJson = it.getString(it.getColumnIndexOrThrow("data_json"))
                    if (!dataJson.isNullOrEmpty()) {
                        val json = JSONObject(dataJson)
                        json.keys().forEach { key ->
                            data[key] = json.getString(key)
                        }
                    }
                }
            } else {
                ConversationContext()
            }
        }

        contextCache[phone] = ctx
        ctx
    }

    suspend fun saveContext(phone: String, ctx: ConversationContext) = withContext(Dispatchers.IO) {
        contextCache[phone] = ctx

        val dataJson = JSONObject().apply {
            ctx.data.forEach { (k, v) -> put(k, v) }
        }.toString()

        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("last_topic", ctx.lastTopic)
            put("last_intent", ctx.lastIntent)
            put("timestamp", ctx.timestamp)
            put("pending_action", ctx.pendingAction)
            put("awaiting_response", if (ctx.awaitingResponse) 1 else 0)
            put("data_json", dataJson)
        }

        db.writableDatabase.insertWithOnConflict(
            "sms_conversation_context", null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun clearContext(phone: String) = withContext(Dispatchers.IO) {
        contextCache.remove(phone)
        db.writableDatabase.delete("sms_conversation_context", "phone = ?", arrayOf(phone))
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. إدارة تفضيلات العميل (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getOrCreatePreferences(phone: String): CustomerPreferences = withContext(Dispatchers.IO) {
        val cached = prefsCache[phone]
        if (cached != null) return@withContext cached

        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM sms_customer_preferences WHERE phone = ? LIMIT 1",
            arrayOf(phone)
        )

        val prefs = cursor.use {
            if (it.moveToFirst()) {
                CustomerPreferences(
                    preferredQuantity = it.getDouble(it.getColumnIndexOrThrow("preferred_quantity")),
                    preferredLocation = it.getString(it.getColumnIndexOrThrow("preferred_location")),
                    preferredTime = it.getString(it.getColumnIndexOrThrow("preferred_time")),
                    lastOrderDate = it.getLong(it.getColumnIndexOrThrow("last_order_date")),
                    orderCount = it.getInt(it.getColumnIndexOrThrow("order_count")),
                    language = it.getString(it.getColumnIndexOrThrow("language")) ?: "ar"
                )
            } else {
                CustomerPreferences()
            }
        }

        prefsCache[phone] = prefs
        prefs
    }

    suspend fun savePreferences(phone: String, prefs: CustomerPreferences) = withContext(Dispatchers.IO) {
        prefsCache[phone] = prefs

        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("preferred_quantity", prefs.preferredQuantity)
            put("preferred_location", prefs.preferredLocation)
            put("preferred_time", prefs.preferredTime)
            put("last_order_date", prefs.lastOrderDate)
            put("order_count", prefs.orderCount)
            put("language", prefs.language)
        }

        db.writableDatabase.insertWithOnConflict(
            "sms_customer_preferences", null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. سجل التفاعلات (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun recordInteraction(phone: String, intent: String, message: String) = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("timestamp", System.currentTimeMillis())
            put("intent", intent)
            put("message", message.take(500))
        }

        db.writableDatabase.insert("sms_interaction_history", null, values)

        db.writableDatabase.execSQL("""
            DELETE FROM sms_interaction_history 
            WHERE phone = ? AND id NOT IN (
                SELECT id FROM sms_interaction_history 
                WHERE phone = ? ORDER BY timestamp DESC LIMIT ?
            )
        """.trimIndent(), arrayOf(phone, phone, MAX_HISTORY_SIZE))
    }

    suspend fun getInteractionHistory(phone: String, limit: Int = 10): List<InteractionRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<InteractionRecord>()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM sms_interaction_history WHERE phone = ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf(phone, limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(InteractionRecord(
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    intent = it.getString(it.getColumnIndexOrThrow("intent")),
                    message = it.getString(it.getColumnIndexOrThrow("message"))
                ))
            }
        }
        list
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. إدارة الطلبات المسودة ═══
    // ═══════════════════════════════════════════════════════════════

    fun getOrCreateOrderDraft(phone: String, product: String = "diesel"): OrderDraft {
        return activeOrdersCache.getOrPut(phone) { OrderDraft(product = product) }
    }

    fun getOrderDraft(phone: String): OrderDraft? {
        return activeOrdersCache[phone]
    }

    fun removeOrderDraft(phone: String) {
        activeOrdersCache.remove(phone)
    }

    fun updateOrderDraft(phone: String, block: OrderDraft.() -> Unit) {
        activeOrdersCache[phone]?.apply(block)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. إدارة الطلبات المتكررة ═══
    // ═══════════════════════════════════════════════════════════════

    private val recurringOrdersCache = java.util.concurrent.ConcurrentHashMap<String, RecurringOrder>()

    suspend fun saveRecurringOrder(order: RecurringOrder) = withContext(Dispatchers.IO) {
        recurringOrdersCache[order.customerId] = order

        val values = android.content.ContentValues().apply {
            put("customer_id", order.customerId)
            put("quantity", order.quantity)
            put("location", order.location)
            put("schedule", order.schedule)
            put("next_delivery", order.nextDelivery)
        }

        db.writableDatabase.insertWithOnConflict(
            "sms_recurring_orders", null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun getRecurringOrder(phone: String): RecurringOrder? = withContext(Dispatchers.IO) {
        val cached = recurringOrdersCache[phone]
        if (cached != null) return@withContext cached

        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM sms_recurring_orders WHERE customer_id = ? LIMIT 1",
            arrayOf(phone)
        )

        cursor.use {
            if (it.moveToFirst()) {
                RecurringOrder(
                    customerId = it.getString(it.getColumnIndexOrThrow("customer_id")),
                    quantity = it.getDouble(it.getColumnIndexOrThrow("quantity")),
                    location = it.getString(it.getColumnIndexOrThrow("location")),
                    schedule = it.getString(it.getColumnIndexOrThrow("schedule")),
                    nextDelivery = it.getLong(it.getColumnIndexOrThrow("next_delivery"))
                )
            } else null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 6. تنظيف الذاكرة المؤقتة ═══
    // ═══════════════════════════════════════════════════════════════

    fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        contextCache.entries.removeIf { now - it.value.timestamp > CONTEXT_TIMEOUT_MS }
    }
}