package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.databinding.ActivityMapPickerBinding
import java.util.Locale

/**
 * OpenStreetMap location picker rendered with Leaflet in a local WebView asset.
 * No Google Maps SDK, API key, or paid map account is required.
 */
class MapPickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INITIAL_LATITUDE = "initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "initial_longitude"
        const val EXTRA_LATITUDE = "selected_latitude"
        const val EXTRA_LONGITUDE = "selected_longitude"
    }

    private lateinit var binding: ActivityMapPickerBinding
    private var selectedLatitude: Double? = null
    private var selectedLongitude: Double? = null
    private var initialLatitude: Double? = null
    private var initialLongitude: Double? = null
    private var mapPageReady = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            centerOnCurrentLocation()
        } else {
            showLocationUnavailable("تم رفض صلاحية الموقع؛ يمكنك اختيار النقطة يدوياً.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val latitude = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, Double.NaN)
        if (intent.hasExtra(EXTRA_INITIAL_LATITUDE) &&
            intent.hasExtra(EXTRA_INITIAL_LONGITUDE) &&
            isValidCoordinate(latitude, longitude)
        ) {
            initialLatitude = latitude
            initialLongitude = longitude
            selectedLatitude = latitude
            selectedLongitude = longitude
        }

        binding.cancelButton.setOnClickListener { finish() }
        binding.confirmButton.setOnClickListener { confirmSelection() }
        binding.myLocationButton.setOnClickListener { requestAndCenterOnCurrentLocation() }
        updateCoordinates(selectedLatitude, selectedLongitude)
        configureMapWebView()
        binding.mapWebView.loadUrl("file:///android_asset/map_picker.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureMapWebView() {
        binding.mapWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        binding.mapWebView.webChromeClient = WebChromeClient()
        binding.mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                mapPageReady = true
                applyInitialSelectionToMap()
            }
        }
        binding.mapWebView.addJavascriptInterface(MapSelectionBridge(), "MapSelectionBridge")
    }

    private fun applyInitialSelectionToMap() {
        val latitude = initialLatitude
        val longitude = initialLongitude
        if (mapPageReady && latitude != null && longitude != null) {
            binding.mapWebView.evaluateJavascript(
                "window.setInitialSelection($latitude,$longitude)", null
            )
        }
    }

    private inner class MapSelectionBridge {
        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                mapPageReady = true
                applyInitialSelectionToMap()
            }
        }

        @JavascriptInterface
        fun onLocationSelected(latitude: Double, longitude: Double) {
            if (!isValidCoordinate(latitude, longitude)) return
            runOnUiThread {
                selectedLatitude = latitude
                selectedLongitude = longitude
                updateCoordinates(latitude, longitude)
            }
        }
    }

    private fun requestAndCenterOnCurrentLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        centerOnCurrentLocation()
    }

    private var activeLocationListener: LocationListener? = null
    private val locationTimeoutHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    private fun centerOnCurrentLocation() {
        if (!hasLocationPermission()) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        activeLocationListener?.let { listener -> runCatching { manager.removeUpdates(listener) } }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            showLocationUnavailable("خدمات الموقع غير مفعلة؛ اختر النقطة يدوياً.")
            return
        }
        // Prefer a fresh device fix; fall back to a recent cached fix only after timeout.
        var delivered = false
        val nowMillis = System.currentTimeMillis()
        val cached = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter {
            isValidCoordinate(it.latitude, it.longitude) &&
                MapPickerLocationPolicy.isRecentCachedLocation(it, nowMillis)
        }.maxByOrNull(Location::getTime)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (delivered || !isValidCoordinate(location.latitude, location.longitude)) return
                delivered = true
                locationTimeoutHandler.removeCallbacksAndMessages(null)
                runCatching { manager.removeUpdates(this) }
                activeLocationListener = null
                applyDeviceLocation(location)
            }
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        activeLocationListener = listener
        try {
            providers.forEach { provider ->
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
            locationTimeoutHandler.postDelayed({
                if (!delivered) {
                    runCatching { manager.removeUpdates(listener) }
                    activeLocationListener = null
                    if (cached != null) applyDeviceLocation(cached)
                    else showLocationUnavailable("لم يصل تثبيت GPS حديث خلال المهلة؛ اختر الموقع يدوياً.")
                }
            }, 15000L)
        } catch (exception: SecurityException) {
            runCatching { manager.removeUpdates(listener) }
            activeLocationListener = null
            showLocationUnavailable("تعذر الوصول إلى الموقع؛ يمكنك الاختيار يدوياً.")
        } catch (exception: Exception) {
            runCatching { manager.removeUpdates(listener) }
            activeLocationListener = null
            if (cached != null) applyDeviceLocation(cached)
            else showLocationUnavailable("تعذر تحديد الموقع الحالي: ${exception.message ?: "خطأ غير معروف"}")
        }
    }

    private fun applyDeviceLocation(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        initialLatitude = latitude
        initialLongitude = longitude
        selectedLatitude = latitude
        selectedLongitude = longitude
        updateCoordinates(latitude, longitude)
        if (mapPageReady) binding.mapWebView.evaluateJavascript(
            "window.setCurrentLocation($latitude,$longitude)", null
        )
        showLocationUnavailable("تم التقاط موقع الجهاز فعلياً بدقة ${if (location.hasAccuracy()) location.accuracy.toInt() else -1} متر.")
    }

    private fun updateCoordinates(latitude: Double?, longitude: Double?) {
        if (latitude == null || longitude == null) {
            binding.latitudeText.text = "لم يتم تحديد الموقع"
            binding.longitudeText.text = "لم يتم تحديد الموقع"
            return
        }
        binding.latitudeText.text = String.format(Locale.US, "%.8f", latitude)
        binding.longitudeText.text = String.format(Locale.US, "%.8f", longitude)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun showLocationUnavailable(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun confirmSelection() {
        val latitude = selectedLatitude
        val longitude = selectedLongitude
        if (latitude == null || longitude == null ||
            !isValidCoordinate(latitude, longitude)
        ) {
            showLocationUnavailable("حدد موقعاً على الخريطة أولاً.")
            return
        }
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_LATITUDE, latitude)
            putExtra(EXTRA_LONGITUDE, longitude)
        })
        finish()
    }


    override fun onPause() {
        // Stop active GPS/network callbacks when the picker is no longer visible.
        activeLocationListener?.let { listener ->
            runCatching {
                (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
            }
        }
        activeLocationListener = null
        locationTimeoutHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        activeLocationListener?.let { listener ->
            runCatching { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener) }
        }
        activeLocationListener = null
        locationTimeoutHandler.removeCallbacksAndMessages(null)
        binding.mapWebView.apply {
            removeJavascriptInterface("MapSelectionBridge")
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }

    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}

/** Reject stale/future cached fixes; cached coordinates are a fallback, never a substitute for GPS. */
internal object MapPickerLocationPolicy {
    const val MAX_CACHED_AGE_MILLIS = 120_000L

    fun isRecentCachedLocation(
        location: Location,
        nowMillis: Long,
        maxAgeMillis: Long = MAX_CACHED_AGE_MILLIS
    ): Boolean {
        if (maxAgeMillis < 0L) return false
        val ageMillis = nowMillis - location.time
        return ageMillis in 0L..maxAgeMillis
    }
}
