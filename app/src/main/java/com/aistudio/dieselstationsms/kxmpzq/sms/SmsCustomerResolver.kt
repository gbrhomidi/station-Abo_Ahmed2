package com.aistudio.dieselstationsms.kxmpzq.sms

import android.database.Cursor
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════
 * محلل العملاء - SmsCustomerResolver
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. البحث عن العميل برقم الهاتف
 * 2. البحث عن العميل بالاسم
 * 3. استخراج معلومات العميل
 * 4. إنشاء عميل جديد إذا لم يوجد
 */
class SmsCustomerResolver(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsCustomerResolver"
    }

    data class CustomerInfo(
        val id: String = "",
        val name: String = "",
        val phone: String = "",
        val email: String = "",
        val address: String = "",
        val city: String = "",
        val country: String = "",
        val postalCode: String = "",
        val company: String = "",
        val taxId: String = "",
        val notes: String = "",
        val status: String = "active",
        val createdAt: Long = 0,
        val updatedAt: Long = 0
    )

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. البحث عن العميل برقم الهاتف ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun resolveCustomer(phone: String): CustomerInfo? = withContext(Dispatchers.IO) {
        val normalizedPhone = phone.replace(Regex("[^0-9+]"), "")

        try {
            val cursor = db.readableDatabase.rawQuery(
                """
                SELECT * FROM customers
                WHERE phone = ? OR phone LIKE ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(normalizedPhone, "%$normalizedPhone%")
            )

            cursor.use {
                if (it.moveToFirst()) {
                    return@withContext cursorToCustomer(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving customer by phone: ${e.message}", e)
        }

        null
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. البحث عن العميل بالاسم ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun findCustomerByName(name: String): List<CustomerInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CustomerInfo>()

        try {
            val cursor = db.readableDatabase.rawQuery(
                """
                SELECT * FROM customers
                WHERE name LIKE ?
                LIMIT 10
                """.trimIndent(),
                arrayOf("%$name%")
            )

            cursor.use {
                while (it.moveToNext()) {
                    results.add(cursorToCustomer(it))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding customer by name: ${e.message}", e)
        }

        results
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. إنشاء عميل جديد ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun createCustomer(
        name: String,
        phone: String,
        email: String = "",
        address: String = "",
        city: String = "",
        country: String = "",
        postalCode: String = "",
        company: String = "",
        taxId: String = "",
        notes: String = ""
    ): CustomerInfo = withContext(Dispatchers.IO) {
        val normalizedPhone = phone.replace(Regex("[^0-9+]"), "")
        val now = System.currentTimeMillis()

        val values = android.content.ContentValues().apply {
            put("name", name)
            put("phone", normalizedPhone)
            put("email", email)
            put("address", address)
            put("city", city)
            put("country", country)
            put("postal_code", postalCode)
            put("company", company)
            put("tax_id", taxId)
            put("notes", notes)
            put("status", "active")
            put("created_at", now)
            put("updated_at", now)
        }

        val id = db.writableDatabase.insert("customers", null, values)

        CustomerInfo(
            id = id.toString(),
            name = name,
            phone = normalizedPhone,
            email = email,
            address = address,
            city = city,
            country = country,
            postalCode = postalCode,
            company = company,
            taxId = taxId,
            notes = notes,
            status = "active",
            createdAt = now,
            updatedAt = now
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. تحديث معلومات العميل ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun updateCustomer(
        customerId: String,
        updates: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                updates.forEach { (key, value) ->
                    put(key, value)
                }
                put("updated_at", System.currentTimeMillis())
            }

            val rows = db.writableDatabase.update(
                "customers",
                values,
                "id = ?",
                arrayOf(customerId)
            )
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating customer: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. الحصول على جميع العملاء ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getAllCustomers(): List<CustomerInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CustomerInfo>()

        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT * FROM customers ORDER BY name ASC",
                null
            )

            cursor.use {
                while (it.moveToNext()) {
                    results.add(cursorToCustomer(it))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all customers: ${e.message}", e)
        }

        results
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 6. تحويل Cursor إلى CustomerInfo ═══
    // ═══════════════════════════════════════════════════════════════

    private fun cursorToCustomer(cursor: Cursor): CustomerInfo {
        return CustomerInfo(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            phone = cursor.getString(cursor.getColumnIndexOrThrow("phone")),
            email = cursor.getString(cursor.getColumnIndexOrThrow("email")) ?: "",
            address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: "",
            city = cursor.getString(cursor.getColumnIndexOrThrow("city")) ?: "",
            country = cursor.getString(cursor.getColumnIndexOrThrow("country")) ?: "",
            postalCode = cursor.getString(cursor.getColumnIndexOrThrow("postal_code")) ?: "",
            company = cursor.getString(cursor.getColumnIndexOrThrow("company")) ?: "",
            taxId = cursor.getString(cursor.getColumnIndexOrThrow("tax_id")) ?: "",
            notes = cursor.getString(cursor.getColumnIndexOrThrow("notes")) ?: "",
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "active",
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 7. مساعدون JSON (JSON Helpers) ═══
    // ═══════════════════════════════════════════════════════════════

    fun customerToJson(customer: CustomerInfo): JSONObject {
        return JSONObject().apply {
            put("id", customer.id)
            put("name", customer.name)
            put("phone", customer.phone)
            put("email", customer.email)
            put("address", customer.address)
            put("city", customer.city)
            put("country", customer.country)
            put("postal_code", customer.postalCode)
            put("company", customer.company)
            put("tax_id", customer.taxId)
            put("notes", customer.notes)
            put("status", customer.status)
            put("created_at", customer.createdAt)
            put("updated_at", customer.updatedAt)
        }
    }

    fun customersToJson(customers: List<CustomerInfo>): JSONArray {
        return JSONArray().apply {
            customers.forEach { customer ->
                put(customerToJson(customer))
            }
        }
    }

    fun jsonToCustomer(json: JSONObject): CustomerInfo {
        return CustomerInfo(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            phone = json.optString("phone", ""),
            email = json.optString("email", ""),
            address = json.optString("address", ""),
            city = json.optString("city", ""),
            country = json.optString("country", ""),
            postalCode = json.optString("postal_code", ""),
            company = json.optString("company", ""),
            taxId = json.optString("tax_id", ""),
            notes = json.optString("notes", ""),
            status = json.optString("status", "active"),
            createdAt = json.optLong("created_at", 0),
            updatedAt = json.optLong("updated_at", 0)
        )
    }

    fun jsonToCustomers(jsonArray: JSONArray): List<CustomerInfo> {
        val results = mutableListOf<CustomerInfo>()
        for (i in 0 until jsonArray.length()) {
            results.add(jsonToCustomer(jsonArray.getJSONObject(i)))
        }
        return results
    }
}
