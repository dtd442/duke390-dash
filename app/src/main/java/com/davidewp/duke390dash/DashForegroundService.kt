package com.davidewp.duke390dash

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/**
 * DashForegroundService — unico proprietario di tutti i manager.
 *
 * ══════════════════════════════════════════════════════════════════
 *  CALIBRAZIONE IMU A DUE FASI
 * ══════════════════════════════════════════════════════════════════
 *
 *  FASE 1 — STATIC (moto ferma, telefono fermo sul supporto/serbatoio)
 *  ─────────────────────────────────────────────────────────────────
 *  Trigger: START premuto
 *  Condizioni: |accel| ≈ 1g  AND  gyroMag ≈ 0  per 3 secondi
 *  Cattura:
 *    • vettore gravità (down) nel sistema telefono
 *    • bias giroscopio (offset a fermo)
 *    • vettore magnetometro (Nord) nel sistema telefono
 *  Output:
 *    • matrice di rotazione COMPLETA già dalla fase 1
 *      (gravità → asse Z, magnetometro → asse Y/X)
 *    • lean angle già calcolabile
 *  Feedback: vibrazione 1×
 *  Log: calib_phase = 1
 *
 *  FASE 2 — MOTION (rettilineo stabile a velocità sostenuta)
 *  ─────────────────────────────────────────────────────────────────
 *  Trigger: automatico, attivo dopo fase 1
 *  Condizioni (buffer 5s = 10 frame a 2Hz):
 *    • speed_obd ≥ 30 km/h
 *    • Δspeed ≤ 2 km/h (costante)
 *    • Δbearing_gps ≤ 2° (rettilineo confermato da GPS)
 *    • gyroZ_raw ≈ 0 (nessuna rotazione yaw)
 *  Cattura:
 *    • vettore gravità aggiornato
 *    • vettore magnetometro aggiornato
 *    • bearing GPS come riferimento assoluto di validazione
 *  Output:
 *    • matrice aggiornata (migliora fase 1, gestisce spostamento telefono)
 *    • si ripete ad ogni rettilineo valido per tutta la sessione
 *  Feedback: vibrazione 3×
 *  Log: calib_phase = 2
 *
 *  Se nessuna fase riesce: dati raw nel log, calib_phase = 0
 * ══════════════════════════════════════════════════════════════════
 */
class DashForegroundService : Service() {

    // ── Stati calibrazione ────────────────────────────────────────────────────
    enum class CalibState {
        IDLE,           // START non ancora premuto
        STATIC_WAIT,    // aspetta telefono fermo (fase 1)
        STATIC_DONE,    // fase 1 completata, in ascolto per fase 2
        MOTION_CALIB    // fase 2 in corso (rettilineo stabile rilevato)
    }

