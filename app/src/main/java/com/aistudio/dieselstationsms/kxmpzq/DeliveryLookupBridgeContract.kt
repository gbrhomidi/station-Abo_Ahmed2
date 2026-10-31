package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable JSON boundary for the two independent delivery lookups.
 *
 * Each method executes and catches only its own query. A failure in one read never
 * changes the state or availability of the other lookup.
 */
internal object DeliveryLookupBridgeContract {

    fun savedLocations(
        helper: DatabaseHelper,
        stationId: Int,
        queryRunner: DeliveryLookupQueryRunner = DefaultDeliveryLookupQueryRunner,
        onError: (Exception) -> Unit = {}
    ): String = readArray(
        fallbackMessage = "تعذر استرجاع مواقع التوصيل المحفوظة",
        onError = onError
    ) {
        DeliveryFeeAccounting.savedDeliveryLocations(helper, stationId, queryRunner)
    }

    fun recentCustomerIds(
        helper: DatabaseHelper,
        stationId: Int,
        queryRunner: DeliveryLookupQueryRunner = DefaultDeliveryLookupQueryRunner,
        onError: (Exception) -> Unit = {}
    ): String = readArray(
        fallbackMessage = "تعذر استرجاع ترتيب العملاء حسب أحدث المبيعات",
        onError = onError
    ) {
        DeliveryFeeAccounting.recentCustomerSaleIds(helper, stationId, queryRunner)
    }

    private inline fun readArray(
        fallbackMessage: String,
        onError: (Exception) -> Unit,
        read: () -> JSONArray
    ): String {
        return try {
            JSONObject().apply {
                put("success", true)
                put("data", read())
            }.toString()
        } catch (exception: Exception) {
            runCatching { onError(exception) }
            JSONObject().apply {
                put("success", false)
                put("error", exception.message?.takeIf { it.isNotBlank() } ?: fallbackMessage)
            }.toString()
        }
    }
}
