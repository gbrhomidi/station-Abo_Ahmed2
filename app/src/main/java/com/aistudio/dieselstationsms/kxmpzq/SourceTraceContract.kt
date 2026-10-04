package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONObject

/**
 * Immutable boundary contract for report drill-down.
 * Operational screens must receive a verified contract, never a bare database id.
 */
object SourceTraceContract {
    const val VERSION = 2

    const val SOURCE_TABLE = "source_table"
    const val SOURCE_ID = "source_id"
    const val REFERENCE_CODE = "reference_code"
    const val STATION_ID = "station_id"
    const val DATE_SCOPE = "date_scope"
    const val DOCUMENT_TYPE = "document_type"

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
        val reportContext: JSONObject = JSONObject()
    ) {
        fun toJson() = JSONObject().apply {
            put("contract_version", VERSION)
            put(SOURCE_TABLE, sourceTable)
            put(SOURCE_ID, sourceId)
            put(REFERENCE_CODE, referenceCode)
            put(STATION_ID, stationId)
            put(DATE_SCOPE, dateScope.toJson())
            put(DOCUMENT_TYPE, documentType)
            put("report_context", reportContext)
        }
    }

    fun requireContract(json: JSONObject, currentStationId: Int): Contract {
        require(currentStationId > 0) { "معرف المحطة الحالية غير صالح" }
        val version = json.optInt("contract_version", 0)
        require(version == VERSION) { "إصدار Source Trace Contract غير متوافق" }

        val table = json.optString(SOURCE_TABLE).trim().lowercase()
        val id = json.optLong(SOURCE_ID, 0L)
        val reference = json.optString(REFERENCE_CODE).trim()
        val station = json.optInt(STATION_ID, 0)
        val type = json.optString(DOCUMENT_TYPE).trim().lowercase()
        val scope = json.optJSONObject(DATE_SCOPE)
            ?: throw IllegalArgumentException("date_scope مطلوب")
        val from = scope.optString("from_date", "").trim()
        val to = scope.optString("to_date", "").trim()

        require(table.isNotEmpty()) { "source_table مطلوب" }
        require(id > 0L) { "source_id مطلوب" }
        require(reference.isNotEmpty()) { "reference_code مطلوب" }
        require(station == currentStationId) { "source station لا تطابق المحطة الحالية" }
        require(type.isNotEmpty()) { "document_type مطلوب" }
        validateDate(from, "from_date")
        validateDate(to, "to_date")
        if (from.isNotEmpty() && to.isNotEmpty()) require(from <= to) { "date_scope غير صالح" }

        return Contract(table, id, reference, station, DateScope(from, to), type,
            json.optJSONObject("report_context") ?: JSONObject())
    }

    private fun validateDate(value: String, field: String) {
        if (value.isNotEmpty()) {
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "$field غير صالح" }
        }
    }

    fun verified(contract: Contract, documentDate: String, screen: String, origin: JSONObject): JSONObject =
        contract.toJson().apply {
            put("verified", true)
            put("document_date", documentDate)
            put("operational_screen", screen)
            put("sqlite_origin", origin)
        }
}
