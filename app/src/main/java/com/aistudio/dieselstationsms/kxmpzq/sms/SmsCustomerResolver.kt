package com.aistudio.dieselstationsms.kxmpzq.sms

import android.database.Cursor
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════
 * محلل العملاء - SmsCustomerResolver
 * Production / Database-Backed Version
 * ═══════════════════════════════════════════════════════════════
 *
 * مسؤول عن:
 *
 * 1. حل العميل الحقيقي من رقم الهاتف.
 * 2. قراءة الرصيد الحقيقي من قاعدة البيانات.
 * 3. قراءة نقاط الولاء والمستوى التجاري.
 * 4. قراءة سجل الطلبات الحقيقي.
 * 5. قراءة آخر طلب حقيقي.
 * 6. قراءة أسعار الوقود الحقيقية.
 * 7. قراءة أرقام المديرين والسائقين الحقيقية.
 * 8. تسجيل طلب توصيل ديزل حقيقي.
 *
 * ═══════════════════════════════════════════════════════════════
 * قواعد وحدات الوقود - عقد ثابت في نظام SMS
 * ═══════════════════════════════════════════════════════════════
 *
 * 1 دبة          = 20 لتر
 * 1 دباب         = 20 لتر
 * 1 برميل عادي   = 10 دباب = 200 لتر
 * 1 برميل كبير   = 12 دبة  = 240 لتر
 *
 * هذه القواعد يجب أن تبقى متوافقة مع:
 * SmsIntentDetector.kt
 * SmsProcessor.kt
 * SmsConversationManager.kt
 *
 * ═══════════════════════════════════════════════════════════════
 *
 * ملاحظات مهمة:
 *
 * - لا توجد قيم تجريبية للرصيد أو الفواتير أو الطلبات.
 * - لا توجد station_id / shift_id / cashier_id ثابتة.
 * - لا يتم اختراع fuel_type_id.
 * - لا يتم تعديل رصيد العميل يدويًا بعد insertSaleTransaction()
 *   إذا كان DatabaseHelper هو المسؤول عن القيد المحاسبي.
 * - الهاتف موجود في party_contacts وليس parties في المخطط الحالي.
 * - الاسم والرصيد والنقاط من parties.
 * - البريد والهاتف من party_contacts.
 * - العنوان من party_addresses.
 * - نوع المركبة من vehicles.
 *
 * ═══════════════════════════════════════════════════════════════
 */
