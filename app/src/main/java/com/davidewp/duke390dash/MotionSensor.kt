package com.davidewp.duke390dash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Legge accelerometro + giroscopio + magnetometro.
 *
 * Assi Android (telefono verticale, schermo verso di te):
 *   X → destra/sinistra
 *   Y → su/giù
 *   Z → verso/da te
 *
 * Accelerometro: in g, grezzo (include componente gravitazionale ~1g).
 *   NON si sottrae 1g — lo fa il service dopo la rotazione.
 *
 * Giroscopio: in rad/s, dead-band ±0.02 rad/s.
 *
 * Magnetometro: in µT, filtrato lentamente (stabile).
 *   Usato dalla calibrazione per determinare la direzione Nord nel sistema telefono.
 */
class MotionSensor(
    private val context: Context,
    private val onUpdate: (
        gX:    Float,   // accel X in g (grezzo, include gravità)
        gY:    Float,   // accel Y in g
        gZ:    Float,   // accel Z in g
        gyroX: Float,   // rad/s
        gyroY: Float,
        gyroZ: Float,
        magX:  Float,   // µT — Nord nel sistema telefono
        magY:  Float,
        magZ:  Float
    ) -> Unit
) : SensorEventListener {

    companion object {
        private const val ACCEL_ALPHA   = 0.1f   // low-pass accelerometro (~50Hz sensore)
        private const val GYRO_ALPHA    = 0.1f   // low-pass giroscopio
        private const val MAG_ALPHA     = 0.05f  // magnetometro: più lento = più stabile
        private const val GYRO_DEADBAND = 0.02f  // rad/s — elimina drift a fermo
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor  = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor   = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var aX = 0f; private var aY = 0f; private var aZ = 0f
    private var gX = 0f; private var gY = 0f; private var gZ = 0f
    private var mX = 0f; private var mY = 0f; private var mZ = 0f

    private var _magReady = false
    val isMagReady: Boolean get() = _magReady

    fun start() {
        accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let  { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magSensor?.let   { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                aX = lerp(aX, event.values[0], ACCEL_ALPHA)
                aY = lerp(aY, event.values[1], ACCEL_ALPHA)
                aZ = lerp(aZ, event.values[2], ACCEL_ALPHA)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gX = lerp(gX, event.values[0], GYRO_ALPHA)
                gY = lerp(gY, event.values[1], GYRO_ALPHA)
                gZ = lerp(gZ, event.values[2], GYRO_ALPHA)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mX = lerp(mX, event.values[0], MAG_ALPHA)
                mY = lerp(mY, event.values[1], MAG_ALPHA)
                mZ = lerp(mZ, event.values[2], MAG_ALPHA)
                _magReady = true
            }
            else -> return
        }
        onUpdate(
            aX / SensorManager.GRAVITY_EARTH,
            aY / SensorManager.GRAVITY_EARTH,
            aZ / SensorManager.GRAVITY_EARTH,
            db(gX), db(gY), db(gZ),
            mX, mY, mZ
        )
    }

    private fun lerp(cur: Float, new: Float, a: Float) = a * new + (1f - a) * cur
    private fun db(v: Float) = if (abs(v) < GYRO_DEADBAND) 0f else v

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
