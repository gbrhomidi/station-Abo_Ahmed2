package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONObject

data class PriceResolution(
    val unitPrice: Double,
    val source: String,
    val sourceId: Long?,
    val reason: String?,
    val validUntil: String?
) {
    init {
        require(unitPrice.isFinite() && unitPrice >= 0.0) { "السعر المحلول غير صالح" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("unit_price", unitPrice)
        put("source", source)
        put("source_id", sourceId ?: JSONObject.NULL)
        put("reason", reason ?: JSONObject.NULL)
        put("valid_until", validUntil ?: JSONObject.NULL)
    }
}
