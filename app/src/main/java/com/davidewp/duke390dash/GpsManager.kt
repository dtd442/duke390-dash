package com.davidewp.duke390dash

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GpsManager(private val context: Context) {

    companion object {
        const val TAG = "GpsManager"
        const val MIN_TIME_MS = 1000L    // aggiorna ogni secondo
        const val MIN_DISTANCE_M = 5f    // aggiorna ogni 5 metri
    }

    data class GpsData(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val speedKmh: Float = 0f,
        val accuracyM: Float = 0f,
        val available: Boolean = false
    )

    private val _gpsState = MutableStateFlow(GpsData())
    val gpsState: StateFlow<GpsData> = _gpsState

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var locationListener: LocationListener? = null

    val isAvailable: Boolean
        get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!isAvailable) {
            Log.d(TAG, "GPS non disponibile su questo dispositivo")
            return
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                _gpsState.value = GpsData(
                    latitude  = loc.latitude,
                    longitude = loc.longitude,
                    speedKmh  = loc.speed * 3.6f,
                    accuracyM = loc.accuracy,
                    available = true
                )
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                _gpsState.value = _gpsState.value.copy(available = false)
            }
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener!!
            )
            Log.d(TAG, "GPS avviato")
        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio GPS: ${e.message}")
        }
    }

    fun stop() {
        locationListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: Exception) {}
        }
        locationListener = null
        Log.d(TAG, "GPS fermato")
    }
}