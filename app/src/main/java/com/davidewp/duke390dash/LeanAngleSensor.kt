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

    companion object {
        private const val CALIB_SAMPLES = 60  // ~1s a ~60Hz
    }

    private val sm        = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)

    private var rollOffset         = 0f
    private var currentRaw         = 0f
    private var isCalibrating      = false
    private val calibSamples       = mutableListOf<Float>()
    private var onCalibrationDone: (() -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        rotSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        rollOffset    = 0f
        isCalibrating = false
        calibSamples.clear()
    }


    fun calibrate() {
        rollOffset = currentRaw  // semplice, istantaneo
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Telefono in portrait, schermo verso il guidatore:
        // nessun remap necessario — orientation[2] è già il roll laterale
        SensorManager.getOrientation(rotationMatrix, orientation)

        currentRaw = Math.toDegrees(orientation[2].toDouble()).toFloat()

        if (isCalibrating) {
            calibSamples.add(currentRaw)
            if (calibSamples.size >= CALIB_SAMPLES) {
                rollOffset    = calibSamples.average().toFloat()
                isCalibrating = false
                calibSamples.clear()
                onCalibrationDone?.invoke()
                onCalibrationDone = null
            }
            return
        }

        onLeanUpdate(currentRaw - rollOffset)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}