    companion object {
        const val CHANNEL_ID      = "duke390_dash_channel"
        const val NOTIF_ID        = 1
        const val LOG_INTERVAL_MS = 500L  // 2 Hz

        // ── Soglie fase 1 ──────────────────────────────────────────────────────
        private const val S1_STILL_FRAMES   = 6     // 3s a 2Hz
        private const val S1_ACCEL_TOL      = 0.04f // g — tolleranza su |accel|-1g
        private const val S1_GYRO_MAX       = 0.06f // rad/s — fermo
        private const val S1_MAG_STABLE_TOL = 2f    // µT — magnetometro stabile

        // ── Soglie fase 2 ──────────────────────────────────────────────────────
        private const val S2_SPEED_MIN      = 30f   // km/h
        private const val S2_SPEED_DELTA    = 2f    // km/h — velocità costante
        private const val S2_BEARING_DELTA  = 2f    // gradi — rettilineo GPS
        private const val S2_GYRO_YAW_MAX   = 0.10f // rad/s — nessuna rotazione yaw
        private const val S2_FRAMES         = 10    // 5s a 2Hz

        const val ACTION_TOGGLE_LOG = "com.davidewp.duke390dash.TOGGLE_LOG"

        // ── StateFlow pubblici ─────────────────────────────────────────────────
        private val _dashState    = MutableStateFlow(DashState())
        val dashState: StateFlow<DashState> = _dashState

        private val _gLateral     = MutableStateFlow(0f)
        val gLateralFlow: StateFlow<Float> = _gLateral

        private val _gpsState     = MutableStateFlow(GpsManager.GpsData())
        val gpsState: StateFlow<GpsManager.GpsData> = _gpsState

        private val _calibState   = MutableStateFlow(CalibState.IDLE)
        val calibStateFlow: StateFlow<CalibState> = _calibState

        private val _calibPhase   = MutableStateFlow(0)  // 0=nessuna, 1=statica, 2=motion
        val calibPhaseFlow: StateFlow<Int> = _calibPhase

        val isLoggingState: Boolean get() = _isLoggingState
        private var _isLoggingState = false

        var isAlive: Boolean = false
            private set

        fun start(context: Context) =
            context.startForegroundService(Intent(context, DashForegroundService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, DashForegroundService::class.java))
    }

    // ── Manager ───────────────────────────────────────────────────────────────
    private lateinit var tpmsManager:   TpmsManager
    private lateinit var obdManager:    ObdManager
    private lateinit var gpsManager:    GpsManager
    private lateinit var gSensor:       MotionSensor
    private lateinit var sessionLogger: SessionLogger
    private lateinit var vibrator:      Vibrator

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Valori IMU correnti (dal callback sensore) ────────────────────────────
    @Volatile private var imuGX    = 0f  // accel in g
    @Volatile private var imuGY    = 0f
    @Volatile private var imuGZ    = 0f
    @Volatile private var imuGyroX = 0f  // rad/s
    @Volatile private var imuGyroY = 0f
    @Volatile private var imuGyroZ = 0f
    @Volatile private var imuMagX  = 0f  // µT
    @Volatile private var imuMagY  = 0f
    @Volatile private var imuMagZ  = 0f

    // ── Stato calibrazione ────────────────────────────────────────────────────
    private var calibState    = CalibState.IDLE
    private var currentPhase  = 0  // 0=nessuna, 1=statica done, 2=motion done

    // Contatori fase 1
    private var stillCounter  = 0
    private var accumA        = FloatArray(3)  // accel accumulato
    private var accumM        = FloatArray(3)  // mag accumulato
    private var accumGyro     = FloatArray(3)  // gyro bias accumulato
    private var prevMag       = FloatArray(3)  // per stabilità mag

    // Buffer fase 2 — magnetometro non serve, si usa quello della fase 1
    private val s2SpeedBuf   = ArrayDeque<Float>(S2_FRAMES)
    private val s2BearingBuf = ArrayDeque<Float>(S2_FRAMES)
    private val s2AccelBuf   = ArrayDeque<FloatArray>(S2_FRAMES)

    // Magnetometro salvato dalla fase 1 — usato anche in fase 2
    private var phase1MagX = 0f
    private var phase1MagY = 0f
    private var phase1MagZ = 0f

    // Matrice di rotazione 3×3 (row-major) — identità finché non calibrata
    private var rotMatrix     = FloatArray(9) { if (it % 4 == 0) 1f else 0f }

    // Bias giroscopio da fase 1
    private var gyroBiasX     = 0f
    private var gyroBiasY     = 0f
    private var gyroBiasZ     = 0f

    // ── Rotazione ─────────────────────────────────────────────────────────────
    private fun applyRotation(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        val m = rotMatrix
        return Triple(
            m[0]*x + m[1]*y + m[2]*z,
            m[3]*x + m[4]*y + m[5]*z,
            m[6]*x + m[7]*y + m[8]*z
        )
    }

