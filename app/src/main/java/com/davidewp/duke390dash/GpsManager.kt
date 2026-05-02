package com.davidewp.duke390dash

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GpsManager(private val context: Context) {

    companion object {
        const val TAG            = "GpsManager"
        const val MIN_TIME_MS    = 1000L  // aggiorna ogni secondo
        const val MIN_DISTANCE_M = 0f     // aggiorna anche da fermo (era 5f: saltava punti in curva lenta)
    }

    data class GpsData(
        val latitude:   Double  = 0.0,
        val longitude:  Double  = 0.0,
        val altitudeM:  Double  = 0.0,   // altitudine GPS nativa (m slm) — 0 se non disponibile
        val bearingDeg: Float   = 0f,    // direzione di marcia 0–360° — 0 se non disponibile
        val speedKmh:   Float   = 0f,
        val accuracyM:  Float   = 0f,
        val available:  Boolean = false
    )

    private val _gpsState = MutableStateFlow(GpsData())
    val gpsState: StateFlow<GpsData> = _gpsState

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var gpsListener:     LocationListener? = null
    private var networkListener: LocationListener? = null

    val isAvailable: Boolean
        get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start() {
        // ── Provider GPS principale ───────────────────────────────────────────
        if (isAvailable) {
            gpsListener = buildListener(authoritative = true)
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    gpsListener!!,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "GPS avviato")
            } catch (e: Exception) {
                Log.e(TAG, "Errore avvio GPS: ${e.message}")
            }
        } else {
            Log.d(TAG, "GPS hardware non disponibile")
        }

        // ── Provider NETWORK — fallback per fix iniziale rapido ───────────────
        // Utile nei primi ~120s prima che il GPS agganci i satelliti.
        // Non sovrascrive i dati una volta che il GPS è attivo.
        val netAvailable = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }

        if (netAvailable) {
            networkListener = buildListener(authoritative = false)
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    networkListener!!,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Network provider avviato come fallback")
            } catch (e: Exception) {
                Log.e(TAG, "Errore avvio network provider: ${e.message}")
            }
        }
    }

    fun stop() {
        gpsListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: Exception) {}
        }
        networkListener?.let {
            try { locationManager.removeUpdates(it) } catch (_: Exception) {}
        }
        gpsListener     = null
        networkListener = null
        Log.d(TAG, "GPS fermato")
    }

    // ── Costruisce il listener ────────────────────────────────────────────────
    // authoritative = true  → provider GPS: aggiorna sempre
    // authoritative = false → provider NETWORK: aggiorna solo finché GPS non ha fix
    private fun buildListener(authoritative: Boolean) = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!authoritative && _gpsState.value.available) return
            _gpsState.value = GpsData(
                latitude   = loc.latitude,
                longitude  = loc.longitude,
                altitudeM  = if (loc.hasAltitude()) loc.altitude else 0.0,
                bearingDeg = if (loc.hasBearing())  loc.bearing  else 0f,
                speedKmh   = loc.speed * 3.6f,
                accuracyM  = loc.accuracy,
                available  = true
            )
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (authoritative) _gpsState.value = _gpsState.value.copy(available = false)
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
}
