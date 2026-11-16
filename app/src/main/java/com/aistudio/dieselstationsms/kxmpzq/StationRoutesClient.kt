package com.aistudio.dieselstationsms.kxmpzq

import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
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

            val token = try { fetchAppCheckToken() } catch (error: Exception) {
                // Routing is optional. Do not block trip entry when Firebase attestation is
                // unavailable (for example debug PIN verification or a captive network).
                return@withContext offlineEstimate(origin, destination)
            }
            if (token.isBlank()) return@withContext offlineEstimate(origin, destination)

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

    private fun offlineEstimate(origin: Coordinate, destination: Coordinate): Route {
        val earthKm = 6371.0088
        val dLat = Math.toRadians(destination.latitude - origin.latitude)
        val dLon = Math.toRadians(destination.longitude - origin.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(origin.latitude)) * cos(Math.toRadians(destination.latitude)) * sin(dLon / 2).pow(2)
        val distanceKm = 2.0 * earthKm * asin(sqrt(a.coerceIn(0.0, 1.0)))
        require(distanceKm > 0.0) { "نقطتا الرحلة متطابقتان" }
        val durationSeconds = distanceKm / 35.0 * 3600.0
        return Route("OFFLINE_ESTIMATE", distanceKm * 1000.0, distanceKm, durationSeconds, encodePolyline(listOf(origin, destination)), 0, true)
    }

    private fun encodePolyline(points: List<Coordinate>): String {
        var lastLat = 0; var lastLon = 0
        val out = StringBuilder()
        fun append(value: Int) { var v = if (value < 0) (value shl 1).inv() else value shl 1; while (v >= 0x20) { out.append(((v and 0x1f) or 0x20) + 63); v = v ushr 5 }; out.append(v + 63) }
        points.forEach { point -> val lat = kotlin.math.round(point.latitude * 1e5).toInt(); val lon = kotlin.math.round(point.longitude * 1e5).toInt(); append(lat-lastLat); append(lon-lastLon); lastLat=lat; lastLon=lon }
        return out.toString()
    }

    private suspend fun fetchAppCheckToken(): String {
        val appCheck = try {
            FirebaseAppCheck.getInstance()
        } catch (error: Exception) {
            throw IOException("Firebase App Check غير مهيأ لهذا الإصدار من التطبيق", error)
        }
        return try {
            requestAppCheckToken(appCheck, forceRefresh = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Exception) {
            // Provider/network failures can be transient. Retry once with a forced refresh;
            // never send an unattested request if both attempts fail.
            try {
                requestAppCheckToken(appCheck, forceRefresh = true)
            } catch (retryFailure: Exception) {
                val reason = retryFailure.message ?: firstFailure.message ?: "سبب غير معروف"
                throw IOException("تعذر الحصول على رمز Firebase App Check بعد إعادة المحاولة: $reason", retryFailure)
            }
        }
    }

    private suspend fun requestAppCheckToken(
        appCheck: FirebaseAppCheck,
        forceRefresh: Boolean
    ): String = suspendCancellableCoroutine { continuation ->
        try {
            appCheck.getAppCheckToken(forceRefresh)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.token)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
