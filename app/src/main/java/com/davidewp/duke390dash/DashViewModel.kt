package com.davidewp.duke390dash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DashViewModel : ViewModel() {

    companion object {
        const val PREFS_NAME    = "duke390_prefs"
        const val PREF_ID_ANT   = "sensor_id_ant"
        const val PREF_ID_POST  = "sensor_id_post"
        const val PREF_MOTO_MODE = "moto_mode"
        // OBD_DEVICE_MAC rimosso: ObdManager si connette per nome (vLinker MC-IOS),
        // non ha bisogno di un MAC configurabile.
    }

    private lateinit var tpmsManager: TpmsManager
    private lateinit var obdManager: ObdManager
    val simulationManager = SimulationManager()

    private val _dashState = MutableStateFlow(DashState())
    val dashState: StateFlow<DashState> = _dashState

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        tpmsManager = TpmsManager(context)
        obdManager  = ObdManager(context)

        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val idAnt  = prefs.getString(PREF_ID_ANT,  "13912F") ?: "13912F"
        val idPost = prefs.getString(PREF_ID_POST, "238D2A") ?: "238D2A"

        tpmsManager.start(idAnt, idPost)
        obdManager.start()

        viewModelScope.launch {
            combine(
                tpmsManager.antState,
                tpmsManager.postState,
                obdManager.obdState,
                obdManager.peaks,
                simulationManager.simState
            ) { ant, post, obd, peaks, sim ->
                sim ?: DashState(
                    tpmsAnt  = ant,
                    tpmsPost = post,
                    obd      = obd,
                    peaks    = peaks
                )
            }.collect { state ->
                _dashState.value = state
            }
        }
    }

    fun saveSettings(context: Context, idAnt: String, idPost: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ID_ANT,  idAnt)
            .putString(PREF_ID_POST, idPost)
            .apply()

        tpmsManager.restart(idAnt, idPost)
        obdManager.restart()
    }

    fun startSimulation() = simulationManager.start()
    fun stopSimulation()  = simulationManager.stop()

    override fun onCleared() {
        super.onCleared()
        tpmsManager.stop()
        obdManager.stop()
        simulationManager.stop()
    }
}