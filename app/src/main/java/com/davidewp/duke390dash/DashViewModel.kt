package com.davidewp.duke390dash

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * DashViewModel — mirror UI read-only dei dati prodotti dal service.
 * Non inizializza nulla. Tutti i manager vivono in DashForegroundService.
 */
class DashViewModel : ViewModel() {

    companion object {
        const val PREFS_NAME     = "duke390_prefs"
        const val PREF_ID_ANT    = "sensor_id_ant"
        const val PREF_ID_POST   = "sensor_id_post"
        const val PREF_MOTO_MODE = "moto_mode"
    }

    // Alias diretti dei companion StateFlow del service — zero overhead
    val dashState: StateFlow<DashState>          = DashForegroundService.dashState
    val gLateral:  StateFlow<Float>              = DashForegroundService.gLateralFlow
    val gpsState:  StateFlow<GpsManager.GpsData> = DashForegroundService.gpsState

    fun saveSettings(
        context: Context,
        idAnt:   String,
        idPost:  String,
        service: DashForegroundService?
    ) {
        val safeIdAnt  = idAnt.trim().uppercase().takeIf  { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_ANT
        val safeIdPost = idPost.trim().uppercase().takeIf { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_POST

        if (service != null) {
            service.applySettings(safeIdAnt, safeIdPost)
        } else {
            // Fallback: salva nelle prefs, il service le leggerà al prossimo avvio
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_ID_ANT,  safeIdAnt)
                .putString(PREF_ID_POST, safeIdPost)
                .apply()
        }
    }
}