class SmsCustomerResolver(
    private val db: DatabaseHelper
) {

    companion object {

        private const val TAG = "SmsCustomerResolver"

        /*
         * قواعد الوحدات المعتمدة.
         */
        const val LITERS_PER_DABBA = 20.0
        const val ORDINARY_BARREL_DABBAS = 10.0
        const val LARGE_BARREL_DABBAS = 12.0

        const val ORDINARY_BARREL_LITERS =
            LITERS_PER_DABBA * ORDINARY_BARREL_DABBAS

        const val LARGE_BARREL_LITERS =
            LITERS_PER_DABBA * LARGE_BARREL_DABBAS

        private const val MIN_QUANTITY_LITERS = 1.0
        private const val MAX_QUANTITY_LITERS = 10_000.0

        private const val MIN_PRICE_PER_LITER = 0.000001
        private const val MAX_PRICE_PER_LITER = 1_000_000.0

        private const val MIN_LOCATION_LENGTH = 3
        private const val MAX_LOCATION_LENGTH = 200

        private const val DEFAULT_ORDER_HISTORY_LIMIT = 20
        private const val MAX_ORDER_HISTORY_LIMIT = 100

        private const val DIESEL_CODE = "DIESEL"
        private const val DEFAULT_GASOLINE_CODE = "PETROL_95"
    }

    // ═══════════════════════════════════════════════════════════════
    // Customer Model
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // Customer Resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * حل العميل الحقيقي من رقم الهاتف.
     *
     * بنية قاعدة البيانات الحالية:
     *
     * parties
     *      │
     *      ├── party_contacts
     *      │       ├── phone
     *      │       ├── phone2
     *      │       └── whatsapp
     *      │
     *      ├── party_addresses
     *      │
     *      └── vehicles
     *
     * لا نعتمد على parties.phone لأنه غير موجود في المخطط الحالي.
     */
    suspend fun findCustomer(
        phone: String
    ): CustomerInfo? = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext null
        }

        try {

            val cursor = queryCustomerByPhone(cleanPhone)

            cursor.use {

                if (!it.moveToFirst()) {
                    return@withContext null
                }

                val name =
                    getStringSafe(it, "name")
                        .ifEmpty {
                            getStringSafe(it, "commercial_name")
                        }

                CustomerInfo(
                    name = name,
                    // عنوان الرد هو الرقم الذي استقبلت منه الرسالة الحالية.
                    phone = cleanPhone,
                    balance = getDoubleSafe(
                        it,
                        "current_balance"
                    ),
                    points = getIntSafe(
                        it,
                        "loyalty_points"
                    ),
                    vipLevel = getVipLevelSafe(it),
                    commercialName = getStringSafe(
                        it,
                        "commercial_name"
                    ),
                    email = getStringSafe(
                        it,
                        "email"
                    ),
                    address = getCustomerAddress(it),
                    vehicleType = getStringSafe(
                        it,
                        "vehicle_type"
                    ),
                    fleetSize = getIntSafe(
                        it,
                        "fleet_size"
                    )
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "findCustomer failed: ${e.javaClass.simpleName}",
                e
            )

            null
        }
    }

    /**
     * API توافقية مستخدمة في بعض مسارات SmsProcessor القديمة.
     */
    suspend fun getCustomerByPhone(
        phone: String
    ): CustomerInfo? {
        return findCustomer(phone)
    }

    /**
     * الاستعلام الأساسي للعميل.
     *
     * مهم:
     * لا نستخدم SELECT p.* فقط لأن بيانات الاتصال والعنوان
     * والمركبة موجودة في جداول مستقلة.
     *
     * يتم استخدام aliases للحفاظ على أسماء الحقول التي يعتمد
     * عليها CustomerInfo والـSMS APIs.
     *
     * party_contacts:
     * - phone
     * - phone2
     * - whatsapp
     *
     * party_addresses:
     * - address_line1
     * - address_line2
     * - city
     * - state
     *
     * vehicles:
     * - vehicle_type
     */
    private fun queryCustomerByPhone(
        cleanPhone: String
    ): Cursor {
        // SQLite لا يضمن وجود normalize_phone كدالة مسجلة.
        // لذلك نستخدم الاستعلام الوحيد الذي يعتمد على قيم مطبّعة في Kotlin.
        return queryCustomerByPhoneFallback(cleanPhone)
    }

    /**
     * الاستعلام الفعلي المتوافق مع SQLite الحالية.
     *
     * لا يعتمد على دالة SQLite مخصصة غير مضمونة.
     */
    private fun queryCustomerByPhoneFallback(
        cleanPhone: String
    ): Cursor {

        val variants =
            buildPhoneVariants(cleanPhone)

        val contactConditions =
            variants.joinToString(
                separator = " OR "
            ) {
                "(pc.phone = ? OR pc.phone2 = ? OR pc.whatsapp = ?)"
            }

        val args = mutableListOf<String>()

        variants.forEach { variant ->
            args.add(variant)
            args.add(variant)
            args.add(variant)
        }

        val sql = """
            SELECT
                p.id AS party_id,

                COALESCE(
                    NULLIF(TRIM(p.legal_name), ''),
                    NULLIF(TRIM(p.commercial_name), ''),
                    NULLIF(TRIM(p.commercial_name_ar), '')
                ) AS name,

                p.commercial_name AS commercial_name,
                p.commercial_name_ar AS commercial_name_ar,

                p.current_balance AS current_balance,
                p.total_due AS total_due,
                p.loyalty_points AS loyalty_points,
                p.loyalty_tier AS loyalty_tier,
                p.fleet_size AS fleet_size,

                pc.phone AS contact_phone,
                pc.phone2 AS contact_phone2,
                pc.whatsapp AS contact_whatsapp,
                pc.email AS email,

                pa.address_line1 AS address_line1,
                pa.address_line2 AS address_line2,
                pa.city AS address_city,
                pa.state AS address_state,

                v.vehicle_type AS vehicle_type

            FROM parties p

            INNER JOIN party_contacts pc
                ON pc.party_id = p.id
               AND pc.is_deleted = 0
               AND pc.is_active = 1
               AND (
                    $contactConditions
               )

            LEFT JOIN party_addresses pa
                ON pa.id = (
                    SELECT pa2.id
                    FROM party_addresses pa2
                    WHERE pa2.party_id = p.id
                      AND pa2.is_deleted = 0
                    ORDER BY
                        CASE
                            WHEN pa2.is_default = 1 THEN 0
                            ELSE 1
                        END,
                        pa2.id ASC
                    LIMIT 1
                )

            LEFT JOIN vehicles v
                ON v.id = (
                    SELECT v2.id
                    FROM vehicles v2
                    WHERE v2.party_id = p.id
                      AND v2.is_deleted = 0
                    ORDER BY v2.id ASC
                    LIMIT 1
                )

            WHERE p.is_deleted = 0

            ORDER BY
                CASE
                    WHEN pc.phone IN (
                        ${variants.joinToString(",") { "?" }}
                    ) THEN 0
                    WHEN pc.phone2 IN (
                        ${variants.joinToString(",") { "?" }}
                    ) THEN 1
                    WHEN pc.whatsapp IN (
                        ${variants.joinToString(",") { "?" }}
                    ) THEN 2
                    ELSE 3
                END,

                CASE
                    WHEN pc.is_primary = 1 THEN 0
                    ELSE 1
                END,

                p.id ASC

            LIMIT 1
        """.trimIndent()

        val finalArgs = ArrayList<String>()

        /*
         * contactConditions arguments.
         */
        variants.forEach { variant ->
            finalArgs.add(variant)
            finalArgs.add(variant)
            finalArgs.add(variant)
        }

        /*
         * ORDER BY pc.phone IN (...)
         */
        variants.forEach {
            finalArgs.add(it)
        }

        /*
         * ORDER BY pc.phone2 IN (...)
         */
        variants.forEach {
            finalArgs.add(it)
        }

        /*
         * ORDER BY pc.whatsapp IN (...)
         */
        variants.forEach {
            finalArgs.add(it)
        }

        return db.readableDatabase.rawQuery(
            sql,
            finalArgs.toTypedArray()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Balance
    // ═══════════════════════════════════════════════════════════════

    /**
     * قراءة الرصيد الحقيقي للعميل.
     */
    suspend fun getCustomerBalanceByPhone(
        phone: String
    ): Double = withContext(Dispatchers.IO) {

        val cleanPhone = normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext 0.0
        }

        try {

            val cursor =
                queryCustomerBalanceCursor(
                    cleanPhone
                )

            cursor.use {

                if (it.moveToFirst()) {
                    getDoubleSafe(
                        it,
                        "current_balance"
                    )
                } else {
                    0.0
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getCustomerBalanceByPhone failed",
                e
            )

            0.0
        }
    }

    /**
     * استعلام الرصيد عبر party_contacts فقط.
     *
     * لا يعتمد على parties.phone.
     */
    private fun queryCustomerBalanceCursor(
        cleanPhone: String
    ): Cursor {

        val variants =
            buildPhoneVariants(cleanPhone)

        val conditions =
            variants.joinToString(
                separator = " OR "
            ) {
                "(pc.phone = ? OR pc.phone2 = ? OR pc.whatsapp = ?)"
            }

        val args = mutableListOf<String>()

        variants.forEach { variant ->
            args.add(variant)
            args.add(variant)
            args.add(variant)
        }

        val sql = """
            SELECT
                p.current_balance AS current_balance
            FROM parties p
            INNER JOIN party_contacts pc
                ON pc.party_id = p.id
               AND pc.is_deleted = 0
               AND pc.is_active = 1
               AND (
                    $conditions
               )
            WHERE p.is_deleted = 0
            ORDER BY
                CASE
                    WHEN pc.phone IN (
                        ${variants.joinToString(",") { "?" }}
                    ) THEN 0
                    WHEN pc.phone2 IN (
                        ${variants.joinToString(",") { "?" }}
                    ) THEN 1
                    WHEN pc.whatsapp IN (
                        ${variants.joinToString(",") { "?" }}
                    ) THEN 2
                    ELSE 3
                END,
                p.id ASC
            LIMIT 1
        """.trimIndent()

        variants.forEach {
            args.add(it)
        }

        variants.forEach {
            args.add(it)
        }

        variants.forEach {
            args.add(it)
        }

        return db.readableDatabase.rawQuery(
            sql,
            args.toTypedArray()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Last Order
    // ═══════════════════════════════════════════════════════════════

    /**
     * قراءة آخر طلب/فاتورة حقيقية للعميل.
     *
     * لا يتم إنشاء بيانات تجريبية.
     */
    suspend fun getLastOrderByPhone(
        phone: String
    ): JSONObject? = withContext(Dispatchers.IO) {

        val partyId =
            getPartyIdByPhoneInternal(phone)
                ?: return@withContext null

        try {

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT s.*
                    FROM sales_transactions s
                    WHERE s.customer_party_id = ?
                      AND s.is_deleted = 0
                    ORDER BY s.id DESC
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(
                        partyId.toString()
                    )
                )

            cursor.use {

                if (!it.moveToFirst()) {
                    return@withContext null
                }

                buildSaleJson(it)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getLastOrderByPhone failed",
                e
            )

            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Order History
    // ═══════════════════════════════════════════════════════════════

    /**
     * قراءة سجل الطلبات الحقيقي.
     *
     * limit يتم ضبطه لمنع الاستعلامات غير المحدودة.
     */
    suspend fun getOrderHistoryByPhone(
        phone: String,
        limit: Int
    ): JSONArray = withContext(Dispatchers.IO) {

        val result = JSONArray()

        val partyId =
            getPartyIdByPhoneInternal(phone)
                ?: return@withContext result

        val safeLimit =
            limit
                .coerceAtLeast(1)
                .coerceAtMost(
                    MAX_ORDER_HISTORY_LIMIT
                )

        try {

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT s.*
                    FROM sales_transactions s
                    WHERE s.customer_party_id = ?
                      AND s.is_deleted = 0
                    ORDER BY s.id DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        partyId.toString(),
                        safeLimit.toString()
                    )
                )

            cursor.use {

                while (it.moveToNext()) {

                    result.put(
                        buildSaleJson(it)
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getOrderHistoryByPhone failed",
                e
            )
        }

        result
    }

    /**
     * نسخة بدون limit للحفاظ على استخدامات قديمة محتملة.
     */
    suspend fun getOrderHistoryByPhone(
        phone: String
    ): JSONArray {

        return getOrderHistoryByPhone(
            phone,
            DEFAULT_ORDER_HISTORY_LIMIT
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Fuel Prices
    // ═══════════════════════════════════════════════════════════════

    /**
     * السعر الحقيقي للديزل من قاعدة البيانات.
     */
    suspend fun getDieselPrice(): Double =
        getFuelPrice(DIESEL_CODE)

    /**
     * السعر الحقيقي للبنزين من قاعدة البيانات.
     *
     * هذا لا يعني أن طلب البنزين مدعوم.
     *
     * Resolver مسؤول عن قراءة السعر فقط.
     * قرار دعم/رفض طلب البنزين ورسالة الرد مكانها SmsProcessor/
     * SmsReplyManager وفق العقد السابق.
     */
    suspend fun getGasolinePrice(
        fuelCode: String
    ): Double =
        getFuelPrice(fuelCode)

    suspend fun getGasolinePrice(): Double =
        getFuelPrice(
            DEFAULT_GASOLINE_CODE
        )

    /**
     * قراءة سعر الوقود حسب fuel_code.
     */
    private suspend fun getFuelPrice(
        fuelCode: String
    ): Double = withContext(Dispatchers.IO) {

        val code =
            fuelCode.trim()

        if (code.isEmpty()) {
            return@withContext 0.0
        }

        try {

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT default_sale_price
                    FROM fuel_types
                    WHERE fuel_code = ?
                      AND is_deleted = 0
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(code)
                )

            cursor.use {

                if (it.moveToFirst()) {
                    getDoubleSafe(
                        it,
                        0
                    )
                } else {
                    0.0
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getFuelPrice($code) failed",
                e
            )

            0.0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Manager
    // ═══════════════════════════════════════════════════════════════

    /**
     * قراءة رقم المدير الحقيقي.
     *
     * الأولوية حسب مستوى الدور.
     */
    suspend fun getManagerPhone(): String? =
        withContext(Dispatchers.IO) {

            try {

                val cursor =
                    db.readableDatabase.rawQuery(
                        """
                        SELECT u.phone
                        FROM users u
                        JOIN roles r
                          ON u.role_id = r.id
                        WHERE r.role_code IN (
                            'SUPER_ADMIN',
                            'ADMIN',
                            'STATION_MANAGER'
                        )
                          AND u.status = 'active'
                          AND u.is_deleted = 0
                          AND u.phone IS NOT NULL
                          AND TRIM(u.phone) <> ''
                        ORDER BY
                            r.level ASC,
                            u.id ASC
                        LIMIT 1
                        """.trimIndent(),
                        null
                    )

                cursor.use {

                    if (it.moveToFirst()) {

                        normalizePhone(
                            getStringSafe(
                                it,
                                0
                            )
                        ).takeIf {
                            it.isNotEmpty()
                        }

                    } else {
                        null
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "getManagerPhone failed",
                    e
                )

                null
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // Drivers
    // ═══════════════════════════════════════════════════════════════

    /**
     * قراءة أرقام السائقين الفعلية.
     */
    suspend fun getDriverPhones(): List<String> =
        withContext(Dispatchers.IO) {

            val phones =
                linkedSetOf<String>()

            try {

                val cursor =
                    db.readableDatabase.rawQuery(
                        """
                        SELECT phone, phone2
                        FROM drivers
                        WHERE status = 'active'
                          AND is_deleted = 0
                        """.trimIndent(),
                        null
                    )

                cursor.use {

                    while (it.moveToNext()) {

                        normalizePhone(
                            getStringSafe(
                                it,
                                0
                            )
                        ).takeIf {
                            it.isNotEmpty()
                        }?.let {
                            phones.add(it)
                        }

                        normalizePhone(
                            getStringSafe(
                                it,
                                1
                            )
                        ).takeIf {
                            it.isNotEmpty()
                        }?.let {
                            phones.add(it)
                        }
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "getDriverPhones failed",
                    e
                )
            }

            phones.toList()
        }

    suspend fun getDriverPhone(): String? {
        return getDriverPhones()
            .firstOrNull()
    }

    // ═══════════════════════════════════════════════════════════════
    // Record Diesel Delivery
    // ═══════════════════════════════════════════════════════════════

    /**
     * تسجيل طلب توصيل ديزل حقيقي.
     *
     * لا تستخدم هذه الدالة أي IDs تجريبية.
     *
     * يتم حل:
     *
     * - customer_party_id
     * - fuel_type_id
     * - station_id
     * - shift_id
     * - cashier_id
     *
     * من قاعدة البيانات الفعلية.
     *
     * كما أن تعديل الرصيد اليدوي بعد insertSaleTransaction()
     * ممنوع هنا لأن DatabaseHelper هو المسؤول عن القيد المحاسبي
     * عند تسجيل البيع الائتماني.
     */
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

            // ─────────────────────────────────────────────────────
            // 1. Validate customer
            // ─────────────────────────────────────────────────────

            val partyId =
                getPartyIdByPhoneInternal(
                    customerId
                )
                    ?: run {
                        Log.w(
                            TAG,
                            "Cannot record delivery: customer not found"
                        )

                        return@withContext false
                    }

            // ─────────────────────────────────────────────────────
            // 2. Validate quantity
            // ─────────────────────────────────────────────────────

            require(
                quantityLiters.isFinite() &&
                        quantityLiters in
                        MIN_QUANTITY_LITERS..MAX_QUANTITY_LITERS
            ) {
                "Invalid quantityLiters: $quantityLiters"
            }

            require(
                quantityDabbas.isFinite() &&
                        quantityDabbas > 0.0
            ) {
                "Invalid quantityDabbas: $quantityDabbas"
            }

            /*
             * 1 دبة / دباب = 20 لتر.
             *
             * لا نفرض أن كل إدخال يجب أن يكون عددًا صحيحًا من الدباب،
             * لأن SmsIntentDetector قد يحول الكمية من اللترات إلى
             * قيمة مكافئة بالدباب.
             */
            val expectedLiters =
                quantityDabbas *
                        LITERS_PER_DABBA

            require(
                expectedLiters.isFinite()
            ) {
                "Invalid quantity conversion"
            }

            /*
             * إذا كانت الكمية بالدباب تمثل كمية الطلب نفسها،
             * يجب ألا تختلف عن quantityLiters إلا في حدود
             * الخطأ العددي البسيط.
             *
             * هذا يمنع تسجيل عملية يكون فيها عدد الدباب لا يطابق
             * اللترات المرسلة من SmsProcessor.
             */
            require(
                amountsEquivalent(
                    expectedLiters,
                    quantityLiters
                )
            ) {
                "Quantity mismatch: expectedLiters=$expectedLiters suppliedLiters=$quantityLiters"
            }

            // ─────────────────────────────────────────────────────
            // 3. Validate location
            // ─────────────────────────────────────────────────────

            val cleanLocation =
                location.trim()

            require(
                cleanLocation.length in
                        MIN_LOCATION_LENGTH..MAX_LOCATION_LENGTH
            ) {
                "Invalid delivery location"
            }

            // ─────────────────────────────────────────────────────
            // 4. Validate price
            // ─────────────────────────────────────────────────────

            require(
                unitPrice.isFinite() &&
                        unitPrice in
                        MIN_PRICE_PER_LITER..MAX_PRICE_PER_LITER
            ) {
                "Invalid unit price: $unitPrice"
            }

            // ─────────────────────────────────────────────────────
            // 5. Calculate real total
            // ─────────────────────────────────────────────────────

            val calculatedTotal =
                safeMultiply(
                    quantityLiters,
                    unitPrice
                )

            require(
                totalAmount.isFinite() &&
                        totalAmount >= 0.0
            ) {
                "Invalid total amount"
            }

            /*
             * إذا أرسل Processor totalAmount محسوبًا مسبقًا،
             * يجب ألا نقبل قيمة متضاربة معه.
             */
            require(
                amountsEquivalent(
                    calculatedTotal,
                    totalAmount
                )
            ) {
                "Total amount mismatch: calculated=$calculatedTotal supplied=$totalAmount"
            }

            // ─────────────────────────────────────────────────────
            // 6. Resolve real fuel type
            // ─────────────────────────────────────────────────────

            val fuelTypeId =
                getFuelTypeId(
                    DIESEL_CODE
                )
                    ?: run {
                        Log.e(
                            TAG,
                            "DIESEL fuel type not found"
                        )

                        return@withContext false
                    }

            // ─────────────────────────────────────────────────────
            // 7. Resolve real station
            // ─────────────────────────────────────────────────────

            val stationId =
                getActiveStationId()
                    ?: run {
                        Log.e(
                            TAG,
                            "No active station found"
                        )

                        return@withContext false
                    }

            // ─────────────────────────────────────────────────────
            // 8. Resolve real open shift
            // ─────────────────────────────────────────────────────

            val shiftId =
                getOpenShiftId(
                    stationId
                )
                    ?: run {
                        Log.e(
                            TAG,
                            "No open shift found for station=$stationId"
                        )

                        return@withContext false
                    }

            // ─────────────────────────────────────────────────────
            // 9. Resolve real cashier/user
            // ─────────────────────────────────────────────────────

            val cashierId =
                getActiveCashierId()
                    ?: run {
                        Log.e(
                            TAG,
                            "No active cashier/user found"
                        )

                        return@withContext false
                    }

            // ─────────────────────────────────────────────────────
            // 10. Record through DatabaseHelper
            // ─────────────────────────────────────────────────────

            /*
             * DatabaseHelper.insertSaleTransaction() هو نقطة
             * التسجيل الأساسية في النظام.
             *
             * لا نضيف update يدويًا للرصيد بعده.
             */
            val result =
                db.insertSaleTransaction(
                    stationId = stationId,
                    shiftId = shiftId,
                    customerPartyId = partyId,
                    fuelTypeId = fuelTypeId,
                    pumpId = null,
                    nozzleId = null,
                    liters = quantityLiters,
                    pricePerLiter = unitPrice,
                    subtotal = calculatedTotal,
                    discountAmount = 0.0,
                    taxAmount = 0.0,
                    grossAmount = calculatedTotal,
                    netAmount = calculatedTotal,
                    paymentMethod = "credit",
                    isCredit = true,
                    dueDate = createDueDate(),
                    cashierId = cashierId,
                    notes = buildDeliveryNotes(
                        customerName = customerName,
                        location = cleanLocation,
                        deliveryTime = deliveryTime,
                        orderId = orderId
                    ),
                    deliveryLocation = cleanLocation,
                    deliveryTime = deliveryTime.trim(),
                    orderType = "delivery"
                )

            if (result <= 0) {

                Log.e(
                    TAG,
                    "insertSaleTransaction returned invalid id=$result"
                )

                return@withContext false
            }

            Log.i(
                TAG,
                "Diesel delivery recorded successfully: saleId=$result partyId=$partyId"
            )

            true

        } catch (e: IllegalArgumentException) {

            Log.e(
                TAG,
                "Invalid diesel delivery data: ${e.message}",
                e
            )

            false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error recording diesel delivery",
                e
            )

            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Party ID
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على party ID الحقيقي من رقم الهاتف.
     *
     * يتم دعم:
     *
     * - party_contacts.phone
     * - party_contacts.phone2
     * - party_contacts.whatsapp
     *
     * ولا نعتمد على parties.phone لأنه غير موجود في المخطط الحالي.
     */
    private suspend fun getPartyIdByPhoneInternal(
        phone: String
    ): Int? = withContext(Dispatchers.IO) {

        val cleanPhone =
            normalizePhone(phone)

        if (cleanPhone.isEmpty()) {
            return@withContext null
        }

        try {

            val variants =
                buildPhoneVariants(
                    cleanPhone
                )

            val conditions =
                variants.joinToString(
                    separator = " OR "
                ) {
                    "(pc.phone = ? OR pc.phone2 = ? OR pc.whatsapp = ?)"
                }

            val args =
                mutableListOf<String>()

            variants.forEach { variant ->
                args.add(variant)
                args.add(variant)
                args.add(variant)
            }

            val sql = """
                SELECT p.id
                FROM parties p
                INNER JOIN party_contacts pc
                    ON pc.party_id = p.id
                   AND pc.is_deleted = 0
                   AND pc.is_active = 1
                   AND (
                        $conditions
                   )
                WHERE p.is_deleted = 0
                ORDER BY
                    CASE
                        WHEN pc.phone IN (
                            ${variants.joinToString(",") { "?" }}
                        ) THEN 0
                        WHEN pc.phone2 IN (
                            ${variants.joinToString(",") { "?" }}
                        ) THEN 1
                        WHEN pc.whatsapp IN (
                            ${variants.joinToString(",") { "?" }}
                        ) THEN 2
                        ELSE 3
                    END,
                    CASE
                        WHEN pc.is_primary = 1 THEN 0
                        ELSE 1
                    END,
                    p.id ASC
                LIMIT 1
            """.trimIndent()

            variants.forEach {
                args.add(it)
            }

            variants.forEach {
                args.add(it)
            }

            variants.forEach {
                args.add(it)
            }

            val cursor =
                db.readableDatabase.rawQuery(
                    sql,
                    args.toTypedArray()
                )

            cursor.use {

                if (it.moveToFirst()) {
                    getIntSafe(
                        it,
                        0
                    )
                } else {
                    null
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getPartyIdByPhoneInternal failed",
                e
            )

            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Fuel Type ID
    // ═══════════════════════════════════════════════════════════════

    private suspend fun getFuelTypeId(
        fuelCode: String
    ): Int? = withContext(Dispatchers.IO) {

        try {

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT id
                    FROM fuel_types
                    WHERE fuel_code = ?
                      AND is_deleted = 0
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(
                        fuelCode
                    )
                )

            cursor.use {

                if (it.moveToFirst()) {
                    getIntSafe(
                        it,
                        0
                    )
                } else {
                    null
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getFuelTypeId failed for $fuelCode",
                e
            )

            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Station Resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على المحطة الفعالة.
     *
     * لا يتم استخدام station_id = 1.
     */
    private suspend fun getActiveStationId(): Int? =
        withContext(Dispatchers.IO) {

            try {

                val cursor =
                    db.readableDatabase.rawQuery(
                        """
                        SELECT id
                        FROM stations
                        WHERE is_deleted = 0
                          AND (
                                status = 'active'
                                OR status IS NULL
                              )
                        ORDER BY id ASC
                        LIMIT 1
                        """.trimIndent(),
                        null
                    )

                cursor.use {

                    if (it.moveToFirst()) {
                        getIntSafe(
                            it,
                            0
                        )
                    } else {
                        null
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "getActiveStationId failed",
                    e
                )

                null
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // Shift Resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على الوردية المفتوحة الفعلية.
     *
     * لا يتم استخدام shift_id = 1.
     */
    private suspend fun getOpenShiftId(
        stationId: Int
    ): Int? = withContext(Dispatchers.IO) {

        try {

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT id
                    FROM shifts
                    WHERE station_id = ?
                      AND is_deleted = 0
                      AND (
                            status = 'open'
                            OR status = 'OPEN'
                            OR status = 'active'
                            OR status = 'ACTIVE'
                          )
                    ORDER BY id DESC
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(
                        stationId.toString()
                    )
                )

            cursor.use {

                if (it.moveToFirst()) {
                    getIntSafe(
                        it,
                        0
                    )
                } else {
                    null
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "getOpenShiftId failed",
                e
            )

            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Cashier Resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على مستخدم/كاشير فعال.
     *
     * لا يتم استخدام cashier_id = 1.
     */
    private suspend fun getActiveCashierId(): Int? =
        withContext(Dispatchers.IO) {

            try {

                val cursor =
                    db.readableDatabase.rawQuery(
                        """
                        SELECT id
                        FROM users
                        WHERE status = 'active'
                          AND is_deleted = 0
                        ORDER BY id ASC
                        LIMIT 1
                        """.trimIndent(),
                        null
                    )

                cursor.use {

                    if (it.moveToFirst()) {
                        getIntSafe(
                            it,
                            0
                        )
                    } else {
                        null
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "getActiveCashierId failed",
                    e
                )

                null
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // JSON Mapping
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحويل سجل sales_transactions الحقيقي إلى JSONObject.
     *
     * يتم إخراج الحقول الموجودة فقط بشكل آمن.
     *
     * مهم:
     * delivery_location وdelivery_time وorder_type حقول حقيقية
     * في sales_transactions، لذلك لا نستخدم notes كموقع.
     */
    private fun buildSaleJson(
        cursor: Cursor
    ): JSONObject {

        return JSONObject().apply {

            put(
                "id",
                getLongSafe(
                    cursor,
                    "id"
                )
            )

            put(
                "sale_code",
                getStringSafe(
                    cursor,
                    "sale_code"
                )
            )

            put(
                "invoice_number",
                getStringSafe(
                    cursor,
                    "invoice_number"
                )
            )

            put(
                "sale_type",
                getStringSafe(
                    cursor,
                    "sale_type"
                )
            )

            put(
                "liters",
                getDoubleSafe(
                    cursor,
                    "liters"
                )
            )

            put(
                "price_per_liter",
                getDoubleSafe(
                    cursor,
                    "price_per_liter"
                )
            )

            put(
                "subtotal",
                getDoubleSafe(
                    cursor,
                    "subtotal"
                )
            )

            put(
                "discount_amount",
                getDoubleSafe(
                    cursor,
                    "discount_amount"
                )
            )

            put(
                "tax_amount",
                getDoubleSafe(
                    cursor,
                    "tax_amount"
                )
            )

            put(
                "gross_amount",
                getDoubleSafe(
                    cursor,
                    "gross_amount"
                )
            )

            put(
                "net_amount",
                getDoubleSafe(
                    cursor,
                    "net_amount"
                )
            )

            put(
                "payment_method",
                getStringSafe(
                    cursor,
                    "payment_method"
                )
            )

            put(
                "is_credit",
                getIntSafe(
                    cursor,
                    "is_credit"
                )
            )

            put(
                "status",
                getStringSafe(
                    cursor,
                    "status"
                )
            )

            /*
             * notes الحقيقي.
             */
            put(
                "notes",
                getStringSafe(
                    cursor,
                    "remarks"
                )
            )

            /*
             * delivery_location الحقيقي.
             *
             * لا يتم تحويل notes إليه.
             *
             * وإذا كان العمود غير موجود في Cursor لسبب توافق
             * مع نسخة قديمة من DB، يرجع "" بدل رمي استثناء.
             */
            put(
                "delivery_location",
                getStringSafe(
                    cursor,
                    "delivery_location"
                )
            )

            /*
             * delivery_time الحقيقي.
             */
            put(
                "delivery_time",
                getStringSafe(
                    cursor,
                    "delivery_time"
                )
            )

            /*
             * order_type الحقيقي.
             */
            put(
                "order_type",
                getStringSafe(
                    cursor,
                    "order_type"
                )
            )

            put(
                "created_at",
                getStringSafe(
                    cursor,
                    "created_at"
                )
            )

            put(
                "updated_at",
                getStringSafe(
                    cursor,
                    "updated_at"
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VIP
    // ═══════════════════════════════════════════════════════════════

    fun getVipText(
        vip: Int
    ): String {

        return when (vip) {
            4 -> "بلاتيني 💎"
            3 -> "ذهبي 👑"
            2 -> "فضي 🥈"
            1 -> "برونزي 🥉"
            else -> "عادي 💎"
        }
    }

    /**
     * تحويل loyalty_tier الحقيقي إلى المستوى الرقمي الذي
     * يحتاجه CustomerInfo.
     *
     * المخطط الحالي يستخدم loyalty_tier بدل vip_level.
     *
     * ندعم:
     * - أرقام 0..3
     * - GOLD / GOLDEN / ذهب / ذهبي
     * - SILVER / فضي
     * - BRONZE / برونزي
     * - VIP
     * - NORMAL / BASIC / عادي
     */
    private fun getVipLevelSafe(
        cursor: Cursor
    ): Int {

        val raw =
            getStringSafe(
                cursor,
                "loyalty_tier"
            )

        if (raw.isEmpty()) {
            return 0
        }

            raw.toIntOrNull()?.let {
                return it.coerceIn(0, 4)
            }

        return when (
            raw
                .trim()
                .uppercase(Locale.ROOT)
        ) {

            "4",
            "PLATINUM",
            "PLATINUM_TIER",
            "VIP_PLATINUM",
            "بلاتيني",
            "بلاتيني 💎" -> 4

            "3",
            "GOLD",
            "GOLDEN",
            "GOLD_TIER",
            "VIP_GOLD",
            "ذهبي",
            "ذهبي 👑" -> 3

            "2",
            "SILVER",
            "SILVER_TIER",
            "VIP_SILVER",
            "فضي",
            "فضي 🥈" -> 2

            "1",
            "BRONZE",
            "BRONZE_TIER",
            "VIP_BRONZE",
            "برونزي",
            "برونزي 🥉" -> 1

            else -> 0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Safe Arithmetic
    // ═══════════════════════════════════════════════════════════════

    /**
     * حساب آمن.
     */
    fun safeMultiply(
        a: Double,
        b: Double
    ): Double {

        require(
            a.isFinite() &&
                    a >= 0.0 &&
                    a <= MAX_QUANTITY_LITERS
        ) {
            "Invalid quantity: $a"
        }

        require(
            b.isFinite() &&
                    b >= 0.0 &&
                    b <= MAX_PRICE_PER_LITER
        ) {
            "Invalid price: $b"
        }

        val result =
            a * b

        require(
            result.isFinite() &&
                    result >= 0.0
        ) {
            "Calculation overflow"
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // Customer Preferences
    // ═══════════════════════════════════════════════════════════════

    /**
     * نقطة توافق مع SmsConversationManager / SmsProcessor.
     *
     * لا تنشئ بيانات وهمية ولا تغير حالة العميل بدون وجود
     * عملية DB حقيقية مخصصة لذلك.
     */
    suspend fun syncPreferences() =
        withContext(Dispatchers.IO) {

            try {

                Log.d(
                    TAG,
                    "Customer preferences sync requested"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to sync customer preferences",
                    e
                )
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // Phone Normalization
    // ═══════════════════════════════════════════════════════════════

    /**
     * تطبيع رقم الهاتف باستخدام PhoneUtils أولًا.
     */
    private fun normalizePhone(
        phone: String?
    ): String {

        if (phone.isNullOrBlank()) {
            return ""
        }

        return try {

            PhoneUtils
                .normalize(phone)
                ?.trim()
                .orEmpty()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Phone normalization failed",
                e
            )

            phone.trim()
        }
    }

    /**
     * إنشاء مجموعة من صيغ رقم الهاتف التي يمكن استخدامها
     * في party_contacts.
     *
     * الهدف هو عدم الاعتماد على صيغة واحدة فقط إذا كانت قاعدة
     * البيانات القديمة/الحالية تحتوي على صيغة مختلفة لنفس الرقم.
     */
    private fun buildPhoneVariants(
        phone: String
    ): List<String> {

        val clean =
            phone.trim()

        if (clean.isEmpty()) {
            return emptyList()
        }

        val variants =
            linkedSetOf<String>()

        variants.add(clean)

        /*
         * أرقام عربية هندية/فارسية محتملة.
         */
        val latinDigits =
            arabicDigitsToLatin(
                clean
            )

        if (latinDigits.isNotEmpty()) {
            variants.add(
                latinDigits
            )
        }

        /*
         * إذا كانت PhoneUtils أعادت رقمًا دوليًا،
         * نجرب الرقم المحلي بدون +.
         */
        if (clean.startsWith("+")) {
            variants.add(
                clean.substring(1)
            )
        }

        /*
         * إذا كان الرقم يحتوي على مسافات أو شرطات،
         * نجرب نسخة الأرقام فقط.
         */
        val digitsOnly =
            clean.filter {
                it.isDigit()
            }

        if (digitsOnly.isNotEmpty()) {
            variants.add(
                digitsOnly
            )
        }

        return variants
            .filter {
                it.isNotBlank()
            }
            .take(8)
    }

    /**
     * تحويل الأرقام العربية/الفارسية إلى أرقام لاتينية.
     */
    private fun arabicDigitsToLatin(
        value: String
    ): String {

        return buildString {

            value.forEach { char ->

                append(
                    when (char) {

                        '٠' -> '0'
                        '١' -> '1'
                        '٢' -> '2'
                        '٣' -> '3'
                        '٤' -> '4'
                        '٥' -> '5'
                        '٦' -> '6'
                        '٧' -> '7'
                        '٨' -> '8'
                        '٩' -> '9'

                        '۰' -> '0'
                        '۱' -> '1'
                        '۲' -> '2'
                        '۳' -> '3'
                        '۴' -> '4'
                        '۵' -> '5'
                        '۶' -> '6'
                        '۷' -> '7'
                        '۸' -> '8'
                        '۹' -> '9'

                        else -> char
                    }
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Due Date
    // ═══════════════════════════════════════════════════════════════

    private fun createDueDate(): String {

        return SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US
        ).format(
            Date()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Delivery Notes
    // ═══════════════════════════════════════════════════════════════

    private fun buildDeliveryNotes(
        customerName: String,
        location: String,
        deliveryTime: String,
        orderId: String
    ): String {

        val safeName =
            customerName
                .trim()
                .take(100)

        val safeLocation =
            location
                .trim()
                .take(
                    MAX_LOCATION_LENGTH
                )

        val safeTime =
            deliveryTime
                .trim()
                .take(50)

        val safeOrderId =
            orderId
                .trim()
                .take(100)

        return buildString {

            append(
                "طلب توصيل ديزل"
            )

            if (safeName.isNotEmpty()) {

                append(
                    " - العميل: "
                )

                append(
                    safeName
                )
            }

            if (safeLocation.isNotEmpty()) {

                append(
                    " - الموقع: "
                )

                append(
                    safeLocation
                )
            }

            if (safeTime.isNotEmpty()) {

                append(
                    " - الوقت: "
                )

                append(
                    safeTime
                )
            }

            if (safeOrderId.isNotEmpty()) {

                append(
                    " - SMS Order ID: "
                )

                append(
                    safeOrderId
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Amount Comparison
    // ═══════════════════════════════════════════════════════════════

    private fun amountsEquivalent(
        first: Double,
        second: Double
    ): Boolean {

        if (!first.isFinite() ||
            !second.isFinite()
        ) {
            return false
        }

        val difference =
            abs(
                first - second
            )

        val tolerance =
            maxOf(
                0.01,
                abs(first) * 0.000001
            )

        return difference <= tolerance
    }

    // ═══════════════════════════════════════════════════════════════
    // Cursor Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun columnIndex(
        cursor: Cursor,
        column: String
    ): Int {

        return try {

            cursor.getColumnIndex(
                column
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to resolve cursor column: $column",
                e
            )

            -1
        }
    }

    private fun getStringSafe(
        cursor: Cursor,
        column: String
    ): String {

        val index =
            columnIndex(
                cursor,
                column
            )

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return ""
        }

        return try {

            cursor
                .getString(index)
                ?.trim()
                .orEmpty()

        } catch (_: Exception) {

            ""
        }
    }

    private fun getStringSafe(
        cursor: Cursor,
        index: Int
    ): String {

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return ""
        }

        return try {

            cursor
                .getString(index)
                ?.trim()
                .orEmpty()

        } catch (_: Exception) {

            ""
        }
    }

    private fun getDoubleSafe(
        cursor: Cursor,
        column: String
    ): Double {

        val index =
            columnIndex(
                cursor,
                column
            )

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return 0.0
        }

        return try {

            cursor.getDouble(
                index
            )

        } catch (_: Exception) {

            0.0
        }
    }

    private fun getDoubleSafe(
        cursor: Cursor,
        index: Int
    ): Double {

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return 0.0
        }

        return try {

            cursor.getDouble(
                index
            )

        } catch (_: Exception) {

            0.0
        }
    }

    private fun getIntSafe(
        cursor: Cursor,
        column: String
    ): Int {

        val index =
            columnIndex(
                cursor,
                column
            )

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return 0
        }

        return try {

            cursor.getInt(
                index
            )

        } catch (_: Exception) {

            /*
             * بعض SQLite schemas قد تخزن المستوى كنص.
             */
            try {

                cursor
                    .getString(index)
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0

            } catch (_: Exception) {

                0
            }
        }
    }

    private fun getIntSafe(
        cursor: Cursor,
        index: Int
    ): Int {

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return 0
        }

        return try {

            cursor.getInt(
                index
            )

        } catch (_: Exception) {

            try {

                cursor
                    .getString(index)
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0

            } catch (_: Exception) {

                0
            }
        }
    }

    private fun getLongSafe(
        cursor: Cursor,
        column: String
    ): Long {

        val index =
            columnIndex(
                cursor,
                column
            )

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return 0L
        }

        return try {

            cursor.getLong(
                index
            )

        } catch (_: Exception) {

            try {

                cursor
                    .getString(index)
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 0L

            } catch (_: Exception) {

                0L
            }
        }
    }

    private fun getLongSafe(
        cursor: Cursor,
        index: Int
    ): Long {

        if (
            index < 0 ||
            index >= cursor.columnCount ||
            cursor.isNull(index)
        ) {
            return 0L
        }

        return try {

            cursor.getLong(
                index
            )

        } catch (_: Exception) {

            try {

                cursor
                    .getString(index)
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 0L

            } catch (_: Exception) {

                0L
            }
        }
    }

    /**
     * قراءة هاتف العميل من party_contacts.
     *
     * الأولوية:
     *
     * 1. phone
     * 2. phone2
     * 3. whatsapp
     * 4. fallback المرسل إلى الدالة
     */
    private fun getCustomerPhone(
        cursor: Cursor,
        fallback: String
    ): String {

        val partyPhone =
            getStringSafe(
                cursor,
                "contact_phone"
            )

        val phone2 =
            getStringSafe(
                cursor,
                "contact_phone2"
            )

        val whatsapp =
            getStringSafe(
                cursor,
                "contact_whatsapp"
            )

        val candidates =
            listOf(
                partyPhone,
                phone2,
                whatsapp,
                fallback
            )

        for (candidate in candidates) {

            val normalized =
                normalizePhone(
                    candidate
                )

            if (normalized.isNotEmpty()) {
                return normalized
            }
        }

        return fallback
    }

    /**
     * قراءة العنوان الحقيقي من aliases الخاصة بالاستعلام.
     *
     * لا نعتمد على parties.address.
     */
    private fun getCustomerAddress(
        cursor: Cursor
    ): String {

        val parts =
            mutableListOf<String>()

        listOf(
            "address_line1",
            "address_line2",
            "address_city",
            "address_state"
        ).forEach { column ->

            val value =
                getStringSafe(
                    cursor,
                    column
                )

            if (value.isNotEmpty()) {
                parts.add(value)
            }
        }

        return parts.joinToString(
            separator = ", "
        )
    }
}