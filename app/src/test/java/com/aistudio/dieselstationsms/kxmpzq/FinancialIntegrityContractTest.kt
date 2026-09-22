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
        assertTrue("Product sale must use the transaction-neutral sale writer", productSale.contains("insertSaleTransactionInternal(db"))
        assertTrue("Fuel sale must use the transaction-neutral sale writer", fuelSale.contains("insertSaleTransactionInternal(db"))
        assertTrue("Stock updates inside a sale must use the transaction-neutral writer", productSale.contains("addStockMovementInternal("))
        assertTrue("Database must define the unique financial idempotency table", source.contains("financial_idempotency_keys"))
        assertTrue("Financial paths must reserve idempotency keys", source.contains("reserveFinancialIdempotency"))
        assertTrue("Financial paths must complete idempotency keys", source.contains("completeFinancialIdempotency"))
    }

    @Test
    fun webViewValidatorBlocksProtectedTables() {
        assertTrue("Fuel sales must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("fuel_sales"))
        assertTrue("Payments must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("payments"))
        assertTrue("Financial idempotency keys must not be reachable through general CRUD", !SecurityValidator.isTableAllowedForGeneralCrud("financial_idempotency_keys"))
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
}
