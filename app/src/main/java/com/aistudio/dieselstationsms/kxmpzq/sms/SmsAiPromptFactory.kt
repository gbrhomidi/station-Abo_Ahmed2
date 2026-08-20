package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONArray
import org.json.JSONObject

/** يبني تعليمات AI منفصلة عن مسار الأعمال وقابلة للتحديث دون تشويه SmsProcessor. */
object SmsAiPromptFactory {
    private val intents = listOf(
        "diesel_request", "quantity_response", "quantity_ambiguous", "location_response",
        "time_response", "confirm_order", "cancel_order", "balance_query", "payment_request",
        "transfer_request", "offers_query", "price_query", "loyalty_query", "redeem_points",
        "track_order", "order_history", "help", "complaint", "emergency", "callback_request",
        "location_query", "working_hours", "invoice_request", "weekly_report", "schedule_appointment",
        "schedule_recurring", "rating", "greeting", "thanks", "gasoline_request", "unknown"
    )

    fun systemInstructions(): String = """
        أنت محرك فهم رسائل SMS لمحطة وقود يمنية. مهمتك فهم العربية الفصحى والعامية واللهجة اليمنية والأخطاء الإملائية، ثم إخراج JSON مطابق للمخطط فقط.
        لا تخترع رصيداً أو سعراً أو حالة طلب أو نجاح دفع. البيانات التجارية الحقيقية لا تأتي إلا من أدوات مصرح بها، وإذا احتجت إليها اطلب الأداة المناسبة.
        لا تنفذ أي عملية مالية. أنت تفهم وتقترح فقط؛ طبقة السياسة والتطبيق هي التي تنفذ.
        اعتبر الرسائل القصيرة مثل «نعم»، «لا»، «نفس السابق»، «عدّلها»، «بكرة»، «أرسلها» امتداداً للسياق الحالي.
        استخدم intent واحداً من القائمة التالية فقط: ${intents.joinToString(", ")}.
        quantity_liters يجب أن يكون رقماً كنص، والدبة تساوي 20 لتراً فقط إذا صرّح العميل بوحدة الدبة أو أمكن استنتاجها بوضوح.
        إذا كان الفهم غير آمن أو ناقصاً فاجعل status = NEEDS_CLARIFICATION، وخفّض confidence، واكتب missing_entities بدلاً من التخمين.
        response_draft اختياري ومختصر بالعربية، ولا يثبت أي نجاح تجاري.
    """.trimIndent()

    fun userMessage(request: SmsAiRequest): String = buildString {
        appendLine("رسالة العميل الحالية:")
        appendLine(request.message.take(2000))
        appendLine("سياق المحادثة الحالي بصيغة JSON:")
        appendLine(request.contextJson.toString().take(5000))
        appendLine("تفضيلات العميل المسموح بها بصيغة JSON:")
        appendLine(request.preferencesJson.toString().take(3000))
        request.draftJson?.let {
            appendLine("مسودة الطلب الحالية بصيغة JSON:")
            appendLine(it.toString().take(4000))
        }
        appendLine("النية السابقة: ${request.lastIntent.take(80)}")
        appendLine("الإجراء المنتظر: ${request.pendingAction.take(100)}")
        appendLine("اسم العميل للخطاب فقط: ${request.customerName.take(120)}")
        appendLine("أخرج JSON فقط.")
    }

    fun responseFormat(): JSONObject = JSONObject().apply {
        put("type", "json_schema")
        put("json_schema", JSONObject().apply {
            put("name", "sms_understanding")
            put("strict", true)
            put("schema", JSONObject().apply {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", JSONObject().apply {
                    put("intent", JSONObject().apply { put("type", "string"); put("enum", JSONArray(intents)) })
                    put("entities", JSONObject().apply {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("properties", entityProperties())
                        put("required", JSONArray(entityKeys))
                    })
                    put("confidence", JSONObject().apply { put("type", "number"); put("minimum", 0); put("maximum", 1) })
                    put("status", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("UNDERSTOOD", "NEEDS_CLARIFICATION", "UNSAFE"))) })
                    put("reason", JSONObject().apply { put("type", "string") })
                    put("missing_entities", JSONObject().apply { put("type", "array"); put("items", JSONObject().put("type", "string")) })
                    put("assumptions", JSONObject().apply { put("type", "array"); put("items", JSONObject().put("type", "string")) })
                    put("response_draft", JSONObject().apply { put("type", listOf("string", "null")) })
                })
                put("required", JSONArray(listOf("intent", "entities", "confidence", "status", "reason", "missing_entities", "assumptions", "response_draft")))
            })
        })
    }

    private val entityKeys = listOf(
        "fuel", "quantity_liters", "unit", "location", "date", "time", "time_window",
        "payment_amount", "order_id", "invoice_id", "customer_name", "phone", "vehicle",
        "delivery_preference", "reference"
    )

    private fun entityProperties(): JSONObject = JSONObject().apply {
        entityKeys.forEach { key -> put(key, JSONObject().apply { put("type", listOf("string", "null")) }) }
    }
}
