package com.davidewp.duke390dash

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * DashTileService — tile del pannello rapido.
 *
 * Legge lo stato direttamente dai companion StateFlow del service
 * senza dipendere da MainActivity o broadcast.
 *
 * onClick parla direttamente con DashForegroundService via startService(Intent)
 * con ACTION_TOGGLE_LOG — funziona anche con schermo spento e MainActivity assente.
 */
class DashTileService : TileService() {

    companion object {
        const val ACTION_STATE_UPDATE = "com.davidewp.duke390dash.STATE_UPDATE"
        const val EXTRA_RUNNING       = "running"
        const val EXTRA_LOGGING       = "logging"

        // Chiamato da MainActivity per forzare un aggiornamento del tile
        // (usato solo in onDestroy per segnare running=false)
        fun updateTile(context: Context, running: Boolean, logging: Boolean) {
            val intent = Intent(context, DashTileService::class.java).apply {
                action = ACTION_STATE_UPDATE
                putExtra(EXTRA_RUNNING, running)
                putExtra(EXTRA_LOGGING, logging)
            }
            context.startService(intent)
        }
    }

    private var stateJob: Job? = null
    private val tileScope = CoroutineScope(Dispatchers.Main)

    // Stato locale — aggiornato dal flow collector
    private var isRunning = false
    private var isLogging = false

    override fun onStartListening() {
        super.onStartListening()
        // Legge lo stato corrente dai companion StateFlow del service —
        // nessun broadcast, nessuna dipendenza da MainActivity.
        // dashState.value.obd.connected indica se il service è attivo e ha dati.
        stateJob = tileScope.launch {
            combine(
                DashForegroundService.dashState,
                DashForegroundService.gpsState  // usato solo per trigger — non ci serve il valore
            ) { dash, _ ->
                // Il service è "running" se ha mai ricevuto dati OBD o TPMS
                val running = dash.obd.connected ||
                        dash.tpmsAnt.pressureBar  > 0f ||
                        dash.tpmsPost.pressureBar > 0f ||
                        DashForegroundService.isLoggingState
                running
            }.collect { running ->
                isRunning = running
                isLogging = DashForegroundService.isLoggingState
                renderTile()
            }
        }
        // Render immediato con lo stato attuale senza aspettare il flow
        isLogging = DashForegroundService.isLoggingState
        renderTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
        stateJob = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STATE_UPDATE) {
            isRunning = intent.getBooleanExtra(EXTRA_RUNNING, isRunning)
            isLogging = intent.getBooleanExtra(EXTRA_LOGGING, isLogging)
            renderTile()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onClick() {
        super.onClick()
        if (!isRunning) {
            // App non attiva — aprila
            startActivityAndCollapse(
                Intent(this, SplashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            return
        }
        // Manda il toggle direttamente al service — bypassa MainActivity
        val intent = Intent(this, DashForegroundService::class.java).apply {
            action = DashForegroundService.ACTION_TOGGLE_LOG
        }
        startService(intent)
        // Aggiorna il tile ottimisticamente — il flow lo aggiornerà comunque
        isLogging = !isLogging
        renderTile()
    }

    private fun renderTile() {
        val tile = qsTile ?: return
        when {
            !isRunning -> {
                tile.state    = Tile.STATE_INACTIVE
                tile.subtitle = ""
            }
            isLogging -> {
                tile.state    = Tile.STATE_ACTIVE
                tile.subtitle = "● REC"
            }
            else -> {
                tile.state    = Tile.STATE_ACTIVE
                tile.subtitle = ""
            }
        }
        tile.label = "Duke 390"
        tile.icon  = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }
}