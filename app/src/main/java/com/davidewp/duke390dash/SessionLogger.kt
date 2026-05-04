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

    // Streaming JSON — nessun array in memoria
    private var isFirstJsonEntry = true

    private val sdf   = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val tsSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun startSession() {
        if (isLogging) return
        val sessionName = "duke390_${sdf.format(Date())}"
        isFirstJsonEntry = true
        try {
            csvOutputStream  = openOutputStream("$sessionName.csv",  "text/csv")
            jsonOutputStream = openOutputStream("$sessionName.json", "application/json")

            val header = "timestamp," +
                    "ant_pressure_bar,ant_temp_c,ant_battery_pct,ant_alarm," +
                    "post_pressure_bar,post_temp_c,post_battery_pct,post_alarm," +
                    "tps_pct,engine_load_pct,iat_c,ignition_advance_deg,fuel_l100," +
                    "fuel_trim_pct,coolant_temp_c,speed_kmh,rpm,obd_connected," +
                    "g_lateral,g_long,g_vert,gyro_x,gyro_y,gyro_z," +
                    "latitude,longitude,altitude_m,bearing_deg,gps_speed_kmh,gps_accuracy_m\n"

            csvOutputStream?.write(header.toByteArray())
            csvOutputStream?.flush()

            // Apre l'array JSON
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
            // Chiude l'array JSON
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
            isLogging = false
        }
    }

    /**
     * Registra un frame di telemetria.
     *
     * @param state    stato OBD + TPMS
     * @param gLateral accelerazione laterale in G (accelerometro asse X)
     * @param gyroX    velocità angolare asse X in rad/s (roll  — utile per lean angle sul manubrio)
     * @param gyroY    velocità angolare asse Y in rad/s (pitch — beccheggio)
     * @param gyroZ    velocità angolare asse Z in rad/s (yaw   — imbardata)
     * @param gps      dati GPS
     */
    fun log(
        state:      DashState,
        gLateral:   Float,
        gLong:      Float = 0f,
        gVert:      Float = 0f,
        gyroX:      Float = 0f,
        gyroY:      Float = 0f,
        gyroZ:      Float = 0f,
        gps:        GpsManager.GpsData = GpsManager.GpsData(),
        calibDone:  Boolean = false,
        calibPhase: Int     = 0,
        leanAngle:  Float   = 0f  // <--- AGGIUNGI QUESTO PARAMETRO
    ) {
        if (!isLogging) return
        val ts = tsSdf.format(Date())
        try {
            // Passa leanAngle a writeJsonEntry (e se vuoi anche a writeCsvRow)
            writeCsvRow(ts, state, gLateral, gLong, gVert, gyroX, gyroY, gyroZ, gps)
            writeJsonEntry(ts, state, gLateral, gLong, gVert, gyroX, gyroY, gyroZ, gps, calibDone, calibPhase, leanAngle)
        } catch (e: Exception) {
            Log.e(TAG, "Errore scrittura log: ${e.message}")
        }
    }

    private fun openOutputStream(fileName: String, mimeType: String): OutputStream {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore insert fallito per $fileName")
            // FIX: usa "w" invece di "wa" — più affidabile su Android 10–13
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw Exception("openOutputStream null per $fileName")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, fileName).outputStream()
        }
    }

    private fun writeCsvRow(
        ts: String, state: DashState,
        gLateral: Float, gLong: Float, gVert: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        gps: GpsManager.GpsData
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
                "$gLateral,$gLong,$gVert,$gyroX,$gyroY,$gyroZ," +
                "${gps.latitude},${gps.longitude},${gps.altitudeM},${gps.bearingDeg}," +
                "${gps.speedKmh},${gps.accuracyM}\n"

        csvOutputStream?.write(row.toByteArray())
        csvOutputStream?.flush()
    }

    private fun writeJsonEntry(
        ts: String, state: DashState,
        gLateral: Float, gLong: Float, gVert: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        gps: GpsManager.GpsData,
        calibDone: Boolean = false,
        calibPhase: Int = 0,
        leanAngle: Any
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
                put("g_lateral",      gLateral)        // accelerometro asse X in G
                put("lean_angle_deg", leanAngle)
                put("g_long",    gLong)   // ← beccheggio: positivo = accelerazione, negativo = frenata
                put("g_vert",    gVert)   // ← verticale: positivo = dosso, negativo = buca
                put("gyro_x",         gyroX)           // roll  rad/s — lean angle sul manubrio
                put("gyro_y",         gyroY)           // pitch rad/s — beccheggio
                put("gyro_z",         gyroZ)           // yaw   rad/s — imbardata
                put("latitude",       gps.latitude)
                put("longitude",      gps.longitude)
                put("altitude_m",     gps.altitudeM)   // altitudine GPS nativa
                put("bearing_deg",    gps.bearingDeg)  // direzione di marcia
                put("gps_speed_kmh",  gps.speedKmh)
                put("gps_accuracy_m", gps.accuracyM)
                put("gps_available",  gps.available)
                put("calib_done",     calibDone)
                put("calib_phase",    calibPhase)  // 0=nessuna 1=statica 2=motion
            })
        }

        // FIX: scrittura streaming — ogni entry viene flushata subito su disco.
        // In questo modo se il processo viene killato i dati già scritti sono salvi,
        // invece di perdersi tutti insieme come succedeva con jsonArray in memoria.
        val prefix = if (isFirstJsonEntry) "" else ",\n"
        isFirstJsonEntry = false
        jsonOutputStream?.write("$prefix${entry.toString(2)}".toByteArray())
        jsonOutputStream?.flush()
    }
}