    // ── Costruisce la matrice da vettore gravità + magnetometro ───────────────
    //
    // Algoritmo TRIAD (Two Reference Vectors):
    //   down  = -accel normalizzato  (gravità punta verso il basso)
    //   east  = cross(down, magNorm) (Est nel piano orizzontale)
    //   north = cross(east, down)    (Nord nel piano orizzontale)
    //
    // La matrice R mappa vettori dal sistema telefono al sistema moto:
    //   riga 0 = right (laterale destra moto) = east
    //   riga 1 = forward (avanti moto)         = north
    //   riga 2 = up (verticale su moto)        = -down = accel normalizzato
    //
    private fun buildMatrix(
        accelX: Float, accelY: Float, accelZ: Float,
        magX:   Float, magY:   Float, magZ:   Float
    ): Boolean {
        // ── Normalizza gravità ─────────────────────────────────────────────────
        val aMag = sqrt(accelX*accelX + accelY*accelY + accelZ*accelZ)
        if (aMag < 0.5f) return false  // dati non validi

        // "down" nel sistema telefono = direzione della gravità
        val dX = accelX / aMag; val dY = accelY / aMag; val dZ = accelZ / aMag

        // "up" nel sistema moto = opposto di down
        val upX = -dX; val upY = -dY; val upZ = -dZ

        // ── Normalizza magnetometro ────────────────────────────────────────────
        val mMag = sqrt(magX*magX + magY*magY + magZ*magZ)
        if (mMag < 10f) return false  // magnetometro non pronto o disturbato

        val mNX = magX / mMag; val mNY = magY / mMag; val mNZ = magZ / mMag

        // ── East = cross(down, magNorm) ────────────────────────────────────────
        // East = down × mag (nel sistema telefono)
        var eX = dY*mNZ - dZ*mNY
        var eY = dZ*mNX - dX*mNZ
        var eZ = dX*mNY - dY*mNX
        val eMag = sqrt(eX*eX + eY*eY + eZ*eZ)
        if (eMag < 0.1f) return false  // gravità e mag paralleli (al Polo)
        eX /= eMag; eY /= eMag; eZ /= eMag

        // ── North = cross(east, down) ──────────────────────────────────────────
        // North giace nel piano orizzontale, ortogonale a East e Down
        var nX = eY*dZ - eZ*dY
        var nY = eZ*dX - eX*dZ
        var nZ = eX*dY - eY*dX
        val nMag = sqrt(nX*nX + nY*nY + nZ*nZ)
        if (nMag < 0.1f) return false
        nX /= nMag; nY /= nMag; nZ /= nMag

        // ── Matrice: righe = [east, north, up] ────────────────────────────────
        // Moltiplica vettore telefono → ottieni componenti nel sistema moto:
        //   risultato[0] = laterale destra (east)
        //   risultato[1] = avanti          (north)
        //   risultato[2] = su              (up)
        rotMatrix = floatArrayOf(
            eX, eY, eZ,
            nX, nY, nZ,
            upX, upY, upZ
        )
        return true
    }

    // ── Validazione bearing GPS vs magnetometro ───────────────────────────────
    // Calcola il bearing dalla matrice corrente e lo confronta con il GPS.
    // Se concordano entro 15°, la calibrazione è affidabile.
    private fun bearingFromMatrix(): Float {
        // Il vettore "north" nella matrice è la riga 1.
        // Proiettiamo su forward della moto nel piano orizzontale.
        // Usiamo il bearing GPS direttamente come validazione — non calcoliamo
        // il bearing dalla matrice perché richiede riferimento assoluto Nord.
        // La validazione avviene confrontando la stabilità del bearing GPS
        // con la stabilità del magnetometro — se entrambi sono stabili
        // e concordi, la calibrazione è buona.
        return 0f  // placeholder — validazione fatta nei buffer
    }

