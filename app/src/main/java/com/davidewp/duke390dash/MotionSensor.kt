package com.davidewp.duke390dash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs  // still used for gyro dead-band

/**
 * Legge accelerometro (tutti e 3 gli assi) + giroscopio (tutti e 3 gli assi).
 *
 * Assi Android (telefono verticale, schermo verso di te):
 *   X → destra/sinistra  (laterale)
 *   Y → su/giù           (longitudinale: accelerazione/frenata)
 *   Z → verso/da te      (verticale: dossi, buche)
 *
 * Il callback espone:
 *   gLateral  = X normalizzato in g, offset-corrected, dead-band
 *   gLong     = Y normalizzato in g, dead-band
 *   gVert     = Z normalizzato in g GREZZO (non sottratto 1g) — usato per calibrazione
 *               Il viewer/service sottrae 1g se serve lo scostamento
 *   gyroX/Y/Z = rad/s filtrati, dead-band
 *
 * NOTA: gVert viene loggato come valore assoluto (include 1g di gravità).
 * Questo permette alla calibrazione di leggere il vettore gravità completo.
 * Per dossi/buche nel viewer: gVert_dosso = gVert - 1.0
 */
class MotionSensor(
    private val context: Context,
    private val onUpdate: (
        gLateral: Float,
        gLong:    Float,
        gVert:    Float,   // GREZZO — include gravità (≈1g quando fermo)
        gyroX:    Float,
        gyroY:    Float,
        gyroZ:    Float
    ) -> Unit
) : SensorEventListener {

    companion object {
        const val PREFS_KEY_OFFSET = "g_lateral_offset"

        private const val ACCEL_ALPHA    = 0.05f
        private const val GYRO_ALPHA     = 0.10f
        private const val GYRO_DEADBAND  = 0.02f  // rad/s — evita drift giroscopio a fermo
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var offset    = 0f

    private var filteredGyroX = 0f
    private var filteredGyroY = 0f
    private var filteredGyroZ = 0f

    // ── Valori filtrati senza dead-band — usati dal service per la calibrazione ─
    // In g, include la componente gravitazionale (non sottratta).
    // Leggibili dal service in qualsiasi momento dopo start().
    val calibGX: Float get() = filteredX / SensorManager.GRAVITY_EARTH
    val calibGY: Float get() = filteredY / SensorManager.GRAVITY_EARTH
    val calibGZ: Float get() = filteredZ / SensorManager.GRAVITY_EARTH
    val calibGyroX: Float get() = filteredGyroX
    val calibGyroY: Float get() = filteredGyroY
    val calibGyroZ: Float get() = filteredGyroZ

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

    private fun currentGLateral(): Float =
        (filteredX - offset) / SensorManager.GRAVITY_EARTH

    private fun currentGLong(): Float =
        filteredY / SensorManager.GRAVITY_EARTH

    // gVert grezzo — include la componente gravitazionale (~1g quando fermo)
    private fun currentGVert(): Float =
        filteredZ / SensorManager.GRAVITY_EARTH

    private fun applyGyroBand(v: Float) = if (abs(v) < GYRO_DEADBAND) 0f else v

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                filteredX = ACCEL_ALPHA * event.values[0] + (1f - ACCEL_ALPHA) * filteredX
                filteredY = ACCEL_ALPHA * event.values[1] + (1f - ACCEL_ALPHA) * filteredY
                filteredZ = ACCEL_ALPHA * event.values[2] + (1f - ACCEL_ALPHA) * filteredZ
                onUpdate(
                    currentGLateral(),
                    currentGLong(),
                    currentGVert(),
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
                    currentGLong(),
                    currentGVert(),
                    applyGyroBand(filteredGyroX),
                    applyGyroBand(filteredGyroY),
                    applyGyroBand(filteredGyroZ)
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}