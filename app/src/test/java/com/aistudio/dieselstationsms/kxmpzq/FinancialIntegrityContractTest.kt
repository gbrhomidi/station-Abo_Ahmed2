package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the P0 financial integrity boundary at source-contract level.
 *
 * These tests intentionally do not replace database integration tests. They protect
 * the architectural invariants while the full Android test environment is unavailable:
 * atomic financial paths must use one transaction, and public sale entry points must
 * delegate to the transaction-neutral internal writer rather than nesting transactions.
 */
class FinancialIntegrityContractTest {

    @Test
    fun financialDatabaseWriterPreservesAtomicityAndIdempotencyContracts() {
        val source = locateDatabaseHelper().readText()

        assertAtomic(source, "addFuelSale")
        assertAtomic(source, "completeSale")
        assertAtomic(source, "processPayment")

        val fuelSale = functionBody(source, "fun addFuelSale")
        val productSale = functionBody(source, "fun completeSale")
        val payment = functionBody(source, "fun processPayment")

        assertTrue("Fuel sale must check the optional idempotency key", fuelSale.contains("idempotency_key"))
        assertTrue("Product sale must check the optional idempotency key", productSale.contains("idempotency_key"))
        assertTrue("Payment must check the optional idempotency key", payment.contains("idempotencyKey"))
        assertTrue("Debt payment must create its accounting journal in the same transaction", payment.contains("postFinanceJournal("))
        assertTrue("Debt payment must persist station scope", payment.contains("put(\"station_id\", paymentStationId)"))
        assertTrue("Debt payment must retain its cash-box or bank settlement source", payment.contains("put(\"cash_box_id\", cashBoxId.takeIf") && payment.contains("put(\"bank_account_id\", bankAccountId.takeIf"))
        assertTrue("Debt payment must link the posted journal to the payment record", payment.contains("put(\"journal_entry_id\", journalId)"))
        assertTrue("Debt payment must create a customer-ledger credit", payment.contains("insertOrThrow(\"customer_ledger\"") && payment.contains("put(\"credit\", amount)"))
        assertTrue("Debt payment must update the selected settlement balance", payment.contains("cash_movements") && payment.contains("insertBankLedgerEntry("))
        assertTrue("Product sale must use the transaction-neutral sale writer", productSale.contains("insertSaleTransactionInternal(db"))
        assertTrue("Fuel sale must use the transaction-neutral sale writer", fuelSale.contains("insertSaleTransactionInternal(db"))
        assertTrue("Stock updates inside a sale must use the transaction-neutral writer", productSale.contains("addStockMovementInternal("))
        assertTrue("Database must define the unique financial idempotency table", source.contains("financial_idempotency_keys"))
        assertTrue("Financial paths must reserve idempotency keys", source.contains("reserveFinancialIdempotency"))
        assertTrue("Financial paths must complete idempotency keys", source.contains("completeFinancialIdempotency"))
    }



    @Test
    fun customerReceivableAndInventoryAccountsAreRepairedForExistingInstallations() {
        val source = locateDatabaseHelper().readText()
        val start = source.indexOf("private fun ensureRequiredSaleAccounts")
        val end = source.indexOf("private fun ensureSalesPricingDefaults", start)
        assertTrue("Required sale-account repair must exist before pricing defaults", start >= 0 && end > start)
        val repair = source.substring(start, end)
        assertTrue("Customer receivable account 1103 must be created when absent", repair.contains("put(\"account_code\", \"1103\")"))
        assertTrue("Soft-deleted customer receivable account must be restored", repair.contains("put(\"is_deleted\", 0)"))
        assertTrue("Misused legacy account code must not be reclassified silently", repair.contains("1103-AR"))
        assertTrue("Inventory account 1104 must be restored or created", repair.contains("put(\"account_code\", \"1104\")"))
        assertTrue("Account repair must run from the existing-installation initialization path", source.contains("ensureRequiredSaleAccounts(db)"))
    }

    @Test
    fun barcodeProductPayloadIncludesTheDatabaseDescriptionForPosDetails() {
        val source = locateDatabaseHelper().readText()
        val product = functionBody(source, "fun getProductByBarcode(barcode: String, stationScopeId")
        assertTrue("POS barcode lookup must return products.quantity", product.contains("put(\"quantity\""))
        assertTrue("POS barcode lookup must return products.description", product.contains("getColumnIndex(\"description\")"))
        assertTrue("Product details must not substitute description_ar", !product.contains("description_ar"))
    }

