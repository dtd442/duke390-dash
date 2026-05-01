package com.davidewp.duke390dash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class GSensor(
    private val context: Context,
    private val onUpdate: (gLateral: Float) -> Unit
) : SensorEventListener {

    companion object {
        const val PREFS_KEY = "g_lateral_offset"
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val alpha = 0.05f
    private var filteredX = 0f
    private var offset = 0f

    init {
        loadOffset()
    }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Salva il valore attuale come offset (chiama con moto ferma e dritta) */
    fun setOffset() {
        offset = filteredX
        context.getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREFS_KEY, offset)
            .apply()
    }

    /** Restituisce l'offset corrente in G per mostrarlo nella UI */
    fun getOffsetG(): Float = offset / SensorManager.GRAVITY_EARTH

    private fun loadOffset() {
        offset = context.getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREFS_KEY, 0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        filteredX = alpha * event.values[0] + (1f - alpha) * filteredX
        val gLateral = (filteredX - offset) / SensorManager.GRAVITY_EARTH
        val gStable = if (Math.abs(gLateral) < 0.15f) 0f else gLateral
        onUpdate(gStable)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}