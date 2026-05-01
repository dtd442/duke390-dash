package com.davidewp.duke390dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DashTileService : TileService() {

    companion object {
        // Azioni broadcast — usate per comunicare con MainActivity
        const val ACTION_TOGGLE_LOG    = "com.davidewp.duke390dash.TOGGLE_LOG"
        const val ACTION_STATE_UPDATE  = "com.davidewp.duke390dash.STATE_UPDATE"
        const val EXTRA_RUNNING        = "running"
        const val EXTRA_LOGGING        = "logging"

        // Chiamato da MainActivity ogni volta che cambia lo stato
        fun updateTile(context: Context, running: Boolean, logging: Boolean) {
            val intent = Intent(ACTION_STATE_UPDATE).apply {
                putExtra(EXTRA_RUNNING, running)
                putExtra(EXTRA_LOGGING, logging)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    private var isRunning = false
    private var isLogging = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STATE_UPDATE) {
                isRunning = intent.getBooleanExtra(EXTRA_RUNNING, false)
                isLogging = intent.getBooleanExtra(EXTRA_LOGGING, false)
                updateTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        registerReceiver(
            stateReceiver,
            IntentFilter(ACTION_STATE_UPDATE),
            RECEIVER_NOT_EXPORTED
        )
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    override fun onClick() {
        super.onClick()
        if (!isRunning) {
            // App non attiva — aprila
            val intent = Intent(this, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivityAndCollapse(intent)
            return
        }
        // App attiva — toglia registrazione telemetria
        val intent = Intent(DashTileService.ACTION_TOGGLE_LOG).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when {
            !isRunning -> {
                tile.state    = Tile.STATE_INACTIVE
                tile.label    = "Duke 390"
                tile.subtitle = ""
            }
            isLogging -> {
                tile.state    = Tile.STATE_ACTIVE
                tile.label    = "Duke 390"
                tile.subtitle = "● REC"
            }
            else -> {
                tile.state    = Tile.STATE_ACTIVE
                tile.label    = "Duke 390"
                tile.subtitle = ""
            }
        }
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }
}