    @Test
    fun fuelCogsUsesDeliveredVolumeRatherThanPostRefillTankLevel() {
        val source = locateDatabaseHelper().readText()
        val fuelSale = functionBody(source, "fun addFuelSale")
        val start = fuelSale.indexOf("val fuelUnitCost =")
        val end = fuelSale.indexOf("val fuelCostAmount = liters * fuelUnitCost", start)
        assertTrue("Fuel sale must calculate a unit-cost query", start >= 0 && end > start)
        val costQuery = fuelSale.substring(start, end)
        assertTrue("Fuel COGS denominator must use actual delivered quantity", costQuery.contains("SUM(delivered_quantity)"))
        assertTrue("Post-refill tank level must not be used as delivered volume", !costQuery.contains("actual_quantity"))
        assertTrue("Fuel COGS must use the fuel purchase amount", costQuery.contains("total_amount") && costQuery.contains("unit_price"))
        assertTrue("Transport-inclusive net_amount must not be used as fuel unit cost", !costQuery.contains("net_amount"))
        assertTrue("Customer delivery fee must not be part of fuel COGS", !costQuery.contains("delivery_fee"))
    }

    @Test
    fun deliveryServiceFeesRequireDeliveredStatusApprovalAndBalancedJournal() {
        val source = locateDatabaseHelper().readText()
        val createDelivery = functionBody(source, "fun addDelivery")
        val updateDelivery = functionBody(source, "fun updateDeliveryRecord")

        assertTrue("Creating a delivery must require delivered status for service-fee changes",
            createDelivery.contains("status == \"delivered\""))
        assertTrue("Creating a delivery must require the delivery-fee approval permission",
            createDelivery.contains("delivery_service_fee.approve"))
        assertTrue("Creating a delivery fee must post a GL journal",
            createDelivery.contains("postFinanceJournal("))
        assertTrue("Updating a delivery fee must require delivered status",
            updateDelivery.contains("status == \"delivered\""))
        assertTrue("Updating a delivery fee must require authorization",
            updateDelivery.contains("delivery_service_fee.approve"))
        assertTrue("Updating a delivery fee must post a GL journal",
            updateDelivery.contains("postFinanceJournal("))
        assertTrue("Fee approval permission must be seeded for designated management roles",
            source.contains("ensureDeliveryFeeApprovalPermission(db)"))
    }

    @Test
    fun saleAdjustmentPathsAreAtomicAuditableAndIdempotent() {
        val source = locateDatabaseHelper().readText()
        val fuelAdjustment = functionBody(source, "fun processFuelSaleAdjustment")
        val productAdjustment = functionBody(source, "fun processProductSaleAdjustment")

        assertTrue("Fuel adjustments must be transactional", fuelAdjustment.contains("db.beginTransaction()") && fuelAdjustment.contains("db.setTransactionSuccessful()") && fuelAdjustment.contains("db.endTransaction()"))
        val mainSource = locateMainActivity().readText()
        assertTrue("Fuel adjustment bridge must be exposed", mainSource.contains("fun processFuelSaleAdjustment"))
        assertTrue("Product adjustment bridge must be exposed", mainSource.contains("fun processProductSaleAdjustment"))
        assertTrue("Product adjustments must be transactional", productAdjustment.contains("db.beginTransaction()") && productAdjustment.contains("db.setTransactionSuccessful()") && productAdjustment.contains("db.endTransaction()"))
        assertTrue("Fuel adjustments must persist an independent audit table", source.contains("fuel_sale_adjustments"))
        assertTrue("Fuel adjustments must enforce unique idempotency", Regex("""UNIQUE\s*\(\s*station_id\s*,\s*idempotency_key\s*\)""").containsMatchIn(source))
        assertTrue("Product sale items must have independent damaged quantity", source.contains("damaged_quantity"))
        assertTrue("Sale-linked damage must be persisted in damaged_products", productAdjustment.contains("\"damaged_products\""))
        assertTrue("Fuel return must explicitly restore tank stock", fuelAdjustment.contains("current_quantity=current_quantity+"))
        assertTrue("Fuel damage must have no tank restoration path", fuelAdjustment.contains("inventory_action") && fuelAdjustment.contains("\"none\""))
        assertTrue("Adjustments must post a journal entry", source.contains("postSalesReversalJournal") && source.contains("put(\"status\", \"posted\")"))
        assertTrue("Cash refunds must create cash movements", source.contains("\"cash_movements\"") && source.contains("\"refund\""))
    }