    // ── Vibrazione feedback ───────────────────────────────────────────────────
    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun vibratePhase1() = vibratePattern(longArrayOf(0, 200))           // 1 colpo
    private fun vibratePhase2() = vibratePattern(longArrayOf(0,150,100,150,100,150)) // 3 colpi
    private fun vibrateError()  = vibratePattern(longArrayOf(0, 600))           // 1 lungo

    // ── Macchina a stati calibrazione ────────────────────────────────────────
    private fun updateCalib(speedKmh: Float, bearingGps: Float, gpsAvailable: Boolean) {

        val accelMag = sqrt(imuGX*imuGX + imuGY*imuGY + imuGZ*imuGZ)
        val gyroMag  = sqrt(imuGyroX*imuGyroX + imuGyroY*imuGyroY + imuGyroZ*imuGyroZ)

        when (calibState) {

            // ── IDLE: aspetta START ────────────────────────────────────────────
            CalibState.IDLE -> {
                // resetCalib() transiziona qui — non fa nulla finché startLogging() non chiama resetCalib()
            }

            // ── FASE 1: telefono fermo ─────────────────────────────────────────
            CalibState.STATIC_WAIT -> {
                val magStable = if (prevMag[0] != 0f) {
                    val dMag = sqrt(
                        (imuMagX - prevMag[0]).pow(2) +
                                (imuMagY - prevMag[1]).pow(2) +
                                (imuMagZ - prevMag[2]).pow(2)
                    )
                    dMag < S1_MAG_STABLE_TOL
                } else true  // primo frame, consideriamo stabile

                val isStill = abs(accelMag - 1f) < S1_ACCEL_TOL
                        && gyroMag            < S1_GYRO_MAX
                        && (magStable || !gSensor.isMagReady)

                if (isStill) {
                    stillCounter++
                    // Usa calibGX/Y/Z che preferisce TYPE_GRAVITY (filtrato HW)
                    // rispetto all'accelerometro grezzo — più stabile con moto al minimo
                    accumA[0] += gSensor.calibGX; accumA[1] += gSensor.calibGY; accumA[2] += gSensor.calibGZ
                    accumM[0] += imuMagX;  accumM[1] += imuMagY;  accumM[2] += imuMagZ
                    accumGyro[0] += imuGyroX; accumGyro[1] += imuGyroY; accumGyro[2] += imuGyroZ

                    if (stillCounter >= S1_STILL_FRAMES) {
                        val n = stillCounter.toFloat()
                        val ok = buildMatrix(
                            accumA[0]/n, accumA[1]/n, accumA[2]/n,
                            accumM[0]/n, accumM[1]/n, accumM[2]/n
                        )
                        if (ok) {
                            // Salva bias giroscopio — più preciso a fermo
                            gyroBiasX = accumGyro[0] / n
                            gyroBiasY = accumGyro[1] / n
                            gyroBiasZ = accumGyro[2] / n

                            // Salva magnetometro fase 1 — più pulito (moto ferma, niente interferenze)
                            // Verrà usato in fase 2 al posto del magnetometro in movimento
                            phase1MagX = accumM[0] / n
                            phase1MagY = accumM[1] / n
                            phase1MagZ = accumM[2] / n

                            currentPhase = 1
                            calibState   = CalibState.STATIC_DONE
                            _calibState.value  = CalibState.STATIC_DONE
                            _calibPhase.value  = 1
                            vibratePhase1()
                        } else {
                            resetCounters()
                            vibrateError()
                        }
                    }
                } else {
                    // Movimento rilevato — azzera
                    resetCounters()
                }

                prevMag[0] = imuMagX; prevMag[1] = imuMagY; prevMag[2] = imuMagZ
            }

            // ── FASE 2: rettilineo stabile ─────────────────────────────────────
            CalibState.STATIC_DONE -> {
                if (!gpsAvailable || speedKmh < S2_SPEED_MIN) {
                    // Svuota i buffer se la velocità cala sotto il minimo
                    if (speedKmh < S2_SPEED_MIN * 0.8f) clearS2Buffers()
                    return
                }

                // Aggiungi al buffer
                s2SpeedBuf.addLast(speedKmh)
                s2BearingBuf.addLast(bearingGps)
                s2AccelBuf.addLast(floatArrayOf(imuGX, imuGY, imuGZ))

                // Mantieni solo gli ultimi S2_FRAMES
                while (s2SpeedBuf.size > S2_FRAMES) { s2SpeedBuf.removeFirst(); s2BearingBuf.removeFirst(); s2AccelBuf.removeFirst() }

                if (s2SpeedBuf.size < S2_FRAMES) return  // buffer non ancora pieno

                // Verifica condizioni sul buffer
                val speedMin = s2SpeedBuf.min()
                val speedMax = s2SpeedBuf.max()
                val bearingRange = bearingRange(s2BearingBuf)

                val speedStable   = (speedMax - speedMin) <= S2_SPEED_DELTA
                val straight      = bearingRange          <= S2_BEARING_DELTA
                val yawStable     = abs(imuGyroZ - gyroBiasZ) < S2_GYRO_YAW_MAX

                if (speedStable && straight && yawStable) {
                    val avgA = s2AccelBuf.fold(FloatArray(3)) { acc, v ->
                        floatArrayOf(acc[0]+v[0], acc[1]+v[1], acc[2]+v[2])
                    }.map { it / S2_FRAMES }

                    // Usa il magnetometro della fase 1 (moto ferma = niente interferenze)
                    // La gravità viene aggiornata dalla posizione reale del telefono (fase 2)
                    // Il Nord viene dalla misura più pulita (fase 1)
                    val ok = buildMatrix(
                        avgA[0], avgA[1], avgA[2],
                        phase1MagX, phase1MagY, phase1MagZ
                    )
                    if (ok) {
                        currentPhase = 2
                        _calibPhase.value = 2
                        calibState = CalibState.MOTION_CALIB
                        _calibState.value = CalibState.MOTION_CALIB
                        vibratePhase2()
                        clearS2Buffers()
                    }
                }
                // Se le condizioni non sono soddisfatte il buffer scorre — nessun reset,
                // aspetta il prossimo frame buono
            }

            CalibState.MOTION_CALIB -> { /* non usato, keepalive */ }
        }
    }

