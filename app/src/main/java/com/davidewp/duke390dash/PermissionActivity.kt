package com.davidewp.duke390dash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Guida l'utente attraverso i permessi necessari, uno alla volta.
 * Appare solo se almeno un permesso manca — la SplashActivity la salta
 * se tutto è già concesso.
 *
 * Ordine: Notifications → Bluetooth → Location → Background Location → Battery
 */
class PermissionActivity : AppCompatActivity() {

    // ── Definizione permessi ─────────────────────────────────────────────────

    data class PermStep(
        val icon:        String,
        val title:       String,
        val description: String,
        val why:         String,
        val isGranted:   () -> Boolean,
        val request:     () -> Unit
    )

    private lateinit var steps: List<PermStep>
    private var currentStep = 0

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var root:        FrameLayout
    private lateinit var iconView:    TextView
    private lateinit var titleView:   TextView
    private lateinit var descView:    TextView
    private lateinit var whyView:     TextView
    private lateinit var grantBtn:    Button
    private lateinit var skipBtn:     TextView
    private lateinit var progressView:TextView
    private lateinit var statusDots:  LinearLayout

    // ── Permission launchers ─────────────────────────────────────────────────

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    private val btLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { advance() }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    // ── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildSteps()
        buildUI()

        // Salta i permessi già concessi
        while (currentStep < steps.size && steps[currentStep].isGranted()) currentStep++
        if (currentStep >= steps.size) { goToMain(); return }

