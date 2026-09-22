package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONObject
import java.util.UUID

/** يحول خطة الحوار إلى Command موحد قابل للتدقيق، ولا يملك صلاحية تعديل قاعدة الأعمال بنفسه. */
class SmsSemanticCommandBus(private val repository: SmsCognitiveRepository) {
    fun route(
        phone: String,
        context: SmsConversationManager.ConversationContext,
        plan: SmsCognitivePlan,
        eventId: String = UUID.randomUUID().toString()
    ): SmsSemanticCommand {
        val commandType = when (plan.intentResult.intent) {
            "diesel_request" -> "CREATE_FUEL_ORDER_COMMAND"
            "confirm_order" -> "CONFIRM_QUOTE_COMMAND"
            "cancel_order" -> "CANCEL_ORDER_COMMAND"
            "balance_query" -> "REQUEST_BALANCE_COMMAND"
            "complaint" -> "REGISTER_COMPLAINT_COMMAND"
            else -> "RESPOND_TO_INFORMATION_COMMAND"
        }
        val command = SmsSemanticCommand(
            commandId = UUID.randomUUID().toString(),
            commandType = commandType,
            conversationId = context.conversationId,
            eventId = eventId,
            idempotencyKey = repository.idempotencyKey(phone, plan.normalizedText, context.conversationId),
            payload = JSONObject().apply {
                put("phone", phone)
                put("plan", plan.toJson())
            }
        )
        repository.enqueueCommand(command)
        repository.recordEvent(
            eventId = eventId,
            conversationId = context.conversationId,
            eventType = "${commandType.removeSuffix("_COMMAND")}_CREATED",
            aggregateType = "conversation",
            aggregateId = context.conversationId,
            payload = command.payload
        )
        repository.recordInboundTrace(context.conversationId, eventId, "COMMAND_CREATED", JSONObject().apply {
            put("command_id", command.commandId)
            put("command_type", command.commandType)
            put("idempotency_key", command.idempotencyKey)
        })
        return command
    }
}
