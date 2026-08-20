package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.json.JSONObject
import java.util.UUID

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
 *
 * ═══════════════════════════════════════════════════════════════
 * قواعد الكميات المعتمدة في النظام:
 *
 * 1 دبة = 20 لتر
 * 1 برميل عادي = 10 دباب = 200 لتر
 * 1 برميل كبير = 12 دبة = 240 لتر
 *
 * ملاحظة مهمة:
 * هذا الملف لا ينشئ أسعارًا أو أرصدة أو فواتير تجريبية.
 * أي قيمة مالية فعلية يجب أن تأتي من DatabaseHelper
 * والبيانات الحقيقية الموجودة في قاعدة البيانات.
 * ═══════════════════════════════════════════════════════════════
 */
class SmsConversationManager(private val db: DatabaseHelper) {

    companion object {

        private const val TAG = "SmsConversationManager"

        /**
         * مدة صلاحية سياق المحادثة:
         * 10 دقائق.
         */
        private const val CONTEXT_TIMEOUT_MS = 600000L
        private const val PENDING_CONTEXT_TIMEOUT_MS = 24L * 60L * 60L * 1000L

        /**
         * الحد الأقصى لسجل التفاعلات المحفوظ لكل عميل.
         */
        private const val MAX_HISTORY_SIZE = 50

        /**
         * الحد الأقصى الذي يمكن طلبه عند قراءة السجل.
         */
        private const val MAX_HISTORY_QUERY_LIMIT = 100

        /**
         * قواعد وحدات الوقود المعتمدة.
         */
        const val LITERS_PER_DABBA = 20.0
        const val ORDINARY_BARREL_DABBAS = 10.0
        const val ORDINARY_BARREL_LITERS = 200.0
        const val LARGE_BARREL_DABBAS = 12.0
        const val LARGE_BARREL_LITERS = 240.0

        /**
         * الحد الأعلى المعقول لكمية الطلب باللتر.
         *
         * لا يمثل سعرًا ولا رصيدًا ولا قيمة تجريبية.
         * هو فقط حماية من الإدخالات غير المنطقية.
         */
        private const val MAX_ORDER_LITERS = 10000.0

        /**
         * الحد الأقصى لطول الموقع المخزن.
         */
        private const val MAX_LOCATION_LENGTH = 200

        /**
         * الحد الأقصى لطول وقت التسليم المخزن.
         */
        private const val MAX_DELIVERY_TIME_LENGTH = 100

        /**
         * الحد الأقصى لطول اسم المنتج.
         */
        private const val MAX_PRODUCT_LENGTH = 50

        /**
         * المنتجات المدعومة في مسار طلب الوقود.
         *
         * لا يتم اعتبار البنزين ديزلًا تلقائيًا.
         */
        const val PRODUCT_DIESEL = "diesel"
        const val PRODUCT_GASOLINE = "gasoline"

        /**
         * مفاتيح البيانات الشائعة داخل ConversationContext.
         *
         * الاحتفاظ بها هنا يمنع اختلاف أسماء المفاتيح
         * بين SmsProcessor وباقي طبقات النظام.
         */
        const val DATA_PHONE = "phone"
        const val DATA_PRODUCT = "product"
        const val DATA_QUANTITY_LITERS = "quantityLiters"
        const val DATA_QUANTITY_DABBAS = "quantityDabbas"
        const val DATA_DELIVERY_LOCATION = "deliveryLocation"
        const val DATA_DELIVERY_TIME = "deliveryTime"
        const val DATA_DELIVERY_TIMESTAMP = "deliveryTimestamp"
        const val DATA_UNIT_PRICE = "unitPrice"
        const val DATA_TOTAL_AMOUNT = "totalAmount"
        const val DATA_ORDER_ID = "orderId"
    }

    // ═══════════════════════════════════════════════════════════════
    // Data Models
    // ═══════════════════════════════════════════════════════════════

    data class OrderDraft(
        var product: String = PRODUCT_DIESEL,
        var quantityLiters: Double = 0.0,
        var quantityDabbas: Double = 0.0,
        var deliveryLocation: String = "",
        var deliveryTime: String = "",
        var deliveryTimestamp: Long = 0,
        var unitPrice: Double = 0.0,
        var totalAmount: Double = 0.0,
        var status: String = "draft",
        var step: Int = 0,
        var createdAt: Long = System.currentTimeMillis(),
        var draftId: String = UUID.randomUUID().toString(),
        var version: Long = 0L
    )

    data class ConversationContext(
        var lastTopic: String = "",
        var lastIntent: String = "",
        var timestamp: Long = System.currentTimeMillis(),
        var pendingAction: String = "",
        var awaitingResponse: Boolean = false,
        var data: MutableMap<String, String> = mutableMapOf(),
        var conversationId: String = UUID.randomUUID().toString(),
        var actorId: Long? = null,
        var conversationType: String = "sms",
        var currentState: String = "IDLE",
        var previousState: String = "",
        var orderId: Long? = null,
        var draftId: String = "",
        var version: Long = 0L,
        var expiresAt: Long = 0L,
        var lastInboundMessageId: String = "",
        var lastOutboundMessageId: String = "",
        var retryCount: Int = 0,
        var status: String = "ACTIVE"
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

    // ═══════════════════════════════════════════════════════════════
    // Caches
    // ═══════════════════════════════════════════════════════════════

    private val activeOrdersCache =
        java.util.concurrent.ConcurrentHashMap<String, OrderDraft>()

    private val contextCache =
        java.util.concurrent.ConcurrentHashMap<String, ConversationContext>()

    private val prefsCache =
        java.util.concurrent.ConcurrentHashMap<String, CustomerPreferences>()

    private val recurringOrdersCache =
        java.util.concurrent.ConcurrentHashMap<String, RecurringOrder>()

    // ═══════════════════════════════════════════════════════════════
    // Phone / Key Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * توحيد رقم الهاتف قبل استخدامه في:
     *
     * - SQLite
     * - Cache
     * - Context
     * - Preferences
     * - History
     * - Recurring Orders
     *
     * الهدف هو منع وجود أكثر من سياق لنفس العميل
     * بسبب اختلاف شكل رقم الهاتف.
     */
    private fun normalizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) {
            return ""
        }

