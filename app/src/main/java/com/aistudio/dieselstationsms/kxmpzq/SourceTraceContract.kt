package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONObject

/**
 * Canonical contract for report drill-down.
 * A document must never be opened from an operational ID alone.
 */
object SourceTraceContract {
    const val VERSION = 2

    data class DateScope(val from: String, val to: String) {
        fun toJson() = JSONObject().apply {
            put("from_date", from)
            put("to_date", to)
        }
    }

    data class Contract(
        val sourceTable: String,
        val sourceId: Long,
        val referenceCode: String,
        val stationId: Int,
        val dateScope: DateScope,
        val documentType: String,
        val reportContext: JSONObject
    ) {
        fun toJson() = JSONObject().apply {
            put("contract_version", VERSION)
            put("source_table", sourceTable)
            put("source_id", sourceId)
            put("reference_code", referenceCode)
            put("station_id", stationId)
            put("date_scope", dateScope.toJson())
            put("document_type", documentType)
            put("report_context", reportContext)
        }
    }

    fun requireContract(json: JSONObject, currentStationId: Int): Contract {
        require(currentStationId > 0) { "معرف المحطة الحالية غير صالح" }
        require(json.optInt("contract_version", 0) == VERSION) { "إصدار Source Trace Contract غير متوافق" }
        val table = json.optString("source_table").trim().lowercase()
        val id = json.optLong("source_id", 0L)
        val reference = json.optString("reference_code").trim()
        val station = json.optInt("station_id", 0)
        val type = json.optString("document_type").trim().lowercase()
        val scope = json.optJSONObject("date_scope") ?: throw IllegalArgumentException("date_scope مطلوب")
        val from = scope.optString("from_date", "").trim()
        val to = scope.optString("to_date", "").trim()
        require(table.isNotEmpty()) { "source_table مطلوب" }
        require(id > 0L) { "source_id مطلوب" }
        require(reference.isNotEmpty()) { "reference_code مطلوب" }
        require(station == currentStationId) { "المصدر خارج نطاق المحطة الحالية" }
        require(type.isNotEmpty()) { "document_type مطلوب" }
        validateDate(from, "from_date")
        validateDate(to, "to_date")
        if (from.isNotEmpty() && to.isNotEmpty()) require(from <= to) { "date_scope غير صالح" }
        return Contract(table, id, reference, station, DateScope(from, to), type, json.optJSONObject("report_context") ?: JSONObject())
    }

    private fun validateDate(value: String, field: String) {
        if (value.isNotEmpty()) require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "$field غير صالح" }
    }

    fun verified(contract: Contract, documentDate: String, screen: String, origin: JSONObject): JSONObject =
        contract.toJson().apply {
            put("success", true)
            put("verified", true)
            put("document_date", documentDate)
            put("operational_screen", screen)
            put("sqlite_origin", origin)
        }
}