    @Test
    fun financialIntegrityAuditCoversPostedJournalAndSourceIntegrity() {
        val candidates = listOf(
            File("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/FinancialIntegrityAuditor.kt"),
            File("../app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/FinancialIntegrityAuditor.kt")
        )
        val auditor = candidates.firstOrNull { it.isFile }
            ?: error("FinancialIntegrityAuditor.kt was not found from " + System.getProperty("user.dir"))
        val source = auditor.readText()
        assertTrue("Audit must inspect posted journal balance totals",
            source.contains("unbalanced_posted_journals") && source.contains("total_debit") && source.contains("total_credit"))
        assertTrue("Audit must reconcile journal headers to their lines",
            source.contains("journal_entry_items") && source.contains("journal_item_mismatches"))
        assertTrue("Audit must detect duplicate posted source journals",
            source.contains("duplicate_posted_source_journals"))
        assertTrue("Audit must detect missing journals for financial source records",
            source.contains("missing_") && source.contains("NOT EXISTS (SELECT 1 FROM journal_entries"))
        assertTrue("Audit must remain read-only",
            !source.contains("UPDATE journal_entries") && !source.contains("DELETE FROM journal_entries"))
    }

    @Test
    fun financialIntegrityAuditorIsReadOnlyAndStationScoped() {
        val auditorCandidates = listOf(
            File("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/FinancialIntegrityAuditor.kt"),
            File("../app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/FinancialIntegrityAuditor.kt")
        )
        val auditorFile = auditorCandidates.firstOrNull { it.isFile }
            ?: error("FinancialIntegrityAuditor.kt was not found from " + System.getProperty("user.dir"))
        val source = auditorFile.readText()
        assertTrue(source.contains("require(stationId > 0)"))
        assertTrue(source.contains("total_debit") && source.contains("total_credit"))
        assertTrue(source.contains("journal_entry_items"))
        assertTrue(source.contains("duplicate_posted_source_journals"))
        assertTrue(!source.contains("UPDATE journal_entries") && !source.contains("DELETE FROM journal_entries"))
        val mainSource = locateMainActivity().readText()
        assertTrue(mainSource.contains("fun runFinancialIntegrityAudit") &&
            mainSource.contains("requireCurrentStationId(db, activity.currentUserId)") &&
            mainSource.contains("checkPermission(\"accounting\", \"read\")"))
    }

    @Test
    fun settlementAccountMappingRejectsMissingOrAmbiguousLinksAndValidatesAccountCreation() {
        val source = locateDatabaseHelper().readText()
        val resolver = functionBody(source, "private fun findSettlementAccount")
        assertTrue("Settlement resolution must not silently choose the first duplicate mapping",
            !resolver.contains("LIMIT 1") && resolver.contains("require(!c.moveToNext())"))
        assertTrue("Missing mappings must fail explicitly instead of using a generic account",
            resolver.contains("throw IllegalStateException") &&
                resolver.contains("لا يوجد حساب محاسبي نشط وصحيح مرتبط بوسيلة التسوية المحددة"))
        assertTrue("Missing mapping diagnostics must report the selected source id and classify the mismatch",
            resolver.contains("val sourceColumn = if (isCash)") &&
                resolver.contains("التشخيص: \\$sourceLabel #\\$arg") &&
                resolver.contains("تصنيف الحساب النقدي/البنكي غير مطابق"))
        assertTrue("Settlement diagnosis must be read-only and must not weaken the eligibility predicate",
            resolver.contains("SELECT COUNT(*)") &&
                resolver.contains("classificationColumn=1") &&
                resolver.contains("لم تُعدّل أي أرصدة."))
        val save = functionBody(source, "fun saveChartAccount")
        assertTrue("A GL settlement account cannot map to both cash and bank resources",
            save.contains("linkedBankAccountId > 0L && linkedCashBoxId > 0L"))
        assertTrue("Cash mappings must reference an active, non-deleted cash box",
            save.contains("FROM cash_boxes WHERE id=? AND status='active' AND is_deleted=0"))
        assertTrue("Bank mappings must reference an active, non-deleted bank account",
            save.contains("FROM bank_accounts WHERE id=? AND status='active' AND is_deleted=0"))
        assertTrue("Settlement links must be unique among active GL accounts",
            save.contains("cash_box_id=? AND is_active=1 AND is_deleted=0 AND id<>?") &&
                save.contains("bank_account_id=? AND is_active=1 AND is_deleted=0 AND id<>?"))
        assertTrue("Linked cash/bank accounts must be classified as asset accounts",
            save.contains("require(type == \"asset\")") &&
                save.contains("linkedBankAccountId > 0L -> 1") &&
                save.contains("linkedCashBoxId > 0L -> 1"))
    }