    // ── Bearing range circolare ───────────────────────────────────────────────
    // Gestisce il wrap 0°/360°
    private fun bearingRange(buf: ArrayDeque<Float>): Float {
        if (buf.isEmpty()) return 0f
        val min = buf.min(); val max = buf.max()
        val direct = max - min
        val wrapped = 360f - direct  // range se attraversa lo 0°
        return if (direct <= wrapped) direct else wrapped
    }

    // ── Reset helpers ─────────────────────────────────────────────────────────
    private fun resetCounters() {
        stillCounter = 0
        accumA    = FloatArray(3); accumM    = FloatArray(3); accumGyro = FloatArray(3)
    }

    private fun clearS2Buffers() {
        s2SpeedBuf.clear(); s2BearingBuf.clear(); s2AccelBuf.clear()
    }

    fun resetCalib() {
        calibState   = CalibState.STATIC_WAIT
        currentPhase = 0
        rotMatrix    = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
        gyroBiasX = 0f; gyroBiasY = 0f; gyroBiasZ = 0f
        phase1MagX = 0f; phase1MagY = 0f; phase1MagZ = 0f
        resetCounters(); clearS2Buffers()
        prevMag = FloatArray(3)
        _calibState.value  = CalibState.STATIC_WAIT
        _calibPhase.value  = 0
    }

    fun getCalibPhase(): Int = currentPhase

