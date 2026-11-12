package com.aistudio.dieselstationsms.kxmpzq

import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional online routing client. SQLite and all core station operations remain local.
 * App Check is mandatory for this endpoint; there is deliberately no unauthenticated fallback.
 */
object StationRoutesClient {
    private const val ENDPOINT = "https://station-routes-worker.db7r01.workers.dev/v1/routes"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class Coordinate(val latitude: Double, val longitude: Double) {
        fun isValid(): Boolean =
            latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                !(latitude == 0.0 && longitude == 0.0)
    }

    data class Route(
        val provider: String,
        val distanceMeters: Double,
        val distanceKm: Double,
        val durationSeconds: Double,
        val encodedPolyline: String,
        val alternativesCount: Int,
        val cacheHit: Boolean
    )

    suspend fun getDrivingRoute(origin: Coordinate, destination: Coordinate): Route =
        withContext(Dispatchers.IO) {
            require(origin.isValid()) { "إحداثيات نقطة الانطلاق غير صحيحة" }
            require(destination.isValid()) { "إحداثيات الوجهة غير صحيحة" }

            val token = fetchAppCheckToken()
            if (token.isBlank()) throw IOException("Firebase App Check returned an empty token")

            val payload = JSONObject()
                .put("origin", JSONObject().put("latitude", origin.latitude).put("longitude", origin.longitude))
                .put("destination", JSONObject().put("latitude", destination.latitude).put("longitude", destination.longitude))
                .put("travelMode", "DRIVE")

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Accept", "application/json")
                .header("X-Firebase-AppCheck", token)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            http.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        401 -> "تعذر التحقق من سلامة التطبيق (App Check)"
                        413 -> "طلب المسار أكبر من الحجم المسموح"
                        422 -> "وسيلة التنقل غير مدعومة"
                        502, 503, 504 -> "خدمة حساب المسارات غير متاحة حالياً"
                        else -> "فشل طلب المسار: HTTP ${response.code}"
                    }
                    throw IOException(message)
                }
                val body = JSONObject(responseText)
                val route = Route(
                    provider = body.optString("provider"),
                    distanceMeters = body.optDouble("distanceMeters", Double.NaN),
                    distanceKm = body.optDouble("distanceKm", Double.NaN),
                    durationSeconds = body.optDouble("durationSeconds", Double.NaN),
                    encodedPolyline = body.optString("encodedPolyline"),
                    alternativesCount = body.optInt("alternativesCount", 0),
                    cacheHit = body.optBoolean("cacheHit", false)
                )
                if (route.provider != "OSRM" ||
                    !route.distanceMeters.isFinite() || route.distanceMeters <= 0.0 ||
                    !route.distanceKm.isFinite() || route.distanceKm <= 0.0 ||
                    !route.durationSeconds.isFinite() || route.durationSeconds <= 0.0 ||
                    route.encodedPolyline.isBlank()
                ) {
                    throw IOException("استجابة خدمة المسارات غير مكتملة أو غير صالحة")
                }
                route
            }
        }

    private suspend fun fetchAppCheckToken(): String =
        suspendCancellableCoroutine { continuation ->
            try {
                FirebaseAppCheck.getInstance().getAppCheckToken(false)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result.token)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(
                            IOException("تعذر الحصول على رمز Firebase App Check", error)
                        )
                    }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                    IOException("Firebase App Check is not initialized", error)
                )
            }
        }
}
