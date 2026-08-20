package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONObject

/**
 * Security Validator for WebView Bridge Inputs
 * Implements patterns from Clean Architecture to sanitize and validate
 * DTOs (Data Transfer Objects) before they reach the DatabaseHelper.
 */
object SecurityValidator {

    private const val MAX_OPERATIONAL_JSON_BYTES = 256 * 1024

    // List of keys that are not allowed to be modified via general CRUD
    private val FORBIDDEN_KEYS = setOf("id", "created_at", "updated_at", "audit_log", "balance", "total_due")

    // Financial, identity, and authorization tables require dedicated domain methods.
    private val BLOCKED_GENERAL_CRUD_TABLES = setOf(
        "fuel_sales", "payments", "invoices", "ledger", "stock_movements",
        "financial_idempotency_keys", "users", "permissions"
    )
    
    /**
     * Sanitizes a JSON string coming from WebView.
     * Ensures that read-only fields are stripped out before passing to the database.
     */
    fun sanitizeOperationalJson(jsonString: String): String {
        if (jsonString.toByteArray(Charsets.UTF_8).size > MAX_OPERATIONAL_JSON_BYTES) {
            return "{}"
        }
        return try {
            val json = JSONObject(jsonString)
            val keysToRemove = mutableListOf<String>()
            
            for (key in json.keys()) {
                if (FORBIDDEN_KEYS.contains(key.lowercase())) {
                    keysToRemove.add(key)
                }
            }
            
            for (key in keysToRemove) {
                json.remove(key)
            }
            
            json.toString()
        } catch (e: Exception) {
            // If it's not a valid JSON, return an empty object to prevent SQL injection or crashes
            "{}"
        }
    }
    
    /**
     * Validates if an operational table key is allowed to be accessed via the general bridge.
     * Financial and core tables should be blocked from general CRUD and use specific methods.
     */
    fun isTableAllowedForGeneralCrud(tableName: String): Boolean {
        val normalizedTableName = tableName.trim().lowercase()
        return normalizedTableName.isNotEmpty() &&
            normalizedTableName.matches(Regex("[a-z0-9_]+")) &&
            !BLOCKED_GENERAL_CRUD_TABLES.contains(normalizedTableName)
    }
}
