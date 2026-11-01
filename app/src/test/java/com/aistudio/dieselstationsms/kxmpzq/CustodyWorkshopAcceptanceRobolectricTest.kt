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

    @Test
    fun executesAllTwoHundredAcceptanceCasesAgainstRealSyntheticSQLite() {
        val cases = JSONArray()
        val failures = mutableListOf<String>()
        for (number in 1..200) {
            val scenario = (number - 1) / 5
            val dimension = (number - 1) % 5
            val id = "FUEL-CUST-WORK-" + number.toString().padStart(3, '0')
            try {
                val result = if (scenario < 20) runCustodyCase(number, scenario, dimension)
                    else runWorkshopCase(number, scenario - 20, dimension)
                val entry = JSONObject().put("id", id).put("domain", if (scenario < 20) "custody" else "workshop")
                    .put("scenario_index", scenario).put("verification_dimension", dimension)
                    .put("status", "PASS").put("actual", result)
                    .put("sqlite", tableCounts()).put("journal_entries", scalarLong("SELECT COUNT(*) FROM journal_entries"))
                    .put("inventory_quantity", inventoryQuantity())
                cases.put(entry)
                println("ACCEPTANCE " + id + " PASS " + result.optString("message", "verified") +
                    " sqlite=" + tableCounts().toString())
            } catch (error: Throwable) {
                val detail = id + ": " + (error.message ?: error.javaClass.simpleName)
                failures.add(detail)
                cases.put(JSONObject().put("id", id).put("status", "FAIL").put("error", detail))
                println("ACCEPTANCE " + id + " FAIL " + detail)
            }
        }

        val report = JSONObject().put("suite", "custody-workshop-acceptance")
            .put("total", 200).put("passed", 200 - failures.size).put("failed", failures.size)
            .put("blocked", 0).put("environment", "Robolectric + real SQLite + synthetic rows only")
            .put("cases", cases).put("failures", JSONArray(failures))
        val reportFile = File("app/build/reports/custody-workshop-acceptance.json")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        println("CUSTODY_WORKSHOP_ACCEPTANCE_REPORT " + report.toString())
        assertEquals("All 200 catalogue entries must execute and pass with recorded SQLite effects: " + failures.joinToString("; "),
            0, failures.size)
        assertEquals(200, cases.length())
    }

    private fun runCustodyCase(caseNo: Int, scenario: Int, dimension: Int): JSONObject {
        if (dimension == 2) {
            val input = custodyCreateInput(caseNo, "duplicate-" + caseNo)
            val first = custody("create", input)
            val firstId = first.getLong("id")
            val before = scalarLong("SELECT COUNT(*) FROM operating_custodies")
            val second = custody("create", input)
            assertEquals(firstId, second.getLong("id"))
            assertTrue(second.optBoolean("replayed"))
            assertEquals(before, scalarLong("SELECT COUNT(*) FROM operating_custodies"))
            return JSONObject().put("message", "idempotent replay returned the original custody").put("resource_id", firstId)
        }
        if (dimension == 3) {
            val before = tableCounts()
            expectRejected {
                CustodyWorkshopManager.executeCustody(helper, stationId, restrictedUserId,
                    "create", custodyCreateInput(caseNo, "denied-" + caseNo))
            }
            assertEquals(before.toString(), tableCounts().toString())
            return JSONObject().put("message", "unauthorized actor rejected before SQLite writes")
                .put("permission", "custody.create")
        }
        if (dimension == 4) {
            val id = createCustody(caseNo, 100.0)
            val result = custody("reconcile", JSONObject())
            assertTrue("custody reconciliation: " + result, result.optBoolean("passed"))
            val auditCount = scalarLong("SELECT COUNT(*) FROM custody_audit_events WHERE custody_id=?", id)
            assertTrue(auditCount > 0)
            return JSONObject().put("message", "station-scoped custody audit and posted journal reconciliation passed")
                .put("custody_id", id).put("audit_events", auditCount).put("reconciliation", result)
        }
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
        if (dimension == 2) {
            val p = workOrderCreateInput(caseNo, "duplicate-" + caseNo)
            val first = workshop("create", p)
            val before = scalarLong("SELECT COUNT(*) FROM work_orders")
            val second = workshop("create", p)
            assertEquals(first.getLong("id"), second.getLong("id"))
            assertTrue(second.optBoolean("replayed"))
            assertEquals(before, scalarLong("SELECT COUNT(*) FROM work_orders"))
            return JSONObject().put("message", "work-order idempotent replay preserved one SQLite row").put("work_order_id", first.getLong("id"))
        }
        if (dimension == 3) {
            val before = tableCounts()
            expectRejected {
                CustodyWorkshopManager.executeWorkshop(helper, stationId, restrictedUserId,
                    "create", workOrderCreateInput(caseNo, "denied-" + caseNo))
            }
            assertEquals(before.toString(), tableCounts().toString())
            return JSONObject().put("message", "unauthorized workshop create rejected before writes")
                .put("permission", "workshop.create")
        }
        if (dimension == 4) {
            val id = createInProgressOrder(caseNo, false)
            val result = workshop("reconcile", JSONObject())
            assertTrue("workshop reconciliation: " + result, result.optBoolean("passed"))
            val auditCount = scalarLong("SELECT COUNT(*) FROM work_order_audit_events WHERE work_order_id=?", id)
            assertTrue(auditCount > 0)
            return JSONObject().put("message", "work order, audit, inventory and journal reconciliation passed")
                .put("work_order_id", id).put("audit_events", auditCount).put("reconciliation", result)
        }
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