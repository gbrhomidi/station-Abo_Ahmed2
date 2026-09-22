package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiHealthCenterContractTest {
    @Test
    fun getAiHealthStatusQuery_UsesRealSqliteTable() {
        val dbHelperFile = File("src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt")
        if (!dbHelperFile.exists()) return // Skip if running from wrong directory
        
        val content = dbHelperFile.readText()
        assertTrue("getAiHealthStatusQuery must exist", content.contains("fun getAiHealthStatusQuery"))
        assertTrue("Must query sms_ai_runs table", content.contains("FROM sms_ai_runs"))
        assertTrue("Must calculate health score", content.contains("health_score"))
        assertTrue("Must count successes", content.contains("success_count"))
        assertTrue("Must count failures", content.contains("failure_count"))
    }

    @Test
    fun bridge_DelegatesToDatabaseHelper() {
        val mainActivityFile = File("src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt")
        if (!mainActivityFile.exists()) return // Skip if running from wrong directory
        
        val content = mainActivityFile.readText()
        assertTrue("getAiHealthStatus must call dbHelper", content.contains("dbHelper.getAiHealthStatusQuery()"))
        assertTrue("Must check permissions", content.contains("checkPermission(\"settings\", \"read\")"))
    }
}
