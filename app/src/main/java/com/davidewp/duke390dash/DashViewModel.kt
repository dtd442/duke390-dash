package com.davidewp.duke390dash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * DashViewModel — solo mirror UI dei dati prodotti dal service.
 *
 * Non inizializza più TpmsManager, ObdManager, GpsManager o GSensor.
 * Tutti i manager vivono in DashForegroundService.
 * Il ViewModel fa collect dei companion StateFlow del service
 * e li espone alla MainActivity.
 *
 * saveSettings() ora delega al service tramite binder invece di
 * gestire direttamente i manager.
 */
class DashViewModel : ViewModel() {

    companion object {
        const val PREFS_NAME     = "duke390_prefs"
        const val PREF_ID_ANT    = "sensor_id_ant"
        const val PREF_ID_POST   = "sensor_id_post"
        const val PREF_MOTO_MODE = "moto_mode"
    }

    // ── StateFlow esposti alla UI ─────────────────────────────────────────────
    // Sono alias diretti dei companion StateFlow del service —
    // nessuna copia, nessun relay, zero overhead.
    val dashState:   StateFlow<DashState>          = DashForegroundService.dashState
    val gLateral:    StateFlow<Float>              = DashForegroundService.gLateralFlow
    val gpsState:    StateFlow<GpsManager.GpsData> = DashForegroundService.gpsState

    // SimulationManager resta nel ViewModel: è pura logica UI, non ha bisogno
    // di vivere nel service.
    val simulationManager = SimulationManager()

    init {
        // Quando la simulazione è attiva sovrascrive il dashState del service
        // esattamente come prima — collect simState e aggiorna _dashState del service.
        viewModelScope.launch {
            simulationManager.simState.collect { sim ->
                if (sim != null) {
                    // La simulazione sovrascrive lo stato — inietta nel companion StateFlow
                    // tramite il cast a MutableStateFlow.
                    // Nota: questo è safe perché _dashState nel companion è privato
                    // e dashState è la sua StateFlow pubblica — usiamo reflection-free cast.
                    (DashForegroundService.dashState as? kotlinx.coroutines.flow.MutableStateFlow)
                        ?.value = sim
                }
                // Quando sim == null il service riprende ad aggiornare dashState
                // con i dati reali dal combine — nessuna azione necessaria.
            }
        }
    }

    /**
     * Delega le impostazioni al service (se bound) oppure salva solo le prefs
     * (il service le leggerà al prossimo onCreate).
     */
    fun saveSettings(
        context: Context,
        idAnt: String,
        idPost: String,
        service: DashForegroundService?
    ) {
        val safeIdAnt  = idAnt.trim().uppercase().takeIf  { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_ANT
        val safeIdPost = idPost.trim().uppercase().takeIf { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_POST

        if (service != null) {
            // Service attivo: applica subito senza riavvio
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

    fun startSimulation() = simulationManager.start()
    fun stopSimulation()  = simulationManager.stop()

    override fun onCleared() {
        super.onCleared()
        simulationManager.stop()
        // TpmsManager, ObdManager, GpsManager, GSensor vengono fermati
        // in DashForegroundService.onDestroy() — non qui.
    }
}
