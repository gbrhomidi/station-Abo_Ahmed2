package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log
import java.util.LinkedHashMap

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
        private const val MAX_CACHE_SIZE = 1000
        private const val ORDER_DRAFT_MAX_AGE_MS = 86400000L // 24 ساعة
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Data Classes ═══
    // ═══════════════════════════════════════════════════════════════

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
        val customerId: String = "",
        val quantity: Double = 0.0,
        val location: String = "",
        val schedule: String = "",
        val nextDelivery: Long = 0
    )

    // ═══════════════════════════════════════════════════════════════
    // ═══ Caches مع حد أقصى وحذف تلقائي ═══
    // ═══════════════════════════════════════════════════════════════

    private val activeOrdersCache = object : LinkedHashMap<String, OrderDraft>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, OrderDraft>): Boolean {
            return size > MAX_CACHE_SIZE ||
                System.currentTimeMillis() - eldest.value.createdAt > ORDER_DRAFT_MAX_AGE_MS
        }
    }

    private val contextCache = object : LinkedHashMap<String, ConversationContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ConversationContext>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val prefsCache = object : LinkedHashMap<String, CustomerPreferences>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CustomerPreferences>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val recurringOrdersCache = object : LinkedHashMap<String, RecurringOrder>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, RecurringOrder>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. إدارة سياق المحادثة (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getOrCreateContext(phone: String): ConversationContext = withContext(Dispatchers.IO) {
        synchronized(contextCache) {
            val cached = contextCache[phone]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CONTEXT_TIMEOUT_MS) {
                return@withContext cached
            }
        }

        val ctx = try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT * FROM sms_conversation_context WHERE phone = ? LIMIT 1",
                arrayOf(phone)
            )

            cursor.use {
                if (it.moveToFirst()) {
                    ConversationContext(
                        lastTopic = it.getString(it.getColumnIndexOrThrow("last_topic")) ?: "",
                        lastIntent = it.getString(it.getColumnIndexOrThrow("last_intent")) ?: "",
                        timestamp = System.currentTimeMillis(), // ✅ إعادة تعيين الوقت
                        pendingAction = it.getString(it.getColumnIndexOrThrow("pending_action")) ?: "",
                        awaitingResponse = it.getInt(it.getColumnIndexOrThrow("awaiting_response")) == 1
                    ).apply {
                        val dataJson = it.getString(it.getColumnIndexOrThrow("data_json"))
                        if (!dataJson.isNullOrEmpty()) {
                            try {
                                val json = JSONObject(dataJson)
                                json.keys().forEach { key ->
                                    data[key] = json.getString(key)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse context JSON for $phone")
                            }
                        }
                    }
                } else {
                    ConversationContext()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load context for $phone: ${e.javaClass.simpleName}")
            ConversationContext()
        }

        synchronized(contextCache) {
            contextCache[phone] = ctx
        }
        ctx
    }

    suspend fun saveContext(phone: String, ctx: ConversationContext) = withContext(Dispatchers.IO) {
        synchronized(contextCache) {
            contextCache[phone] = ctx
        }

        val dataJson = try {
            JSONObject().apply {
                ctx.data.forEach { (k, v) -> put(k, v) }
            }.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize context data")
            "{}"
        }

        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("last_topic", ctx.lastTopic)
            put("last_intent", ctx.lastIntent)
            put("timestamp", ctx.timestamp)
            put("pending_action", ctx.pendingAction)
            put("awaiting_response", if (ctx.awaitingResponse) 1 else 0)
            put("data_json", dataJson)
        }

        try {
            db.writableDatabase.insertWithOnConflict(
                "sms_conversation_context",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save context: ${e.javaClass.simpleName}")
        }
    }

    suspend fun clearContext(phone: String) = withContext(Dispatchers.IO) {
        synchronized(contextCache) {
            contextCache.remove(phone)
        }
        try {
            db.writableDatabase.delete("sms_conversation_context", "phone = ?", arrayOf(phone))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear context: ${e.javaClass.simpleName}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. إدارة تفضيلات العميل ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getOrCreatePreferences(phone: String): CustomerPreferences = withContext(Dispatchers.IO) {
        synchronized(prefsCache) {
            val cached = prefsCache[phone]
            if (cached != null) return@withContext cached
        }

        val prefs = try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT * FROM sms_customer_preferences WHERE phone = ? LIMIT 1",
                arrayOf(phone)
            )

            cursor.use {
                if (it.moveToFirst()) {
                    CustomerPreferences(
                        preferredQuantity = it.getDouble(it.getColumnIndexOrThrow("preferred_quantity")),
                        preferredLocation = it.getString(it.getColumnIndexOrThrow("preferred_location")) ?: "",
                        preferredTime = it.getString(it.getColumnIndexOrThrow("preferred_time")) ?: "",
                        lastOrderDate = it.getLong(it.getColumnIndexOrThrow("last_order_date")),
                        orderCount = it.getInt(it.getColumnIndexOrThrow("order_count")),
                        language = it.getString(it.getColumnIndexOrThrow("language")) ?: "ar"
                    )
                } else {
                    CustomerPreferences()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preferences for $phone: ${e.javaClass.simpleName}")
            CustomerPreferences()
        }

        synchronized(prefsCache) {
            prefsCache[phone] = prefs
        }
        prefs
    }

    suspend fun savePreferences(phone: String, prefs: CustomerPreferences) = withContext(Dispatchers.IO) {
        synchronized(prefsCache) {
            prefsCache[phone] = prefs
        }

        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("preferred_quantity", prefs.preferredQuantity)
            put("preferred_location", prefs.preferredLocation)
            put("preferred_time", prefs.preferredTime)
            put("last_order_date", prefs.lastOrderDate)
            put("order_count", prefs.orderCount)
            put("language", prefs.language)
        }

        try {
            db.writableDatabase.insertWithOnConflict(
                "sms_customer_preferences",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preferences: ${e.javaClass.simpleName}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. سجل التفاعلات ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun recordInteraction(phone: String, intent: String, message: String) = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("intent", intent)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }

        try {
            // ✅ استخدام transaction
            db.writableDatabase.beginTransaction()
            try {
                db.writableDatabase.insert("sms_interaction_history", null, values)
                db.writableDatabase.execSQL("""
                    DELETE FROM sms_interaction_history
                    WHERE phone = ? AND id NOT IN (
                        SELECT id FROM sms_interaction_history
                        WHERE phone = ? ORDER BY timestamp DESC LIMIT ?
                    )
                """.trimIndent(), arrayOf(phone, phone, MAX_HISTORY_SIZE))
                db.writableDatabase.setTransactionSuccessful()
            } finally {
                db.writableDatabase.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record interaction: ${e.javaClass.simpleName}")
        }
    }

    suspend fun getInteractionHistory(phone: String, limit: Int = 10): List<InteractionRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<InteractionRecord>()
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT * FROM sms_interaction_history WHERE phone = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(phone, limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(InteractionRecord(
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        intent = it.getString(it.getColumnIndexOrThrow("intent")) ?: "",
                        message = it.getString(it.getColumnIndexOrThrow("message")) ?: ""
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get history: ${e.javaClass.simpleName}")
        }
        list
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. إدارة الطلبات المسودة ═══
    // ═══════════════════════════════════════════════════════════════

    fun getOrCreateOrderDraft(phone: String, product: String = "diesel"): OrderDraft {
        synchronized(activeOrdersCache) {
            return activeOrdersCache.getOrPut(phone) { OrderDraft(product = product) }
        }
    }

    fun getOrderDraft(phone: String): OrderDraft? {
        synchronized(activeOrdersCache) {
            return activeOrdersCache[phone]
        }
    }

    fun removeOrderDraft(phone: String) {
        synchronized(activeOrdersCache) {
            activeOrdersCache.remove(phone)
        }
    }

    fun updateOrderDraft(phone: String, block: OrderDraft.() -> Unit) {
        synchronized(activeOrdersCache) {
            activeOrdersCache[phone]?.apply(block)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. إدارة الطلبات المتكررة ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun saveRecurringOrder(order: RecurringOrder) = withContext(Dispatchers.IO) {
        synchronized(recurringOrdersCache) {
            recurringOrdersCache[order.customerId] = order
        }

        val values = android.content.ContentValues().apply {
            put("customer_id", order.customerId)
            put("quantity", order.quantity)
            put("location", order.location)
            put("schedule", order.schedule)
            put("next_delivery", order.nextDelivery)
        }

        try {
            db.writableDatabase.insertWithOnConflict(
                "sms_recurring_orders",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recurring order: ${e.javaClass.simpleName}")
        }
    }

    suspend fun getRecurringOrder(phone: String): RecurringOrder? = withContext(Dispatchers.IO) {
        synchronized(recurringOrdersCache) {
            val cached = recurringOrdersCache[phone]
            if (cached != null) return@withContext cached
        }

        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT * FROM sms_recurring_orders WHERE customer_id = ? LIMIT 1",
                arrayOf(phone)
            )

            cursor.use {
                if (it.moveToFirst()) {
                    RecurringOrder(
                        customerId = it.getString(it.getColumnIndexOrThrow("customer_id")) ?: "",
                        quantity = it.getDouble(it.getColumnIndexOrThrow("quantity")),
                        location = it.getString(it.getColumnIndexOrThrow("location")) ?: "",
                        schedule = it.getString(it.getColumnIndexOrThrow("schedule")) ?: "",
                        nextDelivery = it.getLong(it.getColumnIndexOrThrow("next_delivery"))
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recurring order: ${e.javaClass.simpleName}")
            null
        }?.also { order ->
            synchronized(recurringOrdersCache) {
                recurringOrdersCache[phone] = order
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 6. التنظيف ═══
    // ═══════════════════════════════════════════════════════════════

    fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        synchronized(contextCache) {
            contextCache.entries.removeIf { now - it.value.timestamp > CONTEXT_TIMEOUT_MS }
        }
        // ✅ activeOrdersCache يُنظف تلقائياً عبر LinkedHashMap
        // ✅ prefsCache و recurringOrdersCache يُنظفان عبر LinkedHashMap
        Log.d(TAG, "Cache cleaned. Contexts: ${contextCache.size}, Drafts: ${activeOrdersCache.size}")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 7. مزامنة البيانات ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun syncContext() = withContext(Dispatchers.IO) {
        try {
            // مزامنة السياقات المعدلة فقط
            synchronized(contextCache) {
                contextCache.forEach { (phone, ctx) ->
                    saveContext(phone, ctx)
                }
            }
            Log.d(TAG, "Context synced successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync context: ${e.javaClass.simpleName}")
        }
    }
}
