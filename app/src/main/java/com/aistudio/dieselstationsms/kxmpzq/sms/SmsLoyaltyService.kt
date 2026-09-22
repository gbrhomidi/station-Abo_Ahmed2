package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import java.util.UUID

class SmsLoyaltyService(private val db: DatabaseHelper) {
    data class RedemptionResult(
        val success: Boolean,
        val points: Int,
        val value: Double,
        val balanceAfter: Int,
        val message: String
    )

    fun redeem(partyId: Int?, points: Int, referenceId: String): RedemptionResult {
        if (partyId == null || partyId <= 0 || points <= 0) {
            return RedemptionResult(false, points, 0.0, 0, "بيانات العميل أو النقاط غير صالحة")
        }
        val value = redemptionValue(points)
        if (value <= 0.0) {
            return RedemptionResult(false, points, 0.0, 0, "الحد الأدنى للاستبدال هو 500 نقطة")
        }

        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val before = database.rawQuery(
                "SELECT COALESCE(loyalty_points, 0) FROM parties WHERE id = ? AND is_deleted = 0 LIMIT 1",
                arrayOf(partyId.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            if (before < points) {
                database.endTransaction()
                return RedemptionResult(false, points, value, before.coerceAtLeast(0), "رصيد النقاط غير كافٍ")
            }
            val after = before - points
            val idempotencyKey = "REDEEM:$partyId:$referenceId:$points"
            val inserted = database.insertWithOnConflict(
                "sms_loyalty_transactions",
                null,
                ContentValues().apply {
                    put("transaction_id", UUID.randomUUID().toString())
                    put("idempotency_key", idempotencyKey)
                    put("party_id", partyId)
                    put("points", points)
                    put("balance_before", before)
                    put("balance_after", after)
                    put("transaction_type", "REDEEM")
                    put("reason", "SMS loyalty redemption")
                    put("reference_type", "SMS")
                    put("reference_id", referenceId)
                    put("created_at", System.currentTimeMillis())
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            if (inserted == -1L) {
                database.setTransactionSuccessful()
                return RedemptionResult(false, points, value, before, "تمت معالجة طلب الاستبدال سابقاً")
            }
            val updated = database.update(
                "parties",
                ContentValues().apply { put("loyalty_points", after) },
                "id = ? AND is_deleted = 0 AND loyalty_points >= ?",
                arrayOf(partyId.toString(), points.toString())
            )
            if (updated != 1) throw IllegalStateException("تعذر تحديث نقاط العميل")
            database.setTransactionSuccessful()
            return RedemptionResult(true, points, value, after, "تم تسجيل الاستبدال فعلياً")
        } finally {
            if (database.inTransaction()) database.endTransaction()
        }
    }

    private fun redemptionValue(points: Int): Double = when {
        points >= 5000 -> points * 0.1
        points >= 2000 -> points * 0.075
        points >= 1000 -> points * 0.06
        points >= 500 -> points * 0.05
        else -> 0.0
    }
}
