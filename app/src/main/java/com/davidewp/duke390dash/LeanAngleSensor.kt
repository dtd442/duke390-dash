package com.davidewp.duke390dash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class LeanAngleSensor(
    context: Context,
    private val onLeanUpdate: (rollDeg: Float) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acc = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val R = FloatArray(16)
    private val I = FloatArray(16)
    private val outR = FloatArray(16)
    private val LOC = FloatArray(3)
    private val MAG = floatArrayOf(1f, 1f, 1f)

    private var filtered = 0f

    // 👉 offset per la calibrazione
    private var offset = 0f

    fun start() {
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    // 👉 funzione richiesta dal tuo MainActivity
    fun calibrate() {
        offset = filtered   // salva l’angolo attuale come zero
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(R, event.values)

        // Rimappatura per PORTRAIT
        SensorManager.remapCoordinateSystem(
            R,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            outR
        )

        SensorManager.getOrientation(outR, LOC)

        // SOLO PITCH (piega SX/DX)
        var pitch = Math.toDegrees(LOC[1].toDouble()).toFloat()

        if (pitch > 180f) pitch -= 360f

        // Applica offset di calibrazione
        pitch -= offset

        // Smoothing
        filtered = filtered * 0.85f + pitch * 0.15f

        onLeanUpdate(filtered)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
