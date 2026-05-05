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
        private const val CALIB_SAMPLES = 60
    }

    private val sm        = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)

    private var rollOffset         = 0f
    private var currentRaw         = 0f
    private var filteredRoll       = 0f
    private var isCalibrating      = false
    private val calibSamples       = mutableListOf<Float>()
    private var onCalibrationDone: (() -> Unit)? = null

    private var lastUpdate = 0L

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
        filteredRoll  = 0f
    }

    fun calibrate() {
        rollOffset = currentRaw
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        // Limita aggiornamenti a ~60Hz
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 16) return
        lastUpdate = now

        // Ottieni rotation matrix dal rotation vector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // REMAP per telefono in VERTICALE (portrait)
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,   // asse laterale del telefono
            SensorManager.AXIS_Z,   // asse verticale
            rotationMatrix
        )

        // Ottieni angoli (radians → degrees)
        SensorManager.getOrientation(rotationMatrix, orientation)
        currentRaw = Math.toDegrees(orientation[2].toDouble()).toFloat()

        // Corregge il wrap 0–360 → -180/+180
        if (currentRaw > 180f) currentRaw -= 360f

        // Calibrazione (media di 60 campioni)
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

        // LOW-PASS FILTER — elimina jitter e oscillazioni
        val corrected = currentRaw - rollOffset
        filteredRoll = filteredRoll * 0.9f + corrected * 0.1f

        // INVERSIONE SEGNO → sinistra = negativo → barra sinistra
        val finalRoll = -filteredRoll

        // Callback verso la UI
        onLeanUpdate(finalRoll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
