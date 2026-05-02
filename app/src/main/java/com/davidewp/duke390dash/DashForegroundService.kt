package com.davidewp.duke390dash

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DashForegroundService : Service() {

    companion object {
        const val CHANNEL_ID  = "duke390_dash_channel"
        const val NOTIF_ID    = 1
        const val LOG_INTERVAL_MS = 500L   // frequenza di campionamento: 2 Hz

        // Espone gLateral e gyroPitch/Yaw alla MainActivity per la UI,
        // senza che l'Activity debba tenere il proprio GSensor.
        private val _gLateral = MutableStateFlow(0f)
        val gLateralFlow: StateFlow<Float> = _gLateral

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DashForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DashForegroundService::class.java))
        }
    }

    private var wakeLock:      PowerManager.WakeLock? = null
    private lateinit var gpsManager:     GpsManager
    private lateinit var gSensor:        GSensor
    private lateinit var sessionLogger:  SessionLogger

    // Scope del service — cancellato in onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Stato OBD/TPMS aggiornato dall'Activity via updateDashState().
    // MutableStateFlow è thread-safe — nessun @Volatile necessario.
    private val _dashState = MutableStateFlow(DashState())
    @Volatile private var lastGLateral: Float = 0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()

        // ── GPS — inizializzato qui, vive per tutta la durata del service ──────
        gpsManager = GpsManager(this)
        gpsManager.start()

        // ── GSensor — aggiorna lastGLateral e lo espone alla MainActivity ──────
        gSensor = GSensor(this) { gLateral ->
            lastGLateral = gLateral
            _gLateral.value = gLateral
        }
        gSensor.start()

        // ── SessionLogger ─────────────────────────────────────────────────────
        sessionLogger = SessionLogger(this)

        // ── Loop di log a intervallo fisso ────────────────────────────────────
        // Indipendente dalla frequenza OBD: anche se l'OBD non risponde per
        // 1 secondo, il GPS e il G-sensor vengono comunque campionati.
        // Guard: non scrivere frame completamente vuoti (binding non ancora pronto
        // o OBD mai connesso e TPMS mai ricevuto).
        serviceScope.launch {
            while (isActive) {
                delay(LOG_INTERVAL_MS)
                val state = _dashState.value
                val hasData = state.obd.connected ||
                              state.tpmsAnt.pressureBar > 0f ||
                              state.tpmsPost.pressureBar > 0f
                if (sessionLogger.isLogging && hasData) {
                    sessionLogger.log(
                        state    = state,
                        gLateral = lastGLateral,
                        gps      = gpsManager.gpsState.value
                    )
                }
            }
        }
    }

    /**
     * Chiamato dalla MainActivity per aggiornare lo stato OBD/TPMS
     * che il service usa nel loop di log.
     * L'Activity continua a fare collect di dashState per la UI,
     * ma ora è il service a decidere quando scrivere.
     */
    fun updateDashState(state: DashState) {
        _dashState.value = state
    }

    /**
     * Deleghe SessionLogger — chiamati dalla MainActivity tramite binding o broadcast.
     */
    fun startLogging()  = sessionLogger.startSession()
    fun stopLogging()   = sessionLogger.stopSession()
    fun isLogging()     = sessionLogger.isLogging

    /**
     * Offset calibrazione G-sensor — la MainActivity può ancora mostrare il valore
     * nella dialog settings tramite il service bound o via StateFlow.
     */
    fun setGSensorOffset() = gSensor.setOffset()
    fun getGSensorOffsetG() = gSensor.getOffsetG()

    // ── Binder — la MainActivity si lega per chiamare le funzioni di controllo ──
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): DashForegroundService = this@DashForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        serviceScope.cancel()
        gSensor.stop()
        gpsManager.stop()
        sessionLogger.stopSession()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Duke390Dash::BleWakeLock"
        ).apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Notifica ──────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false); enableVibration(false)
            enableLights(false); setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}