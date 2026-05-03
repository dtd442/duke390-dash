package com.davidewp.duke390dash

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * DashForegroundService — unico proprietario di tutti i manager.
 *
 * Architettura:
 *   TpmsManager ─┐
 *   ObdManager  ─┼─► combine ─► _dashState ─► loop log 500ms
 *   GpsManager  ─┘                          ─► ViewModel (UI read-only)
 *   GSensor ──────────────────────────────► _gLateral ─► ViewModel (UI)
 *
 * Il ViewModel non inizializza più nulla — fa solo collect degli StateFlow
 * esposti nel companion object di questo service.
 * La MainActivity non tocca mai i dati grezzi.
 */
class DashForegroundService : Service() {

    companion object {
        const val CHANNEL_ID      = "duke390_dash_channel"
        const val NOTIF_ID        = 1
        const val LOG_INTERVAL_MS = 500L  // 2 Hz

        // Azione inviata dal tile per togglare il log senza passare per MainActivity
        const val ACTION_TOGGLE_LOG = "com.davidewp.duke390dash.TOGGLE_LOG"

        // ── StateFlow pubblici letti dal ViewModel senza binding ──────────────
        private val _dashState = MutableStateFlow(DashState())
        val dashState: StateFlow<DashState> = _dashState

        private val _gLateral = MutableStateFlow(0f)
        val gLateralFlow: StateFlow<Float> = _gLateral

        private val _gpsState = MutableStateFlow(GpsManager.GpsData())
        val gpsState: StateFlow<GpsManager.GpsData> = _gpsState

        // Stato logging leggibile dal tile senza binding
        val isLoggingState: Boolean
            get() = _isLoggingState
        private var _isLoggingState = false

        // true dal momento in cui il service è vivo — settato in onCreate, cleared in onDestroy
        var isAlive: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DashForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DashForegroundService::class.java))
        }
    }

    // ── Manager — tutti nel service, nessuno nel ViewModel ───────────────────
    private lateinit var tpmsManager:   TpmsManager
    private lateinit var obdManager:    ObdManager
    private lateinit var gpsManager:    GpsManager
    private lateinit var gSensor:       GSensor
    private lateinit var sessionLogger: SessionLogger

    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var lastGLateral: Float = 0f
    @Volatile private var lastGyroX:    Float = 0f
    @Volatile private var lastGyroY:    Float = 0f
    @Volatile private var lastGyroZ:    Float = 0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        isAlive = true
        AppLog.init(this)   // apre il file di log prima che i manager inizino a scrivere

        // ── Legge prefs e inizializza i manager ───────────────────────────────
        val prefs  = getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val idAnt  = prefs.getString(DashViewModel.PREF_ID_ANT,  null)
            ?.takeIf { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_ANT
        val idPost = prefs.getString(DashViewModel.PREF_ID_POST, null)
            ?.takeIf { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_POST

        tpmsManager   = TpmsManager(this)
        obdManager    = ObdManager(this)
        gpsManager    = GpsManager(this)
        sessionLogger = SessionLogger(this)

        tpmsManager.start(idAnt, idPost)
        obdManager.start()
        gpsManager.start()

        gSensor = GSensor(this) { gLateral, gyroX, gyroY, gyroZ ->
            lastGLateral    = gLateral
            lastGyroX       = gyroX
            lastGyroY       = gyroY
            lastGyroZ       = gyroZ
            _gLateral.value = gLateral
        }
        gSensor.start()

        // ── Unico combine → _dashState ────────────────────────────────────────
        serviceScope.launch {
            combine(
                tpmsManager.antState,
                tpmsManager.postState,
                obdManager.obdState,
                obdManager.peaks
            ) { ant, post, obd, peaks ->
                DashState(
                    tpmsAnt  = ant,
                    tpmsPost = post,
                    obd      = obd,
                    peaks    = peaks
                )
            }.collect { _dashState.value = it }
        }

        // ── Sincronizza gpsState nel companion ───────────────────────────────
        serviceScope.launch {
            gpsManager.gpsState.collect { _gpsState.value = it }
        }

        // ── Loop di log 2 Hz — indipendente da OBD/TPMS ──────────────────────
        // Scrive dal primo frame utile non appena isLogging è true,
        // indipendentemente da quali sensori siano già connessi.
        serviceScope.launch {
            while (isActive) {
                delay(LOG_INTERVAL_MS)
                if (sessionLogger.isLogging) {
                    sessionLogger.log(
                        state    = _dashState.value,
                        gLateral = lastGLateral,
                        gyroX    = lastGyroX,
                        gyroY    = lastGyroY,
                        gyroZ    = lastGyroZ,
                        gps      = _gpsState.value
                    )
                }
            }
        }
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    fun startLogging() {
        sessionLogger.startSession()
        _isLoggingState = sessionLogger.isLogging
    }
    fun stopLogging() {
        sessionLogger.stopSession()
        _isLoggingState = false
    }
    fun isLogging()         = sessionLogger.isLogging
    fun setGSensorOffset()  = gSensor.setOffset()
    fun getGSensorOffsetG() = gSensor.getOffsetG()

    /**
     * Aggiorna gli ID TPMS e riavvia i manager BLE.
     * Salva anche nelle prefs per il prossimo avvio.
     */
    fun applySettings(idAnt: String, idPost: String) {
        getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DashViewModel.PREF_ID_ANT,  idAnt)
            .putString(DashViewModel.PREF_ID_POST, idPost)
            .apply()
        tpmsManager.restart(idAnt, idPost)
        obdManager.restart()
    }

    // ── Binder ────────────────────────────────────────────────────────────────

    private val binder = LocalBinder()
    private var boundClients = 0

    inner class LocalBinder : android.os.Binder() {
        fun getService(): DashForegroundService = this@DashForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients--
        return super.onUnbind(intent)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_LOG) {
            if (sessionLogger.isLogging) {
                sessionLogger.stopSession()
                _isLoggingState = false
                DashTileService.updateTile(this, running = true, logging = false)
                // Killa il service solo se nessuna Activity è bindata (app chiusa)
                if (boundClients == 0) {
                    DashTileService.updateTile(this, running = false, logging = false)
                    AppLog.close()
                    stopSelf()
                }
            } else {
                sessionLogger.startSession()
                _isLoggingState = sessionLogger.isLogging
                DashTileService.updateTile(this, running = true, logging = _isLoggingState)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isAlive = false
        serviceScope.cancel()
        gSensor.stop()
        gpsManager.stop()
        tpmsManager.stop()
        obdManager.stop()
        sessionLogger.stopSession()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Duke390Dash::WakeLock")
            .apply { acquire(4 * 60 * 60 * 1000L) }
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