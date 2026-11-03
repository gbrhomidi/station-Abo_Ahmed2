package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustodyWorkshopAcceptanceRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: SQLiteDatabase
    private var stationId = 0
    private var adminId = 0L
    private var restrictedUserId = 0L
    private var employeeOne = 0L
    private var employeeTwo = 0L
    private var cashBoxId = 0L
    private var bankAccountId = 0L
    private var productId = 0L
    private var warehouseId = 0L
    private var assetId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase
        CustodyWorkshopManager.ensureSchema(db)
        stationId = scalarLong("SELECT id FROM stations ORDER BY id LIMIT 1").toInt()
        adminId = insertUser("cw-admin", 1)
        restrictedUserId = insertUser("cw-cashier", 4)
        employeeOne = insertEmployee("employee-one")
        employeeTwo = insertEmployee("employee-two")
        cashBoxId = insertCashBox()
        bankAccountId = insertBankAccount()
        warehouseId = insertWarehouse()
        productId = insertProduct()
        assetId = insertAsset()
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", productId); put("warehouse_id", warehouseId)
            put("quantity_on_hand", 500.0); put("quantity_committed", 0.0); put("average_cost", 20.0)
        })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private data class CaseRoute(
        val domain: String,
        val scenario: Int,
        val dimension: Int
    )

    private data class CatalogueCase(
        val definition: JSONObject,
        val route: CaseRoute,
        val caseNumber: Int
    )

    private val scenarioRoutes: Map<Pair<String, String>, Pair<String, Int>> = mapOf(
        ("عهد النزول والعهد النقدية التشغيلية" to "إنشاء عهدة نزول لموظف") to ("custody" to 0),
        ("عهد النزول والعهد النقدية التشغيلية" to "صرف مبلغ العهدة نقدًا أو من البنك") to ("custody" to 1),
        ("عهد النزول والعهد النقدية التشغيلية" to "تسجيل مصروفات عهدة مدعمة بمستندات") to ("custody" to 2),
        ("عهد النزول والعهد النقدية التشغيلية" to "إعادة المتبقي من العهدة") to ("custody" to 3),
        ("عهد النزول والعهد النقدية التشغيلية" to "تسوية العهدة بمصروفات ومتبقٍ نقدي") to ("custody" to 4),
        ("عهد النزول والعهد النقدية التشغيلية" to "عجز عهدة بعد الجرد") to ("custody" to 5),
        ("عهد النزول والعهد النقدية التشغيلية" to "زيادة عهدة غير مفسرة") to ("custody" to 6),
        ("عهد النزول والعهد النقدية التشغيلية" to "تجاوز مصروفات العهدة للمبلغ المصروف") to ("custody" to 7),
        ("عهد النزول والعهد النقدية التشغيلية" to "مصروف عهدة بلا مستند أو بمستند مكرر") to ("custody" to 8),
        ("عهد النزول والعهد النقدية التشغيلية" to "تسوية عهدة بين ورديتين/موظفين") to ("custody" to 9),
        ("عهد النزول والعهد النقدية التشغيلية" to "عهدة مؤقتة لشراء عاجل للمحطة") to ("custody" to 10),
        ("عهد النزول والعهد النقدية التشغيلية" to "عهدة مرتبطة بتحصيل/إيداع نقدية") to ("custody" to 11),
        ("عهد النزول والعهد النقدية التشغيلية" to "عهدة معلقة عند نهاية الفترة") to ("custody" to 12),
        ("عهد النزول والعهد النقدية التشغيلية" to "استرداد عهدة من راتب أو موظف") to ("custody" to 13),
        ("عهد النزول والعهد النقدية التشغيلية" to "إلغاء أو عكس سند عهدة مرحل") to ("custody" to 14),
        ("عهد النزول والعهد النقدية التشغيلية" to "تقرير أعمار العهد ومطابقة الأستاذ") to ("custody" to 15),
        ("عهد النزول والعهد النقدية التشغيلية" to "صلاحيات اعتماد وصرف وتسوية العهدة") to ("custody" to 16),
        ("عهد النزول والعهد النقدية التشغيلية" to "إعادة إرسال طلب صرف العهدة") to ("custody" to 17),
        ("عهد النزول والعهد النقدية التشغيلية" to "تغيير حسابات العهدة أو مركز التكلفة") to ("custody" to 18),
        ("عهد النزول والعهد النقدية التشغيلية" to "مطابقة عهدة النزول مع دفتر اليومية") to ("custody" to 19),
        ("ورش الصيانة الملحقة بالمحطة" to "إنشاء أمر عمل لصيانة أصل المحطة") to ("workshop" to 0),
        ("ورش الصيانة الملحقة بالمحطة" to "إصلاح داخلي دون قطع غيار") to ("workshop" to 1),
        ("ورش الصيانة الملحقة بالمحطة" to "صرف قطع غيار لأمر عمل") to ("workshop" to 2),
        ("ورش الصيانة الملحقة بالمحطة" to "إرجاع قطعة غير مستخدمة") to ("workshop" to 3),
        ("ورش الصيانة الملحقة بالمحطة" to "شراء قطعة صيانة آجلًا") to ("workshop" to 4),
        ("ورش الصيانة الملحقة بالمحطة" to "صيانة دورية مقابل إصلاح جوهري") to ("workshop" to 5),
        ("ورش الصيانة الملحقة بالمحطة" to "خدمة صيانة للغير بمقابل") to ("workshop" to 6),
        ("ورش الصيانة الملحقة بالمحطة" to "تسعير أمر العمل قبل وبعد التنفيذ") to ("workshop" to 7),
        ("ورش الصيانة الملحقة بالمحطة" to "أجور الفنيين وتوزيع ساعاتهم") to ("workshop" to 8),
        ("ورش الصيانة الملحقة بالمحطة" to "ضمان إصلاح/قطعة") to ("workshop" to 9),
        ("ورش الصيانة الملحقة بالمحطة" to "أمر عمل ملغى بعد صرف القطع") to ("workshop" to 10),
        ("ورش الصيانة الملحقة بالمحطة" to "تلف قطعة داخل الورشة") to ("workshop" to 11),
        ("ورش الصيانة الملحقة بالمحطة" to "توقف مضخة وأثره التشغيلي والمالي") to ("workshop" to 12),
        ("ورش الصيانة الملحقة بالمحطة" to "إغلاق أمر العمل وفوترته") to ("workshop" to 13),
        ("ورش الصيانة الملحقة بالمحطة" to "أوامر عمل مفتوحة عند الإقفال") to ("workshop" to 14),
        ("ورش الصيانة الملحقة بالمحطة" to "مقارنة ميزانية الورشة بالتكلفة الفعلية") to ("workshop" to 15),
        ("ورش الصيانة الملحقة بالمحطة" to "مطابقة مخزون قطع الغيار") to ("workshop" to 16),
        ("ورش الصيانة الملحقة بالمحطة" to "صلاحيات اعتماد أمر العمل والفاتورة") to ("workshop" to 17),
        ("ورش الصيانة الملحقة بالمحطة" to "تكرار فاتورة ورشة/عميل") to ("workshop" to 18),
        ("ورش الصيانة الملحقة بالمحطة" to "تقارير ربحية الورشة وتكلفة الصيانة") to ("workshop" to 19)
    )

    private val dimensionRoutes = mapOf(
        "مسار سليم" to 0,
        "حدود ومدخلات غير صحيحة" to 1,
        "تكرار وتزامن" to 2,
        "صلاحيات وحالات مقفلة" to 3,
        "ترابط ومطابقة وتدقيق" to 4
    )

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) {
            "JVM user.dir is unavailable while locating the repository test catalogue"
        }
        var current: File? = File(userDir).canonicalFile
        while (current != null) {
            if (File(current, "all-test/اختبارات_عهد_النزول_وورش_الصيانة.json").isFile) return current
            current = current.parentFile
        }
        throw IllegalStateException("Could not locate repository root containing the 200-case catalogue")
    }

    private fun resolveCaseRoute(definition: JSONObject): CaseRoute {
        val axis = definition.getString("المحور")
        val scenarioTitle = definition.getString("السيناريو")
        val route = scenarioRoutes[axis to scenarioTitle]
            ?: throw IllegalArgumentException("No executable route for axis='" + axis + "', scenario='" + scenarioTitle + "'")
        val dimensionTitle = definition.getString("نوع_الفحص")
        val dimension = dimensionRoutes[dimensionTitle]
            ?: throw IllegalArgumentException("Unsupported verification dimension '" + dimensionTitle + "'")
        return CaseRoute(route.first, route.second, dimension)
    }

    /**
     * Every runtime case is resolved by stable catalogue ID and semantic
     * axis/scenario/dimension. Reversing the source order proves that execution
     * does not infer scenario identity from a row's ordinal position.
     */
    private fun loadCatalogueCases(reverseOrder: Boolean): LinkedHashMap<String, CatalogueCase> {
        val catalogueFile = File(repositoryRoot(), "all-test/اختبارات_عهد_النزول_وورش_الصيانة.json")
        val catalogue = JSONObject(catalogueFile.readText(Charsets.UTF_8))
        val sourceCases = catalogue.getJSONArray("cases")
        require(catalogue.optInt("count") == 200 && sourceCases.length() == 200) {
            "The acceptance catalogue must declare and contain exactly 200 cases"
        }
        require(scenarioRoutes.size == 40) { "The explicit route table must cover all 40 semantic scenarios" }

        val sourceIndexes = (0 until sourceCases.length()).toList()
        val indexes = if (reverseOrder) sourceIndexes.reversed() else sourceIndexes
        val resolved = LinkedHashMap<String, CatalogueCase>()
        val routeCoverage = mutableSetOf<Triple<String, Int, Int>>()
        val idPattern = Regex("^FUEL-CUST-WORK-(\\d{3})$")

        indexes.forEach { index ->
            val definition = sourceCases.getJSONObject(index)
            val id = definition.getString("id")
            val match = idPattern.matchEntire(id)
                ?: throw IllegalArgumentException("Invalid acceptance case ID '" + id + "'")
            val caseNumber = match.groupValues[1].toInt()
            require(caseNumber in 1..200) { "Acceptance case number is outside 001..200: " + id }
            require(!resolved.containsKey(id)) { "Duplicate acceptance case ID '" + id + "'" }
            val route = resolveCaseRoute(definition)
            require(routeCoverage.add(Triple(route.domain, route.scenario, route.dimension))) {
                "Duplicate executable route/dimension for case '" + id + "'"
            }
            resolved[id] = CatalogueCase(definition, route, caseNumber)
        }

        val expectedIds = (1..200).map { "FUEL-CUST-WORK-" + it.toString().padStart(3, '0') }.toSet()
        require(resolved.keys == expectedIds) {
            "Catalogue ID mismatch; missing=" + (expectedIds - resolved.keys) + ", unknown=" + (resolved.keys - expectedIds)
        }
        require(routeCoverage.size == 200) { "Every one of the 40 scenarios × 5 dimensions must map exactly once" }
        return resolved
    }

    private fun catalogueMetadata(definition: JSONObject): JSONObject = JSONObject()
        .put("axis", definition.optString("المحور"))
        .put("scenario", definition.optString("السيناريو"))
        .put("verification_dimension", definition.optString("نوع_الفحص"))
        .put("instructions", definition.optString("تعليمات_للوكيل"))
        .put("required_outputs", definition.optString("مخرجات_مطلوبة"))

    private fun databaseSnapshot(): JSONObject = JSONObject()
        .put("table_rows", tableCounts())
        .put("journal_entries", scalarLong("SELECT COUNT(*) FROM journal_entries"))
        .put("inventory_quantity", inventoryQuantity())

    private fun databaseSnapshotDelta(before: JSONObject, after: JSONObject): JSONObject {
        val beforeTables = before.getJSONObject("table_rows")
        val afterTables = after.getJSONObject("table_rows")
        val rowDeltas = JSONObject()
        val keys = afterTables.keys()
        while (keys.hasNext()) {
            val table = keys.next()
            rowDeltas.put(table, afterTables.optLong(table) - beforeTables.optLong(table))
        }
        return JSONObject()
            .put("table_row_deltas", rowDeltas)
            .put("journal_entry_delta", after.optLong("journal_entries") - before.optLong("journal_entries"))
            .put("inventory_quantity_delta", after.optDouble("inventory_quantity") - before.optDouble("inventory_quantity"))
    }

    @Test
    fun catalogueRoutingUsesIdsAndIsIndependentOfJsonOrder() {
        val originalOrder = loadCatalogueCases(reverseOrder = false)
        val reversedOrder = loadCatalogueCases(reverseOrder = true)
        val originalRoutes = originalOrder.mapValues { (_, item) ->
            Triple(item.route.domain, item.route.scenario, item.route.dimension)
        }
        val reversedRoutes = reversedOrder.mapValues { (_, item) ->
            Triple(item.route.domain, item.route.scenario, item.route.dimension)
        }
        assertEquals("All 200 IDs must resolve identically after catalogue reordering", originalRoutes, reversedRoutes)
        assertEquals(200, originalRoutes.size)
        assertEquals(200, originalRoutes.values.toSet().size)
    }

    @Test
    fun executesAllTwoHundredAcceptanceCasesAgainstRealSyntheticSQLite() {
        val cases = JSONArray()
        val failures = JSONArray()
        val catalogueCasesById = loadCatalogueCases(reverseOrder = true)

        for (number in 1..200) {
            val id = "FUEL-CUST-WORK-" + number.toString().padStart(3, '0')
            val catalogueCase = catalogueCasesById[id]
                ?: throw AssertionError("Catalogue case " + id + " was not resolved before execution")
            val before = databaseSnapshot()
            val startedAtNanos = System.nanoTime()
            try {
                val route = catalogueCase.route
                val result = when (route.domain) {
                    "custody" -> runCustodyCase(catalogueCase.caseNumber, route.scenario, route.dimension)
                    "workshop" -> runWorkshopCase(catalogueCase.caseNumber, route.scenario, route.dimension)
                    else -> throw IllegalStateException("Unsupported route domain '" + route.domain + "' for " + id)
                }
                val after = databaseSnapshot()
                val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
                cases.put(
                    JSONObject()
                        .put("id", id)
                        .put("catalogue", catalogueMetadata(catalogueCase.definition))
                        .put("route", JSONObject()
                            .put("domain", route.domain)
                            .put("scenario_index", route.scenario)
                            .put("verification_dimension_index", route.dimension))
                        .put("status", "PASS")
                        .put("duration_ms", durationMs)
                        .put("actual", result)
                        .put("sqlite_before", before)
                        .put("sqlite_after", after)
                        .put("sqlite_delta", databaseSnapshotDelta(before, after))
                )
                println("ACCEPTANCE " + id + " PASS " + result.optString("message", "verified") + " duration_ms=" + durationMs)
            } catch (error: Throwable) {
                val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
                val after = runCatching { databaseSnapshot() }
                    .getOrElse { JSONObject().put("snapshot_error", it.message ?: it.javaClass.simpleName) }
                val detail = id + ": " + (error.message ?: error.javaClass.simpleName)
                failures.put(detail)
                cases.put(
                    JSONObject()
                        .put("id", id)
                        .put("catalogue", catalogueMetadata(catalogueCase.definition))
                        .put("route", JSONObject()
                            .put("domain", catalogueCase.route.domain)
                            .put("scenario_index", catalogueCase.route.scenario)
                            .put("verification_dimension_index", catalogueCase.route.dimension))
                        .put("status", "FAIL")
                        .put("duration_ms", durationMs)
                        .put("error", detail)
                        .put("error_type", error.javaClass.name)
                        .put("stack_trace", error.stackTrace.take(12).joinToString("\n"))
                        .put("sqlite_before", before)
                        .put("sqlite_after", after)
                        .put("sqlite_delta", if (after.has("table_rows")) databaseSnapshotDelta(before, after) else JSONObject())
                )
                println("ACCEPTANCE " + id + " FAIL " + detail + " duration_ms=" + durationMs)
            }
        }

        val passed = (0 until cases.length()).count { cases.getJSONObject(it).optString("status") == "PASS" }
        val failed = (0 until cases.length()).count { cases.getJSONObject(it).optString("status") == "FAIL" }
        val report = JSONObject()
            .put("suite", "custody-workshop-acceptance")
            .put("catalogue", "all-test/اختبارات_عهد_النزول_وورش_الصيانة.json")
            .put("catalogue_count", 200)
            .put("total", 200)
            .put("executed", cases.length())
            .put("passed", passed)
            .put("failed", failed)
            .put("blocked", 0)
            .put("not_executed", 200 - cases.length())
            .put("routing", "stable case ID + explicit axis/scenario/dimension; catalogue was reversed before execution")
            .put("environment", "Robolectric + Android DatabaseHelper + isolated SQLite database + synthetic rows only")
            .put("cases", cases)
            .put("failures", failures)

        val reportFile = File(repositoryRoot(), "app/build/reports/custody-workshop-acceptance.json")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        println("CUSTODY_WORKSHOP_ACCEPTANCE_REPORT path=" + reportFile.absolutePath +
            " total=200 executed=" + cases.length() + " passed=" + passed + " failed=" + failed +
            " blocked=0 not_executed=" + (200 - cases.length()))

        assertEquals("Exactly 200 catalogue IDs must be executed", 200, cases.length())
        assertEquals("Every case must be PASS; failure details are in the report", 0, failed)
        assertEquals("Every case must be represented as executed PASS", 200, passed)
    }

    private fun runCustodyCase(caseNo: Int, scenario: Int, dimension: Int): JSONObject {
        if (dimension == 2) return runCustodyIdempotencyCase(caseNo, scenario)
        if (dimension == 3) return runCustodyPermissionCase(caseNo, scenario)
        if (dimension == 4) return runCustodyIntegrityCase(caseNo, scenario)
        if (dimension == 1) {
            val before = tableCounts()
            when (scenario) {
                0 -> expectRejected { custody("create", custodyCreateInput(caseNo, "invalid").put("amount", 0)) }
                1 -> {
                    val id = createApprovedCustody(caseNo, 100.0)
                    expectRejected { custody("disburse", custodyActionInput(caseNo, id).put("amount", -1).put("cash_box_id", cashBoxId)) }
                }
                2, 7, 8, 10, 11 -> {
                    val id = createIssuedCustody(caseNo, 100.0)
                    val p = custodyActionInput(caseNo, id).put("amount", -10)
                        .put("document_no", "INVALID-" + caseNo).put("document_hash", "INVALID-HASH-" + caseNo)
                    if (scenario == 10) p.put("expense_type", "inventory").put("quantity", 1)
                    if (scenario == 11) p.put("expense_type", "deposit_transit")
                    if (scenario == 8) p.put("amount", 10).put("document_no", "").put("document_hash", "")
                    if (scenario == 7) p.put("amount", 101)
                    expectRejected { custody("expense", p) }
                }
                3 -> {
                    val id = createIssuedCustody(caseNo, 100.0)
                    expectRejected { custody("return", custodyActionInput(caseNo, id).put("amount", -1).put("cash_box_id", cashBoxId)) }
                }
                4 -> {
                    val id = createIssuedCustody(caseNo, 100.0)
                    expectRejected { custody("settle", custodyActionInput(caseNo, id)) }
                }
                5, 6 -> {
                    val id = createIssuedCustody(caseNo, 100.0)
                    expectRejected { custody("variance", custodyActionInput(caseNo, id).put("amount", -1)
                        .put("variance_type", if (scenario == 5) "shortage" else "overage")) }
                }
                9 -> {
                    val id = createCustody(caseNo, 100.0)
                    expectRejected { custody("transfer", custodyActionInput(caseNo, id).put("to_employee_id", 999999)) }
                }
                12, 15, 19 -> {
                    val result = custody("list", JSONObject().put("limit", -50))
                    assertTrue(result.optBoolean("success"))
                    assertEquals(before.toString(), tableCounts().toString())
                }
                13 -> {
                    val id = createIssuedCustody(caseNo, 100.0)
                    expectRejected { custody("recover", custodyActionInput(caseNo, id).put("amount", -1)
                        .put("approval_reference", "TEST-APPROVAL")) }
                }
                14 -> {
                    val id = createCustody(caseNo, 100.0)
                    expectRejected { custody("reverse", custodyActionInput(caseNo, id).put("reason", "")) }
                }
                16 -> {
                    val id = createCustody(caseNo, 100.0)
                    expectRejected { CustodyWorkshopManager.executeCustody(helper, stationId, restrictedUserId,
                        "approve", custodyActionInput(caseNo, id)) }
                }
                17 -> {
                    val id = createApprovedCustody(caseNo, 100.0)
                    expectRejected { custody("disburse", custodyActionInput(caseNo, id).put("amount", -1).put("cash_box_id", cashBoxId)) }
                }
                18 -> {
                    val id = createCustody(caseNo, 100.0)
                    expectRejected { custody("configure", custodyActionInput(caseNo, id).put("account_id", -1)) }
                }
            }
            return JSONObject().put("message", "invalid amount, relationship, document, state or permission rejected with no partial write")
        }

        return when (scenario) {
            0 -> {
                val result = custody("create", custodyCreateInput(caseNo, "create-" + caseNo))
                assertEquals("draft", result.optString("status"))
                result
            }
            1, 17 -> {
                val id = createApprovedCustody(caseNo, 100.0)
                val key = "disburse-repeat-" + caseNo
                val p = custodyActionInput(caseNo, id).put("idempotency_key", key)
                    .put("amount", 70).put("bank_account_id", bankAccountId)
                val first = custody("disburse", p)
                val count = scalarLong("SELECT COUNT(*) FROM custody_movements WHERE custody_id=? AND movement_type='issue'", id)
                val second = custody("disburse", p)
                assertEquals(first.getLong("id"), second.getLong("id"))
                assertTrue(second.optBoolean("replayed"))
                assertEquals(count, scalarLong("SELECT COUNT(*) FROM custody_movements WHERE custody_id=? AND movement_type='issue'", id))
                first
            }
            2 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("expense", custodyActionInput(caseNo, id).put("amount", 25)
                    .put("document_no", "C-EXP-" + caseNo).put("document_hash", "C-EXP-HASH-" + caseNo))
            }
            3 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("return", custodyActionInput(caseNo, id).put("amount", 40).put("cash_box_id", cashBoxId))
            }
            4 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("return", custodyActionInput(caseNo, id).put("amount", 100).put("cash_box_id", cashBoxId))
                custody("settle", custodyActionInput(caseNo, id))
            }
            5, 6 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("variance", custodyActionInput(caseNo, id).put("amount", 15)
                    .put("variance_type", if (scenario == 5) "shortage" else "overage").put("reason", "synthetic stocktake report"))
            }
            7 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val before = tableCounts()
                expectRejected { custody("expense", custodyActionInput(caseNo, id).put("amount", 150)
                    .put("document_no", "OVER-" + caseNo).put("document_hash", "OVER-HASH-" + caseNo)) }
                assertEquals(before.toString(), tableCounts().toString())
                JSONObject().put("message", "expense above the authorized imprest was rejected without posting")
            }
            8 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                expectRejected { custody("expense", custodyActionInput(caseNo, id).put("amount", 10)) }
                val saved = custody("expense", custodyActionInput(caseNo, id).put("amount", 10)
                    .put("document_no", "DOC-" + caseNo).put("document_hash", "DOC-HASH-" + caseNo))
                expectRejected { custody("expense", custodyActionInput(caseNo, id).put("amount", 10)
                    .put("document_no", "DOC-" + caseNo).put("document_hash", "DOC-HASH-" + caseNo)) }
                saved
            }
            9 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("transfer", custodyActionInput(caseNo, id).put("to_employee_id", employeeTwo))
            }
            10 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("expense", custodyActionInput(caseNo, id).put("amount", 40).put("expense_type", "inventory")
                    .put("quantity", 2).put("product_id", productId).put("warehouse_id", warehouseId)
                    .put("document_no", "INV-" + caseNo).put("document_hash", "INV-HASH-" + caseNo))
            }
            11 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("expense", custodyActionInput(caseNo, id).put("amount", 20).put("expense_type", "deposit_transit")
                    .put("document_no", "TRANSIT-" + caseNo).put("document_hash", "TRANSIT-HASH-" + caseNo))
            }
            12 -> {
                createIssuedCustody(caseNo, 100.0)
                custody("ageing", JSONObject()).put("message", "outstanding imprests remain assets at period end")
            }
            13 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("recover", custodyActionInput(caseNo, id).put("amount", 35).put("approval_reference", "PAYROLL-APP-" + caseNo))
            }
            14 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("expense", custodyActionInput(caseNo, id).put("amount", 100)
                    .put("document_no", "REV-" + caseNo).put("document_hash", "REV-HASH-" + caseNo))
                custody("reverse", custodyActionInput(caseNo, id).put("reason", "approved reversal in isolated test"))
            }
            15 -> custody("ageing", JSONObject())
            16 -> {
                val id = createCustody(caseNo, 100.0)
                expectRejected { CustodyWorkshopManager.executeCustody(helper, stationId, restrictedUserId,
                    "approve", custodyActionInput(caseNo, id)) }
                JSONObject().put("message", "separation of requester/approver permission enforced")
            }
            18 -> {
                val id = createCustody(caseNo, 100.0)
                val accountId = scalarLong("SELECT id FROM accounts WHERE account_type='asset' AND is_active=1 LIMIT 1")
                custody("configure", custodyActionInput(caseNo, id).put("account_id", accountId))
            }
            else -> custody("reconcile", JSONObject())
        }
    }

    private fun runWorkshopCase(caseNo: Int, scenario: Int, dimension: Int): JSONObject {
        if (dimension == 2) return runWorkshopIdempotencyCase(caseNo, scenario)
        if (dimension == 3) return runWorkshopPermissionCase(caseNo, scenario)
        if (dimension == 4) return runWorkshopIntegrityCase(caseNo, scenario)
        if (dimension == 1) {
            val before = tableCounts()
            when (scenario) {
                0 -> expectRejected { workshop("create", workOrderCreateInput(caseNo, "invalid").put("asset_id", 0)) }
                1, 8 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("labor", workOrderActionInput(caseNo, id).put("employee_id", employeeOne)
                        .put("hours", -2).put("hourly_rate", 5)) }
                }
                2 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("issue_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                        .put("warehouse_id", warehouseId).put("quantity", -1)) }
                }
                3 -> {
                    val id = createInProgressOrder(caseNo, false)
                    val part = issuePart(caseNo, id)
                    expectRejected { workshop("return_part", workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", -1)) }
                }
                4 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("purchase_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                        .put("warehouse_id", warehouseId).put("quantity", -1).put("unit_cost", 20)) }
                }
                5 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("capitalize", workOrderActionInput(caseNo, id).put("amount", -10).put("decision", "capitalize")) }
                }
                6, 18 -> {
                    val id = createInProgressOrder(caseNo, true)
                    expectRejected { workshop("invoice", workOrderActionInput(caseNo, id).put("invoice_type", "customer").put("subtotal", -10)) }
                }
                7 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("estimate", workOrderActionInput(caseNo, id)
                        .put("estimate_labor_cost", -1).put("estimate_parts_cost", 2)) }
                }
                9 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("warranty", workOrderActionInput(caseNo, id).put("claim_amount", -1)) }
                }
                10 -> {
                    val id = createWorkOrderDraft(caseNo)
                    expectRejected { workshop("cancel", workOrderActionInput(caseNo, id).put("reason", "")) }
                }
                11 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("damage_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                        .put("warehouse_id", warehouseId).put("quantity", -1).put("damage_reason", "invalid")) }
                }
                12 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("downtime", workOrderActionInput(caseNo, id).put("downtime_minutes", -10)) }
                }
                13 -> {
                    val id = createWorkOrderDraft(caseNo)
                    expectRejected { workshop("close", workOrderActionInput(caseNo, id)) }
                }
                14, 19 -> {
                    val result = workshop("list", JSONObject().put("limit", 0))
                    assertTrue(result.optBoolean("success"))
                    assertEquals(before.toString(), tableCounts().toString())
                }
                15 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("budget", workOrderActionInput(caseNo, id).put("budget_year", 2026)
                        .put("budget_month", 14).put("budget_amount", 1)) }
                }
                16 -> {
                    val id = createInProgressOrder(caseNo, false)
                    expectRejected { workshop("stocktake", workOrderActionInput(caseNo, id).put("product_id", productId)
                        .put("warehouse_id", warehouseId).put("counted_quantity", -1).put("approve", true)
                        .put("approval_reference", "INV-AUTH")) }
                }
                17 -> expectRejected { CustodyWorkshopManager.executeWorkshop(helper, stationId, restrictedUserId,
                    "create", workOrderCreateInput(caseNo, "unauthorized")) }
            }
            return JSONObject().put("message", "invalid work order, amount, quantity, status or access was rejected without partial writes")
        }

        return when (scenario) {
            0 -> workshop("create", workOrderCreateInput(caseNo, "create-" + caseNo))
            1, 8 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("labor", workOrderActionInput(caseNo, id).put("employee_id", if (scenario == 8) employeeTwo else employeeOne)
                    .put("hours", if (scenario == 8) 4 else 2).put("hourly_rate", 15).put("work_date", "2026-11-01"))
            }
            2 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("issue_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("quantity", 2))
            }
            3 -> {
                val id = createInProgressOrder(caseNo, false)
                val part = issuePart(caseNo, id)
                workshop("return_part", workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", 1))
            }
            4 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("purchase_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("quantity", 2).put("unit_cost", 18))
            }
            5 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("capitalize", workOrderActionInput(caseNo, id).put("amount", 350)
                    .put("decision", if (caseNo % 2 == 0) "capitalize" else "expense").put("approval_reference", "CAP-APP-" + caseNo))
            }
            6 -> {
                val id = createInProgressOrder(caseNo, true)
                workshop("invoice", workOrderActionInput(caseNo, id).put("invoice_type", "customer")
                    .put("subtotal", 500).put("tax_rate", 15).put("invoice_code", "CUS-INV-" + caseNo))
            }
            7 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("estimate", workOrderActionInput(caseNo, id).put("estimate_labor_cost", 120)
                    .put("estimate_parts_cost", 300))
            }
            9 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("warranty", workOrderActionInput(caseNo, id).put("claim_type", "supplier")
                    .put("claim_amount", 200).put("reference_no", "WARRANTY-" + caseNo))
            }
            10 -> {
                val id = createInProgressOrder(caseNo, false)
                val part = issuePart(caseNo, id)
                workshop("return_part", workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", 1))
                workshop("cancel", workOrderActionInput(caseNo, id).put("reason", "cancel after inventory return"))
            }
            11 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("damage_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("quantity", 1).put("damage_reason", "test damage report"))
            }
            12 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("downtime", workOrderActionInput(caseNo, id).put("started_at", "2026-11-01 08:00:00")
                    .put("ended_at", "2026-11-01 09:00:00").put("downtime_minutes", 60)
                    .put("lost_sales_estimate", 1000))
            }
            13 -> {
                val id = createInProgressOrder(caseNo, true)
                workshop("invoice", workOrderActionInput(caseNo, id).put("invoice_type", "customer")
                    .put("subtotal", 300).put("tax_rate", 5).put("invoice_code", "CLOSE-INV-" + caseNo))
                workshop("close", workOrderActionInput(caseNo, id))
            }
            14 -> {
                createInProgressOrder(caseNo, false)
                workshop("list", JSONObject().put("status", "in_progress"))
            }
            15 -> {
                val id = createInProgressOrder(caseNo, false)
                workshop("budget", workOrderActionInput(caseNo, id).put("budget_year", 2026)
                    .put("budget_month", 11).put("cost_center", "WORKSHOP").put("budget_amount", 5000))
                workshop("budget_report", JSONObject().put("budget_year", 2026).put("budget_month", 11))
            }
            16 -> {
                val id = createInProgressOrder(caseNo, false)
                val before = inventoryQuantity()
                val result = workshop("stocktake", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("counted_quantity", before + 3)
                    .put("approve", true).put("approval_reference", "STOCKTAKE-APP-" + caseNo))
                assertEquals(before + 3, inventoryQuantity(), 0.0001)
                result
            }
            17 -> {
                expectRejected { CustodyWorkshopManager.executeWorkshop(helper, stationId, restrictedUserId,
                    "create", workOrderCreateInput(caseNo, "denied-create-" + caseNo)) }
                JSONObject().put("message", "requester cannot approve/create without the dedicated workshop permission")
            }
            18 -> {
                val id = createInProgressOrder(caseNo, true)
                val p = workOrderActionInput(caseNo, id).put("invoice_type", "customer").put("subtotal", 220)
                    .put("tax_rate", 0).put("invoice_code", "IDEM-INV-" + caseNo)
                    .put("idempotency_key", "same-invoice-" + caseNo)
                val first = workshop("invoice", p)
                val count = scalarLong("SELECT COUNT(*) FROM work_order_invoices WHERE work_order_id=?", id)
                val second = workshop("invoice", p)
                assertTrue(second.optBoolean("replayed"))
                assertEquals(count, scalarLong("SELECT COUNT(*) FROM work_order_invoices WHERE work_order_id=?", id))
                first
            }
            else -> workshop("profitability", JSONObject())
        }
    }


    /**
     * Dimension 2 exercises both concurrent duplicate submissions and a later
     * sequential replay. Every mutation must be applied once, with no duplicate
     * journals, journal lines, inventory movements, or stock effect.
     */
    private fun assertIdempotentReplay(block: () -> JSONObject, label: String): JSONObject {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            executor.submit(Callable {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS)) { label + " concurrent start gate timed out" }
                block()
            })
        }

        val results = try {
            assertTrue(label + " concurrent workers did not become ready", ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(label + " concurrent workers did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        }

        assertEquals(label + " must return exactly two responses", 2, results.size)
        val created = results.filterNot { it.optBoolean("replayed") }
        val replayed = results.filter { it.optBoolean("replayed") }
        assertEquals(label + " must create exactly one resource under concurrency", 1, created.size)
        assertEquals(label + " must replay the competing request", 1, replayed.size)
        val first = created.single()
        val second = replayed.single()
        assertTrue(label + " original command failed: " + first, first.optBoolean("success"))
        assertTrue(label + " concurrent replay failed: " + second, second.optBoolean("success"))
        assertEquals(label + " resource ID changed on concurrent replay", first.optLong("id"), second.optLong("id"))

        val rowsAfterConcurrentPair = tableCounts()
        val journalsAfterConcurrentPair = scalarLong("SELECT COUNT(*) FROM journal_entries")
        val linesAfterConcurrentPair = scalarLong("SELECT COUNT(*) FROM journal_entry_items")
        val movementsAfterConcurrentPair = scalarLong("SELECT COUNT(*) FROM inventory_movements")
        val stockAfterConcurrentPair = inventoryQuantity()

        val third = block()
        assertTrue(label + " subsequent retry was not recognized", third.optBoolean("replayed"))
        assertEquals(label + " resource ID changed on subsequent retry", first.optLong("id"), third.optLong("id"))
        assertEquals(label + " SQLite rows changed after replay", rowsAfterConcurrentPair.toString(), tableCounts().toString())
        assertEquals(label + " journal count changed after replay", journalsAfterConcurrentPair,
            scalarLong("SELECT COUNT(*) FROM journal_entries"))
        assertEquals(label + " journal lines changed after replay", linesAfterConcurrentPair,
            scalarLong("SELECT COUNT(*) FROM journal_entry_items"))
        assertEquals(label + " inventory movements changed after replay", movementsAfterConcurrentPair,
            scalarLong("SELECT COUNT(*) FROM inventory_movements"))
        assertEquals(label + " inventory quantity changed after replay", stockAfterConcurrentPair, inventoryQuantity(), 0.0001)

        return JSONObject(first.toString())
            .put("idempotent_replay_verified", true)
            .put("concurrent_request_count", 2)
            .put("concurrent_replay_verified", true)
            .put("replay_message", second.optString("message"))
            .put("subsequent_replay_message", third.optString("message"))
    }

    private fun assertReadOnlyRepeat(block: () -> JSONObject, label: String): JSONObject {
        val before = tableCounts()
        val journalCount = scalarLong("SELECT COUNT(*) FROM journal_entries")
        val movementCount = scalarLong("SELECT COUNT(*) FROM inventory_movements")
        val stockBefore = inventoryQuantity()
        val first = block()
        assertTrue(label + " first read failed: " + first, first.optBoolean("success"))
        assertEquals(label + " first read mutated SQLite", before.toString(), tableCounts().toString())
        val second = block()
        assertTrue(label + " second read failed: " + second, second.optBoolean("success"))
        assertEquals(label + " repeated read mutated SQLite", before.toString(), tableCounts().toString())
        assertEquals(journalCount, scalarLong("SELECT COUNT(*) FROM journal_entries"))
        assertEquals(movementCount, scalarLong("SELECT COUNT(*) FROM inventory_movements"))
        assertEquals(stockBefore, inventoryQuantity(), 0.0001)
        return JSONObject(first.toString()).put("read_repeat_verified", true)
    }

    private fun assertPermissionDeniedWithoutWrites(
        permission: String,
        operation: () -> JSONObject
    ): JSONObject {
        val before = tableCounts()
        val journals = scalarLong("SELECT COUNT(*) FROM journal_entries")
        val journalLines = scalarLong("SELECT COUNT(*) FROM journal_entry_items")
        val movements = scalarLong("SELECT COUNT(*) FROM inventory_movements")
        val stock = inventoryQuantity()
        val failure = runCatching { operation() }.exceptionOrNull()
        assertTrue("Expected SecurityException for " + permission + " but received " +
            (failure?.javaClass?.name ?: "no exception"), failure is SecurityException)
        assertEquals(permission + " unauthorized attempt changed SQLite rows", before.toString(), tableCounts().toString())
        assertEquals(permission + " unauthorized attempt created journal", journals, scalarLong("SELECT COUNT(*) FROM journal_entries"))
        assertEquals(permission + " unauthorized attempt created journal lines", journalLines, scalarLong("SELECT COUNT(*) FROM journal_entry_items"))
        assertEquals(permission + " unauthorized attempt moved inventory", movements, scalarLong("SELECT COUNT(*) FROM inventory_movements"))
        assertEquals(permission + " unauthorized attempt changed stock", stock, inventoryQuantity(), 0.0001)
        return JSONObject().put("success", true).put("message", "denied before mutation")
            .put("permission", permission).put("exception", failure!!.javaClass.simpleName)
            .put("sqlite_unchanged", true).put("journal_unchanged", true).put("inventory_unchanged", true)
    }

    private fun runCustodyIdempotencyCase(caseNo: Int, scenario: Int): JSONObject {
        val key = "custody-retry-" + caseNo
        return when (scenario) {
            0 -> {
                val input = custodyCreateInput(caseNo, "retry-create-" + caseNo)
                assertIdempotentReplay({ custody("create", input) }, "custody create")
            }
            1, 17 -> {
                val id = createApprovedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key)
                    .put("amount", 70.0).put("cash_box_id", cashBoxId)
                assertIdempotentReplay({ custody("disburse", input) }, "custody disbursement")
            }
            2, 7 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("amount", 25.0)
                    .put("document_no", "DUP-EXP-" + caseNo).put("document_hash", "DUP-EXP-HASH-" + caseNo)
                assertIdempotentReplay({ custody("expense", input) }, "custody expense")
            }
            3 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key)
                    .put("amount", 40.0).put("cash_box_id", cashBoxId)
                assertIdempotentReplay({ custody("return", input) }, "custody cash return")
            }
            4 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("return", custodyActionInput(caseNo, id).put("amount", 100.0)
                    .put("cash_box_id", cashBoxId).put("idempotency_key", key + "-return"))
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key + "-settle")
                assertIdempotentReplay({ custody("settle", input) }, "custody settlement")
            }
            5, 6 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("amount", 15.0)
                    .put("variance_type", if (scenario == 5) "shortage" else "overage")
                    .put("reason", "Synthetic counted variance")
                assertIdempotentReplay({ custody("variance", input) }, "custody variance")
            }
            8 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val number = "DUPLICATE-RECEIPT-" + caseNo
                val hash = "DUPLICATE-RECEIPT-HASH-" + caseNo
                val first = custody("expense", custodyActionInput(caseNo, id).put("amount", 10.0)
                    .put("document_no", number).put("document_hash", hash))
                val afterFirst = tableCounts()
                expectRejected {
                    custody("expense", custodyActionInput(caseNo, id).put("amount", 10.0)
                        .put("document_no", number).put("document_hash", hash))
                }
                assertEquals("duplicate receipt must not create a second expense", afterFirst.toString(), tableCounts().toString())
                JSONObject(first.toString()).put("duplicate_document_rejected", true)
                    .put("sqlite_unchanged_after_duplicate", true)
            }
            9 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("to_employee_id", employeeTwo)
                assertIdempotentReplay({ custody("transfer", input) }, "custody transfer")
            }
            10 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("amount", 40.0)
                    .put("expense_type", "inventory").put("quantity", 2.0).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("document_no", "INV-RETRY-" + caseNo)
                    .put("document_hash", "INV-RETRY-HASH-" + caseNo)
                assertIdempotentReplay({ custody("expense", input) }, "custody inventory purchase")
            }
            11 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("amount", 20.0)
                    .put("expense_type", "deposit_transit").put("document_no", "TRANSIT-RETRY-" + caseNo)
                    .put("document_hash", "TRANSIT-RETRY-HASH-" + caseNo)
                assertIdempotentReplay({ custody("expense", input) }, "custody deposit transit")
            }
            12, 15 -> assertReadOnlyRepeat({ custody("ageing", JSONObject()) }, "custody ageing")
            13 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("amount", 35.0)
                    .put("approval_reference", "PAYROLL-REPLAY-" + caseNo)
                assertIdempotentReplay({ custody("recover", input) }, "custody payroll recovery")
            }
            14 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("return", custodyActionInput(caseNo, id).put("amount", 100.0)
                    .put("cash_box_id", cashBoxId).put("idempotency_key", key + "-return"))
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key + "-reverse")
                    .put("reason", "Synthetic approved reversal")
                assertIdempotentReplay({ custody("reverse", input) }, "custody reversal")
            }
            16 -> {
                val id = createCustody(caseNo, 100.0)
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("approved_amount", 100.0)
                assertIdempotentReplay({ custody("approve", input) }, "custody approval")
            }
            18 -> {
                val id = createCustody(caseNo, 100.0)
                val accountId = scalarLong("SELECT id FROM accounts WHERE account_type='asset' AND is_active=1 AND is_deleted=0 LIMIT 1")
                val input = custodyActionInput(caseNo, id).put("idempotency_key", key).put("account_id", accountId)
                assertIdempotentReplay({ custody("configure", input) }, "custody account mapping")
            }
            else -> assertReadOnlyRepeat({ custody("reconcile", JSONObject()) }, "custody ledger reconciliation")
        }
    }

    private fun runCustodyPermissionCase(caseNo: Int, scenario: Int): JSONObject {
        val key = "custody-denied-" + caseNo
        val (action, input, permission) = when (scenario) {
            0 -> Triple("create", custodyCreateInput(caseNo, key), "custody.create")
            1, 17 -> {
                val id = createApprovedCustody(caseNo, 100.0)
                Triple("disburse", custodyActionInput(caseNo, id).put("amount", 50.0)
                    .put("cash_box_id", cashBoxId), "custody.create")
            }
            2, 7, 8 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("expense", custodyActionInput(caseNo, id).put("amount", 20.0)
                    .put("document_no", "PERM-EXP-" + caseNo).put("document_hash", "PERM-EXP-HASH-" + caseNo), "custody.update")
            }
            3 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("return", custodyActionInput(caseNo, id).put("amount", 20.0).put("cash_box_id", cashBoxId), "custody.update")
            }
            4 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("return", custodyActionInput(caseNo, id).put("amount", 100.0).put("cash_box_id", cashBoxId))
                Triple("settle", custodyActionInput(caseNo, id), "custody.update")
            }
            5, 6 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("variance", custodyActionInput(caseNo, id).put("amount", 10.0)
                    .put("variance_type", if (scenario == 5) "shortage" else "overage").put("reason", "Permission fixture"), "custody.update")
            }
            9 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("transfer", custodyActionInput(caseNo, id).put("to_employee_id", employeeTwo), "custody.update")
            }
            10 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("expense", custodyActionInput(caseNo, id).put("amount", 40.0).put("expense_type", "inventory")
                    .put("quantity", 2.0).put("product_id", productId).put("warehouse_id", warehouseId)
                    .put("document_no", "PERM-INV-" + caseNo).put("document_hash", "PERM-INV-HASH-" + caseNo), "custody.update")
            }
            11 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("expense", custodyActionInput(caseNo, id).put("amount", 20.0).put("expense_type", "deposit_transit")
                    .put("document_no", "PERM-TRANSIT-" + caseNo).put("document_hash", "PERM-TRANSIT-HASH-" + caseNo), "custody.update")
            }
            12, 15 -> Triple("ageing", JSONObject(), "custody.read")
            13 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                Triple("recover", custodyActionInput(caseNo, id).put("amount", 25.0)
                    .put("approval_reference", "PERM-RECOVER-" + caseNo), "custody.update")
            }
            14 -> {
                val id = createIssuedCustody(caseNo, 100.0)
                custody("return", custodyActionInput(caseNo, id).put("amount", 100.0).put("cash_box_id", cashBoxId))
                Triple("reverse", custodyActionInput(caseNo, id).put("reason", "Permission fixture"), "custody.delete")
            }
            16 -> {
                val id = createCustody(caseNo, 100.0)
                Triple("approve", custodyActionInput(caseNo, id).put("approved_amount", 100.0), "custody.approve")
            }
            18 -> {
                val id = createCustody(caseNo, 100.0)
                val accountId = scalarLong("SELECT id FROM accounts WHERE account_type='asset' AND is_active=1 AND is_deleted=0 LIMIT 1")
                Triple("configure", custodyActionInput(caseNo, id).put("account_id", accountId), "custody.update")
            }
            else -> Triple("reconcile", JSONObject(), "custody.read")
        }
        return assertPermissionDeniedWithoutWrites(permission) {
            CustodyWorkshopManager.executeCustody(helper, stationId, restrictedUserId, action, input)
        }
    }

    private fun runCustodyIntegrityCase(caseNo: Int, scenario: Int): JSONObject {
        val executed = runCustodyCase(caseNo, scenario, 0)
        val reconciliation = custody("reconcile", JSONObject())
        assertTrue("custody integrity check failed: " + reconciliation, reconciliation.optBoolean("passed"))
        val auditCount = scalarLong("SELECT COUNT(*) FROM custody_audit_events WHERE station_id=?", stationId)
        assertTrue("custody audit ledger is empty", auditCount > 0)
        val unbalanced = scalarLong("SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND status='posted' AND ABS(total_debit-total_credit)>0.01",
            stationId)
        assertEquals("posted custody journals must balance", 0L, unbalanced)
        return JSONObject().put("message", "the scenario executed and station custody/GL/audit reconciliation passed")
            .put("scenario_result", executed).put("reconciliation", reconciliation).put("station_audit_events", auditCount)
    }

    private fun runWorkshopIdempotencyCase(caseNo: Int, scenario: Int): JSONObject {
        val key = "workshop-retry-" + caseNo
        return when (scenario) {
            0 -> {
                val input = workOrderCreateInput(caseNo, "retry-create-" + caseNo)
                assertIdempotentReplay({ workshop("create", input) }, "work order create")
            }
            1, 8 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("employee_id", if (scenario == 8) employeeTwo else employeeOne)
                    .put("hours", if (scenario == 8) 4.0 else 2.0).put("hourly_rate", 15.0).put("work_date", "2026-11-01")
                assertIdempotentReplay({ workshop("labor", input) }, "technician labor")
            }
            2 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("product_id", productId).put("warehouse_id", warehouseId).put("quantity", 2.0)
                assertIdempotentReplay({ workshop("issue_part", input) }, "work order parts issue")
            }
            3 -> {
                val id = createInProgressOrder(caseNo, false)
                val part = issuePart(caseNo, id)
                val input = workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", 1.0)
                assertIdempotentReplay({ workshop("return_part", input) }, "work order part return")
            }
            4 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("product_id", productId).put("warehouse_id", warehouseId)
                    .put("quantity", 2.0).put("unit_cost", 18.0)
                assertIdempotentReplay({ workshop("purchase_part", input) }, "credit purchase of spare parts")
            }
            5 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("amount", 350.0)
                    .put("decision", if (caseNo % 2 == 0) "capitalize" else "expense")
                    .put("approval_reference", "CAP-RETRY-" + caseNo)
                assertIdempotentReplay({ workshop("capitalize", input) }, "maintenance capitalization decision")
            }
            6, 18 -> {
                val id = createInProgressOrder(caseNo, true)
                val input = workOrderActionInput(caseNo, id).put("invoice_type", "customer")
                    .put("subtotal", 500.0).put("tax_rate", 15.0).put("invoice_code", "DUP-INV-" + caseNo)
                assertIdempotentReplay({ workshop("invoice", input) }, "workshop customer invoice")
            }
            7 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("estimate_labor_cost", 120.0).put("estimate_parts_cost", 300.0)
                assertIdempotentReplay({ workshop("estimate", input) }, "work order estimate update")
            }
            9 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("claim_type", "supplier").put("claim_amount", 200.0)
                    .put("reference_no", "WARRANTY-RETRY-" + caseNo)
                assertIdempotentReplay({ workshop("warranty", input) }, "warranty claim")
            }
            10 -> {
                val id = createInProgressOrder(caseNo, false)
                val part = issuePart(caseNo, id)
                workshop("return_part", workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", 1.0))
                val input = workOrderActionInput(caseNo, id).put("reason", "Cancel after returned synthetic parts")
                assertIdempotentReplay({ workshop("cancel", input) }, "work order cancellation")
            }
            11 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("product_id", productId).put("warehouse_id", warehouseId)
                    .put("quantity", 1.0).put("damage_reason", "Synthetic damaged part")
                assertIdempotentReplay({ workshop("damage_part", input) }, "workshop damaged part")
            }
            12 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("started_at", "2026-11-01 08:00:00")
                    .put("ended_at", "2026-11-01 09:00:00").put("downtime_minutes", 60.0).put("lost_sales_estimate", 1000.0)
                assertIdempotentReplay({ workshop("downtime", input) }, "asset downtime")
            }
            13 -> {
                val id = createInProgressOrder(caseNo, true)
                workshop("invoice", workOrderActionInput(caseNo, id).put("invoice_type", "customer").put("subtotal", 300.0)
                    .put("tax_rate", 5.0).put("invoice_code", "CLOSE-RETRY-INV-" + caseNo))
                val input = workOrderActionInput(caseNo, id)
                assertIdempotentReplay({ workshop("close", input) }, "work order close")
            }
            14 -> assertReadOnlyRepeat({ workshop("list", JSONObject().put("status", "in_progress")) }, "open work orders report")
            15 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("budget_year", 2026).put("budget_month", 11)
                    .put("cost_center", "WORKSHOP").put("budget_amount", 5000.0)
                assertIdempotentReplay({ workshop("budget", input) }, "workshop budget")
            }
            16 -> {
                val id = createInProgressOrder(caseNo, false)
                val input = workOrderActionInput(caseNo, id).put("product_id", productId).put("warehouse_id", warehouseId)
                    .put("counted_quantity", inventoryQuantity() + 3.0).put("approve", true)
                    .put("approval_reference", "STOCKTAKE-RETRY-" + caseNo)
                assertIdempotentReplay({ workshop("stocktake", input) }, "parts stocktake")
            }
            17 -> {
                val id = createWorkOrderDraft(caseNo)
                val input = workOrderActionInput(caseNo, id)
                assertIdempotentReplay({ workshop("approve", input) }, "work order approval")
            }
            else -> assertReadOnlyRepeat({ workshop("profitability", JSONObject()) }, "workshop profitability report")
        }
    }

    private fun runWorkshopPermissionCase(caseNo: Int, scenario: Int): JSONObject {
        val (action, input, permission) = when (scenario) {
            0 -> Triple("create", workOrderCreateInput(caseNo, "permission-" + caseNo), "workshop.create")
            1, 8 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("labor", workOrderActionInput(caseNo, id).put("employee_id", employeeOne)
                    .put("hours", 2.0).put("hourly_rate", 15.0), "workshop.update")
            }
            2 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("issue_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("quantity", 1.0), "workshop.create")
            }
            3 -> {
                val id = createInProgressOrder(caseNo, false)
                val part = issuePart(caseNo, id)
                Triple("return_part", workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", 1.0), "workshop.update")
            }
            4 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("purchase_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("quantity", 1.0).put("unit_cost", 18.0), "workshop.create")
            }
            5 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("capitalize", workOrderActionInput(caseNo, id).put("amount", 150.0).put("decision", "expense")
                    .put("approval_reference", "CAP-PERM-" + caseNo), "workshop.update")
            }
            6, 18 -> {
                val id = createInProgressOrder(caseNo, true)
                Triple("invoice", workOrderActionInput(caseNo, id).put("invoice_type", "customer")
                    .put("subtotal", 100.0).put("tax_rate", 15.0).put("invoice_code", "PERM-INV-" + caseNo), "workshop.create")
            }
            7 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("estimate", workOrderActionInput(caseNo, id).put("estimate_labor_cost", 50.0)
                    .put("estimate_parts_cost", 75.0), "workshop.update")
            }
            9 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("warranty", workOrderActionInput(caseNo, id).put("claim_type", "supplier")
                    .put("claim_amount", 50.0).put("reference_no", "PERM-WARRANTY-" + caseNo), "workshop.update")
            }
            10 -> {
                val id = createInProgressOrder(caseNo, false)
                val part = issuePart(caseNo, id)
                workshop("return_part", workOrderActionInput(caseNo, id).put("part_id", part).put("quantity", 1.0))
                Triple("cancel", workOrderActionInput(caseNo, id).put("reason", "Permission test"), "workshop.update")
            }
            11 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("damage_part", workOrderActionInput(caseNo, id).put("product_id", productId)
                    .put("warehouse_id", warehouseId).put("quantity", 1.0).put("damage_reason", "Permission test damage"),
                    "workshop.update")
            }
            12 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("downtime", workOrderActionInput(caseNo, id).put("started_at", "2026-11-01 08:00:00")
                    .put("ended_at", "2026-11-01 09:00:00").put("downtime_minutes", 60.0), "workshop.update")
            }
            13 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("close", workOrderActionInput(caseNo, id), "workshop.update")
            }
            14 -> Triple("list", JSONObject().put("status", "in_progress"), "workshop.read")
            15 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("budget", workOrderActionInput(caseNo, id).put("budget_year", 2026).put("budget_month", 11)
                    .put("cost_center", "WORKSHOP").put("budget_amount", 500.0), "workshop.update")
            }
            16 -> {
                val id = createInProgressOrder(caseNo, false)
                Triple("stocktake", workOrderActionInput(caseNo, id).put("product_id", productId).put("warehouse_id", warehouseId)
                    .put("counted_quantity", inventoryQuantity() + 2.0).put("approve", true).put("approval_reference", "PERM-STOCK-" + caseNo),
                    "workshop.update")
            }
            17 -> Triple("approve", workOrderActionInput(caseNo, createWorkOrderDraft(caseNo)), "workshop.approve")
            else -> Triple("profitability", JSONObject(), "workshop.read")
        }
        return assertPermissionDeniedWithoutWrites(permission) {
            CustodyWorkshopManager.executeWorkshop(helper, stationId, restrictedUserId, action, input)
        }
    }

    private fun runWorkshopIntegrityCase(caseNo: Int, scenario: Int): JSONObject {
        val executed = runWorkshopCase(caseNo, scenario, 0)
        val reconciliation = workshop("reconcile", JSONObject())
        assertTrue("workshop reconciliation failed: " + reconciliation, reconciliation.optBoolean("passed"))
        val auditCount = scalarLong("SELECT COUNT(*) FROM work_order_audit_events WHERE station_id=?", stationId)
        assertTrue("work order audit ledger is empty", auditCount > 0)
        val unbalanced = scalarLong("SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND status='posted' AND ABS(total_debit-total_credit)>0.01",
            stationId)
        assertEquals("posted workshop journals must balance", 0L, unbalanced)
        return JSONObject().put("message", "the scenario executed and work order/stock/GL/audit reconciliation passed")
            .put("scenario_result", executed).put("reconciliation", reconciliation).put("station_audit_events", auditCount)
    }

    private fun createCustody(caseNo: Int, amount: Double): Long =
        custody("create", custodyCreateInput(caseNo, "base-" + caseNo).put("amount", amount)).getLong("id")

    private fun createApprovedCustody(caseNo: Int, amount: Double): Long {
        val id = createCustody(caseNo, amount)
        custody("approve", custodyActionInput(caseNo, id).put("approved_amount", amount))
        return id
    }

    private fun createIssuedCustody(caseNo: Int, amount: Double): Long {
        val id = createApprovedCustody(caseNo, amount)
        custody("disburse", custodyActionInput(caseNo, id).put("amount", amount).put("cash_box_id", cashBoxId))
        return id
    }

    private fun createWorkOrderDraft(caseNo: Int): Long =
        workshop("create", workOrderCreateInput(caseNo, "draft-" + caseNo)).getLong("id")

    private fun createInProgressOrder(caseNo: Int, billable: Boolean): Long {
        val id = workshop("create", workOrderCreateInput(caseNo, "active-" + caseNo).put("bill_to_customer", billable)).getLong("id")
        workshop("approve", workOrderActionInput(caseNo, id))
        workshop("start", workOrderActionInput(caseNo, id))
        return id
    }

    private fun issuePart(caseNo: Int, orderId: Long): Long =
        workshop("issue_part", workOrderActionInput(caseNo, orderId).put("product_id", productId)
            .put("warehouse_id", warehouseId).put("quantity", 1)).getLong("part_id")

    private fun custodyCreateInput(caseNo: Int, suffix: String): JSONObject =
        JSONObject().put("idempotency_key", "c-create-" + suffix).put("employee_id", employeeOne)
            .put("amount", 100).put("purpose", "synthetic operating imprest " + suffix)
            .put("custody_code", "CST-" + suffix + "-" + UUID.randomUUID().toString().take(5))

    private fun custodyActionInput(caseNo: Int, id: Long): JSONObject =
        JSONObject().put("custody_id", id).put("idempotency_key", "c-" + caseNo + "-" + UUID.randomUUID().toString())

    private fun workOrderCreateInput(caseNo: Int, suffix: String): JSONObject =
        JSONObject().put("idempotency_key", "wo-create-" + suffix).put("asset_type", "fixed_asset")
            .put("asset_id", assetId).put("order_code", "WO-" + suffix + "-" + UUID.randomUUID().toString().take(5))
            .put("title", "Synthetic maintenance " + suffix).put("description", "Isolated acceptance fixture")
            .put("cost_center", "WORKSHOP")

    private fun workOrderActionInput(caseNo: Int, id: Long): JSONObject =
        JSONObject().put("work_order_id", id).put("idempotency_key", "wo-" + caseNo + "-" + UUID.randomUUID().toString())

    private fun custody(action: String, input: JSONObject): JSONObject =
        CustodyWorkshopManager.executeCustody(helper, stationId, adminId, action, input)

    private fun workshop(action: String, input: JSONObject): JSONObject =
        CustodyWorkshopManager.executeWorkshop(helper, stationId, adminId, action, input)

    private fun expectRejected(block: () -> Any?) {
        val before = tableCounts()
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("operation should be rejected", rejected)
        assertEquals("rejected operation must not leave partial rows", before.toString(), tableCounts().toString())
    }

    private fun tableCounts(): JSONObject {
        val names = listOf("operating_custodies", "custody_movements", "custody_documents",
            "custody_audit_events", "work_orders", "work_order_parts", "work_order_labor",
            "work_order_invoices", "work_order_audit_events", "work_order_capitalizations", "workshop_budgets", "journal_entries",
            "journal_entry_items", "inventory_movements", "inventory_ledger", "custody_workshop_idempotency")
        val out = JSONObject()
        names.forEach { out.put(it, scalarLong("SELECT COUNT(*) FROM " + it)) }
        return out
    }

    private fun inventoryQuantity(): Double =
        db.rawQuery("SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    private fun scalarLong(sql: String, vararg args: Any): Long =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun insertUser(prefix: String, roleId: Int): Long =
        db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("username", prefix + "-" + UUID.randomUUID().toString().take(6))
            put("password_hash", "synthetic-test-hash"); put("full_name", prefix)
            put("role_id", roleId); put("station_id", stationId); put("status", "active")
        })

    private fun insertEmployee(prefix: String): Long =
        db.insertOrThrow("employees", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("employee_code", "EMP-" + prefix + "-" + UUID.randomUUID().toString().take(4))
            put("full_name", "Test " + prefix); put("job_title", "Technician"); put("hire_date", "2026-01-01")
            put("station_id", stationId); put("status", "active")
        })

    private fun insertCashBox(): Long =
        db.insertOrThrow("cash_boxes", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("box_code", "CWB-" + UUID.randomUUID().toString().take(8))
            put("box_name", "Acceptance test safe"); put("station_id", stationId)
            put("opening_balance", 1000000.0); put("current_balance", 1000000.0); put("status", "active")
            put("created_by", adminId)
        })

    private fun insertBankAccount(): Long {
        var bankId = scalarLong("SELECT id FROM banks ORDER BY id LIMIT 1")
        if (bankId == 0L) {
            bankId = db.insertOrThrow("banks", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("bank_code", "CWBANK-" + UUID.randomUUID().toString().take(6))
                put("bank_name", "Synthetic test bank")
            })
        }
        return db.insertOrThrow("bank_accounts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("account_code", "CWBA-" + UUID.randomUUID().toString().take(8))
            put("bank_id", bankId); put("station_id", stationId); put("account_name", "Synthetic test bank account")
            put("account_number", "TEST-" + UUID.randomUUID().toString().take(8))
            put("opening_balance", 1000000.0); put("current_balance", 1000000.0); put("available_balance", 1000000.0)
            put("status", "active"); put("created_by", adminId)
        })
    }

    private fun insertWarehouse(): Long =
        db.insertOrThrow("warehouses", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("station_id", stationId)
            put("warehouse_name", "Acceptance warehouse " + UUID.randomUUID().toString().take(8))
            put("location_details", "synthetic test fixture"); put("is_default", 0); put("is_active", 1)
        })

    private fun insertProduct(): Long {
        var category = scalarLong("SELECT id FROM product_categories ORDER BY id LIMIT 1")
        if (category == 0L) {
            category = db.insertOrThrow("product_categories", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("category_code", "CW-CAT-" + UUID.randomUUID().toString().take(6))
                put("category_name", "Synthetic Workshop Parts"); put("category_type", "product")
            })
        }
        var unit = scalarLong("SELECT id FROM units ORDER BY id LIMIT 1")
        if (unit == 0L) {
            unit = db.insertOrThrow("units", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("unit_name", "CW Piece " + UUID.randomUUID().toString().take(5))
                put("unit_symbol", "cw" + UUID.randomUUID().toString().take(5)); put("category", "product")
            })
        }
        return db.insertOrThrow("products", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("product_code", "CW-PART-" + UUID.randomUUID().toString().take(7))
            put("product_name", "Synthetic pump seal"); put("product_name_ar", "قطعة اختبار اصطناعية")
            put("category_id", category); put("unit_id", unit); put("station_id", stationId)
            put("purchase_price", 20.0); put("sale_price", 25.0); put("quantity", 500.0); put("status", "active")
            put("created_by", adminId)
        })
    }

    private fun insertAsset(): Long =
        db.insertOrThrow("fixed_assets", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("station_id", stationId)
            put("asset_code", "CW-ASSET-" + UUID.randomUUID().toString().take(7))
            put("asset_name", "Synthetic station generator"); put("asset_type", "generator")
            put("purchase_cost", 10000.0); put("current_value", 9000.0); put("status", "active")
        })
}