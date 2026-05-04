package com.davidewp.duke390dash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.davidewp.duke390dash.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashViewModel by viewModels()

    // ── Service binding ───────────────────────────────────────────────────────
    private var dashService: DashForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val binderService = (binder as DashForegroundService.LocalBinder).getService()
            dashService = binderService
            serviceBound = true

            // --- SINCRONIZZAZIONE INTELLIGENTE ---
            if (binderService.isLogging()) {
                // Se il servizio sta già registrando, nascondi l'overlay START
                binding.overlayStart.visibility = android.view.View.GONE
                updateRecordingDot(true) // Accendi il pallino rosso REC sulla dashboard
            } else {
                // Se il servizio è in attesa, mostra l'overlay per iniziare
                binding.overlayStart.visibility = android.view.View.VISIBLE
                updateRecordingDot(false)
            }

            notifyTile()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            dashService = null
        }
    }

    private var sweepDone = false

    // ── Long press a 3 dita per aprire i settings ─────────────────────────────
    // Richiede almeno 3 dita tenute premute per LONG_PRESS_MS ms.
    // Un tocco accidentale con 1-2 dita (tasca, vibrazioni) non fa nulla.
    private companion object {
        const val LONG_PRESS_MS = 800L
        const val MIN_FINGERS   = 3
    }

    private val longPressHandler   = Handler(Looper.getMainLooper())
    private var longPressTriggered = false

    private val longPressRunnable = Runnable {
        if (!longPressTriggered) {
            longPressTriggered = true
            showSettingsDialog()
        }
    }

    // ── Permessi ──────────────────────────────────────────────────────────────
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Manager già avviati nel service — nessuna init aggiuntiva necessaria
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Intercetta back/gesture back — non uscire mai dall'app ────────────
        // Come home app, il back non deve fare nulla (o al massimo tornare
        // alla schermata principale se siamo in un sotto-schermo).
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Non fare nulla — siamo la home, non c'è "indietro"
                    // Se in futuro hai sotto-schermi, naviga lì invece di uscire
                }
            }
        )

        // ── Schermo sempre acceso, no lock, no standby ────────────────────────
        // FLAG_KEEP_SCREEN_ON non è deprecato — gli altri sì, sostituiti con API moderne
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // API moderne per show-when-locked e turn-screen-on (deprecano i vecchi FLAG_*)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        DashForegroundService.start(this)
        bindService(
            Intent(this, DashForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        // ── Fullscreen — le barre escono solo con swipe doppio (come prima) ───
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: il primo swipe mostra le barre
        // in modalità transitoria, il secondo le "sblocca" — comportamento identico
        // a quello originale dell'app.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
// Inserisci questo nel onCreate della tua MainActivity dopo setContentView
        binding.btnStartOverlay.setOnClickListener {
            if (serviceBound) { // <--- Usiamo il tuo "serviceBound"
                dashService?.startLogging()

                // Aggiorna il pallino rosso (REC) sulla UI
                updateRecordingDot(true)

                // Nasconde l'overlay per mostrare la dashboard
                binding.overlayStart.visibility = android.view.View.GONE

                // Notifica il Tile di sistema che stiamo registrando
                notifyTile()
            }
        }
        AppLog.add("MainActivity", "App avviata")
        initNa()

        requestPermissions.launch(arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS
        ))

        // ── Long press a 3 dita ───────────────────────────────────────────────
        // return true: consuma l'evento così il sistema non lo intercetta prima
        // e tutti i MotionEvent multitouch arrivano correttamente al listener.
        @SuppressLint("ClickableViewAccessibility")
        binding.root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= MIN_FINGERS) {
                        longPressTriggered = false
                        longPressHandler.removeCallbacks(longPressRunnable)
                        longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
            }
            true  // consuma l'evento — necessario per ricevere tutti gli eventi multitouch
        }

        // ── Collect dashState ─────────────────────────────────────────────────
        lifecycleScope.launch {
            viewModel.dashState.collect { state ->
                if (sweepDone) updateUI(state)
            }
        }

        // ── Collect gLateral ──────────────────────────────────────────────────
        lifecycleScope.launch {
            viewModel.gLateral.collect { gLateral ->
                runOnUiThread { updateGSensor(gLateral) }
            }
        }

        // ── Collect calibState → pallino calibrazione ─────────────────────────
        lifecycleScope.launch {
            DashForegroundService.calibStateFlow.collect { state ->
                runOnUiThread { updateCalibDot(state) }
            }
        }

        notifyTile()
    }

    override fun onResume() {
        super.onResume()
        notifyTile()
        hideSystemBars()
        // Aggiorna il pallino registrazione — potrebbe essere cambiato
        // tramite il tile di sistema mentre l'Activity era in pausa
        updateRecordingDot(dashService?.isLogging() == true)
    }

    override fun onPause() {
        super.onPause()
        // Ri-scheduliamo hideSystemBars per quando torniamo in primo piano —
        // il post delay dà tempo al sistema di completare la transizione
        window.decorView.postDelayed({ hideSystemBars() }, 300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !sweepDone) runSweep()
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            hide(WindowInsetsCompat.Type.statusBars())
            // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: le barre appaiono temporaneamente
            // con un swipe ma NON triggera il task switcher — corretto per una home app
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Mantieni il layout under notch/cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // ─── Status dots ──────────────────────────────────────────────────────────

    private fun updateCalibDot(state: DashForegroundService.CalibState) {
        val color = when (state) {
            DashForegroundService.CalibState.IDLE          -> 0xFF333333.toInt() // grigio — nessuna sessione
            DashForegroundService.CalibState.STATIC_WAIT   -> 0xFFEF9F27.toInt() // arancio — fase 1 in corso
            DashForegroundService.CalibState.STATIC_DONE   -> 0xFF00CC44.toInt() // verde — fase 1 ok
            DashForegroundService.CalibState.MOTION_CALIB  -> 0xFF4DA6FF.toInt() // azzurro — fase 2 in corso
        }
        binding.dotCalib.setTextColor(color)
    }

    fun updateRecordingDot(isRecording: Boolean) {
        binding.dotRecording.setTextColor(
            if (isRecording) 0xFFFF3300.toInt() else 0xFF333333.toInt()
        )
    }

    // ─── Init N/A ─────────────────────────────────────────────────────────────

    private fun initNa() {
        val na   = getString(R.string.na)
        val dash = getString(R.string.dash)

        // TPMS e OBD
        binding.valAntPress.text      = na;  binding.commentAntPress.text  = dash
        binding.valAntTemp.text       = na;  binding.commentAntTemp.text   = dash
        binding.valPostPress.text     = na;  binding.commentPostPress.text = dash
        binding.valPostTemp.text      = na;  binding.commentPostTemp.text  = dash
        binding.valTps.text           = na
        binding.valLoad.text          = na
        binding.valIat.text           = na
        binding.valAfr.text           = na;  binding.commentAfr.text       = dash
        binding.valConsume.text       = na
        binding.valOilTemp.text       = na;  binding.commentOilTemp.text   = dash
        binding.valSpeedMax.text      = na
        binding.valRpmMax.text        = na
        binding.valLoadMax.text       = na

        // NUOVI CAMPI LEAN ANGLE
        binding.valLeanAngle.text = "0°"
        binding.valLeanSide.visibility = android.view.View.INVISIBLE
        binding.valLeanSideRight.visibility = android.view.View.INVISIBLE

        // Reset barre (opzionale, utile per sicurezza)
        binding.barLeanLeft.progress = 0
        binding.barLeanRight.progress = 0
    }

    // ─── Sweep ────────────────────────────────────────────────────────────────

    private fun runSweep() {
        val bars = listOf(
            binding.barAntPress, binding.barAntTemp,
            binding.barPostPress, binding.barPostTemp,
            binding.barTps, binding.barLoad,
            binding.barIat, binding.barAfr,
            binding.barConsume, binding.barOilTemp,
            binding.barLeanLeft, binding.barLeanRight
        )
        val upAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = 600; interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> bars.forEach { it.progress = anim.animatedValue as Int } }
        }
        val downAnimator = ValueAnimator.ofInt(100, 0).apply {
            duration = 400; interpolator = AccelerateInterpolator()
            addUpdateListener { anim -> bars.forEach { it.progress = anim.animatedValue as Int } }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { sweepDone = true }
            })
        }
        upAnimator.start()
        Handler(Looper.getMainLooper()).postDelayed({ downAnimator.start() }, 650)
    }

    // ─── G Laterali ───────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun updateGSensor(gLateral: Float) {
        // 1. Calcolo Angolo (gLateral arriva corretto dal Service)
        val leanAngle = Math.toDegrees(Math.atan2(gLateral.toDouble(), 1.0)).toFloat()
        val absAngle = Math.abs(leanAngle)

        // Percentuale per le barre (scala 0-60 gradi)
        val percent = absAngle.toInt().coerceIn(0, 60)

        // 2. Colore dinamico (Verde -> Arancio -> Rosso)
        val color = when {
            absAngle < 20f -> 0xFF00CC44.toInt() // Verde
            absAngle < 40f -> 0xFFEF9F27.toInt() // Arancio
            else           -> 0xFFFF3300.toInt() // Rosso
        }

        // 3. Aggiornamento Testo e Colori (Usa i tuoi ID originali)
        binding.valLeanAngle.text = "${absAngle.toInt()}°"
        binding.valLeanAngle.setTextColor(color)



        // 4. Aggiornamento Barre (barLeanLeft e barLeanRight)
        // Se gLateral è positivo (+) -> Destra
        if (gLateral > 0) {
            binding.barLeanLeft.progress = 0
            binding.barLeanRight.progress = percent
        } else {
            binding.barLeanLeft.progress = percent
            binding.barLeanRight.progress = 0
        }

        // Applica il colore alle barre per renderle visibili
        setBarColor(binding.barLeanLeft, color)
        setBarColor(binding.barLeanRight, color)
    }

    // ─── UI principale ────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun updateUI(state: DashState) {
        val na   = getString(R.string.na)
        val dash = getString(R.string.dash)

        val antOk    = state.tpmsAnt.pressureBar  > 0f && !state.tpmsAnt.staleWarning
        val postOk   = state.tpmsPost.pressureBar > 0f && !state.tpmsPost.staleWarning
        val antSeen  = state.tpmsAnt.pressureBar  > 0f
        val postSeen = state.tpmsPost.pressureBar > 0f
        binding.spiaTpms.setTextColor(when {
            antOk && postOk     -> 0xFF00CC44.toInt()
            antSeen || postSeen -> 0xFFEF9F27.toInt()
            else                -> 0xFFFF3300.toInt()
        })
        binding.spiaObd.setTextColor(if (state.obd.connected) 0xFF00CC44.toInt() else 0xFFFF3300.toInt())

        if (state.obd.milOn) {
            binding.spiaMil.text = "MIL ${state.obd.dtcCount}"
            binding.spiaMil.setTextColor(0xFFFF3300.toInt())
            binding.spiaMil.setBackgroundColor(0x33FF3300)
        } else if (state.obd.dtcCount > 0) {
            binding.spiaMil.text = "DTC ${state.obd.dtcCount}"
            binding.spiaMil.setTextColor(0xFFEF9F27.toInt())
            binding.spiaMil.setBackgroundColor(0x22EF9F27)
        } else {
            binding.spiaMil.text = ""
            binding.spiaMil.setBackgroundColor(0x00000000)
        }

        if (state.tpmsAnt.pressureBar > 0f) {
            binding.barAntPress.progress = ((state.tpmsAnt.pressureBar / 4f * 100).toInt())
            binding.valAntPress.text = "%.1f bar".format(state.tpmsAnt.pressureBar)
            val antPressLabel = when {
                state.tpmsAnt.alarm              -> getString(R.string.press_alarm)
                state.tpmsAnt.pressureBar < 1.8f -> getString(R.string.press_low)
                state.tpmsAnt.pressureBar > 3.2f -> getString(R.string.press_high)
                else                             -> getString(R.string.press_ok)
            }
            binding.commentAntPress.text = if (state.tpmsAnt.staleWarning) "⚠ $antPressLabel" else antPressLabel
            setBarColor(binding.barAntPress, when {
                state.tpmsAnt.alarm              -> 0xFFFF3300.toInt()
                state.tpmsAnt.pressureBar < 1.8f -> 0xFFFF3300.toInt()
                state.tpmsAnt.pressureBar > 3.2f -> 0xFFEF9F27.toInt()
                else                             -> 0xFF00CC44.toInt()
            })
            binding.barAntTemp.progress = ((state.tpmsAnt.tempC / 100f * 100).toInt())
            binding.valAntTemp.text = "%.0f °C".format(state.tpmsAnt.tempC)
            binding.commentAntTemp.text = when {
                state.tpmsAnt.tempC < 30f -> getString(R.string.temp_cold)
                state.tpmsAnt.tempC < 60f -> getString(R.string.temp_warming)
                else                      -> getString(R.string.temp_hot)
            }
            setBarColor(binding.barAntTemp, when {
                state.tpmsAnt.tempC < 30f -> 0xFF4DA6FF.toInt()
                state.tpmsAnt.tempC < 60f -> 0xFFEF9F27.toInt()
                else                      -> 0xFFFF3300.toInt()
            })
        }

        if (state.tpmsPost.pressureBar > 0f) {
            binding.barPostPress.progress = ((state.tpmsPost.pressureBar / 4f * 100).toInt())
            binding.valPostPress.text = "%.1f bar".format(state.tpmsPost.pressureBar)
            val postPressLabel = when {
                state.tpmsPost.alarm              -> getString(R.string.press_alarm)
                state.tpmsPost.pressureBar < 1.8f -> getString(R.string.press_low)
                state.tpmsPost.pressureBar > 3.2f -> getString(R.string.press_high)
                else                              -> getString(R.string.press_ok)
            }
            binding.commentPostPress.text = if (state.tpmsPost.staleWarning) "⚠ $postPressLabel" else postPressLabel
            setBarColor(binding.barPostPress, when {
                state.tpmsPost.alarm              -> 0xFFFF3300.toInt()
                state.tpmsPost.pressureBar < 1.8f -> 0xFFFF3300.toInt()
                state.tpmsPost.pressureBar > 3.2f -> 0xFFEF9F27.toInt()
                else                              -> 0xFF00CC44.toInt()
            })
            binding.barPostTemp.progress = ((state.tpmsPost.tempC / 100f * 100).toInt())
            binding.valPostTemp.text = "%.0f °C".format(state.tpmsPost.tempC)
            binding.commentPostTemp.text = when {
                state.tpmsPost.tempC < 30f -> getString(R.string.temp_cold)
                state.tpmsPost.tempC < 60f -> getString(R.string.temp_warming)
                else                       -> getString(R.string.temp_hot)
            }
            setBarColor(binding.barPostTemp, when {
                state.tpmsPost.tempC < 30f -> 0xFF4DA6FF.toInt()
                state.tpmsPost.tempC < 60f -> 0xFFEF9F27.toInt()
                else                       -> 0xFFFF3300.toInt()
            })
        }

        if (state.obd.connected) {
            binding.barTps.progress = state.obd.tpsPercent.toInt()
            binding.valTps.text = "%.0f %%".format(state.obd.tpsPercent)
            setBarColor(binding.barTps, when {
                state.obd.tpsPercent < 30f -> 0xFF00CC44.toInt()
                state.obd.tpsPercent < 70f -> 0xFFEF9F27.toInt()
                else                       -> 0xFFFF3300.toInt()
            })
            binding.barLoad.progress = state.obd.engineLoadPercent.toInt()
            binding.valLoad.text = "%.0f %%".format(state.obd.engineLoadPercent)
            setBarColor(binding.barLoad, when {
                state.obd.engineLoadPercent < 50f -> 0xFF00CC44.toInt()
                state.obd.engineLoadPercent < 80f -> 0xFFEF9F27.toInt()
                else                              -> 0xFFFF3300.toInt()
            })
            val iatPct = (((state.obd.iatCelsius + 20f) / 100f) * 100).toInt().coerceIn(0, 100)
            binding.barIat.progress = iatPct
            binding.valIat.text = "%.0f °C".format(state.obd.iatCelsius)
            setBarColor(binding.barIat, when {
                state.obd.iatCelsius < 30f -> 0xFF4DA6FF.toInt()
                state.obd.iatCelsius < 50f -> 0xFFEF9F27.toInt()
                else                       -> 0xFFFF3300.toInt()
            })
            val ignPct = (state.obd.ignitionAdvance / 40f * 100f).toInt().coerceIn(0, 100)
            binding.barAfr.progress = ignPct
            binding.valAfr.text = "%.1f°".format(state.obd.ignitionAdvance)
            binding.commentAfr.text = when {
                state.obd.ignitionAdvance < 5f  -> getString(R.string.ign_low)
                state.obd.ignitionAdvance < 25f -> getString(R.string.ign_ok)
                else                            -> getString(R.string.ign_high)
            }
            setBarColor(binding.barAfr, 0xFF4DA6FF.toInt())
            val consumePct = ((state.obd.fuelConsumptionL100 / 20f) * 100).toInt().coerceIn(0, 100)
            binding.barConsume.progress = consumePct
            binding.valConsume.text = "%.1f l/100".format(state.obd.fuelConsumptionL100)
            setBarColor(binding.barConsume, when {
                state.obd.fuelConsumptionL100 < 5f  -> 0xFF00CC44.toInt()
                state.obd.fuelConsumptionL100 < 10f -> 0xFFEF9F27.toInt()
                else                                -> 0xFFFF3300.toInt()
            })
            val trimPct = ((state.obd.fuelTrimPct + 15f) / 30f * 100f).toInt().coerceIn(0, 100)
            binding.barOilTemp.progress = trimPct
            binding.valOilTemp.text = "%.1f %%".format(state.obd.fuelTrimPct)
            binding.commentOilTemp.text = when {
                state.obd.fuelTrimPct < -5f -> getString(R.string.trim_rich)
                state.obd.fuelTrimPct > 5f  -> getString(R.string.trim_lean)
                else                        -> getString(R.string.trim_ok)
            }
            setBarColor(binding.barOilTemp, when {
                state.obd.fuelTrimPct < -5f -> 0xFFEF9F27.toInt()
                state.obd.fuelTrimPct > 5f  -> 0xFFFF3300.toInt()
                else                        -> 0xFF00CC44.toInt()
            })
            binding.valSpeedMax.text = "%.0f km/h".format(state.peaks.maxSpeedKmh)
            binding.valRpmMax.text   = "%.0f".format(state.peaks.maxRpm)
            binding.valLoadMax.text  = "%.0f %%".format(state.peaks.maxEngineLoad)
        } else {
            listOf(binding.barTps, binding.barLoad, binding.barIat,
                binding.barAfr, binding.barConsume, binding.barOilTemp)
                .forEach { it.progress = 0; setBarColor(it, 0xFF333333.toInt()) }
            binding.valTps.text = na;     binding.valLoad.text    = na
            binding.valIat.text = na;     binding.valAfr.text     = na; binding.commentAfr.text    = dash
            binding.valConsume.text = na; binding.valOilTemp.text = na; binding.commentOilTemp.text = dash
            binding.valSpeedMax.text = na; binding.valRpmMax.text = na; binding.valLoadMax.text    = na
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDestroy() {
        longPressHandler.removeCallbacks(longPressRunnable)

        // Se il servizio è connesso, facciamo pulizia prima di chiudere
        if (serviceBound) {
            // Se NON stiamo registrando, fermiamo il servizio del tutto
            if (dashService?.isLogging() != true) {
                DashForegroundService.stop(this)
                DashTileService.updateTile(this, running = false, logging = false)
            } else {
                // Se invece stiamo registrando, aggiorniamo solo il Tile
                DashTileService.updateTile(this, running = true, logging = true)
            }

            unbindService(serviceConnection)
            serviceBound = false
        }

        AppLog.close()
        super.onDestroy()

        // Se l'app viene chiusa e non c'è una registrazione attiva,
        // uccidiamo il processo per pulire la RAM al 100%
        if (dashService?.isLogging() != true) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun notifyTile() {
        DashTileService.updateTile(this, running = true, logging = dashService?.isLogging() == true)
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun setBarColor(bar: ProgressBar, color: Int) {
        bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = android.app.AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val prefs       = getSharedPreferences(DashViewModel.PREFS_NAME, MODE_PRIVATE)
        val savedIdAnt  = prefs.getString(DashViewModel.PREF_ID_ANT,  "") ?: ""
        val savedIdPost = prefs.getString(DashViewModel.PREF_ID_POST, "") ?: ""
        val editIdAnt       = dialogView.findViewById<android.widget.EditText>(R.id.editIdAnt)
        val editIdPost      = dialogView.findViewById<android.widget.EditText>(R.id.editIdPost)
        val switchMoto      = dialogView.findViewById<android.widget.Switch>(R.id.switchMotoMode)
        val btnSetOffset    = dialogView.findViewById<android.widget.Button>(R.id.btnSetOffset)
        val txtOffset       = dialogView.findViewById<android.widget.TextView>(R.id.txtOffsetValue)
        val btnToggleLog    = dialogView.findViewById<android.widget.Button>(R.id.btnToggleLog)
        val txtLogStatus    = dialogView.findViewById<android.widget.TextView>(R.id.txtLogStatus)
        val spinnerLanguage = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerLanguage)

        val langOptions = listOf(
            getString(R.string.lang_system)  to "",
            getString(R.string.lang_italian) to "it",
            getString(R.string.lang_english) to "en"
        )
        val langAdapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_item, langOptions.map { it.first })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerLanguage.adapter = langAdapter
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else currentLocales[0]?.language ?: ""
        spinnerLanguage.setSelection(langOptions.indexOfFirst { it.second == currentTag }.takeIf { it >= 0 } ?: 0)

        editIdAnt.setText(savedIdAnt)
        editIdPost.setText(savedIdPost)
        switchMoto.isChecked = prefs.getBoolean(DashViewModel.PREF_MOTO_MODE, false)

        // Offset manuale rimosso — la calibrazione è ora automatica (fase 1 + fase 2)
        txtOffset.text = "calib: phase ${dashService?.getCalibPhase() ?: 0}"
        btnSetOffset.visibility = android.view.View.GONE
        txtOffset.visibility    = android.view.View.VISIBLE

        updateLogStatus(txtLogStatus, btnToggleLog)

        btnSetOffset.setOnClickListener {
            // Offset manuale rimosso — pulsante nascosto, questo handler non viene mai chiamato
        }

        btnToggleLog.setOnClickListener {
            val svc = dashService
            if (svc == null) {
                txtLogStatus.text = getString(R.string.log_inactive)
                return@setOnClickListener
            }
            if (svc.isLogging()) svc.stopLogging() else svc.startLogging()
            notifyTile()
            updateLogStatus(txtLogStatus, btnToggleLog)
            updateRecordingDot(svc.isLogging())
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnShowLog).setOnClickListener {
            showLogDialog()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnOpenAnalyzer).setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(this, AnalyzerActivity::class.java))
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnExit).setOnClickListener {
            dialog.dismiss()

            // Reset della memoria sensori prima di spegnere
            dashService?.resetCalib()

            // Stop fisico al servizio (notifica e sensori)
            DashForegroundService.stop(this)

            SplashActivity.splashShown = false
            finishAffinity()

            // Colpo di grazia: elimina il processo e svuota la RAM
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnSave).setOnClickListener {
            val idAnt  = editIdAnt.text.toString().trim().uppercase()
            val idPost = editIdPost.text.toString().trim().uppercase()
            viewModel.saveSettings(this, idAnt, idPost, dashService)
            prefs.edit().putBoolean(DashViewModel.PREF_MOTO_MODE, switchMoto.isChecked).apply()
            val selectedLangTag = langOptions[spinnerLanguage.selectedItemPosition].second
            val newLocales = if (selectedLangTag.isEmpty())
                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            else
                androidx.core.os.LocaleListCompat.forLanguageTags(selectedLangTag)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(newLocales)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showLogDialog() {
        val tv = android.widget.TextView(this).apply {
            text = AppLog.get().ifEmpty { "Nessun log disponibile" }
            setTextColor(0xFF00CC44.toInt())
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv); setBackgroundColor(0xFF0A0A0A.toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Log connessioni")
            .setView(scroll)
            .setPositiveButton("CHIUDI") { d, _ -> d.dismiss() }
            .setNegativeButton("PULISCI") { d, _ -> AppLog.clear(); d.dismiss() }
            .create().also { dlg ->
                dlg.show()
                scroll.post { scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
            }
    }

    private fun updateLogStatus(
        txtLogStatus: android.widget.TextView,
        btnToggleLog: android.widget.Button
    ) {
        if (dashService?.isLogging() == true) {
            txtLogStatus.text = getString(R.string.log_recording)
            txtLogStatus.setTextColor(0xFF00CC44.toInt())
            btnToggleLog.text = getString(R.string.btn_stop)
            btnToggleLog.setBackgroundColor(0xFFFF3300.toInt())
        } else {
            txtLogStatus.text = getString(R.string.log_inactive)
            txtLogStatus.setTextColor(0xFF555555.toInt())
            btnToggleLog.text = getString(R.string.btn_start)
            btnToggleLog.setBackgroundColor(0xFF00CC44.toInt())
        }
    }
}