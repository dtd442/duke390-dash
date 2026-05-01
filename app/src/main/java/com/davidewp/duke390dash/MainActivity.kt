package com.davidewp.duke390dash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
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
    private lateinit var gSensor: GSensor
    private lateinit var gpsManager: GpsManager
    private lateinit var sessionLogger: SessionLogger
    private var sweepDone = false
    private var lastGLateral = 0f

    // Riceve il comando toggle dal tile del pannello rapido
    private val tileReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == DashTileService.ACTION_TOGGLE_LOG) {
                if (sessionLogger.isLogging) sessionLogger.stopSession()
                else sessionLogger.startSession()
                notifyTile()
            }
        }
    }



    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // BLE e GPS sono obbligatori — senza questi l'app non funziona
        val bleOk = permissions[android.Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[android.Manifest.permission.BLUETOOTH_CONNECT] == true
        val gpsOk = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        // POST_NOTIFICATIONS è opzionale — se negato la notifica non appare
        // ma BLE e telemetria funzionano comunque
        if (bleOk && gpsOk) {
            AppLog.init(this)
            viewModel.init(this)
            gpsManager.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        DashForegroundService.start(this)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppLog.add("MainActivity", "App avviata")
        initNa()

        gSensor = GSensor(this) { gLateral ->
            lastGLateral = gLateral
            runOnUiThread { updateGSensor(gLateral) }
        }
        gSensor.start()

        sessionLogger = SessionLogger(this)

        // GPS — opzionale, avviato nel callback permessi insieme al ViewModel
        gpsManager = GpsManager(this)

        requestPermissions.launch(arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS
        ))

        binding.root.setOnLongClickListener {
            showSettingsDialog()
            true
        }

        lifecycleScope.launch {
            viewModel.dashState.collect { state ->
                sessionLogger.log(state, lastGLateral, gpsManager.gpsState.value)
                if (sweepDone) updateUI(state)
            }
        }

        // Registra receiver per comandi dal tile pannello rapido
        registerReceiver(
            tileReceiver,
            android.content.IntentFilter(DashTileService.ACTION_TOGGLE_LOG),
            RECEIVER_NOT_EXPORTED
        )
        // Notifica tile che l'app è attiva
        notifyTile()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !sweepDone) runSweep()
    }

    // ─── Animatori fluidi ─────────────────────────────────────────────────────

    // ─── Init N/A ─────────────────────────────────────────────────────────────

    private fun initNa() {
        val na   = getString(R.string.na)
        val dash = getString(R.string.dash)
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
        binding.valG.text             = "0.00 G"
        binding.valGDir.text          = "•"
    }

    // ─── Sweep ────────────────────────────────────────────────────────────────

    private fun runSweep() {
        val bars = listOf(
            binding.barAntPress, binding.barAntTemp,
            binding.barPostPress, binding.barPostTemp,
            binding.barTps, binding.barLoad,
            binding.barIat, binding.barAfr,
            binding.barConsume, binding.barOilTemp,
            binding.barGLeft, binding.barGRight
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
        val absG    = Math.abs(gLateral)
        val percent = (absG / 1.5f * 100f).toInt().coerceIn(0, 100)
        val color = when {
            absG < 0.3f -> 0xFF00CC44.toInt()
            absG < 0.7f -> 0xFFEF9F27.toInt()
            else        -> 0xFFFF3300.toInt()
        }
        val arrow = when {
            gLateral > 0.1f  -> "→"
            gLateral < -0.1f -> "←"
            else             -> "•"
        }
        binding.valG.text = "%.2f G".format(absG)
        binding.valGDir.text = arrow
        binding.valG.setTextColor(color)
        binding.valGDir.setTextColor(color)
        if (gLateral < 0) { binding.barGLeft.progress = percent; binding.barGRight.progress = 0 }
        else              { binding.barGLeft.progress = 0;        binding.barGRight.progress = percent }
        setBarColor(binding.barGLeft, color)
        setBarColor(binding.barGRight, color)
    }

    // ─── UI principale ────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun updateUI(state: DashState) {
        val na   = getString(R.string.na)
        val dash = getString(R.string.dash)

        // Spie
        // Verde  = entrambi ricevuti e non stale
        // Arancione = almeno uno stale (non trasmette da troppo) o solo uno ricevuto finora
        // Rosso  = nessuno ha mai trasmesso (N/A)
        val antOk   = state.tpmsAnt.pressureBar  > 0f && !state.tpmsAnt.staleWarning
        val postOk  = state.tpmsPost.pressureBar > 0f && !state.tpmsPost.staleWarning
        val antSeen = state.tpmsAnt.pressureBar  > 0f
        val postSeen= state.tpmsPost.pressureBar > 0f
        binding.spiaTpms.setTextColor(when {
            antOk && postOk           -> 0xFF00CC44.toInt()  // entrambi ok
            antSeen || postSeen       -> 0xFFEF9F27.toInt()  // almeno uno visto ma stale o mancante
            else                      -> 0xFFFF3300.toInt()  // nessuno mai visto
        })
        binding.spiaObd.setTextColor(if (state.obd.connected) 0xFF00CC44.toInt() else 0xFFFF3300.toInt())

        // MIL / DTC
        if (state.obd.milOn) {
            binding.spiaMil.text    = "MIL ${state.obd.dtcCount}"
            binding.spiaMil.setTextColor(0xFFFF3300.toInt())
            binding.spiaMil.setBackgroundColor(0x33FF3300)
        } else if (state.obd.dtcCount > 0) {
            // DTC presenti ma MIL spenta — segnala in arancione
            binding.spiaMil.text    = "DTC ${state.obd.dtcCount}"
            binding.spiaMil.setTextColor(0xFFEF9F27.toInt())
            binding.spiaMil.setBackgroundColor(0x22EF9F27)
        } else {
            binding.spiaMil.text    = ""
            binding.spiaMil.setBackgroundColor(0x00000000)
        }

        // ── TPMS Anteriore
        // Se mai ricevuto almeno un valore (pressureBar > 0), mostra sempre l'ultimo.
        // Il colore diventa grigio quando il segnale cade — i valori restano visibili.
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

        // ── TPMS Posteriore
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

        // ── OBD2
        if (state.obd.connected) {
            // TPS
            binding.barTps.progress = state.obd.tpsPercent.toInt()
            binding.valTps.text = "%.0f %%".format(state.obd.tpsPercent)
            setBarColor(binding.barTps, when {
                state.obd.tpsPercent < 30f -> 0xFF00CC44.toInt()
                state.obd.tpsPercent < 70f -> 0xFFEF9F27.toInt()
                else                       -> 0xFFFF3300.toInt()
            })
            // Carico
            binding.barLoad.progress = state.obd.engineLoadPercent.toInt()
            binding.valLoad.text = "%.0f %%".format(state.obd.engineLoadPercent)
            setBarColor(binding.barLoad, when {
                state.obd.engineLoadPercent < 50f -> 0xFF00CC44.toInt()
                state.obd.engineLoadPercent < 80f -> 0xFFEF9F27.toInt()
                else                              -> 0xFFFF3300.toInt()
            })
            // IAT
            val iatPct = (((state.obd.iatCelsius + 20f) / 100f) * 100).toInt().coerceIn(0, 100)
            binding.barIat.progress = iatPct
            binding.valIat.text = "%.0f °C".format(state.obd.iatCelsius)
            setBarColor(binding.barIat, when {
                state.obd.iatCelsius < 30f -> 0xFF4DA6FF.toInt()
                state.obd.iatCelsius < 50f -> 0xFFEF9F27.toInt()
                else                       -> 0xFFFF3300.toInt()
            })
            // Ignition advance
            val ignPct = (state.obd.ignitionAdvance / 40f * 100f).toInt().coerceIn(0, 100)
            binding.barAfr.progress = ignPct
            binding.valAfr.text = "%.1f°".format(state.obd.ignitionAdvance)
            binding.commentAfr.text = when {
                state.obd.ignitionAdvance < 5f  -> getString(R.string.ign_low)
                state.obd.ignitionAdvance < 25f -> getString(R.string.ign_ok)
                else                            -> getString(R.string.ign_high)
            }
            setBarColor(binding.barAfr, 0xFF4DA6FF.toInt())
            // Consumo
            val consumePct = ((state.obd.fuelConsumptionL100 / 20f) * 100).toInt().coerceIn(0, 100)
            binding.barConsume.progress = consumePct
            binding.valConsume.text = "%.1f l/100".format(state.obd.fuelConsumptionL100)
            setBarColor(binding.barConsume, when {
                state.obd.fuelConsumptionL100 < 5f  -> 0xFF00CC44.toInt()
                state.obd.fuelConsumptionL100 < 10f -> 0xFFEF9F27.toInt()
                else                                -> 0xFFFF3300.toInt()
            })
            // Fuel trim
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
            // Picchi
            binding.valSpeedMax.text = "%.0f km/h".format(state.peaks.maxSpeedKmh)
            binding.valRpmMax.text = "%.0f".format(state.peaks.maxRpm)
            binding.valLoadMax.text = "%.0f %%".format(state.peaks.maxEngineLoad)
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

    override fun onResume()  { super.onResume();  gSensor.start(); notifyTile() }
    override fun onPause()   { super.onPause();   gSensor.stop()  }
    override fun onDestroy() {
        super.onDestroy()
        gSensor.stop()
        sessionLogger.stopSession()
        AppLog.close()
        try { unregisterReceiver(tileReceiver) } catch (_: Exception) {}
        DashTileService.updateTile(this, running = false, logging = false)
        DashForegroundService.stop(this)
    }

    // Aggiorna il tile con lo stato corrente
    private fun notifyTile() {
        DashTileService.updateTile(this, running = true, logging = sessionLogger.isLogging)
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
        val savedIdAnt  = prefs.getString(DashViewModel.PREF_ID_ANT, "") ?: ""
        val savedIdPost = prefs.getString(DashViewModel.PREF_ID_POST, "") ?: ""
        val editIdAnt       = dialogView.findViewById<android.widget.EditText>(R.id.editIdAnt)
        val editIdPost      = dialogView.findViewById<android.widget.EditText>(R.id.editIdPost)
        val switchSim       = dialogView.findViewById<android.widget.Switch>(R.id.switchSimulation)
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
        switchSim.isChecked  = viewModel.simulationManager.isRunning
        switchMoto.isChecked = prefs.getBoolean(DashViewModel.PREF_MOTO_MODE, false)

        updateLogStatus(txtLogStatus, btnToggleLog)

        txtOffset.text = "offset: ${"%.2f".format(gSensor.getOffsetG())} G"
        btnSetOffset.setOnClickListener {
            gSensor.setOffset()
            txtOffset.text = "offset: ${"%.2f".format(gSensor.getOffsetG())} G"
            btnSetOffset.text = getString(R.string.btn_offset_saved)
            btnSetOffset.setBackgroundColor(0xFF00CC44.toInt())
        }

        btnToggleLog.setOnClickListener {
            if (sessionLogger.isLogging) sessionLogger.stopSession() else sessionLogger.startSession()
            notifyTile()
            updateLogStatus(txtLogStatus, btnToggleLog)
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnShowLog).setOnClickListener {
            showLogDialog()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnOpenAnalyzer).setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(this, AnalyzerActivity::class.java))
        }

        // BLE — nessuna selezione device necessaria, connessione automatica

        dialogView.findViewById<android.widget.Button>(R.id.btnExit).setOnClickListener {
            dialog.dismiss(); DashForegroundService.stop(this)
            SplashActivity.splashShown = false; finishAffinity()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnSave).setOnClickListener {
            val idAnt  = editIdAnt.text.toString().trim().uppercase()
            val idPost = editIdPost.text.toString().trim().uppercase()
            viewModel.saveSettings(this, idAnt, idPost)
            prefs.edit().putBoolean(DashViewModel.PREF_MOTO_MODE, switchMoto.isChecked).apply()
            if (switchSim.isChecked) viewModel.startSimulation() else viewModel.stopSimulation()
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
            addView(tv)
            setBackgroundColor(0xFF0A0A0A.toInt())
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Log connessioni")
            .setView(scroll)
            .setPositiveButton("CHIUDI") { d, _ -> d.dismiss() }
            .setNegativeButton("PULISCI") { d, _ -> AppLog.clear(); d.dismiss() }
            .create()
        dialog.show()
        // Scrolla in fondo automaticamente
        scroll.post { scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }

    private fun updateLogStatus(
        txtLogStatus: android.widget.TextView,
        btnToggleLog: android.widget.Button
    ) {
        if (sessionLogger.isLogging) {
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