    @Test
    fun paymentReversalRequiresMatchingPostedJournalAndStationScopedSettlement() {
        val source = locateDatabaseHelper().readText()
        val reversal = functionBody(source, "private fun reversePaymentRecordCore")
        assertTrue("Payment reversal must require a source journal", reversal.contains("require(journalId > 0L)"))
        assertTrue("Payment journal must match payment reference, station and posted status",
            reversal.contains("SELECT reference_type,reference_id,status,total_debit") &&
                reversal.contains("originalJournal[0] == \"payment\"") &&
                reversal.contains("originalJournal[1].toLongOrNull() == id") &&
                reversal.contains("originalJournal[2] == \"posted\""))
        assertTrue("Payment journal amount must match source amount",
            reversal.contains("kotlin.math.abs((originalJournal[3].toDoubleOrNull() ?: Double.NaN) - amount) <= 0.01"))
        assertTrue("Payment reversal must require exactly one original settlement account",
            reversal.contains("(cashBoxId > 0L) xor (bankAccountId > 0L)"))
        assertTrue("Cash balance update must be station scoped",
            reversal.contains("\"id=? AND station_id=? AND is_deleted=0\""))
        assertTrue("Payment reversal must reverse its original journal before committing",
            reversal.contains("reverseJournalEntryWithinTransaction(db, journalId"))
    }

    @Test
    fun expenseReversalRequiresPaidSourceJournalAndRestoresCashOnlyForCashExpenses() {
        val source = locateDatabaseHelper().readText()
        val reversal = functionBody(source, "private fun reverseExpenseRecordCore")
        assertTrue("Expense reversal must reject unpaid or merely approved expenses",
            reversal.contains("row.optString(\"status\") == \"paid\" && row.optString(\"payment_status\") == \"paid\""))
        assertTrue("Expense reversal must require an original journal entry",
            reversal.contains("require(journalId>0)"))
        assertTrue("Original journal must match the expense reference and station",
            reversal.contains("SELECT reference_type,reference_id,status FROM journal_entries WHERE id=? AND station_id=? AND is_deleted=0") &&
                reversal.contains("originalJournal.first==\"expense\" && originalJournal.second==id && originalJournal.third==\"posted\""))
        assertTrue("Cash-box restoration must only occur for cash expenses",
            reversal.contains("if (row.optString(\"payment_method\") == \"cash\")"))
        assertTrue("Every eligible expense reversal must reverse its posted journal",
            reversal.contains("reverseJournalEntryWithinTransaction(db,journalId"))
    }

    @Test
    fun salesClassificationUsesFuelSalesAndSaleItemsInsteadOfHeaderOnly() {
        val source = locateDatabaseHelper().readText()
        assertTrue("Unified sales ledger must union fuel_sales and sale_items", source.contains("FROM fuel_sales fs") && source.contains("FROM sale_items si") && source.contains("getSalesLedgerPage"))
        assertTrue("Invoice classification must inspect both physical sale sources", source.contains("SELECT 1 FROM fuel_sales WHERE sale_id=?") && source.contains("SELECT 1 FROM sale_items WHERE sale_id=? AND item_type='product'"))
        assertTrue("Fuel reports must subtract posted adjustments", source.contains("fuel_sale_adjustments") && source.contains("adjusted_amount") && source.contains("adjusted_liters"))
    }