        return PhoneUtils.normalize(phone)?.trim().orEmpty()
    }

    private fun isValidPhone(phone: String): Boolean {
        return normalizePhone(phone).isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════════════
    // Safe Cursor Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun CursorGetString(
        cursor: android.database.Cursor,
        column: String,
        defaultValue: String = ""
    ): String {
        val index = cursor.getColumnIndex(column)

        if (index < 0 || cursor.isNull(index)) {
            return defaultValue
        }

        return cursor.getString(index)?.takeIf { it.isNotBlank() }
            ?: defaultValue
    }

    private fun cursorGetDouble(
        cursor: android.database.Cursor,
        column: String,
        defaultValue: Double = 0.0
    ): Double {
        val index = cursor.getColumnIndex(column)

        if (index < 0 || cursor.isNull(index)) {
            return defaultValue
        }

        return cursor.getDouble(index)
    }

    private fun cursorGetLong(
        cursor: android.database.Cursor,
        column: String,
        defaultValue: Long = 0L
    ): Long {
        val index = cursor.getColumnIndex(column)

        if (index < 0 || cursor.isNull(index)) {
            return defaultValue
        }

        return cursor.getLong(index)
    }

    private fun cursorGetInt(
        cursor: android.database.Cursor,
        column: String,
        defaultValue: Int = 0
    ): Int {
        val index = cursor.getColumnIndex(column)

        if (index < 0 || cursor.isNull(index)) {
            return defaultValue
        }

        return cursor.getInt(index)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. إدارة سياق المحادثة (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getOrCreateContext(
        phone: String
    ): ConversationContext = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext ConversationContext()
        }

        val now = System.currentTimeMillis()

        val cached = contextCache[cleanPhone]

        if (cached != null && isContextUsable(cached, now)) {
            return@withContext cached
        }

        /**
         * إذا كان الكاش منتهيًا فلا نستخدمه.
         */
        if (cached != null) {
            contextCache.remove(cleanPhone)
        }

        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT *
            FROM sms_conversation_context
            WHERE phone = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(cleanPhone)
        )

        val ctx = cursor.use {

            if (!it.moveToFirst()) {
                ConversationContext()
            } else {

                val savedTimestamp =
                    cursorGetLong(
                        it,
                        "timestamp",
                        now
                    )
                val savedPendingAction = CursorGetString(it, "pending_action")
                val savedAwaitingResponse = cursorGetInt(it, "awaiting_response") == 1
                val savedExpiresAt = cursorGetLong(it, "expires_at", 0L)
                val pendingExpiry = savedTimestamp + PENDING_CONTEXT_TIMEOUT_MS
                val fallbackExpiry = savedTimestamp + CONTEXT_TIMEOUT_MS
                val effectiveExpiresAt = if (savedAwaitingResponse && savedPendingAction.isNotBlank()) {
                    maxOf(savedExpiresAt, pendingExpiry)
                } else {
                    savedExpiresAt.takeIf { expiry -> expiry > 0L } ?: fallbackExpiry
                }

                /**
                 * السياق منتهي الصلاحية، مع احترام expires_at للصفوف الجديدة.
                 */
                if (
                    savedTimestamp <= 0L ||
                    now >= effectiveExpiresAt
                ) {

                    ConversationContext()

                } else {

                    ConversationContext(
                        lastTopic = CursorGetString(it, "last_topic"),
                        lastIntent = CursorGetString(it, "last_intent"),
                        timestamp = savedTimestamp,
                        pendingAction = savedPendingAction,
                        awaitingResponse = savedAwaitingResponse,
                        conversationId = CursorGetString(it, "conversation_id").ifBlank { UUID.randomUUID().toString() },
                        actorId = cursorGetLong(it, "actor_id").takeIf { id -> id > 0L },
                        conversationType = CursorGetString(it, "conversation_type", "sms"),
                        currentState = CursorGetString(it, "current_state", "IDLE"),
                        previousState = CursorGetString(it, "previous_state"),
                        orderId = cursorGetLong(it, "order_id").takeIf { id -> id > 0L },
                        draftId = CursorGetString(it, "draft_id"),
                        version = cursorGetLong(it, "version"),
                        expiresAt = effectiveExpiresAt,
                        lastInboundMessageId = CursorGetString(it, "last_inbound_message_id"),
                        lastOutboundMessageId = CursorGetString(it, "last_outbound_message_id"),
                        retryCount = cursorGetInt(it, "retry_count"),
                        status = CursorGetString(it, "status", "ACTIVE")
                    ).apply {

                        val dataJson =
                            CursorGetString(
                                it,
                                "data_json"
                            )

                        if (dataJson.isNotBlank()) {

                            try {

                                val json =
                                    JSONObject(dataJson)

                                val keys =
                                    json.keys()

                                while (keys.hasNext()) {

                                    val key =
                                        keys.next()

                                    if (json.isNull(key)) {
                                        continue
                                    }

                                    data[key] =
                                        json.optString(
                                            key,
                                            ""
                                        )
                                }

                            } catch (e: Exception) {

                                Log.w(
                                    TAG,
                                    "Invalid conversation data JSON: ${e.message}"
                                )
                            }
                        }
                    }
                }
            }
        }

        contextCache[cleanPhone] = ctx

        ctx
    }

    suspend fun saveContext(
        phone: String,
        ctx: ConversationContext
    ) = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext
        }

        /**
         * تحديث الوقت عند الحفظ.
         *
         * هذا مهم حتى لا نحفظ سياقًا جديدًا
         * ولكنه يحمل timestamp قديمًا.
         */
        ctx.timestamp = System.currentTimeMillis()
        ctx.expiresAt = ctx.timestamp + if (ctx.awaitingResponse && ctx.pendingAction.isNotBlank()) {
            PENDING_CONTEXT_TIMEOUT_MS
        } else {
            CONTEXT_TIMEOUT_MS
        }
        ctx.version = (ctx.version + 1L).coerceAtLeast(1L)

        val existingData = db.readableDatabase.rawQuery(
            "SELECT data_json FROM sms_conversation_context WHERE phone = ? LIMIT 1",
            arrayOf(cleanPhone)
        ).use { if (it.moveToFirst()) it.getString(0) else "{}" }
        val mergedJson = runCatching { JSONObject(existingData) }
            .onFailure { error -> Log.e(TAG, "Failed to decode context data for phone=$cleanPhone", error) }
            .getOrElse { JSONObject() }
        ctx.data.forEach { (key, value) -> mergedJson.put(key, value.take(1000)) }
        val dataJson = mergedJson.toString()

        val values =
            ContentValues().apply {

                put(
                    "phone",
                    cleanPhone
                )

                put(
                    "last_topic",
                    ctx.lastTopic.take(200)
                )

                put(
                    "last_intent",
                    ctx.lastIntent.take(100)
                )

                put(
                    "timestamp",
                    ctx.timestamp
                )

                put(
                    "pending_action",
                    ctx.pendingAction.take(100)
                )

                put(
                    "awaiting_response",
                    if (ctx.awaitingResponse) 1 else 0
                )

                put(
                    "data_json",
                    dataJson
                )
                put("conversation_id", ctx.conversationId)
                put("actor_id", ctx.actorId)
                put("conversation_type", ctx.conversationType)
                put("current_state", ctx.currentState)
                put("previous_state", ctx.previousState)
                put("order_id", ctx.orderId)
                put("draft_id", ctx.draftId)
                put("version", ctx.version)
                put("expires_at", ctx.expiresAt)
                put("last_inbound_message_id", ctx.lastInboundMessageId)
                put("last_outbound_message_id", ctx.lastOutboundMessageId)
                put("retry_count", ctx.retryCount)
                put("status", ctx.status)
            }

        val rowId = db.writableDatabase.insertWithOnConflict(
            "sms_conversation_context",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        if (rowId == -1L) {
            Log.e(TAG, "Conversation context persistence failed for phone=$cleanPhone")
        }

        contextCache[cleanPhone] = ctx
    }

    suspend fun clearContext(
        phone: String
    ) = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext
        }

        contextCache.remove(cleanPhone)

        db.writableDatabase.delete(
            "sms_conversation_context",
            "phone = ?",
            arrayOf(cleanPhone)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. إدارة تفضيلات العميل (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getOrCreatePreferences(
        phone: String
    ): CustomerPreferences = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext CustomerPreferences()
        }

        val cached = prefsCache[cleanPhone]

        if (cached != null) {
            return@withContext cached
        }

        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT *
            FROM sms_customer_preferences
            WHERE phone = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(cleanPhone)
        )

        val prefs = cursor.use {

            if (!it.moveToFirst()) {

                CustomerPreferences()

            } else {

                CustomerPreferences(

                    preferredQuantity =
                        cursorGetDouble(
                            it,
                            "preferred_quantity"
                        ).coerceAtLeast(0.0),

                    preferredLocation =
                        CursorGetString(
                            it,
                            "preferred_location"
                        ).take(MAX_LOCATION_LENGTH),

                    preferredTime =
                        CursorGetString(
                            it,
                            "preferred_time"
                        ).take(MAX_DELIVERY_TIME_LENGTH),

                    lastOrderDate =
                        cursorGetLong(
                            it,
                            "last_order_date"
                        ).coerceAtLeast(0L),

                    orderCount =
                        cursorGetInt(
                            it,
                            "order_count"
                        ).coerceAtLeast(0),

                    language =
                        CursorGetString(
                            it,
                            "language",
                            "ar"
                        ).ifBlank { "ar" }
                )
            }
        }

        prefsCache[cleanPhone] = prefs

        prefs
    }

    suspend fun savePreferences(
        phone: String,
        prefs: CustomerPreferences
    ) = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext
        }

        val safeQuantity =
            prefs.preferredQuantity
                .takeIf {
                    it.isFinite() &&
                    it >= 0.0 &&
                    it <= MAX_ORDER_LITERS
                }
                ?: 0.0

        prefs.preferredQuantity = safeQuantity

        prefs.preferredLocation =
            prefs.preferredLocation
                .take(MAX_LOCATION_LENGTH)

        prefs.preferredTime =
            prefs.preferredTime
                .take(MAX_DELIVERY_TIME_LENGTH)

        prefs.orderCount =
            prefs.orderCount.coerceAtLeast(0)

        prefs.lastOrderDate =
            prefs.lastOrderDate.coerceAtLeast(0L)

        prefs.language =
            prefs.language
                .ifBlank { "ar" }
                .take(10)

        val values =
            ContentValues().apply {

                put(
                    "phone",
                    cleanPhone
                )

                put(
                    "preferred_quantity",
                    prefs.preferredQuantity
                )

                put(
                    "preferred_location",
                    prefs.preferredLocation
                )

                put(
                    "preferred_time",
                    prefs.preferredTime
                )

                put(
                    "last_order_date",
                    prefs.lastOrderDate
                )

                put(
                    "order_count",
                    prefs.orderCount
                )

                put(
                    "language",
                    prefs.language
                )
            }

        db.writableDatabase.insertWithOnConflict(
            "sms_customer_preferences",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )

        prefsCache[cleanPhone] = prefs
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. سجل التفاعلات (SQLite-backed) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun recordInteraction(
        phone: String,
        intent: String,
        message: String
    ) = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext
        }

        val values =
            ContentValues().apply {

                put(
                    "phone",
                    cleanPhone
                )

                put(
                    "timestamp",
                    System.currentTimeMillis()
                )

                put(
                    "intent",
                    intent.take(100)
                )

                put(
                    "message",
                    message.take(500)
                )
            }

        db.writableDatabase.insert(
            "sms_interaction_history",
            null,
            values
        )

        /**
         * الاحتفاظ بآخر MAX_HISTORY_SIZE سجل فقط.
         */
        db.writableDatabase.execSQL(
            """
            DELETE FROM sms_interaction_history
            WHERE phone = ?
              AND id NOT IN (
                  SELECT id
                  FROM sms_interaction_history
                  WHERE phone = ?
                  ORDER BY timestamp DESC
                  LIMIT ?
              )
            """.trimIndent(),
            arrayOf(
                cleanPhone,
                cleanPhone,
                MAX_HISTORY_SIZE.toString()
            )
        )
    }

    suspend fun getInteractionHistory(
        phone: String,
        limit: Int = 10
    ): List<InteractionRecord> =
        withContext(Dispatchers.IO) {

            val cleanPhone = normalizePhone(phone)

            if (cleanPhone.isEmpty()) {
                return@withContext emptyList()
            }

            val safeLimit =
                limit.coerceIn(
                    1,
                    MAX_HISTORY_QUERY_LIMIT
                )

            val list =
                mutableListOf<InteractionRecord>()

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT *
                    FROM sms_interaction_history
                    WHERE phone = ?
                    ORDER BY timestamp DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        cleanPhone,
                        safeLimit.toString()
                    )
                )

            cursor.use {

                while (it.moveToNext()) {

                    list.add(
                        InteractionRecord(

                            timestamp =
                                cursorGetLong(
                                    it,
                                    "timestamp"
                                ),

                            intent =
                                CursorGetString(
                                    it,
                                    "intent"
                                ),

                            message =
                                CursorGetString(
                                    it,
                                    "message"
                                )
                        )
                    )
                }
            }

            list
        }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. إدارة الطلبات المسودة ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * الطلبات المسودة تبقى في الذاكرة أثناء دورة المحادثة.
     *
     * لا يتم إنشاء فاتورة أو رصيد أو عملية بيع هنا.
     *
     * إنشاء العملية المالية الفعلية يجب أن يتم في المسار
     * التنفيذي المعتمد وبعد استكمال التحقق والتأكيد.
     */
    fun getOrCreateOrderDraft(
        phone: String,
        product: String = PRODUCT_DIESEL
    ): OrderDraft {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return OrderDraft(
                product = normalizeProduct(product)
            )
        }

        return activeOrdersCache.getOrPut(cleanPhone) {
            loadDurableDraft(cleanPhone) ?: OrderDraft(
                product = normalizeProduct(product)
            ).also { persistDurableDraft(cleanPhone, it) }
        }
    }

    fun getOrderDraft(
        phone: String
    ): OrderDraft? {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return null
        }

        return activeOrdersCache[cleanPhone] ?: loadDurableDraft(cleanPhone)?.also {
            activeOrdersCache[cleanPhone] = it
        }
    }

    fun removeOrderDraft(
        phone: String
    ) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return
        }

        activeOrdersCache.remove(cleanPhone)
        clearDurableDraft(cleanPhone)
    }

    fun updateOrderDraft(
        phone: String,
        block: OrderDraft.() -> Unit
    ) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return
        }

        val draft = activeOrdersCache.getOrPut(cleanPhone) {
            loadDurableDraft(cleanPhone) ?: OrderDraft()
        }
        draft.apply {
            block()
            sanitizeOrderDraft(this)
            persistDurableDraft(cleanPhone, this)
        }
    }

    /**
     * تنظيف والتحقق من بيانات المسودة.
     *
     * لا يقوم بحساب سعر من نفسه.
     * السعر النهائي يجب أن يأتي من المصدر الحقيقي
     * في DatabaseHelper / طبقة معالجة الطلب.
     */
    private fun sanitizeOrderDraft(
        draft: OrderDraft
    ) {

        draft.product =
            normalizeProduct(draft.product)

        draft.quantityLiters =
            if (
                draft.quantityLiters.isFinite() &&
                draft.quantityLiters >= 0.0
            ) {
                draft.quantityLiters.coerceAtMost(
                    MAX_ORDER_LITERS
                )
            } else {
                0.0
            }

        draft.quantityDabbas =
            if (
                draft.quantityDabbas.isFinite() &&
                draft.quantityDabbas >= 0.0
            ) {
                draft.quantityDabbas
            } else {
                0.0
            }

        /**
         * الحفاظ على قاعدة الوحدات المعتمدة.
         *
         * إذا كانت كمية اللترات معروفة، يمكن اشتقاق الدباب
         * منها دون إنشاء أي قيمة مالية.
         */
        if (
            draft.quantityLiters > 0.0 &&
            draft.quantityDabbas <= 0.0
        ) {

            draft.quantityDabbas =
                draft.quantityLiters /
                    LITERS_PER_DABBA
        }

        /**
         * وإذا كانت الدباب معروفة واللترات غير معروفة،
         * نحسب اللترات وفق القاعدة المعتمدة.
         */
        if (
            draft.quantityDabbas > 0.0 &&
            draft.quantityLiters <= 0.0
        ) {

            draft.quantityLiters =
                draft.quantityDabbas *
                    LITERS_PER_DABBA
        }

        draft.deliveryLocation =
            draft.deliveryLocation
                .take(MAX_LOCATION_LENGTH)

        draft.deliveryTime =
            draft.deliveryTime
                .take(MAX_DELIVERY_TIME_LENGTH)

        draft.deliveryTimestamp =
            draft.deliveryTimestamp
                .coerceAtLeast(0L)

        /**
         * السعر والمبلغ لا يتم اختراعهما هنا.
         *
         * إذا كانت القيم موجودة من المصدر الحقيقي نحافظ عليها،
         * وإلا تبقى صفرًا إلى أن يتم جلب السعر الفعلي.
         */
        draft.unitPrice =
            if (
                draft.unitPrice.isFinite() &&
                draft.unitPrice >= 0.0
            ) {
                draft.unitPrice
            } else {
                0.0
            }

        draft.totalAmount =
            if (
                draft.totalAmount.isFinite() &&
                draft.totalAmount >= 0.0
            ) {
                draft.totalAmount
            } else {
                0.0
            }

        draft.step =
            draft.step.coerceAtLeast(0)

        draft.status =
            draft.status
                .ifBlank { "draft" }
                .take(50)

        draft.createdAt =
            draft.createdAt
                .takeIf { it > 0L }
                ?: System.currentTimeMillis()
        draft.draftId = draft.draftId.ifBlank { UUID.randomUUID().toString() }
        draft.version = draft.version.coerceAtLeast(0L)
    }

    private fun loadDurableDraft(phone: String): OrderDraft? {
        val jsonText = db.readableDatabase.rawQuery(
            "SELECT data_json FROM sms_conversation_context WHERE phone = ? LIMIT 1",
            arrayOf(phone)
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val json = runCatching { JSONObject(jsonText) }
            .onFailure { error -> Log.e(TAG, "Failed to decode durable draft for phone=$phone", error) }
            .getOrNull() ?: return null
        if (json.optString("draft_status").isBlank()) return null
        return OrderDraft(
            product = normalizeProduct(json.optString("draft_product", PRODUCT_DIESEL)),
            quantityLiters = json.optDouble("draft_quantity_liters", 0.0),
            quantityDabbas = json.optDouble("draft_quantity_dabbas", 0.0),
            deliveryLocation = json.optString("draft_location", ""),
            deliveryTime = json.optString("draft_time", ""),
            deliveryTimestamp = json.optLong("draft_timestamp", 0L),
            unitPrice = json.optDouble("draft_unit_price", 0.0),
            totalAmount = json.optDouble("draft_total", 0.0),
            status = json.optString("draft_status", "draft"),
            step = json.optInt("draft_step", 0),
            createdAt = json.optLong("draft_created_at", System.currentTimeMillis()),
            draftId = json.optString("draft_id", UUID.randomUUID().toString()),
            version = json.optLong("draft_version", 0L)
        ).also { sanitizeOrderDraft(it) }
    }

    private fun persistDurableDraft(phone: String, draft: OrderDraft) {
        try {
            sanitizeOrderDraft(draft)
            val current = db.readableDatabase.rawQuery(
                "SELECT data_json FROM sms_conversation_context WHERE phone = ? LIMIT 1",
                arrayOf(phone)
            ).use { if (it.moveToFirst()) it.getString(0) else "{}" }
            val json = runCatching { JSONObject(current) }
                .onFailure { error -> Log.e(TAG, "Failed to decode existing draft JSON for phone=$phone", error) }
                .getOrElse { JSONObject() }
            draft.version += 1L
            json.put("draft_id", draft.draftId)
            json.put("draft_product", draft.product)
            json.put("draft_quantity_liters", draft.quantityLiters)
            json.put("draft_quantity_dabbas", draft.quantityDabbas)
            json.put("draft_location", draft.deliveryLocation)
            json.put("draft_time", draft.deliveryTime)
            json.put("draft_timestamp", draft.deliveryTimestamp)
            json.put("draft_unit_price", draft.unitPrice)
            json.put("draft_total", draft.totalAmount)
            json.put("draft_status", draft.status)
            json.put("draft_step", draft.step)
            json.put("draft_created_at", draft.createdAt)
            json.put("draft_version", draft.version)
            val values = ContentValues().apply {
                put("data_json", json.toString())
                put("draft_id", draft.draftId)
                put("current_state", "ORDER_DRAFT")
                put("status", "ACTIVE")
                put("version", draft.version)
                put("updated_at", DatabaseHelper.getDateOnlyFormat().format(java.util.Date()))
            }
            val database = db.writableDatabase
            val updated = database.update(
                "sms_conversation_context",
                values,
                "phone = ?",
                arrayOf(phone)
            )
            if (updated == 0) {
                val inserted = database.insertWithOnConflict(
                    "sms_conversation_context",
                    null,
                    ContentValues(values).apply {
                        put("phone", phone)
                        put("last_topic", "order")
                        put("last_intent", "diesel_request")
                        put("timestamp", System.currentTimeMillis())
                        put("pending_action", "")
                        put("awaiting_response", 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (inserted == -1L) {
                    Log.e(TAG, "Durable draft UPSERT failed for phone=$phone")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Durable draft persistence failed for phone=$phone", error)
        }
    }

    private fun clearDurableDraft(phone: String) {
        val current = db.readableDatabase.rawQuery(
            "SELECT data_json FROM sms_conversation_context WHERE phone = ? LIMIT 1",
            arrayOf(phone)
        ).use { if (it.moveToFirst()) it.getString(0) else "{}" }
        val json = runCatching { JSONObject(current) }.getOrElse { JSONObject() }
        listOf("draft_id", "draft_product", "draft_quantity_liters", "draft_quantity_dabbas", "draft_location", "draft_time", "draft_timestamp", "draft_unit_price", "draft_total", "draft_status", "draft_step", "draft_created_at", "draft_version").forEach(json::remove)
        db.writableDatabase.update(
            "sms_conversation_context",
            ContentValues().apply { put("data_json", json.toString()); put("draft_id", ""); put("current_state", "IDLE") },
            "phone = ?",
            arrayOf(phone)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. إدارة الطلبات المتكررة ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun saveRecurringOrder(
        order: RecurringOrder
    ) = withContext(Dispatchers.IO) {

        val cleanPhone =
            normalizePhone(order.customerId)

        if (cleanPhone.isEmpty()) {
            return@withContext
        }

        val safeQuantity =
            order.quantity
                .takeIf {
                    it.isFinite() &&
                    it > 0.0 &&
                    it <= MAX_ORDER_LITERS
                }
                ?: return@withContext

        val safeLocation =
            order.location
                .trim()
                .take(MAX_LOCATION_LENGTH)

        if (safeLocation.isEmpty()) {
            return@withContext
        }

        val safeSchedule =
            order.schedule
                .trim()
                .take(200)

        if (safeSchedule.isEmpty()) {
            return@withContext
        }

        val safeNextDelivery =
            order.nextDelivery
                .takeIf { it > 0L }
                ?: return@withContext

        val safeOrder =
            RecurringOrder(
                customerId = cleanPhone,
                quantity = safeQuantity,
                location = safeLocation,
                schedule = safeSchedule,
                nextDelivery = safeNextDelivery
            )

        recurringOrdersCache[cleanPhone] =
            safeOrder

        val values =
            ContentValues().apply {

                put(
                    "customer_id",
                    cleanPhone
                )

                put(
                    "quantity",
                    safeQuantity
                )

                put(
                    "location",
                    safeLocation
                )

                put(
                    "schedule",
                    safeSchedule
                )

                put(
                    "next_delivery",
                    safeNextDelivery
                )
            }

        db.writableDatabase.insertWithOnConflict(
            "sms_recurring_orders",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun getRecurringOrder(
        phone: String
    ): RecurringOrder? =
        withContext(Dispatchers.IO) {

            val cleanPhone =
                normalizePhone(phone)

            if (cleanPhone.isEmpty()) {
                return@withContext null
            }

            val cached =
                recurringOrdersCache[cleanPhone]

            if (cached != null) {
                return@withContext cached
            }

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT *
                    FROM sms_recurring_orders
                    WHERE customer_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(cleanPhone)
                )

            val order =
                cursor.use {

                    if (!it.moveToFirst()) {
                        null
                    } else {

                        val quantity =
                            cursorGetDouble(
                                it,
                                "quantity"
                            )

                        val location =
                            CursorGetString(
                                it,
                                "location"
                            )

                        val schedule =
                            CursorGetString(
                                it,
                                "schedule"
                            )

                        val nextDelivery =
                            cursorGetLong(
                                it,
                                "next_delivery"
                            )

                        if (
                            !quantity.isFinite() ||
                            quantity <= 0.0 ||
                            quantity > MAX_ORDER_LITERS ||
                            location.isBlank() ||
                            schedule.isBlank() ||
                            nextDelivery <= 0L
                        ) {
                            null
                        } else {

                            RecurringOrder(
                                customerId =
                                    CursorGetString(
                                        it,
                                        "customer_id",
                                        cleanPhone
                                    ),

                                quantity = quantity,

                                location =
                                    location.take(
                                        MAX_LOCATION_LENGTH
                                    ),

                                schedule =
                                    schedule.take(200),

                                nextDelivery =
                                    nextDelivery
                            )
                        }
                    }
                }

            if (order != null) {
                recurringOrdersCache[cleanPhone] =
                    order
            }

            order
        }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 6. تنظيف الذاكرة المؤقتة ═══
    // ═══════════════════════════════════════════════════════════════

    fun cleanupExpiredCache() {

        val now =
            System.currentTimeMillis()

        /**
         * سياق المحادثة.
         */
        contextCache.entries.removeIf { entry ->
            !isContextUsable(entry.value, now)
        }

        /**
         * الطلبات المسودة.
         *
         * الطلبات القديمة جدًا لا ينبغي أن تبقى في الذاكرة
         * إلى أجل غير محدود.
         *
         * لا يتم حذف أي سجل مالي من SQLite هنا.
         */
        activeOrdersCache.entries.removeIf { entry ->

            val createdAt =
                entry.value.createdAt

            createdAt <= 0L ||
                now - createdAt >= CONTEXT_TIMEOUT_MS
        }

        /**
         * التفضيلات والطلبات المتكررة لا يتم حذفها من الكاش
         * لمجرد مرور الوقت، لأنها بيانات تفضيلات/جدولة
         * وليست سياق محادثة مؤقتًا.
         */
    }

    private fun isContextUsable(ctx: ConversationContext, now: Long): Boolean {
        if (ctx.timestamp <= 0L) return false
        val fallbackExpiry = ctx.timestamp + if (ctx.awaitingResponse && ctx.pendingAction.isNotBlank()) {
            PENDING_CONTEXT_TIMEOUT_MS
        } else {
            CONTEXT_TIMEOUT_MS
        }
        val expiry = if (ctx.awaitingResponse && ctx.pendingAction.isNotBlank()) {
            maxOf(ctx.expiresAt, fallbackExpiry)
        } else {
            ctx.expiresAt.takeIf { it > 0L } ?: fallbackExpiry
        }
        return now < expiry
    }

    /**
     * تنظيف كامل للكاش في الحالات التي تحتاج ذلك.
     *
     * لا يمس قاعدة البيانات.
     */
    fun clearMemoryCache() {

        contextCache.clear()
        activeOrdersCache.clear()
        prefsCache.clear()
        recurringOrdersCache.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 7. مزامنة سياق المحادثة (syncContext) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun syncContext() =
        withContext(Dispatchers.IO) {

            try {

                cleanupExpiredCache()

                Log.d(
                    TAG,
                    "Conversation context synced successfully"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to sync conversation context: ${e.message}",
                    e
                )
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 8. أدوات قواعد الكميات المعتمدة ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحويل الدباب إلى لترات.
     *
     * 1 دبة = 20 لتر.
     */
    fun dabbasToLiters(
        dabbas: Double
    ): Double {

        require(
            dabbas.isFinite() &&
                dabbas >= 0.0
        ) {
            "Invalid dabbas: $dabbas"
        }

        val liters =
            dabbas * LITERS_PER_DABBA

        require(
            liters.isFinite() &&
                liters <= MAX_ORDER_LITERS
        ) {
            "Invalid liters result: $liters"
        }

        return liters
    }

    /**
     * تحويل اللترات إلى دباب.
     *
     * 20 لتر = 1 دبة.
     */
    fun litersToDabbas(
        liters: Double
    ): Double {

        require(
            liters.isFinite() &&
                liters >= 0.0 &&
                liters <= MAX_ORDER_LITERS
        ) {
            "Invalid liters: $liters"
        }

        return liters / LITERS_PER_DABBA
    }

    /**
     * البرميل العادي:
     *
     * 1 برميل عادي
     * = 10 دباب
     * = 200 لتر.
     */
    fun ordinaryBarrelsToLiters(
        barrels: Double
    ): Double {

        require(
            barrels.isFinite() &&
                barrels >= 0.0
        ) {
            "Invalid ordinary barrels: $barrels"
        }

        val liters =
            barrels *
                ORDINARY_BARREL_LITERS

        require(
            liters.isFinite() &&
                liters <= MAX_ORDER_LITERS
        ) {
            "Invalid liters result: $liters"
        }

        return liters
    }

    /**
     * البرميل الكبير:
     *
     * 1 برميل كبير
     * = 12 دبة
     * = 240 لتر.
     */
    fun largeBarrelsToLiters(
        barrels: Double
    ): Double {

        require(
            barrels.isFinite() &&
                barrels >= 0.0
        ) {
            "Invalid large barrels: $barrels"
        }

        val liters =
            barrels *
                LARGE_BARREL_LITERS

        require(
            liters.isFinite() &&
                liters <= MAX_ORDER_LITERS
        ) {
            "Invalid liters result: $liters"
        }

        return liters
    }

    /**
     * الحصول على عدد الدباب في برميل عادي.
     */
    fun ordinaryBarrelsToDabbas(
        barrels: Double
    ): Double {

        require(
            barrels.isFinite() &&
                barrels >= 0.0
        ) {
            "Invalid ordinary barrels: $barrels"
        }

        return barrels *
            ORDINARY_BARREL_DABBAS
    }

    /**
     * الحصول على عدد الدباب في البرميل الكبير.
     */
    fun largeBarrelsToDabbas(
        barrels: Double
    ): Double {

        require(
            barrels.isFinite() &&
                barrels >= 0.0
        ) {
            "Invalid large barrels: $barrels"
        }

        return barrels *
            LARGE_BARREL_DABBAS
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 9. أدوات المنتج ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * توحيد اسم المنتج دون تحويل البنزين إلى ديزل.
     *
     * أي منتج غير معروف يبقى كما هو بعد تنظيفه،
     * ولا يتم اختراع نوع وقود جديد.
     */
    private fun normalizeProduct(
        product: String?
    ): String {

        val value =
            product
                ?.trim()
                ?.lowercase()
                .orEmpty()

        return when (value) {

            "",
            "diesel",
            "ديزل",
            "ديزل سيارات",
            "سولار" ->
                PRODUCT_DIESEL

            "gasoline",
            "petrol",
            "بنزين",
            "بترول" ->
                PRODUCT_GASOLINE

            else ->
                value.take(MAX_PRODUCT_LENGTH)
        }
    }

    /**
     * فحص ما إذا كان المنتج ديزل.
     */
    fun isDieselProduct(
        product: String?
    ): Boolean {

        return normalizeProduct(product) ==
            PRODUCT_DIESEL
    }

    /**
     * فحص ما إذا كان المنتج بنزين.
     *
     * لا يتم إنشاء طلب ديزل عندما يرسل العميل طلب بنزين.
     */
    fun isGasolineProduct(
        product: String?
    ): Boolean {

        return normalizeProduct(product) ==
            PRODUCT_GASOLINE
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 10. مساعدات تحديث الطلب المسودة ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحديث كمية الطلب باللترات.
     *
     * يتم اشتقاق الدباب وفق:
     * 1 دبة = 20 لتر.
     */
    fun setDraftQuantityLiters(
        phone: String,
        liters: Double
    ) {

        require(
            liters.isFinite() &&
                liters > 0.0 &&
                liters <= MAX_ORDER_LITERS
        ) {
            "Invalid quantity liters: $liters"
        }

        updateOrderDraft(phone) {

            quantityLiters = liters

            quantityDabbas =
                litersToDabbas(liters)
        }
    }

    /**
     * تحديث كمية الطلب بالدباب.
     *
     * يتم اشتقاق اللترات وفق:
     * 1 دبة = 20 لتر.
     */
    fun setDraftQuantityDabbas(
        phone: String,
        dabbas: Double
    ) {

        require(
            dabbas.isFinite() &&
                dabbas > 0.0
        ) {
            "Invalid quantity dabbas: $dabbas"
        }

        val liters =
            dabbasToLiters(dabbas)

        updateOrderDraft(phone) {

            quantityDabbas = dabbas
            quantityLiters = liters
        }
    }

    /**
     * تحديث موقع التسليم.
     */
    fun setDraftDeliveryLocation(
        phone: String,
        location: String
    ) {

        val safeLocation =
            location.trim()

        require(
            safeLocation.length in 3..MAX_LOCATION_LENGTH
        ) {
            "Invalid delivery location"
        }

        updateOrderDraft(phone) {

            deliveryLocation =
                safeLocation
        }
    }

    /**
     * تحديث وقت التسليم.
     *
     * لا يقوم بتغيير أو اختراع timestamp.
     */
    fun setDraftDeliveryTime(
        phone: String,
        displayTime: String,
        timestamp: Long = 0L
    ) {

        val safeTime =
            displayTime.trim()

        require(
            safeTime.isNotEmpty() &&
                safeTime.length <=
                    MAX_DELIVERY_TIME_LENGTH
        ) {
            "Invalid delivery time"
        }

        updateOrderDraft(phone) {

            deliveryTime =
                safeTime

            deliveryTimestamp =
                timestamp.coerceAtLeast(0L)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 11. مساعدات حالة المسودة ═══
    // ═══════════════════════════════════════════════════════════════

    fun markDraftAwaitingConfirmation(
        phone: String
    ) {

        updateOrderDraft(phone) {

            status = "awaiting_confirmation"
        }
    }

    fun markDraftConfirmed(
        phone: String
    ) {

        updateOrderDraft(phone) {

            status = "confirmed"
        }
    }

    fun markDraftCancelled(
        phone: String
    ) {

        updateOrderDraft(phone) {

            status = "cancelled"
        }
    }

    fun markDraftFailed(
        phone: String
    ) {

        updateOrderDraft(phone) {

            status = "failed"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 12. أدوات معلومات المسودة ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * إرجاع نسخة آمنة من المسودة الحالية.
     *
     * الهدف منع تعديل الكائن الموجود في الكاش
     * مباشرة من خارج المدير.
     */
    fun getSafeOrderDraft(
        phone: String
    ): OrderDraft? {

        val draft =
            getOrderDraft(phone)
                ?: return null

        return draft.copy()
    }

    /**
     * التحقق من اكتمال البيانات الأساسية للطلب.
     *
     * لا يعني هذا أن الطلب أصبح فاتورة.
     * ولا يعني أنه تم الخصم من رصيد العميل.
     * ولا يعني أنه تم تنفيذ عملية بيع.
     *
     * هو فقط فحص لاكتمال المسودة قبل الانتقال
     * إلى مرحلة التأكيد والتنفيذ الفعلي.
     */
    fun isDraftReadyForConfirmation(
        phone: String
    ): Boolean {

        val draft =
            getOrderDraft(phone)
                ?: return false

        if (!isDieselProduct(draft.product)) {
            return false
        }

        if (
            !draft.quantityLiters.isFinite() ||
            draft.quantityLiters <= 0.0 ||
            draft.quantityLiters > MAX_ORDER_LITERS
        ) {
            return false
        }

        if (draft.deliveryLocation.length !in 3..MAX_LOCATION_LENGTH) {
            return false
        }

        if (draft.deliveryTime.isBlank()) {
            return false
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 13. مزامنة عامة للمدير ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun sync() =
        withContext(Dispatchers.IO) {

            try {

                cleanupExpiredCache()

                Log.d(
                    TAG,
                    "Conversation manager synced successfully"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to sync conversation manager: ${e.message}",
                    e
                )
            }
        }
}