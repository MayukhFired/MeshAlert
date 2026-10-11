package com.meshalert.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class LocationHelper(private val context: Context) {

    companion object {
        private const val TAG = "LocationHelper"
        private const val MIN_TIME_MS = 5000L   // 5 seconds between updates
        private const val MIN_DISTANCE_M = 5f   // 5 meters change

        /**
         * Computes distance in meters and initial bearing in degrees (-180..180).
         */
        fun calculateDistanceAndBearing(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double
        ): Pair<Float, Float> {
            val results = FloatArray(2)
            Location.distanceBetween(fromLat, fromLon, toLat, toLon, results)
            return Pair(results[0], results[1])
        }

        fun formatDistance(meters: Float): String {
            return if (meters < 1000f) {
                "${meters.roundToInt()} m"
            } else {
                "%.1f km".format(meters / 1000f)
            }
        }

        fun formatBearing(bearing: Float): String {
            val normalized = (bearing % 360 + 360) % 360
            return when {
                normalized in 22.5..<67.5 -> "NE ↗"
                normalized in 67.5..<112.5 -> "E →"
                normalized in 112.5..<157.5 -> "SE ↘"
                normalized in 157.5..<202.5 -> "S ↓"
                normalized in 202.5..<247.5 -> "SW ↙"
                normalized in 247.5..<292.5 -> "W ←"
                normalized in 292.5..<337.5 -> "NW ↖"
                else -> "N ↑"
            }
        }
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateIfBetter(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun startListening() {
        if (!hasLocationPermission() || locationManager == null || isListening) return

        try {
            // Immediate cached fix from any provider
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val lastPassive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            val bestLast = listOfNotNull(lastGps, lastNetwork, lastPassive)
                .maxByOrNull { it.time }

            bestLast?.let { updateIfBetter(it) }

            // Register continuous updates with GPS Satellite Provider (works 100% offline)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener
                )
            }

            // Also register Network/Cell/WiFi provider if enabled
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener
                )
            }

            isListening = true
            Log.d(TAG, "Location listening started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location updates", e)
        }
    }

    fun stopListening() {
        if (!isListening || locationManager == null) return
        try {
            locationManager.removeUpdates(locationListener)
            isListening = false
            Log.d(TAG, "Location listening stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping location updates", e)
        }
    }

    private fun updateIfBetter(newLocation: Location) {
        val current = _currentLocation.value
        if (current == null) {
            _currentLocation.value = newLocation
            return
        }

        val timeDiff = newLocation.time - current.time
        val isSignificantlyNewer = timeDiff > 60_000L
        val isSignificantlyOlder = timeDiff < -60_000L
        val isNewer = timeDiff > 0

        if (isSignificantlyNewer) {
            _currentLocation.value = newLocation
            return
        } else if (isSignificantlyOlder) {
            return
        }

        val accuracyDiff = (newLocation.accuracy - current.accuracy).toInt()
        val isLessAccurate = accuracyDiff > 0
        val isMoreAccurate = accuracyDiff < 0

        if (isMoreAccurate || (isNewer && !isLessAccurate)) {
            _currentLocation.value = newLocation
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
