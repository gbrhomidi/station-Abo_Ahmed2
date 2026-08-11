package com.aistudio.dieselstationsms.kxmpzq.startup.di

import android.content.Context
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.sms.*
import com.aistudio.dieselstationsms.kxmpzq.utils.*

/**
 * ═══════════════════════════════════════════════════════════════
 * جذر التكوين - StartupCompositionRoot
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. تهيئة جميع المكونات عند بدء التطبيق
 * 2. إدارة دورة حياة المكونات
 * 3. توفير وصول مركزي إلى المكونات
 */
class StartupCompositionRoot private constructor(context: Context) {

    companion object {
        private const val TAG = "StartupCompositionRoot"
        @Volatile
        private var instance: StartupCompositionRoot? = null

        fun getInstance(context: Context): StartupCompositionRoot {
            return instance ?: synchronized(this) {
                instance ?: StartupCompositionRoot(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Database ═══
    // ═══════════════════════════════════════════════════════════════

    val databaseHelper: DatabaseHelper by lazy {
        DatabaseHelper(context)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ SMS Components ═══
    // ═══════════════════════════════════════════════════════════════

    val conversationManager: SmsConversationManager by lazy {
        SmsConversationManager(databaseHelper)
    }

    val securityOTP: SmsSecurityOTP by lazy {
        SmsSecurityOTP(databaseHelper)
    }

    val customerResolver: SmsCustomerResolver by lazy {
        SmsCustomerResolver(databaseHelper)
    }

    val partyGateway: SmsPartyGateway by lazy {
        SmsPartyGateway(databaseHelper)
    }

    val responseGenerator: SmsResponseGenerator by lazy {
        SmsResponseGenerator()
    }

    val intentDetector: SmsIntentDetector by lazy {
        SmsIntentDetector()
    }

    val locationExtractor: SmsLocationExtractor by lazy {
        SmsLocationExtractor()
    }

    val quantityExtractor: SmsQuantityExtractor by lazy {
        SmsQuantityExtractor()
    }

    val smsProcessor: SmsProcessor by lazy {
        SmsProcessor(
            context = context,
            conversationManager = conversationManager,
            customerResolver = customerResolver,
            securityOTP = securityOTP,
            gateway = partyGateway,
            responseGenerator = responseGenerator,
            intentDetector = intentDetector,
            locationExtractor = locationExtractor,
            quantityExtractor = quantityExtractor
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Initialization ═══
    // ═══════════════════════════════════════════════════════════════

    fun initialize() {
        Log.i(TAG, "Initializing StartupCompositionRoot...")

        // تهيئة قاعدة البيانات
        databaseHelper.writableDatabase

        // تهيئة المكونات
        conversationManager // force initialization
        securityOTP
        customerResolver
        partyGateway

        Log.i(TAG, "StartupCompositionRoot initialized successfully")
    }

    fun cleanup() {
        Log.i(TAG, "Cleaning up StartupCompositionRoot...")
        conversationManager.cleanupExpiredCache()
        instance = null
    }
}

/**
 * ═══════════════════════════════════════════════════════════════
 * Factory للوصول إلى SmsProcessor
 * ═══════════════════════════════════════════════════════════════
 */
object SmsProcessorFactory {
    fun getProcessor(context: Context): SmsProcessor {
        return StartupCompositionRoot.getInstance(context).smsProcessor
    }
}
