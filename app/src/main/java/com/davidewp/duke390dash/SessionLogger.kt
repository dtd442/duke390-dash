package com.davidewp.duke390dash

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionLogger(private val context: Context) {

    companion object {
        const val TAG = "SessionLogger"
    }

    var isLogging = false
        private set

    private var csvOutputStream:  OutputStream? = null
    private var jsonOutputStream: OutputStream? = null

    private var isFirstJsonEntry = true

    private val sdf   = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",     Locale.getDefault())
    private val tsSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // ─────────────────────────────────────────────────────────────────────────
    //  Ciclo di vita
    // ─────────────────────────────────────────────────────────────────────────

    fun startSession() {
        if (isLogging) return

        val sessionName  = "duke390_${sdf.format(Date())}"
        isFirstJsonEntry = true

        try {
            csvOutputStream  = openOutputStream("$sessionName.csv",  "text/csv")
            jsonOutputStream = openOutputStream("$sessionName.json", "text/application/octet-stream")

            val header = "timestamp," +
                    "ant_pressure_bar,ant_temp_c,ant_battery_pct,ant_alarm," +
                    "post_pressure_bar,post_temp_c,post_battery_pct,post_alarm," +
                    "tps_pct,engine_load_pct,iat_c,ignition_advance_deg,fuel_l100," +
                    "fuel_trim_pct,coolant_temp_c,speed_kmh,rpm,obd_connected," +
                    "lean_angle_deg," +
                    "latitude,longitude,altitude_m,bearing_deg,gps_speed_kmh,gps_accuracy_m\n"

            csvOutputStream?.write(header.toByteArray())
            csvOutputStream?.flush()

            jsonOutputStream?.write("[\n".toByteArray())
            jsonOutputStream?.flush()

            isLogging = true
            Log.d(TAG, "Sessione avviata: $sessionName")

        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio sessione: ${e.message}")
            csvOutputStream?.close();  csvOutputStream  = null
            jsonOutputStream?.close(); jsonOutputStream = null
            isLogging = false
        }
    }

    fun stopSession() {
        if (!isLogging) return
        try {
            jsonOutputStream?.write("\n]".toByteArray())
            jsonOutputStream?.flush()
            jsonOutputStream?.close()
            csvOutputStream?.close()
            Log.d(TAG, "Sessione terminata")
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiusura sessione: ${e.message}")
        } finally {
            csvOutputStream  = null
            jsonOutputStream = null
            isLogging        = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Logging
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registra un frame di telemetria a 2 Hz.
     *
     * @param state     stato OBD + TPMS
     * @param gps       dati GPS
     * @param leanAngle angolo di piega in gradi (da LeanAngleSensor)
     */
    fun log(
        state:     DashState,
        gps:       GpsManager.GpsData = GpsManager.GpsData(),
        leanAngle: Float              = 0f
    ) {
        if (!isLogging) return
        val ts = tsSdf.format(Date())
        try {
            writeCsvRow(ts, state, gps, leanAngle)
            writeJsonEntry(ts, state, gps, leanAngle)
        } catch (e: Exception) {
            Log.e(TAG, "Errore scrittura log: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  I/O
    // ─────────────────────────────────────────────────────────────────────────

    private fun openOutputStream(fileName: String, mimeType: String): OutputStream {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE,    mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore insert fallito per $fileName")
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw Exception("openOutputStream null per $fileName")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, fileName).outputStream()
        }
    }

    private fun writeCsvRow(
        ts:        String,
        state:     DashState,
        gps:       GpsManager.GpsData,
        leanAngle: Float
    ) {
        val ant  = state.tpmsAnt
        val post = state.tpmsPost
        val obd  = state.obd

        val row = "$ts," +
                "${ant.pressureBar},${ant.tempC},${ant.batteryPct},${ant.alarm}," +
                "${post.pressureBar},${post.tempC},${post.batteryPct},${post.alarm}," +
                "${obd.tpsPercent},${obd.engineLoadPercent},${obd.iatCelsius}," +
                "${obd.ignitionAdvance},${obd.fuelConsumptionL100}," +
                "${obd.fuelTrimPct},${obd.coolantTempCelsius}," +
                "${obd.speedKmh},${obd.rpmValue},${obd.connected}," +
                "$leanAngle," +
                "${gps.latitude},${gps.longitude},${gps.altitudeM},${gps.bearingDeg}," +
                "${gps.speedKmh},${gps.accuracyM}\n"

        csvOutputStream?.write(row.toByteArray())
        csvOutputStream?.flush()
    }

    private fun writeJsonEntry(
        ts:        String,
        state:     DashState,
        gps:       GpsManager.GpsData,
        leanAngle: Float
    ) {
        val ant  = state.tpmsAnt
        val post = state.tpmsPost
        val obd  = state.obd

        val entry = JSONObject().apply {
            put("timestamp", ts)

            put("tpms_ant", JSONObject().apply {
                put("pressure_bar", ant.pressureBar)
                put("temp_c",       ant.tempC)
                put("battery_pct",  ant.batteryPct)
                put("alarm",        ant.alarm)
            })

            put("tpms_post", JSONObject().apply {
                put("pressure_bar", post.pressureBar)
                put("temp_c",       post.tempC)
                put("battery_pct",  post.batteryPct)
                put("alarm",        post.alarm)
            })

            put("obd", JSONObject().apply {
                put("tps_pct",              obd.tpsPercent)
                put("accel_pedal_pct",      obd.accelPedalPct)
                put("engine_load_pct",      obd.engineLoadPercent)
                put("iat_c",                obd.iatCelsius)
                put("ignition_advance_deg", obd.ignitionAdvance)
                put("fuel_l100",            obd.fuelConsumptionL100)
                put("fuel_trim_pct",        obd.fuelTrimPct)
                put("coolant_temp_c",       obd.coolantTempCelsius)
                put("speed_kmh",            obd.speedKmh)
                put("rpm",                  obd.rpmValue)
                put("map_kpa",              obd.mapKpa)
                put("baro_kpa",             obd.baroKpa)
                put("battery_voltage",      obd.batteryVoltage)
                put("dtc_count",            obd.dtcCount)
                put("mil_on",               obd.milOn)
                put("connected",            obd.connected)
            })

            put("sensors", JSONObject().apply {
                put("lean_angle_deg", leanAngle)
                put("latitude",       gps.latitude)
                put("longitude",      gps.longitude)
                put("altitude_m",     gps.altitudeM)
                put("bearing_deg",    gps.bearingDeg)
                put("gps_speed_kmh",  gps.speedKmh)
                put("gps_accuracy_m", gps.accuracyM)
                put("gps_available",  gps.available)
            })
        }

        val prefix = if (isFirstJsonEntry) "" else ",\n"
        isFirstJsonEntry = false

        jsonOutputStream?.write("$prefix${entry.toString(2)}".toByteArray())
        jsonOutputStream?.flush()
    }
}