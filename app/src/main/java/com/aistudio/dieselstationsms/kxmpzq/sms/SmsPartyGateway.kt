package com.aistudio.dieselstationsms.kxmpzq.sms

import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════
 * بوابة الأطراف - SmsPartyGateway
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. البحث عن العميل برقم الهاتف
 * 2. الحصول على الأسعار الحالية
 * 3. التحقق من التوفر
 * 4. إنشاء/إلغاء الطلبات
 * 5. إنشاء تذاكر الشكاوى
 */
class SmsPartyGateway(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsPartyGateway"
        private val DEFAULT_PRICES = mapOf(
            "diesel" to 2.18,
            "gasoline" to 2.33,
            "kerosene" to 1.65
        )
    }

    data class OrderInfo(
        val id: String,
        val customerPhone: String,
        val product: String,
        val quantity: Double,
        val location: String,
        val status: String,
        val createdAt: Long,
        val estimatedDelivery: String = ""
    )

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. البحث عن العميل ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun findPartyByPhone(phone: String): PartyInfo? = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                """
                SELECT * FROM parties
                WHERE phone = ? OR phone LIKE ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(phone, "%$phone%")
            )

            cursor.use {
                if (it.moveToFirst()) {
                    PartyInfo(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        phone = it.getString(it.getColumnIndexOrThrow("phone")),
                        email = it.getString(it.getColumnIndexOrThrow("email")) ?: "",
                        address = it.getString(it.getColumnIndexOrThrow("address")) ?: "",
                        type = it.getString(it.getColumnIndexOrThrow("type")) ?: "customer"
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding party: ${e.message}", e)
            null
        }
    }

    suspend fun findPartyByName(name: String): List<PartyInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PartyInfo>()
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT * FROM parties WHERE name LIKE ? LIMIT 10",
                arrayOf("%$name%")
            )
            cursor.use {
                while (it.moveToNext()) {
                    results.add(PartyInfo(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        phone = it.getString(it.getColumnIndexOrThrow("phone")),
                        email = it.getString(it.getColumnIndexOrThrow("email")) ?: "",
                        address = it.getString(it.getColumnIndexOrThrow("address")) ?: "",
                        type = it.getString(it.getColumnIndexOrThrow("type")) ?: "customer"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding parties: ${e.message}", e)
        }
        results
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. الأسعار ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getCurrentPrice(product: String): Double = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT price FROM product_prices WHERE product = ? ORDER BY updated_at DESC LIMIT 1",
                arrayOf(product)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    it.getDouble(it.getColumnIndexOrThrow("price"))
                } else {
                    DEFAULT_PRICES[product] ?: 0.0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting price: ${e.message}", e)
            DEFAULT_PRICES[product] ?: 0.0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. التوفر ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun checkAvailability(product: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT available FROM product_inventory WHERE product = ? LIMIT 1",
                arrayOf(product)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    it.getInt(it.getColumnIndexOrThrow("available")) == 1
                } else true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking availability: ${e.message}", e)
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. وقت التوصيل ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun getEstimatedDeliveryTime(location: String): String = withContext(Dispatchers.IO) {
        // تقدير وقت التوصيل بناءً على الموقع
        // يمكن استبداله بمنطق أكثر تعقيداً
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT estimated_minutes FROM delivery_estimates WHERE location LIKE ? LIMIT 1",
                arrayOf("%$location%")
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val minutes = it.getInt(it.getColumnIndexOrThrow("estimated_minutes"))
                    formatDuration(minutes)
                } else {
                    "30-60 دقيقة"
                }
            }
        } catch (e: Exception) {
            "30-60 دقيقة"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. الطلبات ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun submitOrder(phone: String, draft: SmsConversationManager.OrderDraft): String = withContext(Dispatchers.IO) {
        val orderId = "ORD-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()

        val values = android.content.ContentValues().apply {
            put("id", orderId)
            put("customer_phone", phone)
            put("product", draft.product)
            put("quantity_liters", draft.quantityLiters)
            put("quantity_dabbas", draft.quantityDabbas)
            put("delivery_location", draft.deliveryLocation)
            put("delivery_time", draft.deliveryTime)
            put("unit_price", draft.unitPrice)
            put("total_amount", draft.totalAmount)
            put("status", "pending")
            put("created_at", now)
            put("updated_at", now)
        }

        try {
            db.writableDatabase.insert("orders", null, values)
            Log.i(TAG, "Order created: $orderId for $phone")
            orderId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}", e)
            throw e
        }
    }

    suspend fun getOrderStatus(orderId: String): String = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT status FROM orders WHERE id = ? LIMIT 1",
                arrayOf(orderId)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow("status")) ?: "unknown"
                } else "not_found"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting order status: ${e.message}", e)
            "error"
        }
    }

    suspend fun getCustomerLatestOrderStatus(phone: String): String = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT status FROM orders WHERE customer_phone = ? ORDER BY created_at DESC LIMIT 1",
                arrayOf(phone)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow("status")) ?: "no_orders"
                } else "no_orders"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting latest order status: ${e.message}", e)
            "error"
        }
    }

    suspend fun getCustomerLatestOrder(phone: String): OrderInfo? = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                """
                SELECT * FROM orders
                WHERE customer_phone = ?
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
                arrayOf(phone)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    OrderInfo(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        customerPhone = it.getString(it.getColumnIndexOrThrow("customer_phone")),
                        product = it.getString(it.getColumnIndexOrThrow("product")),
                        quantity = it.getDouble(it.getColumnIndexOrThrow("quantity_liters")),
                        location = it.getString(it.getColumnIndexOrThrow("delivery_location")) ?: "",
                        status = it.getString(it.getColumnIndexOrThrow("status")) ?: "unknown",
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting latest order: ${e.message}", e)
            null
        }
    }

    suspend fun canCancelOrder(orderId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT status FROM orders WHERE id = ? LIMIT 1",
                arrayOf(orderId)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val status = it.getString(it.getColumnIndexOrThrow("status"))
                    status == "pending" || status == "draft"
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cancel eligibility: ${e.message}", e)
            false
        }
    }

    suspend fun cancelOrder(orderId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put("status", "cancelled")
                put("updated_at", System.currentTimeMillis())
            }
            val rows = db.writableDatabase.update("orders", values, "id = ?", arrayOf(orderId))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling order: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 6. الشكاوى ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun createComplaintTicket(phone: String, message: String, type: String): String = withContext(Dispatchers.IO) {
        val ticketId = "TICKET-${System.currentTimeMillis()}"
        val values = android.content.ContentValues().apply {
            put("id", ticketId)
            put("customer_phone", phone)
            put("type", type)
            put("message", message)
            put("status", "open")
            put("created_at", System.currentTimeMillis())
        }

        try {
            db.writableDatabase.insert("complaint_tickets", null, values)
            Log.i(TAG, "Complaint ticket created: $ticketId")
            ticketId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating complaint: ${e.message}", e)
            throw e
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Helpers ═══
    // ═══════════════════════════════════════════════════════════════

    private fun formatDuration(minutes: Int): String {
        return when {
            minutes < 60 -> "$minutes دقيقة"
            minutes == 60 -> "ساعة واحدة"
            minutes < 120 -> "ساعة و${minutes - 60} دقيقة"
            else -> "${minutes / 60} ساعات"
        }
    }
}

data class PartyInfo(
    val id: String,
    val name: String,
    val phone: String,
    val email: String = "",
    val address: String = "",
    val type: String = "customer"
)
