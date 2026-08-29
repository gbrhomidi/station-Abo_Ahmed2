package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONObject

/** قرار أعمال deterministic؛ لا يسمح للخطة المعرفية بتعديل المال مباشرة. */
class SmsDecisionEngine {
    fun evaluateOrderConfirmation(
        customer: SmsCustomerResolver.CustomerInfo,
        order: SmsConversationManager.OrderDraft,
        eventId: String
    ): SmsDecisionResult {
        val reasons = mutableListOf<String>()
        val proof = JSONObject().apply {
            put("confirmation_event_id", eventId)
            put("customer_party_id", customer.partyId ?: JSONObject.NULL)
            put("quantity_liters", order.quantityLiters)
            put("total_amount", order.totalAmount)
        }
        if (customer.partyId == null) return denied("IDENTITY_REQUIRED", "HIGH", reasons + "verified customer required", proof)
        if (order.product != SmsConversationManager.PRODUCT_DIESEL) return denied("PRODUCT_NOT_ALLOWED", "MEDIUM", reasons + "product policy denied", proof)
        if (order.quantityLiters <= 0.0 || order.quantityLiters > 10_000.0) return denied("QUANTITY_INVALID", "HIGH", reasons + "quantity outside policy", proof)
        if (order.deliveryLocation.isBlank()) return denied("LOCATION_REQUIRED", "MEDIUM", reasons + "delivery location required", proof)
        if (order.deliveryTime.isBlank()) return denied("TIME_REQUIRED", "MEDIUM", reasons + "delivery time required", proof)
        if (order.totalAmount <= 0.0) return denied("QUOTE_REQUIRED", "HIGH", reasons + "valid quote required", proof)
        reasons += "verified customer"
        reasons += "valid quote"
        reasons += "quantity policy allowed"
        reasons += "delivery details complete"
        return SmsDecisionResult(true, "APPROVED", "ORDER_CONFIRMATION_V3", reasons, "LOW", proof)
    }

    fun simulateOrder(order: SmsConversationManager.OrderDraft): JSONObject = JSONObject().apply {
        put("product", order.product)
        put("quantity_liters", order.quantityLiters)
        put("total_amount", order.totalAmount)
        put("inventory_impact", "PENDING_VALIDATION")
        put("payment_impact", order.totalAmount)
        put("delivery_impact", if (order.deliveryLocation.isBlank()) "MISSING_LOCATION" else "PLANNED")
        put("allowed", order.quantityLiters > 0 && order.totalAmount > 0 && order.deliveryLocation.isNotBlank())
    }

    private fun denied(outcome: String, risk: String, reasons: List<String>, proof: JSONObject) =
        SmsDecisionResult(false, outcome, "ORDER_CONFIRMATION_V3", reasons, risk, proof)
}
