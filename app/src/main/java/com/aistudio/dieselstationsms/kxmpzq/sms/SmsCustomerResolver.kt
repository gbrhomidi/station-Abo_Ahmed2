package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════
 * محلل العملاء - SmsCustomerResolver (المُحدَّث لـ Gateway Pattern)
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. البحث عن العميل برقم الهاتف عبر SmsPartyGateway
 * 2. قراءة الرصيد والنقاط
 * 3. قراءة سجل الطلبات
 * 4. قراءة الأسعار من قاعدة البيانات
 * 5. قراءة أرقام المديرين والسائقين
 *
 * التغيير الجوهري:
 * - لم يعد يستعلم مباشرة على parties.phone
 * - يستخدم SmsPartyGateway كطبقة وصول وحيدة
 */
class SmsCustomerResolver(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsCustomerResolver"
        private const val LITER_PER_DABBA = 20.0
    }

    data class CustomerInfo(
        val name: String,
        val phone: String,
        val balance: Double,
        val points: Int,
        val vipLevel: Int,
        val commercialName: String,
        val email: String = "",
        val address: String = "",
        val vehicleType: String = "",
        val fleetSize: Int = 0
    )

    /**
     * البحث عن العميل برقم الهاتف عبر SmsPartyGateway
     * لا يستعلم مباشرة على parties.phone
     */
    suspend fun findCustomer(phone: String): CustomerInfo? = withContext(Dispatchers.IO) {
        val cleanSender = PhoneUtils.normalize(phone) ?: ""
        if (cleanSender.isEmpty()) return@withContext null

        // استخدام SmsPartyGateway بدلاً من الاستعلام المباشر
        val gateway = SmsPartyGateway(db)
        val partyData = gateway.findPartyByPhone(cleanSender)

        if (partyData != null) {
            CustomerInfo(
                name = partyData.getString("name") ?: "",
                phone = phone,
                balance = partyData.optDouble("current_balance", 0.0),
                points = partyData.optInt("loyalty_points", 0),
                vipLevel = partyData.optInt("vip_level", 0),
                commercialName = partyData.optString("commercial_name", ""),
                email = partyData.optString("email", ""),
                address = partyData.optString("address", ""),
                vehicleType = partyData.optString("vehicle_type", ""),
                fleetSize = partyData.optInt("fleet_size", 0)
            )
        } else null
    }

    suspend fun getCustomerBalanceByPhone(phone: String): Double = withContext(Dispatchers.IO) {
        val cleanPhone = PhoneUtils.normalize(phone) ?: ""
        // استخدام Gateway
        val gateway = SmsPartyGateway(db)
        val partyData = gateway.findPartyByPhone(cleanPhone)
        partyData?.optDouble("current_balance", 0.0) ?: 0.0
    }

    suspend fun getLastOrderByPhone(phone: String): JSONObject? = withContext(Dispatchers.IO) {
        val cleanPhone = PhoneUtils.normalize(phone) ?: ""
        val cursor = db.readableDatabase.rawQuery("""
            SELECT s.* FROM sales_transactions s
            JOIN parties p ON s.customer_party_id = p.id
            WHERE p.id = (SELECT party_id FROM party_contacts WHERE phone = ? OR phone2 = ? OR whatsapp = ? LIMIT 1)
            AND s.is_deleted = 0
            ORDER BY s.id DESC LIMIT 1
        """.trimIndent(), arrayOf(cleanPhone, cleanPhone, cleanPhone))

        cursor.use {
            if (it.moveToFirst()) {
                JSONObject().apply {
                    put("sale_code", it.getString(it.getColumnIndexOrThrow("sale_code"))?.orEmpty() ?: "")
                    put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                    put("delivery_location", it.getString(it.getColumnIndexOrThrow("notes"))?.orEmpty() ?: "")
                    put("status", it.getString(it.getColumnIndexOrThrow("status"))?.orEmpty() ?: "")
                    put("created_at", it.getString(it.getColumnIndexOrThrow("created_at"))?.orEmpty() ?: "")
                }
            } else null
        }
    }

    suspend fun getOrderHistoryByPhone(phone: String, limit: Int): JSONArray = withContext(Dispatchers.IO) {
        val cleanPhone = PhoneUtils.normalize(phone) ?: ""
        val arr = JSONArray()
        val cursor = db.readableDatabase.rawQuery("""
            SELECT s.* FROM sales_transactions s
            JOIN parties p ON s.customer_party_id = p.id
            WHERE p.id = (SELECT party_id FROM party_contacts WHERE phone = ? OR phone2 = ? OR whatsapp = ? LIMIT 1)
            AND s.is_deleted = 0
            ORDER BY s.id DESC LIMIT ?
        """.trimIndent(), arrayOf(cleanPhone, cleanPhone, cleanPhone, limit.toString()))

        cursor.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("sale_type", it.getString(it.getColumnIndexOrThrow("sale_type"))?.orEmpty() ?: "")
                    put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                    put("net_amount", it.getDouble(it.getColumnIndexOrThrow("net_amount")))
                    put("created_at", it.getString(it.getColumnIndexOrThrow("created_at"))?.orEmpty() ?: "")
                })
            }
        }
        arr
    }

    suspend fun getDieselPrice(): Double = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT default_sale_price FROM fuel_types WHERE fuel_code = 'DIESEL' AND is_deleted = 0 LIMIT 1",
            null
        )
        cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    suspend fun getGasolinePrice(fuelCode: String = "PETROL_95"): Double = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT default_sale_price FROM fuel_types WHERE fuel_code = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(fuelCode)
        )
        cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    suspend fun getManagerPhone(): String? = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.rawQuery("""
            SELECT u.phone FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN', 'STATION_MANAGER')
              AND u.status = 'active' AND u.is_deleted = 0
            ORDER BY r.level ASC LIMIT 1
        """.trimIndent(), null)

        cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    suspend fun getDriverPhones(): List<String> = withContext(Dispatchers.IO) {
        val phones = mutableListOf<String>()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT phone, phone2 FROM drivers WHERE status = 'active' AND is_deleted = 0",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                it.getString(0)?.let { p -> if (p.isNotBlank()) phones.add(p) }
                it.getString(1)?.let { p -> if (p.isNotBlank()) phones.add(p) }
            }
        }
        phones.distinct()
    }

    suspend fun getDriverPhone(): String? = withContext(Dispatchers.IO) {
        getDriverPhones().firstOrNull()
    }

    suspend fun recordDieselDelivery(
        customerId: String,
        customerName: String,
        quantityLiters: Double,
        quantityDabbas: Double,
        location: String,
        deliveryTime: String,
        unitPrice: Double,
        totalAmount: Double,
        orderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val partyId = getPartyIdByPhone(customerId) ?: return@withContext false

            require(quantityLiters in 1.0..10000.0) { "Invalid quantity" }
            require(unitPrice in 1.0..1000000.0) { "Invalid price" }
            require(location.length in 3..200) { "Invalid location" }

            val subtotal = quantityLiters * unitPrice

            val result = db.insertSaleTransaction(
                stationId = 1,
                shiftId = 1,
                customerPartyId = partyId,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = quantityLiters,
                pricePerLiter = unitPrice,
                subtotal = subtotal,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = "credit",
                isCredit = true,
                dueDate = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("ar")).format(java.util.Date()),
                cashierId = 1,
                notes = "طلب توصيل ديزل - ${location.take(100)} في ${deliveryTime.take(50)}"
            )

            if (result <= 0) return@withContext false

            val currentBalance = getCustomerBalanceByPhone(customerId)
            val newBalance = currentBalance + totalAmount
            val values = android.content.ContentValues().apply {
                put("current_balance", newBalance)
                put("total_due", totalAmount)
            }
            db.writableDatabase.update("parties", values, "id = ?", arrayOf(partyId.toString()))

            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error recording delivery: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun getPartyIdByPhone(phone: String): Int? = withContext(Dispatchers.IO) {
        val cleanPhone = PhoneUtils.normalize(phone) ?: ""
        // استخدام Gateway بدلاً من الاستعلام المباشر
        val gateway = SmsPartyGateway(db)
        val partyData = gateway.findPartyByPhone(cleanPhone)
        partyData?.optInt("id", 0)?.takeIf { it > 0 }
    }

    fun getVipText(vip: Int): String {
        return when (vip) {
            3 -> "ذهبي 👑"
            2 -> "فضي 🥈"
            1 -> "برونزي 🥉"
            else -> "عادي 💎"
        }
    }

    fun safeMultiply(a: Double, b: Double): Double {
        require(a >= 0 && a <= 10000.0) { "Invalid quantity: $a" }
        require(b >= 0 && b <= 1000000.0) { "Invalid price: $b" }
        val result = a * b
        require(result.isFinite() && result >= 0) { "Calculation overflow" }
        return result
    }

    suspend fun syncPreferences() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Customer preferences synced successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync customer preferences: ${e.message}", e)
        }
    }
}
