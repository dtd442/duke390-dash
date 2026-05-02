package com.davidewp.duke390dash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Legge accelerometro (G laterale) + giroscopio (tutti e 3 gli assi).
 *
 * Assi Android (telefono verticale, schermo verso di te):
 *   X → destra/sinistra  (laterale sul manubrio)
 *   Y → su/giù           (beccheggio: accelerazione/frenata)
 *   Z → verso/da te      (imbardata: rotazione attorno asse verticale)
 *
 * Accelerometro — asse X con low-pass alpha=0.05 + dead-band ±0.15g
 *
 * Giroscopio — tutti e 3 gli assi in rad/s, low-pass alpha=0.10
 *   gyroX: roll  (rotolamento — utile per lean angle sul manubrio)
 *   gyroY: pitch (beccheggio — accelerazione/frenata)
 *   gyroZ: yaw   (imbardata — cambio direzione)
 *   Dead-band ±0.02 rad/s per stabilità a fermo.
 *
 * Il telefono può essere in tasca o sul manubrio — si salvano tutti gli assi
 * grezzi nel JSON così in post-processing si può scegliere quale usare
 * in base all'orientamento.
 */
class GSensor(
    private val context: Context,
    private val onUpdate: (gLateral: Float, gyroX: Float, gyroY: Float, gyroZ: Float) -> Unit
) : SensorEventListener {

    companion object {
        const val PREFS_KEY_OFFSET = "g_lateral_offset"

        private const val ACCEL_ALPHA    = 0.05f
        private const val GYRO_ALPHA     = 0.10f
        private const val ACCEL_DEADBAND = 0.15f  // g
        private const val GYRO_DEADBAND  = 0.02f  // rad/s
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Accelerometro
    private var filteredX = 0f
    private var offset    = 0f

    // Giroscopio — tutti e 3 gli assi filtrati
    private var filteredGyroX = 0f
    private var filteredGyroY = 0f
    private var filteredGyroZ = 0f

    init { loadOffset() }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Salva il valore attuale come offset di calibrazione. */
    fun setOffset() {
        offset = filteredX
        context.getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREFS_KEY_OFFSET, offset)
            .apply()
    }

    fun getOffsetG(): Float = offset / SensorManager.GRAVITY_EARTH

    private fun loadOffset() {
        offset = context.getSharedPreferences(DashViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREFS_KEY_OFFSET, 0f)
    }

    // ── Calcola gLateral dal filteredX corrente ───────────────────────────────
    private fun currentGLateral(): Float {
        val g = (filteredX - offset) / SensorManager.GRAVITY_EARTH
        return if (abs(g) < ACCEL_DEADBAND) 0f else g
    }

    // ── Dead-band giroscopio ──────────────────────────────────────────────────
    private fun applyGyroBand(v: Float) = if (abs(v) < GYRO_DEADBAND) 0f else v

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                filteredX = ACCEL_ALPHA * event.values[0] + (1f - ACCEL_ALPHA) * filteredX
                onUpdate(
                    currentGLateral(),
                    applyGyroBand(filteredGyroX),
                    applyGyroBand(filteredGyroY),
                    applyGyroBand(filteredGyroZ)
                )
            }

            Sensor.TYPE_GYROSCOPE -> {
                filteredGyroX = GYRO_ALPHA * event.values[0] + (1f - GYRO_ALPHA) * filteredGyroX
                filteredGyroY = GYRO_ALPHA * event.values[1] + (1f - GYRO_ALPHA) * filteredGyroY
                filteredGyroZ = GYRO_ALPHA * event.values[2] + (1f - GYRO_ALPHA) * filteredGyroZ
                onUpdate(
                    currentGLateral(),
                    applyGyroBand(filteredGyroX),
                    applyGyroBand(filteredGyroY),
                    applyGyroBand(filteredGyroZ)
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}