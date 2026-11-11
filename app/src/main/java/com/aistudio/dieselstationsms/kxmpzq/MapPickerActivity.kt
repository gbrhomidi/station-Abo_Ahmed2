package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.aistudio.dieselstationsms.kxmpzq.databinding.ActivityMapPickerBinding
import java.util.Locale

/** Native Google Maps picker used by stations.html through MainActivity's bridge. */
class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        const val EXTRA_INITIAL_LATITUDE = "initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "initial_longitude"
        const val EXTRA_LATITUDE = "selected_latitude"
        const val EXTRA_LONGITUDE = "selected_longitude"
    }

    private lateinit var binding: ActivityMapPickerBinding
    private var googleMap: GoogleMap? = null
    private var selectedMarker: Marker? = null
    private var selectedLocation: LatLng? = null
    private var initialLocation: LatLng? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) enableMyLocationAndCenter() else showLocationUnavailable("تم رفض صلاحية الموقع؛ يمكنك اختيار النقطة يدوياً.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val hasInitialCoordinates = intent.hasExtra(EXTRA_INITIAL_LATITUDE) &&
            intent.hasExtra(EXTRA_INITIAL_LONGITUDE)
        val lat = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, Double.NaN)
        initialLocation = if (
            hasInitialCoordinates &&
            lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            !(lat == 0.0 && lon == 0.0)
        ) LatLng(lat, lon) else null

        binding.cancelButton.setOnClickListener { finish() }
        binding.confirmButton.setOnClickListener { confirmSelection() }
        binding.myLocationButton.setOnClickListener { requestAndCenterOnCurrentLocation() }
        updateCoordinates(initialLocation)

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map_container, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false
        map.setOnMapClickListener { updateSelection(it) }
        map.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                updateSelection(marker.position, moveCamera = false)
            }
        })

        val existingLocation = initialLocation
        if (existingLocation != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(existingLocation, 16f))
            updateSelection(existingLocation, moveCamera = false)
        } else {
            // Camera position is not a selected location. Require an explicit map tap
            // or the current-location control before enabling confirmation.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 2f))
            selectedLocation = null
            updateCoordinates(null)
        }
        if (hasLocationPermission()) enableMyLocationAndCenter(center = false)
    }

    private fun updateSelection(location: LatLng, moveCamera: Boolean = true) {
        selectedLocation = location
        selectedMarker?.remove()
        selectedMarker = googleMap?.addMarker(
            MarkerOptions().position(location).title("الموقع المحدد").draggable(true)
        )
        if (moveCamera) googleMap?.animateCamera(CameraUpdateFactory.newLatLng(location))
        updateCoordinates(location)
    }

    private fun updateCoordinates(location: LatLng?) {
        if (location == null) {
            binding.latitudeText.text = "لم يتم تحديد الموقع"
            binding.longitudeText.text = "لم يتم تحديد الموقع"
            return
        }
        binding.latitudeText.text = String.format(Locale.US, "%.8f", location.latitude)
        binding.longitudeText.text = String.format(Locale.US, "%.8f", location.longitude)
    }

    private fun requestAndCenterOnCurrentLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        enableMyLocationAndCenter()
    }

    private fun enableMyLocationAndCenter(center: Boolean = true) {
        val map = googleMap ?: return
        if (!hasLocationPermission()) return
        try {
            map.isMyLocationEnabled = true
            if (!center) return
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val location = providers.asSequence()
                .filter { locationManager.isProviderEnabled(it) }
                .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull(Location::getTime)
            if (location != null) {
                updateSelection(LatLng(location.latitude, location.longitude))
            } else {
                showLocationUnavailable("لم يتوفر موقع حالي؛ اختر النقطة يدوياً على الخريطة.")
            }
        } catch (securityException: SecurityException) {
            showLocationUnavailable("تعذر الوصول إلى الموقع؛ يمكنك الاختيار يدوياً.")
        } catch (exception: Exception) {
            showLocationUnavailable("تعذر تحديد الموقع الحالي: ${exception.message ?: "خطأ غير معروف"}")
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun showLocationUnavailable(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun confirmSelection() {
        val location = selectedLocation ?: run {
            showLocationUnavailable("حدد موقعاً على الخريطة أولاً.")
            return
        }
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_LATITUDE, location.latitude)
            putExtra(EXTRA_LONGITUDE, location.longitude)
        })
        finish()
    }
}
