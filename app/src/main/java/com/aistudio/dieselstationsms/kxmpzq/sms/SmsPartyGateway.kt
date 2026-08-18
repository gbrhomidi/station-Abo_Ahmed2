package com.aistudio.dieselstationsms.kxmpzq.sms

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils

/**
 * ═══════════════════════════════════════════════════════════════════
 * بوابة الوصول إلى بيانات العميل من خلال رقم الهاتف
 * SmsPartyGateway
 * ═══════════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 * - حل هوية العميل/الطرف المرتبط برقم SMS.
 * - استخدام party_contacts كمصدر الهاتف الأساسي.
 * - دعم phone / phone2 / whatsapp.
 * - عدم افتراض وجود أعمدة قديمة في parties.
 * - توفير fallback آمن فقط عند وجود أعمدة الهاتف القديمة.
 *
 * مبدأ التصميم:
 * DatabaseHelper.kt هو مصدر الحقيقة لمخطط قاعدة البيانات.
 *
 * مهم:
 * هذه الطبقة لا تحتوي على منطق معالجة SMS أو الردود أو Rate Limiting.
 * مهمتها الوحيدة هي Party Resolution.
 */
class SmsPartyGateway(
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "SmsPartyGateway"

        private const val TABLE_PARTIES = "parties"
        private const val TABLE_PARTY_CONTACTS = "party_contacts"
        private const val TABLE_PARTY_ADDRESSES = "party_addresses"

        private const val DEFAULT_LOYALTY_TIER = "bronze"
    }

    /**
     * النتيجة الموحدة لعملية حل العميل.
     */
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

    // ═══════════════════════════════════════════════════════════════
    // Database helpers
    // ═══════════════════════════════════════════════════════════════

    private fun database(): SQLiteDatabase = db.readableDatabase

    /**
     * التحقق من وجود جدول قبل استخدامه.
     *
     * يمنع انهيار نظام SMS إذا كانت قاعدة البيانات قديمة أو ناقصة.
     */
    private fun tableExists(
        database: SQLiteDatabase,
        table: String
    ): Boolean {
        return database.rawQuery(
            """
            SELECT 1
            FROM sqlite_master
            WHERE type = 'table'
              AND name = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(table)
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    /**
     * التحقق من وجود عمود داخل جدول.
     *
     * يتم استخدام PRAGMA table_info بدل افتراض أن العمود موجود.
     */
    private fun columnExists(
        database: SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        if (!tableExists(database, table)) return false

        return database.rawQuery(
            "PRAGMA table_info($table)",
            null
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return@use false

            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    return@use true
                }
            }

            false
        }
    }

    /**
     * إرجاع أول عمود موجود من مجموعة أعمدة محتملة.
     */
    private fun firstExistingColumn(
        database: SQLiteDatabase,
        table: String,
        candidates: List<String>
    ): String? {
        return candidates.firstOrNull {
            columnExists(database, table, it)
        }
    }

    /**
     * إنشاء تعبير SQL آمن لقيمة نصية.
     */
    private fun textExpression(
        database: SQLiteDatabase,
        table: String,
        columns: List<String>,
        defaultValue: String = ""
    ): String {
        val existing = columns.filter {
            columnExists(database, table, it)
        }

        if (existing.isEmpty()) {
            return "''"
        }

        if (existing.size == 1) {
            return "COALESCE(${existing[0]}, '$defaultValue')"
        }

        val expressions = existing.joinToString(", ") {
            "NULLIF(TRIM(COALESCE($it, '')), '')"
        }

        return "COALESCE($expressions, '$defaultValue')"
    }

    /**
     * إنشاء تعبير رقمي آمن.
     */
    private fun numericExpression(
        database: SQLiteDatabase,
        table: String,
        columns: List<String>,
        defaultValue: String = "0"
    ): String {
        val existing = columns.filter {
            columnExists(database, table, it)
        }

        if (existing.isEmpty()) {
            return defaultValue
        }

        return "COALESCE(${existing.first()}, $defaultValue)"
    }

    /**
     * تطبيع رقم الهاتف.
     *
     * أي فشل أو قيمة فارغة تعتبر غير صالحة للمطابقة.
     */
    private fun normalized(phone: String?): String? {
        if (phone.isNullOrBlank()) return null

        return PhoneUtils.normalize(phone)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * مقارنة رقمين بعد التطبيع.
     *
     * تتم المقارنة بعدة مستويات:
     * 1. تطابق كامل.
     * 2. تطابق آخر 12 رقمًا عندما يكون الرقمان طويلين.
     * 3. تطابق آخر 9 أرقام كحل عملي لأرقام الشبكات المحلية.
     */
    private fun phoneMatches(
        incoming: String,
        candidate: String?
    ): Boolean {
        val normalizedCandidate = normalized(candidate)
            ?: return false

        if (incoming == normalizedCandidate) {
            return true
        }

        if (incoming.length >= 12 && normalizedCandidate.length >= 12) {
            if (incoming.takeLast(12) == normalizedCandidate.takeLast(12)) {
                return true
            }
        }

        if (incoming.length >= 9 && normalizedCandidate.length >= 9) {
            if (incoming.takeLast(9) == normalizedCandidate.takeLast(9)) {
                return true
            }
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // Main resolver
    // ═══════════════════════════════════════════════════════════════

    /**
     * البحث عن Party بواسطة رقم الهاتف.
     *
     * المصدر الأساسي:
     *     party_contacts
     *
     * fallback:
     *     parties.phone / parties.phone2
     *
     * لا يتم الاعتماد على parties.phone عندما لا يكون العمود موجودًا.
     */
    fun find(phone: String?): PartyMatch? {
        val incomingPhone = normalized(phone) ?: return null
        val database = database()

        if (!tableExists(database, TABLE_PARTIES)) {
            Log.e(TAG, "Cannot resolve SMS party: parties table does not exist")
            return null
        }

        // ───────────────────────────────────────────────────────────
        // 1. المصدر الأساسي: party_contacts
        // ───────────────────────────────────────────────────────────

        if (tableExists(database, TABLE_PARTY_CONTACTS)) {
            val canonicalMatch = findFromPartyContacts(
                database = database,
                incomingPhone = incomingPhone
            )

            if (canonicalMatch != null) {
                return canonicalMatch
            }
        }

        // ───────────────────────────────────────────────────────────
        // 2. fallback للتوافق مع المخططات القديمة
        // ───────────────────────────────────────────────────────────

        return findFromLegacyPartyPhones(
            database = database,
            incomingPhone = incomingPhone
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Canonical party_contacts resolver
    // ═══════════════════════════════════════════════════════════════

    private fun findFromPartyContacts(
        database: SQLiteDatabase,
        incomingPhone: String
    ): PartyMatch? {

        val phoneColumns = listOf("phone", "phone2", "whatsapp")
            .filter {
                columnExists(
                    database,
                    TABLE_PARTY_CONTACTS,
                    it
                )
            }

        if (phoneColumns.isEmpty()) {
            Log.w(
                TAG,
                "party_contacts exists but has no supported phone columns"
            )
            return null
        }

        val partyIdColumn = firstExistingColumn(
            database,
            TABLE_PARTIES,
            listOf("id")
        ) ?: return null

        val partyActiveCondition =
            if (columnExists(database, TABLE_PARTIES, "is_active")) {
                "AND COALESCE(p.is_active, 1) = 1"
            } else {
                ""
            }

        val partyDeletedCondition =
            if (columnExists(database, TABLE_PARTIES, "is_deleted")) {
                "AND COALESCE(p.is_deleted, 0) = 0"
            } else {
                ""
            }

        val contactActiveCondition =
            if (columnExists(database, TABLE_PARTY_CONTACTS, "is_active")) {
                "AND COALESCE(pc.is_active, 1) = 1"
            } else {
                ""
            }

        val contactDeletedCondition =
            if (columnExists(database, TABLE_PARTY_CONTACTS, "is_deleted")) {
                "AND COALESCE(pc.is_deleted, 0) = 0"
            } else {
                ""
            }

        val primaryOrder =
            if (columnExists(database, TABLE_PARTY_CONTACTS, "is_primary")) {
                "CASE WHEN COALESCE(pc.is_primary, 0) = 1 THEN 0 ELSE 1 END,"
            } else {
                ""
            }

        val contactIdOrder =
            if (columnExists(database, TABLE_PARTY_CONTACTS, "id")) {
                "pc.id ASC"
            } else {
                "p.id ASC"
            }

        val displayNameExpression = buildDisplayNameExpression(database)

        val commercialNameExpression = textExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf(
                "commercial_name",
                "legal_name"
            )
        )

        val emailExpression = textExpression(
            database = database,
            table = TABLE_PARTY_CONTACTS,
            columns = listOf("email")
        )

        val fleetSizeExpression = numericExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf("fleet_size")
        )

        val balanceExpression = numericExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf(
                "current_balance",
                "balance"
            )
        )

        val pointsExpression = numericExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf(
                "loyalty_points",
                "points"
            )
        )

        val loyaltyTierExpression = textExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf("loyalty_tier"),
            defaultValue = DEFAULT_LOYALTY_TIER
        )

        /*
         * لا نعتمد على equality SQL وحدها.
         *
         * يتم جلب جهات الاتصال النشطة ثم تتم المطابقة بواسطة
         * PhoneUtils.normalize() في Kotlin، مما يجعل:
         *
         * +967xxxxxxxxx
         * 967xxxxxxxxx
         * 0xxxxxxxxx
         * والأرقام ذات المسافات/الشرطات
         *
         * قابلة للمعالجة وفق منطق PhoneUtils.
         */
        val sql = """
            SELECT
                p.$partyIdColumn AS party_id,

                ${phoneColumns.joinToString(
                    ",\n                "
                ) { "pc.$it AS contact_$it" }},

                $displayNameExpression AS display_name,
                $commercialNameExpression AS commercial_name,
                $emailExpression AS email,

                $fleetSizeExpression AS fleet_size,
                $balanceExpression AS current_balance,
                $pointsExpression AS loyalty_points,
                $loyaltyTierExpression AS loyalty_tier

            FROM $TABLE_PARTIES p

            INNER JOIN $TABLE_PARTY_CONTACTS pc
                    ON pc.party_id = p.$partyIdColumn

            WHERE 1 = 1
              $partyDeletedCondition
              $partyActiveCondition
              $contactDeletedCondition
              $contactActiveCondition

            ORDER BY
                $primaryOrder
                $contactIdOrder
        """.trimIndent()

        return try {
            database.rawQuery(sql, null).use { cursor ->

                while (cursor.moveToNext()) {

                    val contactPhones = phoneColumns.mapNotNull { column ->
                        val index = cursor.getColumnIndex(
                            "contact_$column"
                        )

                        if (index >= 0 && !cursor.isNull(index)) {
                            cursor.getString(index)
                        } else {
                            null
                        }
                    }

                    val matchedPhone = contactPhones.firstOrNull {
                        phoneMatches(
                            incoming = incomingPhone,
                            candidate = it
                        )
                    } ?: continue

                    return PartyMatch(
                        partyId = cursor.getInt(
                            cursor.getColumnIndexOrThrow("party_id")
                        ),

                        phone = normalized(matchedPhone)
                            ?: incomingPhone,

                        displayName = cursor.safeString(
                            "display_name"
                        ),

                        commercialName = cursor.safeString(
                            "commercial_name"
                        ),

                        email = cursor.safeString(
                            "email"
                        ),

                        address = getPartyAddress(
                            database = database,
                            partyId = cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                    "party_id"
                                )
                            )
                        ),

                        fleetSize = cursor.safeInt(
                            "fleet_size"
                        ),

                        balance = cursor.safeDouble(
                            "current_balance"
                        ),

                        points = cursor.safeInt(
                            "loyalty_points"
                        ),

                        loyaltyTier = cursor.safeString(
                            "loyalty_tier"
                        ).ifBlank {
                            DEFAULT_LOYALTY_TIER
                        }
                    )
                }

                null
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Canonical party resolution failed: ${e.javaClass.simpleName}",
                e
            )
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Legacy parties.phone resolver
    // ═══════════════════════════════════════════════════════════════

    private fun findFromLegacyPartyPhones(
        database: SQLiteDatabase,
        incomingPhone: String
    ): PartyMatch? {

        if (!columnExists(database, TABLE_PARTIES, "phone")) {
            return null
        }

        val hasPhone2 = columnExists(
            database,
            TABLE_PARTIES,
            "phone2"
        )

        val phoneColumns = buildList {
            add("phone")
            if (hasPhone2) add("phone2")
        }

        val deletedCondition =
            if (columnExists(database, TABLE_PARTIES, "is_deleted")) {
                "AND COALESCE(is_deleted, 0) = 0"
            } else {
                ""
            }

        val activeCondition =
            if (columnExists(database, TABLE_PARTIES, "is_active")) {
                "AND COALESCE(is_active, 1) = 1"
            } else {
                ""
            }

        val displayNameExpression =
            buildDisplayNameExpression(database)

        val commercialNameExpression = textExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf(
                "commercial_name",
                "legal_name"
            )
        )

        val fleetSizeExpression = numericExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf("fleet_size")
        )

        val balanceExpression = numericExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf(
                "current_balance",
                "balance"
            )
        )

        val pointsExpression = numericExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf(
                "loyalty_points",
                "points"
            )
        )

        val loyaltyTierExpression = textExpression(
            database = database,
            table = TABLE_PARTIES,
            columns = listOf("loyalty_tier"),
            defaultValue = DEFAULT_LOYALTY_TIER
        )

        val sql = """
            SELECT
                id AS party_id,

                ${phoneColumns.joinToString(
                    ",\n                "
                ) { "$it AS legacy_$it" }},

                $displayNameExpression AS display_name,
                $commercialNameExpression AS commercial_name,
                $fleetSizeExpression AS fleet_size,
                $balanceExpression AS current_balance,
                $pointsExpression AS loyalty_points,
                $loyaltyTierExpression AS loyalty_tier

            FROM $TABLE_PARTIES

            WHERE 1 = 1
              $deletedCondition
              $activeCondition

            ORDER BY id ASC
        """.trimIndent()

        return try {
            database.rawQuery(sql, null).use { cursor ->

                while (cursor.moveToNext()) {

                    val candidatePhones = phoneColumns.mapNotNull { column ->
                        val index = cursor.getColumnIndex(
                            "legacy_$column"
                        )

                        if (index >= 0 && !cursor.isNull(index)) {
                            cursor.getString(index)
                        } else {
                            null
                        }
                    }

                    val matchedPhone = candidatePhones.firstOrNull {
                        phoneMatches(
                            incoming = incomingPhone,
                            candidate = it
                        )
                    } ?: continue

                    return PartyMatch(
                        partyId = cursor.getInt(
                            cursor.getColumnIndexOrThrow("party_id")
                        ),

                        phone = normalized(matchedPhone)
                            ?: incomingPhone,

                        displayName = cursor.safeString(
                            "display_name"
                        ),

                        commercialName = cursor.safeString(
                            "commercial_name"
                        ),

                        email = "",

                        address = getPartyAddress(
                            database = database,
                            partyId = cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                    "party_id"
                                )
                            )
                        ),

                        fleetSize = cursor.safeInt(
                            "fleet_size"
                        ),

                        balance = cursor.safeDouble(
                            "current_balance"
                        ),

                        points = cursor.safeInt(
                            "loyalty_points"
                        ),

                        loyaltyTier = cursor.safeString(
                            "loyalty_tier"
                        ).ifBlank {
                            DEFAULT_LOYALTY_TIER
                        }
                    )
                }

                null
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Legacy party resolution failed: ${e.javaClass.simpleName}",
                e
            )
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Display name
    // ═══════════════════════════════════════════════════════════════

    private fun buildDisplayNameExpression(
        database: SQLiteDatabase
    ): String {

        val candidates = listOf(
            "commercial_name_ar",
            "commercial_name",
            "legal_name",
            "name",
            "display_name"
        ).filter {
            columnExists(database, TABLE_PARTIES, it)
        }

        if (candidates.isEmpty()) {
            return "''"
        }

        val expressions = candidates.map {
            "NULLIF(TRIM(COALESCE($it, '')), '')"
        }

        return "COALESCE(${expressions.joinToString(", ")}, '')"
    }

    // ═══════════════════════════════════════════════════════════════
    // Address
    // ═══════════════════════════════════════════════════════════════

    private fun getPartyAddress(
        database: SQLiteDatabase,
        partyId: Int
    ): String {

        if (!tableExists(database, TABLE_PARTY_ADDRESSES)) {
            return ""
        }

        if (!columnExists(
                database,
                TABLE_PARTY_ADDRESSES,
                "party_id"
            )
        ) {
            return ""
        }

        val addressColumn = firstExistingColumn(
            database,
            TABLE_PARTY_ADDRESSES,
            listOf(
                "address_line1",
                "address",
                "address_text"
            )
        ) ?: return ""

        val deletedCondition =
            if (columnExists(
                    database,
                    TABLE_PARTY_ADDRESSES,
                    "is_deleted"
                )
            ) {
                "AND COALESCE(is_deleted, 0) = 0"
            } else {
                ""
            }

        val orderExpression = when {
            columnExists(
                database,
                TABLE_PARTY_ADDRESSES,
                "is_default"
            ) &&
                    columnExists(
                        database,
                        TABLE_PARTY_ADDRESSES,
                        "id"
                    ) ->
                "ORDER BY is_default DESC, id DESC"

            columnExists(
                database,
                TABLE_PARTY_ADDRESSES,
                "is_default"
            ) ->
                "ORDER BY is_default DESC"

            columnExists(
                database,
                TABLE_PARTY_ADDRESSES,
                "id"
            ) ->
                "ORDER BY id DESC"

            else -> ""
        }

        val sql = """
            SELECT COALESCE($addressColumn, '')
            FROM $TABLE_PARTY_ADDRESSES
            WHERE party_id = ?
              $deletedCondition
            $orderExpression
            LIMIT 1
        """.trimIndent()

        return try {
            database.rawQuery(
                sql,
                arrayOf(partyId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0) ?: ""
                } else {
                    ""
                }
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Unable to resolve party address: ${e.javaClass.simpleName}"
            )
            ""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Public convenience methods
    // ═══════════════════════════════════════════════════════════════

    fun getPartyId(phone: String?): Int? {
        return find(phone)?.partyId
    }

    fun getBalance(phone: String?): Double {
        return find(phone)?.balance ?: 0.0
    }

    // ═══════════════════════════════════════════════════════════════
    // Cursor safe readers
    // ═══════════════════════════════════════════════════════════════

    private fun Cursor.safeString(column: String): String {
        val index = getColumnIndex(column)

        if (index < 0 || isNull(index)) {
            return ""
        }

        return getString(index) ?: ""
    }

    private fun Cursor.safeInt(column: String): Int {
        val index = getColumnIndex(column)

        if (index < 0 || isNull(index)) {
            return 0
        }

        return try {
            getInt(index)
        } catch (_: Exception) {
            0
        }
    }

    private fun Cursor.safeDouble(column: String): Double {
        val index = getColumnIndex(column)

        if (index < 0 || isNull(index)) {
            return 0.0
        }

        return try {
            getDouble(index)
        } catch (_: Exception) {
            0.0
        }
    }
}