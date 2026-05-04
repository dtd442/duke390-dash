package com.davidewp.duke390dash

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/**
 * DashForegroundService — unico proprietario di tutti i manager.
 *
 * Architettura:
 *   TpmsManager ─┐
 *   ObdManager  ─┼─► combine ─► _dashState ─► loop log 500ms
 *   GpsManager  ─┘                          ─► ViewModel (UI read-only)
 *   GSensor ──────────────────────────────► _gLateral ─► ViewModel (UI)
 *
 * Calibrazione automatica orientamento IMU:
 *   WAITING_STILL → WAITING_MOVE → CALIBRATING → DONE
 *   - WAITING_STILL : telefono fermo in tasca (|acc|≈1g, gyro≈0 per 3s)
 *   - WAITING_MOVE  : aspetta GPS speed > 8 km/h (moto partita)
 *   - CALIBRATING   : rettilineo stabile per 4s → cattura vettore gravità
 *   - DONE          : matrice di rotazione applicata a tutti i frame IMU
 *
 * Il ViewModel non inizializza più nulla — fa solo collect degli StateFlow
 * esposti nel companion object di questo service.
 * La MainActivity non tocca mai i dati grezzi.
 */
class DashForegroundService : Service() {

    // ── Calibrazione IMU ──────────────────────────────────────────────────────

    enum class CalibState { IDLE, WAITING_STILL, WAITING_MOVE, CALIBRATING, DONE }

