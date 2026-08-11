package com.aistudio.dieselstationsms.kxmpzq.sms

import android.database.Cursor
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils

/**
 * Single source of truth for resolving a party from an SMS phone number.
 */
class SmsPartyGateway(private val db: DatabaseHelper) {

    data class PartyMatch(
        val partyId: Int,
        val phone: String,
        val displayName: String,
        val commercialName: String,
        val email: String,
        val address: String,
        val fleetSize: Int,
        val balance: Double,
        val points: Int,
        val loyaltyTier: String
    )

    // تخزين مؤقت - PRAGMA مرة واحدة فقط
    private val legacyPhoneExists: Boolean by lazy { columnExists("parties", "phone") }
    private val legacyPhone2Exists: Boolean by lazy { columnExists("parties", "phone2") }

    private fun normalized(phone: String?): String? = PhoneUtils.normalize(phone)

    private fun columnExists(table: String, column: String): Boolean {
        return try {
            db.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIndex = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (nameIndex >= 0 && c.getString(nameIndex) == column) return true
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun find(phone: String?): PartyMatch? {
        val p = normalized(phone) ?: return null
        val database = db.readableDatabase

        // Canonical lookup
        database.rawQuery(
            """
            SELECT
                p.id AS party_id,
                COALESCE(pc.phone, pc.phone2) AS resolved_phone,
                COALESCE(NULLIF(p.commercial_name_ar,''), p.commercial_name, p.legal_name, '') AS display_name,
                COALESCE(p.commercial_name, p.legal_name, '') AS commercial_name,
                COALESCE(pc.email, '') AS email,
                COALESCE(
                    (SELECT pa.address_line1 FROM party_addresses pa
                     WHERE pa.party_id = p.id AND pa.is_deleted = 0
                     ORDER BY pa.is_default DESC, pa.id DESC LIMIT 1), ''
                ) AS address,
                COALESCE(p.fleet_size, 0) AS fleet_size,
                COALESCE(p.current_balance, 0) AS current_balance,
                COALESCE(p.loyalty_points, 0) AS loyalty_points,
                COALESCE(p.loyalty_tier, 'bronze') AS loyalty_tier
            FROM parties p
            INNER JOIN party_contacts pc ON pc.party_id = p.id
            WHERE p.is_deleted = 0 AND p.is_active = 1
              AND pc.is_deleted = 0 AND pc.is_active = 1
              AND (pc.phone = ? OR pc.phone2 = ? OR pc.whatsapp = ?)
            ORDER BY pc.is_primary DESC, pc.id ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(p, p, p)
        ).use { c ->
            if (c.moveToFirst()) return c.toPartyMatch()
        }

        // Compatibility fallback
        if (legacyPhoneExists) {
            val sql = if (legacyPhone2Exists) {
                """
                SELECT id AS party_id,
                       COALESCE(NULLIF(commercial_name_ar,''), commercial_name, legal_name, '') AS display_name,
                       COALESCE(commercial_name, legal_name, '') AS commercial_name,
                       COALESCE(phone, phone2, '') AS resolved_phone,
                       current_balance, loyalty_points,
                       COALESCE(loyalty_tier, 'bronze') AS loyalty_tier,
                       fleet_size
                FROM parties
                WHERE is_deleted = 0 AND is_active = 1
                  AND (phone = ? OR phone2 = ?)
                LIMIT 1
                """.trimIndent()
            } else {
                """
                SELECT id AS party_id,
                       COALESCE(NULLIF(commercial_name_ar,''), commercial_name, legal_name, '') AS display_name,
                       COALESCE(commercial_name, legal_name, '') AS commercial_name,
                       COALESCE(phone, '') AS resolved_phone,
                       current_balance, loyalty_points,
                       COALESCE(loyalty_tier, 'bronze') AS loyalty_tier,
                       fleet_size
                FROM parties
                WHERE is_deleted = 0 AND is_active = 1
                  AND phone = ?
                LIMIT 1
                """.trimIndent()
            }
            val args = if (legacyPhone2Exists) arrayOf(p, p) else arrayOf(p)
            database.rawQuery(sql, args).use { c ->
                if (c.moveToFirst()) {
                    PartyMatch(
                        partyId = c.getInt(c.getColumnIndexOrThrow("party_id")),
                        phone = c.getString(c.getColumnIndexOrThrow("resolved_phone")) ?: p,
                        displayName = c.getString(c.getColumnIndexOrThrow("display_name")) ?: "",
                        commercialName = c.getString(c.getColumnIndexOrThrow("commercial_name")) ?: "",
                        email = "",
                        address = "",
                        fleetSize = c.getInt(c.getColumnIndexOrThrow("fleet_size")),
                        balance = c.getDouble(c.getColumnIndexOrThrow("current_balance")),
                        points = c.getInt(c.getColumnIndexOrThrow("loyalty_points")),
                        loyaltyTier = c.getString(c.getColumnIndexOrThrow("loyalty_tier")) ?: "bronze"
                    )
                } else null
            }
        } else null
    }

    /** استعلام مباشر - لا يُنفِّذ find() كاملاً */
    fun getPartyId(phone: String?): Int? {
        val p = normalized(phone) ?: return null
        return db.readableDatabase.rawQuery(
            "SELECT p.id FROM parties p " +
            "JOIN party_contacts pc ON pc.party_id = p.id " +
            "WHERE pc.phone = ? AND p.is_deleted = 0 LIMIT 1",
            arrayOf(p)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    }

    /** استعلام مباشر - لا يُنفِّذ find() كاملاً */
    fun getBalance(phone: String?): Double {
        val p = normalized(phone) ?: return 0.0
        return db.readableDatabase.rawQuery(
            "SELECT COALESCE(p.current_balance, 0) FROM parties p " +
            "JOIN party_contacts pc ON pc.party_id = p.id " +
            "WHERE pc.phone = ? AND p.is_deleted = 0 LIMIT 1",
            arrayOf(p)
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    private fun Cursor.toPartyMatch(): PartyMatch {
        return PartyMatch(
            partyId = getInt(getColumnIndexOrThrow("party_id")),
            phone = getString(getColumnIndexOrThrow("resolved_phone")) ?: "",
            displayName = getString(getColumnIndexOrThrow("display_name")) ?: "",
            commercialName = getString(getColumnIndexOrThrow("commercial_name")) ?: "",
            email = getString(getColumnIndexOrThrow("email")) ?: "",
            address = getString(getColumnIndexOrThrow("address")) ?: "",
            fleetSize = getInt(getColumnIndexOrThrow("fleet_size")),
            balance = getDouble(getColumnIndexOrThrow("current_balance")),
            points = getInt(getColumnIndexOrThrow("loyalty_points")),
            loyaltyTier = getString(getColumnIndexOrThrow("loyalty_tier")) ?: "bronze"
        )
    }
}