        showStep(currentStep)
    }

    override fun onResume() {
        super.onResume()
        // Quando l'utente torna dalle impostazioni di sistema, ricontrolla
        if (::steps.isInitialized) advance()
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private fun buildSteps() {
        steps = buildList {

            // 1. Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermStep(
                    icon        = "🔔",
                    title       = "Notifications",
                    description = "Allow Duke 390 Dash to send notifications.",
                    why         = "The app runs as a foreground service to keep OBD and GPS active " +
                            "while the screen is off. Android requires a visible notification " +
                            "for foreground services — without this the service cannot start.",
                    isGranted   = {
                        ContextCompat.checkSelfPermission(
                            this@PermissionActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    },
                    request     = {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ))
            }

            // 2. Bluetooth
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(PermStep(
                    icon        = "📡",
                    title       = "Bluetooth",
                    description = "Allow Duke 390 Dash to scan and connect to Bluetooth devices.",
                    why         = "The app connects to your OBD adapter and TPMS sensors via " +
                            "Bluetooth Low Energy. Without this permission no sensor data " +
                            "will be available.",
                    isGranted   = {
                        ContextCompat.checkSelfPermission(
                            this@PermissionActivity,
                            Manifest.permission.BLUETOOTH_SCAN
                        ) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(
                                    this@PermissionActivity,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                    },
                    request     = {
                        btLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ))
                    }
                ))
            }

            // 3. Location (Fine)
            add(PermStep(
                icon        = "📍",
                title       = "Location",
                description = "Allow Duke 390 Dash to access your precise location.",
                why         = "GPS provides speed, position and track recording for the telemetry " +
                        "log. Location access is also required by Android to scan for " +
                        "Bluetooth devices.",
                isGranted   = {
                    ContextCompat.checkSelfPermission(
                        this@PermissionActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                },
                request     = {
                    locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            ))

            // 4. Background Location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(PermStep(
                    icon        = "🗺️",
                    title       = "Background Location",
                    description = "Allow Duke 390 Dash to access location at all times.",
                    why         = "When the screen is off the app continues to record your route. " +
                            "Without 'Allow all the time' the GPS is throttled to once every " +
                            "10 minutes while riding — making the track useless.\n\n" +
                            "On the next screen choose \"Allow all the time\".",
                    isGranted   = {
                        ContextCompat.checkSelfPermission(
                            this@PermissionActivity,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    },
                    request     = {
                        // Android apre direttamente le impostazioni di sistema
                        bgLocationLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    }
                ))
            }

            // 5. Battery optimization exemption
            add(PermStep(
                icon        = "🔋",
                title       = "Battery Optimization",
                description = "Exclude Duke 390 Dash from battery optimization.",
                why         = "Android can kill background apps to save battery. If the app is " +
                        "optimized, the OBD and GPS service may be interrupted mid-ride, " +
                        "losing your session data.\n\n" +
                        "On the next screen tap \"Don't optimize\".",
                isGranted   = {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(packageName)
                },
                request     = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    // onResume ricontrollerà quando l'utente torna
                }
            ))
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val bg = 0xFF0B0C10.toInt()
        val or = 0xFFFF5500.toInt()
        val wh = 0xFFFFFFFF.toInt()
        val di = 0xFF52556A.toInt()

        root = FrameLayout(this).apply { setBackgroundColor(bg) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(48, 0, 48, 0)
        }

        // Progress dots
        statusDots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }

        // Icon
        iconView = TextView(this).apply {
            textSize  = 56f
            gravity   = Gravity.CENTER
            setPadding(0, 32, 0, 8)
        }

        // Title
        titleView = TextView(this).apply {
            textSize  = 22f
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(or)
            gravity   = Gravity.CENTER
            letterSpacing = 0.05f
            setPadding(0, 0, 0, 16)
        }

        // Description
        descView = TextView(this).apply {
            textSize  = 15f
            setTextColor(wh)
            gravity   = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, 24)
        }

        // Why card
        val whyCard = FrameLayout(this).apply {
            setBackgroundColor(0xFF161820.toInt())
            setPadding(24, 20, 24, 20)
        }
        whyView = TextView(this).apply {
            textSize  = 12f
            setTextColor(di)
            setLineSpacing(0f, 1.5f)
        }
        whyCard.addView(whyView)

        // Grant button
        grantBtn = Button(this).apply {
            textSize     = 13f
            letterSpacing = 0.1f
            setTextColor(wh)
            setBackgroundColor(or)
            setPadding(48, 0, 48, 0)
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 130
        ).apply { topMargin = 32 }

        // Skip link
        skipBtn = TextView(this).apply {
            text      = "Skip for now"
            textSize  = 11f
            setTextColor(di)
            gravity   = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        // Progress text
        progressView = TextView(this).apply {
            textSize  = 10f
            setTextColor(di)
            gravity   = Gravity.CENTER
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 32)
        }

        card.addView(progressView)
        card.addView(statusDots)
        card.addView(iconView)
        card.addView(titleView)
        card.addView(descView)
        card.addView(whyCard)
        card.addView(grantBtn, btnParams)
        card.addView(skipBtn)

        // Center card vertically
        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL }

        root.addView(card, cardParams)
        setContentView(root)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) { goToMain(); return }
        val step = steps[index]

        iconView.text  = step.icon
        titleView.text = step.title
        descView.text  = step.description
        whyView.text   = step.why

        val total   = steps.size
        val current = index + 1
        progressView.text = "STEP $current OF $total"

        // Dots
        statusDots.removeAllViews()
        for (i in steps.indices) {
            val dot = TextView(this).apply {
                text    = "●"
                textSize = 10f
                setTextColor(
                    if (i < index)       0xFF00D47A.toInt()   // done = green
                    else if (i == index) 0xFFFF5500.toInt()   // current = orange
                    else                 0xFF2A2D3A.toInt()    // pending = dark
                )
                setPadding(6, 0, 6, 0)
            }
            statusDots.addView(dot)
        }

        grantBtn.text = "Grant ${step.title} Permission"
        grantBtn.setOnClickListener { step.request() }

        // Skip: Battery e Background Location sono opzionali, gli altri no
        val skippable = step.title in listOf("Battery Optimization", "Background Location")
        skipBtn.visibility = if (skippable) android.view.View.VISIBLE else android.view.View.GONE
        skipBtn.setOnClickListener {
            currentStep++
            advance()
        }
    }

    // ── Avanzamento ──────────────────────────────────────────────────────────

    private fun advance() {
        // Salta tutti i permessi già concessi a partire dall'attuale
        while (currentStep < steps.size && steps[currentStep].isGranted()) currentStep++
        if (currentStep >= steps.size) goToMain()
        else showStep(currentStep)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}