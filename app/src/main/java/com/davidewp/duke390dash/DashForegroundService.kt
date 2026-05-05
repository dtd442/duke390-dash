package com.davidewp.duke390dash

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DashForegroundService : Service() {

    // ── Stati calibrazione ────────────────────────────────────────────────────
    enum class CalibState {
        IDLE,           // START non ancora premuto
        STATIC_WAIT,    // calibrazione lean in corso (~400ms)
        STATIC_DONE,    // calibrazione lean completata
        MOTION_CALIB    // riservato per uso futuro
    }

    companion object {
        const val CHANNEL_ID      = "duke390_dash_channel"
        const val NOTIF_ID        = 1
        const val LOG_INTERVAL_MS = 500L  // 2 Hz

        const val ACTION_TOGGLE_LOG = "com.davidewp.duke390dash.TOGGLE_LOG"

        // ── StateFlow pubblici ────────────────────────────────────────────────
        private val _dashState  = MutableStateFlow(DashState())
        val dashState: StateFlow<DashState> = _dashState

        private val _gpsState   = MutableStateFlow(GpsManager.GpsData())
        val gpsState: StateFlow<GpsManager.GpsData> = _gpsState

        private val _calibState = MutableStateFlow(CalibState.IDLE)
        val calibStateFlow: StateFlow<CalibState> = _calibState

        // ── Lean angle condiviso MainActivity → service (per il log) ──────────
        @Volatile
        var currentLeanAngle: Float = 0f

        // ── Stato logging ─────────────────────────────────────────────────────
        val isLoggingState: Boolean get() = _isLoggingState
        private var _isLoggingState = false

        var isAlive: Boolean = false
            private set

        // ── API calibrazione — chiamata da MainActivity ────────────────────────
        fun setCalibState(state: CalibState) {
            _calibState.value = state
        }

        // ── Lifecycle service ─────────────────────────────────────────────────
        fun start(context: Context) =
            context.startForegroundService(Intent(context, DashForegroundService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, DashForegroundService::class.java))
    }

    // ── Manager ───────────────────────────────────────────────────────────────
    private lateinit var tpmsManager:   TpmsManager
    private lateinit var obdManager:    ObdManager
    private lateinit var gpsManager:    GpsManager
    private lateinit var sessionLogger: SessionLogger

    @Suppress("DEPRECATION")
    private lateinit var vibrator: Vibrator

    private var wakeLock:     PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val binder       = LocalBinder()
    private var boundClients = 0

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        // Crash handler — cattura eccezioni non gestite su tutti i thread del service
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("CRASH_SVC", "═══ SERVICE CRASH ═══")
            android.util.Log.e("CRASH_SVC", "Thread: ${thread.name}")
            android.util.Log.e("CRASH_SVC", "Message: ${throwable.message}")
            android.util.Log.e("CRASH_SVC", "Stacktrace:", throwable)
            Thread.sleep(300)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        super.onCreate()
        isAlive = true

        // startForeground PRIMA di qualsiasi altra operazione — obbligatorio entro 5s
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()

        // Init manager
        @Suppress("DEPRECATION")
        vibrator      = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        sessionLogger = SessionLogger(this)
        gpsManager    = GpsManager(this)
        tpmsManager   = TpmsManager(this)
        obdManager    = ObdManager(this)

        // Avvio con ID dalle prefs
        val prefs  = getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val idAnt  = prefs.getString(DashViewModel.PREF_ID_ANT,  TpmsManager.DEFAULT_ID_ANT)  ?: TpmsManager.DEFAULT_ID_ANT
        val idPost = prefs.getString(DashViewModel.PREF_ID_POST, TpmsManager.DEFAULT_ID_POST) ?: TpmsManager.DEFAULT_ID_POST

        gpsManager.start()
        tpmsManager.start(idAnt, idPost)
        obdManager.start()

        // Propaga i StateFlow dei manager nel dashState unificato
        serviceScope.launch {
            gpsManager.gpsState.collect { data ->
                _gpsState.value = data
            }
        }
        serviceScope.launch {
            tpmsManager.antState.collect { ant ->
                _dashState.value = _dashState.value.copy(tpmsAnt = ant)
            }
        }
        serviceScope.launch {
            tpmsManager.postState.collect { post ->
                _dashState.value = _dashState.value.copy(tpmsPost = post)
            }
        }
        serviceScope.launch {
            obdManager.obdState.collect { obd ->
                _dashState.value = _dashState.value.copy(obd = obd)
            }
        }
        serviceScope.launch {
            obdManager.peaks.collect { peaks ->
                _dashState.value = _dashState.value.copy(peaks = peaks)
            }
        }

        // Loop di log a 2 Hz
        serviceScope.launch {
            while (isActive) {
                delay(LOG_INTERVAL_MS)
                if (sessionLogger.isLogging) {
                    sessionLogger.log(
                        state     = _dashState.value,
                        gps       = _gpsState.value,
                        leanAngle = currentLeanAngle
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_LOG) {
            if (sessionLogger.isLogging) {
                sessionLogger.stopSession()
                _isLoggingState = false
                DashTileService.updateTile(this, running = true, logging = false)
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
        gpsManager.stop()
        tpmsManager.stop()
        obdManager.stop()
        sessionLogger.stopSession()
        releaseWakeLock()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  API pubblica (chiamata dalla MainActivity tramite binder)
    // ─────────────────────────────────────────────────────────────────────────

    fun startLogging() {
        sessionLogger.startSession()
        _isLoggingState = sessionLogger.isLogging
    }

    fun stopLogging() {
        sessionLogger.stopSession()
        _isLoggingState = false
    }

    fun isLogging() = sessionLogger.isLogging

    fun applySettings(idAnt: String, idPost: String) {
        getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DashViewModel.PREF_ID_ANT,  idAnt)
            .putString(DashViewModel.PREF_ID_POST, idPost)
            .apply()
        tpmsManager.restart(idAnt, idPost)
        obdManager.restart()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Binder
    // ─────────────────────────────────────────────────────────────────────────

    inner class LocalBinder : android.os.Binder() {
        fun getService(): DashForegroundService = this@DashForegroundService
    }

    override fun onBind(intent: Intent?): IBinder   { boundClients++; return binder }
    override fun onUnbind(intent: Intent?): Boolean { boundClients--; return super.onUnbind(intent) }

    // ─────────────────────────────────────────────────────────────────────────
    //  WakeLock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Duke390Dash::WakeLock")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Notifica
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}