    companion object {
        const val CHANNEL_ID      = "duke390_dash_channel"
        const val NOTIF_ID        = 1
        const val LOG_INTERVAL_MS = 500L  // 2 Hz

        // Soglie calibrazione
        private const val CALIB_STILL_FRAMES   = 6   // 3s a 2Hz — telefono fermo in tasca
        private const val CALIB_MOVE_SPEED_KMH  = 8f  // moto considerata partita
        private const val CALIB_STABLE_FRAMES   = 8   // 4s a 2Hz — rettilineo stabile
        private const val CALIB_GYRO_THRESHOLD  = 0.15f  // rad/s — quasi nessuna rotazione
        private const val CALIB_ACCEL_TOLERANCE = 0.05f  // g — tolleranza da 1g per "fermo"
        private const val CALIB_MIN_SPEED_KMH   = 20f    // km/h — velocità minima per calibrazione

        // Azione inviata dal tile per togglare il log senza passare per MainActivity
        const val ACTION_TOGGLE_LOG = "com.davidewp.duke390dash.TOGGLE_LOG"

        // ── StateFlow pubblici letti dal ViewModel senza binding ──────────────
        private val _dashState = MutableStateFlow(DashState())
        val dashState: StateFlow<DashState> = _dashState

        private val _gLateral = MutableStateFlow(0f)
        val gLateralFlow: StateFlow<Float> = _gLateral

        private val _gpsState = MutableStateFlow(GpsManager.GpsData())
        val gpsState: StateFlow<GpsManager.GpsData> = _gpsState

        // Stato calibrazione leggibile dall'UI
        private val _calibState = MutableStateFlow(CalibState.WAITING_STILL)
        val calibStateFlow: StateFlow<CalibState> = _calibState

        // Stato logging leggibile dal tile senza binding
        val isLoggingState: Boolean
            get() = _isLoggingState
        private var _isLoggingState = false

        // true dal momento in cui il service è vivo
        var isAlive: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DashForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DashForegroundService::class.java))
        }
    }

    // ── Manager ───────────────────────────────────────────────────────────────
    private lateinit var tpmsManager:   TpmsManager
    private lateinit var obdManager:    ObdManager
    private lateinit var gpsManager:    GpsManager
    private lateinit var gSensor:       MotionSensor
    private lateinit var sessionLogger: SessionLogger

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Valori IMU grezzi (aggiornati dal callback sensore, thread-safe) ──────
    // Valori IMU con dead-band — per log e UI
    @Volatile private var lastGLateral: Float = 0f
    @Volatile private var lastGLong:    Float = 0f
    @Volatile private var lastGVert:    Float = 0f
    @Volatile private var lastGyroX:    Float = 0f
    @Volatile private var lastGyroY:    Float = 0f
    @Volatile private var lastGyroZ:    Float = 0f

    // Valori IMU grezzi (senza dead-band) — solo per la calibrazione
    // La dead-band azzera componenti piccole e corrompe il vettore gravità
    @Volatile private var rawGX:    Float = 0f
    @Volatile private var rawGY:    Float = 0f
    @Volatile private var rawGZ:    Float = 0f
    @Volatile private var rawGyroX: Float = 0f
    @Volatile private var rawGyroY: Float = 0f
    @Volatile private var rawGyroZ: Float = 0f

    // ── Stato macchina a stati calibrazione ───────────────────────────────────
    private var calibPhase       = CalibState.IDLE
    private var stillCounter     = 0
    private var calibCounter     = 0
    private var accumGX          = 0f
    private var accumGY          = 0f
    private var accumGZ          = 0f

    // Matrice di rotazione 3×3 (row-major) — identità finché non calibrata
    private var rotMatrix = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    // ── Logica calibrazione ───────────────────────────────────────────────────

    /**
     * Applica la matrice di rotazione a un vettore 3D.
     * Converte le coordinate del telefono negli assi della moto:
     *   X = laterale (positivo = destra)
     *   Y = longitudinale (positivo = avanti)
     *   Z = verticale (positivo = su)
     */
    private fun applyRotation(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        val m = rotMatrix
        return Triple(
            m[0]*x + m[1]*y + m[2]*z,
            m[3]*x + m[4]*y + m[5]*z,
            m[6]*x + m[7]*y + m[8]*z
        )
    }

    /**
     * Macchina a stati per la calibrazione automatica dell'orientamento IMU.
     * Chiamata ad ogni tick del loop di log (2 Hz).
     *
     * Transizioni:
     *   WAITING_STILL → WAITING_MOVE : telefono fermo per 3s (in tasca/zaino)
     *   WAITING_MOVE  → CALIBRATING  : GPS speed > 8 km/h (moto partita)
     *   CALIBRATING   → DONE         : 4s di rettilineo stabile → matrice calcolata
     *
     * Se durante CALIBRATING la moto fa una curva o frena, il contatore
     * si azzera e ricomincia ad aspettare un nuovo tratto stabile.
     */
    private fun updateCalib(
        gx: Float, gy: Float, gz: Float,
        speedKmh: Float
    ) {
        val accelMag = sqrt(gx*gx + gy*gy + gz*gz)
        // Usa il giroscopio grezzo per la magnitudine — più preciso
        val gyroMag  = sqrt(rawGyroX*rawGyroX + rawGyroY*rawGyroY + rawGyroZ*rawGyroZ)

        when (calibPhase) {

            CalibState.IDLE -> {
                // Aspetta che la moto si muova almeno una volta —
                // questo garantisce che START è stato premuto (siamo qui)
                // E che la moto è partita (telefono è già in tasca/zaino)
                if (speedKmh >= CALIB_MOVE_SPEED_KMH) {
                    // La moto si è mossa — da ora aspettiamo che si fermi/stabilizzi
                    // per rilevare il telefono fermo in tasca
                    calibPhase = CalibState.WAITING_STILL
                    stillCounter = 0
                    _calibState.value = CalibState.WAITING_STILL
                }
            }

            CalibState.WAITING_STILL -> {
                // Telefono fermo: |acc| ≈ 1g (solo gravità), giroscopio silenzioso
                if (abs(accelMag - 1f) < CALIB_ACCEL_TOLERANCE && gyroMag < 0.08f) {
                    stillCounter++
                    if (stillCounter >= CALIB_STILL_FRAMES) {
                        calibPhase   = CalibState.WAITING_MOVE
                        stillCounter = 0
                        _calibState.value = CalibState.WAITING_MOVE
                    }
                } else {
                    stillCounter = 0
                }
            }

            CalibState.WAITING_MOVE -> {
                // Aspetta che la moto parta — se il telefono torna a muoversi
                // prima (es. viene ripreso in mano) torna a WAITING_STILL
                if (gyroMag > 0.3f && speedKmh < 3f) {
                    calibPhase   = CalibState.WAITING_STILL
                    stillCounter = 0
                    _calibState.value = CalibState.WAITING_STILL
                } else if (speedKmh >= CALIB_MOVE_SPEED_KMH) {
                    calibPhase    = CalibState.CALIBRATING
                    calibCounter  = 0
                    accumGX = 0f; accumGY = 0f; accumGZ = 0f
                    _calibState.value = CalibState.CALIBRATING
                }
            }

            CalibState.CALIBRATING -> {
                // Rettilineo stabile: velocità sufficiente, giroscopio quasi fermo
                // (nessuna curva, nessuna frenata brusca)
                val isStable = speedKmh >= CALIB_MIN_SPEED_KMH && gyroMag < CALIB_GYRO_THRESHOLD

                if (isStable) {
                    accumGX += gx; accumGY += gy; accumGZ += gz
                    calibCounter++

                    if (calibCounter >= CALIB_STABLE_FRAMES) {
                        computeRotationMatrix(
                            accumGX / calibCounter,
                            accumGY / calibCounter,
                            accumGZ / calibCounter
                        )
                        calibPhase = CalibState.DONE
                        _calibState.value = CalibState.DONE
                    }
                } else {
                    // Tratto non stabile — azzera e riprova
                    calibCounter = 0
                    accumGX = 0f; accumGY = 0f; accumGZ = 0f
                }
            }

            CalibState.DONE -> {
                // Matrice stabile per tutta la sessione.
                // Non ricalibra — se il telefono si sposta l'utente
                // può forzare una nuova calibrazione con resetCalib().
            }
        }
    }

    /**
     * Calcola la matrice di rotazione dal vettore gravità medio misurato.
     *
     * Il vettore gravità (gx, gy, gz) nel sistema di riferimento del telefono
     * punta verso il basso (-Z nel sistema della moto).
     * Usiamo questo per costruire una base ortonormale:
     *   down  = -gravità normalizzata
     *   fwd   = asse con minore componente di gravità (≈ direzione di marcia)
     *   right = cross(fwd, down)
     */
    private fun computeRotationMatrix(gx: Float, gy: Float, gz: Float) {
        val mag = sqrt(gx*gx + gy*gy + gz*gz).takeIf { it > 0.01f } ?: return

        // Asse "down" della moto = direzione della gravità normalizzata
        val downX = gx / mag
        val downY = gy / mag
        val downZ = gz / mag

        // Asse forward = quello del telefono più ortogonale alla gravità
        // (il meno allineato con down → è l'asse che punta avanti)
        val absX = abs(downX); val absY = abs(downY); val absZ = abs(downZ)
        val fwdX: Float; val fwdY: Float; val fwdZ: Float

        if (absX <= absY && absX <= absZ) {
            // X del telefono è più ortogonale alla gravità → uso X come forward
            val m = sqrt(downY*downY + downZ*downZ).takeIf { it > 0.01f } ?: 1f
            fwdX =  0f;      fwdY = -downZ/m; fwdZ = downY/m
        } else if (absY <= absX && absY <= absZ) {
            val m = sqrt(downX*downX + downZ*downZ).takeIf { it > 0.01f } ?: 1f
            fwdX =  downZ/m; fwdY =  0f;      fwdZ = -downX/m
        } else {
            val m = sqrt(downX*downX + downY*downY).takeIf { it > 0.01f } ?: 1f
            fwdX = -downY/m; fwdY =  downX/m; fwdZ =  0f
        }

        // Asse right = cross(fwd, down)  →  laterale destro della moto
        val rtX = fwdY*downZ - fwdZ*downY
        val rtY = fwdZ*downX - fwdX*downZ
        val rtZ = fwdX*downY - fwdY*downX

        // Matrice di rotazione: righe = [right, fwd, down] nel sistema moto
        // Moltiplica un vettore telefono per ottenere le componenti moto
        rotMatrix = floatArrayOf(
            rtX,   rtY,   rtZ,
            fwdX,  fwdY,  fwdZ,
            downX, downY, downZ
        )
    }

    // ── API pubblica calibrazione ─────────────────────────────────────────────

    /** Forza il reset della calibrazione — riparte da WAITING_STILL */
    fun resetCalib() {
        calibPhase   = CalibState.IDLE
        stillCounter = 0; calibCounter = 0
        accumGX = 0f; accumGY = 0f; accumGZ = 0f
        rotMatrix = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        _calibState.value = CalibState.IDLE
    }

    fun getCalibState(): CalibState = calibPhase

    // ── onCreate ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        isAlive = true
        AppLog.init(this)

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

        gSensor = MotionSensor(this) { gLateral, gLong, gVert, gyroX, gyroY, gyroZ ->
            // Valori con dead-band → log e UI
            lastGLateral = gLateral
            lastGLong    = gLong
            lastGVert    = gVert
            lastGyroX    = gyroX
            lastGyroY    = gyroY
            lastGyroZ    = gyroZ
            // Valori grezzi → calibrazione (gVert qui è già senza sottrazione 1g)
            // Usiamo i valori filtrati low-pass ma senza dead-band
            // Nota: MotionSensor ci passa gVert grezzo (include ~1g di gravità)
            rawGX    = gLateral  // gLateral ha solo dead-band, non sottrae gravità
            rawGY    = gLong     // idem
            rawGZ    = gVert     // gVert ora è grezzo (vedi MotionSensor fix)
            rawGyroX = gyroX
            rawGyroY = gyroY
            rawGyroZ = gyroZ
            // UI: usa il valore calibrato se disponibile
            val (calLat, _, _) = applyRotation(gLateral, gLong, gVert)
            _gLateral.value = if (calibPhase == CalibState.DONE) calLat else gLateral
        }
        gSensor.start()

        // ── Combine → _dashState ──────────────────────────────────────────────
        serviceScope.launch {
            combine(
                tpmsManager.antState,
                tpmsManager.postState,
                obdManager.obdState,
                obdManager.peaks
            ) { ant, post, obd, peaks ->
                DashState(tpmsAnt = ant, tpmsPost = post, obd = obd, peaks = peaks)
            }.collect { _dashState.value = it }
        }

        // ── Sync GPS state ────────────────────────────────────────────────────
        serviceScope.launch {
            gpsManager.gpsState.collect { _gpsState.value = it }
        }

        // ── Loop principale 2 Hz ──────────────────────────────────────────────
        // La calibrazione gira SEMPRE, anche prima che parta il log,
        // così quando l'utente preme REC la matrice è già pronta.
        serviceScope.launch {
            while (isActive) {
                delay(LOG_INTERVAL_MS)

                val currentGps   = _gpsState.value
                val currentState = _dashState.value
                val speedKmh     = currentState.obd.speedKmh

                if (sessionLogger.isLogging) {
                    // Calibrazione usa i valori GREZZI (senza dead-band)
                    // così il vettore gravità è completo e la mag ≈ 1g quando fermo
                    updateCalib(rawGX, rawGY, rawGZ, speedKmh)

                    // Applica rotazione se calibrazione completata
                    val (calLat, calLong, calVert) = if (calibPhase == CalibState.DONE)
                        applyRotation(lastGLateral, lastGLong, lastGVert)
                    else
                        Triple(lastGLateral, lastGLong, lastGVert)

                    val (calGyroX, calGyroY, calGyroZ) = if (calibPhase == CalibState.DONE)
                        applyRotation(lastGyroX, lastGyroY, lastGyroZ)
                    else
                        Triple(lastGyroX, lastGyroY, lastGyroZ)

                    sessionLogger.log(
                        state      = currentState,
                        gLateral   = calLat,
                        gLong      = calLong,
                        gVert      = calVert,
                        gyroX      = calGyroX,
                        gyroY      = calGyroY,
                        gyroZ      = calGyroZ,
                        gps        = currentGps,
                        calibDone  = calibPhase == CalibState.DONE
                    )
                }
            }
        }
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    fun startLogging() {
        // Reset calibrazione ad ogni nuova sessione —
        // riparte da WAITING_STILL al momento del tasto START
        resetCalib()
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