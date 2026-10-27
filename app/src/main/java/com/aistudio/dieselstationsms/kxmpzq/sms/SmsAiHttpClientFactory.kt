package com.aistudio.dieselstationsms.kxmpzq.sms

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * سياسة اتصال موحدة لمزوّدي AI.
 *
 * عند تفعيل networkOptimizationEnabled تتم مشاركة ConnectionPool وDispatcher
 * بين طلبات AI لإعادة استخدام اتصالات HTTPS وتقليل زمن إنشاء الاتصالات.
 * وعند إيقافه يبقى سلوك العميل المستقل لكل طلب كما كان سابقاً.
 */
object SmsAiHttpClientFactory {
    @Volatile
    private var networkOptimizationEnabled: Boolean = false

    private val optimizedClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    fun setNetworkOptimizationEnabled(enabled: Boolean) {
        networkOptimizationEnabled = enabled
    }

    fun isNetworkOptimizationEnabled(): Boolean = networkOptimizationEnabled

    fun create(timeoutMs: Long): OkHttpClient {
        val safeTimeoutMs = timeoutMs.coerceIn(1_000L, 600_000L)
        val builder = if (networkOptimizationEnabled) {
            optimizedClient.newBuilder()
        } else {
            OkHttpClient.Builder()
        }
        return builder
            .connectTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