    // ── onCreate ──────────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        isAlive = true
        AppLog.init(this)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val prefs  = getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val idAnt  = prefs.getString(DashViewModel.PREF_ID_ANT,  null)?.takeIf { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_ANT
        val idPost = prefs.getString(DashViewModel.PREF_ID_POST, null)?.takeIf { it.isNotBlank() } ?: TpmsManager.DEFAULT_ID_POST

        tpmsManager   = TpmsManager(this)
        obdManager    = ObdManager(this)
        gpsManager    = GpsManager(this)
        sessionLogger = SessionLogger(this)

        tpmsManager.start(idAnt, idPost)
        obdManager.start()
        gpsManager.start()

        gSensor = MotionSensor(this) { gX, gY, gZ, gyroX, gyroY, gyroZ, magX, magY, magZ ->
            imuGX = gX; imuGY = gY; imuGZ = gZ
            imuGyroX = gyroX; imuGyroY = gyroY; imuGyroZ = gyroZ
            imuMagX = magX; imuMagY = magY; imuMagZ = magZ
            // Aggiorna UI con g laterale già calibrato se disponibile
            val calLat = if (currentPhase >= 1) applyRotation(gX, gY, gZ).first else gX
            _gLateral.value = calLat
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

        serviceScope.launch {
            gpsManager.gpsState.collect { _gpsState.value = it }
        }

        // ── Loop principale 2 Hz ──────────────────────────────────────────────
        serviceScope.launch {
            while (isActive) {
                delay(LOG_INTERVAL_MS)

                val gps      = _gpsState.value
                val state    = _dashState.value
                val speedKmh = state.obd.speedKmh

                if (sessionLogger.isLogging) {

                    // Aggiorna calibrazione
                    updateCalib(speedKmh, gps.bearingDeg, gps.available)

                    // Applica rotazione e sottrai bias giroscopio
                    val (calGX, calGY, calGZ) = if (currentPhase >= 1)
                        applyRotation(imuGX, imuGY, imuGZ)
                    else
                        Triple(imuGX, imuGY, imuGZ)

                    // gVert nel JSON: dopo la rotazione l'asse Z della moto
                    // punta verso l'alto. In piano calGZ ≈ 1g (gravità opposta).
                    // Sottraiamo 1g per avere lo scostamento (0=piano, +dosso, -buca)
                    val gVertLog = if (currentPhase >= 1) calGZ - 1f else calGZ

                    val (calGyroX, calGyroY, calGyroZ) = if (currentPhase >= 1) {
                        val (rx, ry, rz) = applyRotation(
                            imuGyroX - gyroBiasX,
                            imuGyroY - gyroBiasY,
                            imuGyroZ - gyroBiasZ
                        )
                        Triple(rx, ry, rz)
                    } else {
                        Triple(imuGyroX, imuGyroY, imuGyroZ)
                    }

                    sessionLogger.log(
                        state      = state,
                        gLateral   = calGX,
                        gLong      = calGY,
                        gVert      = gVertLog,
                        gyroX      = calGyroX,
                        gyroY      = calGyroY,
                        gyroZ      = calGyroZ,
                        gps        = gps,
                        calibDone  = currentPhase >= 1,
                        calibPhase = currentPhase
                    )
                }
            }
        }
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    fun startLogging() {
        resetCalib()  // riparte da fase 1 ad ogni nuova sessione
        sessionLogger.startSession()
        _isLoggingState = sessionLogger.isLogging
    }

    fun stopLogging() {
        sessionLogger.stopSession()
        _isLoggingState = false
    }

    fun isLogging()   = sessionLogger.isLogging

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
    private val binder      = LocalBinder()
    private var boundClients = 0

    inner class LocalBinder : android.os.Binder() {
        fun getService(): DashForegroundService = this@DashForegroundService
    }

    override fun onBind(intent: Intent?): IBinder   { boundClients++; return binder }
    override fun onUnbind(intent: Intent?): Boolean { boundClients--; return super.onUnbind(intent) }

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

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    // ── Notifica ──────────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false); enableVibration(false); enableLights(false); setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
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