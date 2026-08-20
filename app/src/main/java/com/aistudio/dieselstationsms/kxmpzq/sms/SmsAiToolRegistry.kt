package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * أدوات AI المعتمدة. كل أداة قراءة فقط، باستثناء إنشاء مهمة مراجعة بشرية.
 * لا توجد أداة تكتب ledger أو تعتمد دفعاً أو تنشئ أثراً مالياً.
 */
class SmsAiToolRegistry(
    private val db: DatabaseHelper,
    private val customer: SmsCustomerResolver.CustomerInfo,
    private val context: SmsConversationManager.ConversationContext,
    private val preferences: SmsConversationManager.CustomerPreferences,
    private val draft: SmsConversationManager.OrderDraft?
) : SmsAiToolExecutor {
    override fun definitions(): JSONArray = JSONArray().apply {
        put(tool(
            name = "get_customer_balance",
            description = "قراءة الرصيد والنقاط والعضوية الفعلية للعميل الحالي فقط.",
            properties = JSONObject(),
            required = emptyList()
        ))
        put(tool(
            name = "get_current_fuel_price",
            description = "قراءة سعر الديزل الحالي من مستودع المحطة.",
            properties = JSONObject(),
            required = emptyList()
        ))
        put(tool(
            name = "get_last_order",
            description = "قراءة آخر تفضيلات وطلب محفوظ للعميل، دون إنشاء طلب جديد.",
            properties = JSONObject(),
            required = emptyList()
        ))
        put(tool(
            name = "get_order_status",
            description = "قراءة حالة الطلب الجاري من السياق الفعلي إن كان معرف الطلب موجوداً.",
            properties = JSONObject().put("order_id", stringProperty("معرف الطلب الاختياري")),
            required = emptyList()
        ))
        put(tool(
            name = "get_loyalty_balance",
            description = "قراءة نقاط الولاء الحالية من هوية العميل.",
            properties = JSONObject(),
            required = emptyList()
        ))
        put(tool(
            name = "request_human_review",
            description = "إنشاء مهمة مراجعة بشرية عندما يكون الفهم منخفض الثقة أو المخاطر مرتفعة.",
            properties = JSONObject()
                .put("reason", stringProperty("سبب التحويل للمراجعة"))
                .put("severity", stringProperty("LOW أو NORMAL أو HIGH"))
                .put("summary", stringProperty("ملخص آمن للمراجع")),
            required = listOf("reason", "severity", "summary")
        ))
    }

    override suspend fun execute(call: SmsAiToolCall): SmsAiToolResult {
        return when (call.name) {
            "get_customer_balance" -> result(call, true, JSONObject().apply {
                put("status", "FOUND")
                put("balance", customer.balance)
                put("points", customer.points)
                put("vip_level", customer.vipLevel)
            })
            "get_current_fuel_price" -> result(call, true, JSONObject().apply {
                put("status", "FOUND")
                put("fuel", "diesel")
                put("price_per_liter", SmsCustomerResolver(db).getDieselPrice())
                put("currency", "YER")
            })
            "get_last_order" -> result(call, true, JSONObject().apply {
                if (preferences.orderCount > 0 && preferences.preferredQuantity > 0) {
                    put("status", "FOUND")
                    put("quantity_liters", preferences.preferredQuantity)
                    put("location", preferences.preferredLocation)
                    put("time", preferences.preferredTime)
                    put("last_order_date", preferences.lastOrderDate)
                    put("order_count", preferences.orderCount)
                } else {
                    put("status", "NOT_FOUND")
                    put("reason", "لا يوجد طلب سابق محفوظ يمكن الاعتماد عليه")
                }
                draft?.let {
                    put("active_draft", JSONObject().apply {
                        put("draft_id", it.draftId)
                        put("quantity_liters", it.quantityLiters)
                        put("location", it.deliveryLocation)
                        put("time", it.deliveryTime)
                        put("status", it.status)
                    })
                }
            })
            "get_order_status" -> result(call, true, JSONObject().apply {
                val requestedId = call.arguments.optString("order_id", "").trim()
                val orderId = requestedId.ifBlank { context.orderId?.toString().orEmpty() }
                if (orderId.isBlank()) {
                    put("status", "NOT_FOUND")
                    put("reason", "لا يوجد order_id فعلي في سياق المحادثة")
                } else {
                    put("status", "CONTEXT_ONLY")
                    put("order_id", orderId)
                    put("conversation_state", context.currentState)
                    put("delivery_time", draft?.deliveryTime.orEmpty())
                }
            })
            "get_loyalty_balance" -> result(call, true, JSONObject().apply {
                put("status", "FOUND")
                put("points", customer.points)
                put("vip_level", customer.vipLevel)
            })
            "request_human_review" -> createHumanReview(call)
            else -> result(call, false, JSONObject().apply {
                put("status", "DENIED")
                put("reason", "الأداة غير مسموحة")
            }, riskLevel = "HIGH")
        }
    }

    private fun createHumanReview(call: SmsAiToolCall): SmsAiToolResult {
        val taskId = UUID.randomUUID().toString()
        val reason = call.arguments.optString("reason", "AI requested review").take(500)
        val severity = call.arguments.optString("severity", "NORMAL").uppercase().take(20)
        val summary = call.arguments.optString("summary", "").take(1000)
        val inserted = db.writableDatabase.insertWithOnConflict(
            "sms_human_review_tasks",
            null,
            ContentValues().apply {
                put("task_id", taskId)
                put("conversation_id", context.conversationId)
                put("phone", customer.phone)
                put("reason", reason)
                put("severity", severity)
                put("summary", summary)
                put("status", "OPEN")
                put("created_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        return result(call, inserted, JSONObject().apply {
            put("status", if (inserted) "CREATED" else "FAILED")
            put("task_id", taskId)
        }, riskLevel = "MEDIUM")
    }

    private fun result(
        call: SmsAiToolCall,
        success: Boolean,
        output: JSONObject,
        riskLevel: String = "LOW"
    ): SmsAiToolResult = SmsAiToolResult(call.id, call.name, success, output, riskLevel)

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray(required))
                put("additionalProperties", false)
            })
        })
    }

    private fun stringProperty(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }
}
