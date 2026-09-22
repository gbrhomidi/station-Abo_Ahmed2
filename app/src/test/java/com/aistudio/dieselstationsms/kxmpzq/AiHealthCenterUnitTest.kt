package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiHealthCenterUnitTest {

    private lateinit var context: Context
    private lateinit var dbHelper: DatabaseHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        dbHelper = DatabaseHelper.getInstance(context)
        // تهيئة قاعدة البيانات بإنشاء الجداول
        val db = dbHelper.writableDatabase
        assertNotNull(db)
    }

    @Test
    fun getAiHealthStatusQuery_DoesNotThrowSqlException() {
        // إدخال بيانات وهمية للاختبار
        val db = dbHelper.writableDatabase
        db.execSQL("""
            INSERT INTO sms_ai_runs (run_id, event_id, conversation_id, provider, model, request_hash, availability, created_at)
            VALUES ('run1', 'evt1', 'conv1', 'openai', 'gpt-4', 'hash1', 'AVAILABLE', 1000)
        """)
        
        // استدعاء الدالة والتحقق من عدم رمي استثناء
        val result = dbHelper.getAiHealthStatusQuery()
        
        // التحقق من صحة هيكل JSON
        assertNotNull(result)
        assertTrue(result.has("system_status"))
        assertTrue(result.has("providers"))
        
        val providers = result.getJSONArray("providers")
        assertTrue(providers.length() > 0)
        
        val firstProvider = providers.getJSONObject(0)
        assertTrue(firstProvider.has("health_score"))
        assertTrue(firstProvider.has("is_cooldown"))
    }
}