    @Test
    fun webViewValidatorBlocksProtectedTables() {
        assertTrue("Fuel sales must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("fuel_sales"))
        assertTrue("Payments must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("payments"))
        assertTrue("Financial idempotency keys must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("financial_idempotency_keys"))
        assertTrue("Sale item adjustments must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("sale_item_adjustments"))
        assertTrue("Fuel sale adjustments must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("fuel_sale_adjustments"))
        assertTrue("Reference data should remain available through general CRUD", SecurityValidator.isTableAllowedForGeneralCrud("fuel_types"))
    }

    @Test
    fun webViewValidatorRemovesServerOwnedFields() {
        val sanitized = SecurityValidator.sanitizeOperationalJson("{\"name\":\"عميل\",\"id\":99,\"balance\":500,\"created_at\":\"forged\"}")
        assertTrue("Business fields must be preserved: $sanitized", sanitized.contains("name"))
        assertTrue("Client supplied id must be removed", !sanitized.contains("\"id\""))
        assertTrue("Client supplied balance must be removed", !sanitized.contains("\"balance\""))
        assertTrue("Client supplied created_at must be removed", !sanitized.contains("\"created_at\""))
    }

    @Test
    fun webViewValidatorRejectsMalformedJsonSafely() {
        assertTrue("Malformed JSON must become an empty object", SecurityValidator.sanitizeOperationalJson("not-json") == "{}")
    }

    @Test
    fun mainActivityMustNotPersistPlainTextPasswords() {
        val source = locateMainActivity().readText()
        assertTrue("MainActivity must not persist a password field", !source.contains("putString(\"password\""))
        assertTrue("Remember-me flow must not log the password value", !source.contains("password=" + "$" + "password"))
        assertTrue("Remember-me flow must explicitly ignore the password parameter", source.contains("password intentionally ignored"))
    }

    @Test
    fun generalCrudBridgeMustRejectProtectedFinancialTables() {
        val source = locateMainActivity().readText()
        val saveBody = functionBody(source, "private fun operationalSave")
        val updateBody = functionBody(source, "private fun operationalUpdate")
        val deleteBody = functionBody(source, "private fun operationalDelete")
        assertTrue("General save must enforce table allow-list", saveBody.contains("isTableAllowedForGeneralCrud"))
        assertTrue("General update must enforce table allow-list", updateBody.contains("isTableAllowedForGeneralCrud"))
        assertTrue("General delete must enforce table allow-list", deleteBody.contains("isTableAllowedForGeneralCrud"))
        assertTrue("General save must sanitize JSON", saveBody.contains("sanitizeOperationalJson"))
        assertTrue("General update must sanitize JSON", updateBody.contains("sanitizeOperationalJson"))
        assertTrue("General delete must reject invalid identifiers", deleteBody.contains("id <= 0L"))
    }

    @Test
    fun webViewValidatorNormalizesTableNamesAndBoundsPayloadSize() {
        assertTrue("Table names must be normalized before authorization", SecurityValidator.isTableAllowedForGeneralCrud("  FUEL_TYPES "))
        assertTrue("Malformed table names must be rejected", !SecurityValidator.isTableAllowedForGeneralCrud("fuel_types;DROP TABLE users"))
        val oversized = "{\"name\":\"${"x".repeat(256 * 1024)}\"}"
        assertTrue("Oversized operational JSON must be rejected", SecurityValidator.sanitizeOperationalJson(oversized) == "{}")
    }

    @Test
    fun publicSaleWriterDelegatesWithoutNestedTransaction() {
        val source = locateDatabaseHelper().readText()
        val publicWriter = functionBody(source, "fun insertSaleTransaction")

        assertTrue("Public sale writer must delegate to the internal writer", publicWriter.contains("insertSaleTransactionInternal("))
        assertTrue("Internal writer must be declared separately", source.contains("private fun insertSaleTransactionInternal("))
        assertTrue("Public writer must not call beginTransaction after delegating", !publicWriter.contains("db.beginTransaction()"))
    }

    private fun assertAtomic(source: String, functionName: String) {
        val body = functionBody(source, "fun $functionName")
        assertTrue("$functionName must begin a database transaction", body.contains("db.beginTransaction()"))
        assertTrue("$functionName must mark the transaction successful", body.contains("db.setTransactionSuccessful()"))
        assertTrue("$functionName must always end the transaction", body.contains("db.endTransaction()"))
    }

    private fun functionBody(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Could not locate $marker" }
        val next = Regex("\\n\\s+(?:(?:private|public|internal|protected)\\s+)?fun\\s")
            .find(source, start + marker.length)
            ?.range
            ?.first
        return source.substring(start, next ?: source.length)
    }

    private fun locateMainActivity(): File {
        val candidates = listOf(
            File("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt"),
            File("../app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("MainActivity.kt was not found from " + System.getProperty("user.dir"))
    }

    private fun locateDatabaseHelper(): File {
        val candidates = listOf(
            File("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt"),
            File("../app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("DatabaseHelper.kt was not found from " + System.getProperty("user.dir"))
    }

    @Test
    fun ledgerOpeningBalancesRetainSoftDeletedAccountsWithStationHistory() {
        val source = locateDatabaseHelper().readText()
        val ledger = functionBody(source, "fun getLedgerEntries")
        assertTrue(
            "Opening-balance query must retain soft-deleted accounts that have posted history in the requested station",
            ledger.contains("historical_entry.station_id=?") &&
                ledger.contains("historical_entry.status='posted'") &&
                ledger.contains("WHERE a.is_deleted=0 OR EXISTS")
        )
        assertTrue(
            "Opening-balance query must bind the station scope for both dated and undated ledger reports",
            ledger.contains("openingArgs += stationScopeId.toString(); openingArgs += startDate; openingArgs += stationScopeId.toString()") &&
                ledger.contains("openingArgs += stationScopeId.toString()")
        )
    }
}
