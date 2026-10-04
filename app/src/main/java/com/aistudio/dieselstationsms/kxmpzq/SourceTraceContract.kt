package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONObject

/**
 * Canonical contract for every report -> transaction -> SQLite -> operational-document hop.
 * An operational screen must never trust an id without this verified contract.
 */
object SourceTraceContract {
    const val VERSION = 1
    const val KEY_SOURCE_TABLE = "source_table"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_REFERENCE_CODE = "reference_code"
    const val KEY_STATION_ID = "station_id"
    const val KEY_DATE_SCOPE = "date_scope"
    const val KEY_DOCUMENT_TYPE = "document_type"

    data class Request(
        val sourceTable: String,
        val sourceId: Long,
        val referenceCode: String,
        val stationId: Int,
        val fromDate: String,
        val toDate: String,
        val documentType: String
    ) {
        fun toJson() = JSONObject().apply {
            put("contract_version", VERSION)
            put(KEY_SOURCE_TABLE, sourceTable)
            put(KEY_SOURCE_ID, sourceId)
            put(KEY_REFERENCE_CODE, referenceCode)
            put(KEY_STATION_ID, stationId)
            put(KEY_DATE_SCOPE, JSONObject().apply {
                put("from_date", fromDate)
                put("to_date", toDate)
            })
            put(KEY_DOCUMENT_TYPE, documentType)
        }
    }

    fun parse(json: JSONObject): Request {
        val sourceTable = json.optString(KEY_SOURCE_TABLE).trim().lowercase()
        val sourceId = json.optLong(KEY_SOURCE_ID, 0L)
        val referenceCode = json.optString(KEY_REFERENCE_CODE).trim()
        val stationId = json.optInt(KEY_STATION_ID, 0)
        val scope = json.optJSONObject(KEY_DATE_SCOPE)
        val from = scope?.optString("from_date", "")?.trim()
            ?: json.optString("from_date", "").trim()
        val to = scope?.optString("to_date", "")?.trim()
            ?: json.optString("to_date", "").trim()
        val documentType = json.optString(KEY_DOCUMENT_TYPE).trim().lowercase()

        require(sourceTable.isNotBlank()) { "source_table مطلوب" }
        require(sourceId > 0L) { "source_id مطلوب" }
        require(referenceCode.isNotBlank()) { "reference_code مطلوب" }
        require(stationId > 0) { "station_id مطلوب" }
        require(documentType.isNotBlank()) { "document_type مطلوب" }
        val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
        if (from.isNotBlank()) require(datePattern.matches(from)) { "date_scope.from_date غير صالح" }
        if (to.isNotBlank()) require(datePattern.matches(to)) { "date_scope.to_date غير صالح" }
        if (from.isNotBlank() && to.isNotBlank()) require(from <= to) { "date_scope غير صالح" }

        return Request(sourceTable, sourceId, referenceCode, stationId, from, to, documentType)
    }

    fun verifiedJson(request: Request, documentDate: String, screen: String, origin: JSONObject): JSONObject =
        request.toJson().apply {
            put("verified", true)
            put("document_date", documentDate)
            put("operational_screen", screen)
            put("sqlite_origin", origin)
        }
}
