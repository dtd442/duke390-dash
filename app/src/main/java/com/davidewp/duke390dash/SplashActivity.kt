package com.davidewp.duke390dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SplashActivity : AppCompatActivity() {

    companion object {
        var splashShown = false
    }

    private var powerReceiver: BroadcastReceiver? = null
    private var layout: android.widget.FrameLayout? = null
    private var mainLaunched = false

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

        layout = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val waitText = android.widget.TextView(this).apply {
            text = " "
            setTextColor(android.graphics.Color.parseColor("#FF6600"))
            textSize = 14f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = 40
            }
        }

        layout!!.addView(waitText)
        setContentView(layout)

        val prefs = getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val motoMode = prefs.getBoolean(DashViewModel.PREF_MOTO_MODE, false)

        if (motoMode) {
            if (isPowerConnected()) {
                if (splashShown) startMainActivity() else playIntro()
            } else {
                registerPowerReceiver()
            }
        } else {
            if (splashShown) startMainActivity() else playIntro()
        }
    }

    private fun isPowerConnected(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_AC
    }

    private fun registerPowerReceiver() {
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                    unregisterReceiver(this)
                    powerReceiver = null
                    if (splashShown) startMainActivity() else playIntro()
                }
            }
        }
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))

        // Long press 3 secondi per bypass modalità moto
        var pressHandler: Handler? = null
        var pressRunnable: Runnable? = null

        layout?.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressRunnable = Runnable {
                        powerReceiver?.let {
                            try { unregisterReceiver(it) } catch (_: Exception) { }
                        }
                        powerReceiver = null
                        if (splashShown) startMainActivity() else playIntro()
                    }
                    pressHandler = Handler(Looper.getMainLooper())
                    pressHandler?.postDelayed(pressRunnable!!, 3000)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    pressHandler?.removeCallbacks(pressRunnable!!)
                    pressHandler = null
                    pressRunnable = null
                }
            }
            true
        }
    }

    private fun playIntro() {
        splashShown = true
        layout?.removeAllViews()

        val videoView = VideoView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.gravity = android.view.Gravity.CENTER }
        }

        layout!!.addView(videoView)

        val videoUri = Uri.parse("android.resource://${packageName}/${R.raw.ktm_intro}")
        videoView.setVideoURI(videoUri)

        videoView.setOnPreparedListener { mediaPlayer ->
            val duration = mediaPlayer.duration
            // Preload: lancia MainActivity 800ms prima della fine del video
            val preloadAt = (duration - 800).toLong().coerceAtLeast(0)
            Handler(Looper.getMainLooper()).postDelayed({
                startMainActivityOnce()
            }, preloadAt)
        }

        // Fallback: se onPrepared non scatta o il video è cortissimo
        videoView.setOnCompletionListener { startMainActivityOnce() }
        videoView.setOnErrorListener { _, _, _ -> startMainActivityOnce(); true }
        videoView.start()
    }

    private fun startMainActivityOnce() {
        if (mainLaunched) return
        mainLaunched = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // startMainActivity rimane per i casi senza video (splashShown già true)
    private fun startMainActivity() = startMainActivityOnce()

    override fun onDestroy() {
        super.onDestroy()
        powerReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
    }
}