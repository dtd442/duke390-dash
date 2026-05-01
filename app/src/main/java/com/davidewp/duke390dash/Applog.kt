package com.davidewp.duke390dash

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val sdf     = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileSdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    // OutputStream diretto invece di FileWriter — funziona su tutti i dispositivi
    // Android Q+ senza dover fare query su MediaStore.Downloads.DATA (che può tornare null).
    private var outputStream: OutputStream? = null

    @Synchronized
    fun init(context: Context) {
        try {
            val fileName = "duke390_log_${fileSdf.format(Date())}.txt"
            outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: run {
                    Log.e("AppLog", "MediaStore insert fallito")
                    return
                }
                context.contentResolver.openOutputStream(uri, "wa")
                    ?: run {
                        Log.e("AppLog", "openOutputStream null")
                        return
                    }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, fileName).outputStream()
            }
            add("AppLog", "Log avviato — $fileName")
        } catch (e: Exception) {
            Log.e("AppLog", "Errore init: ${e.message}")
        }
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val ts   = sdf.format(Date())
        val line = "$ts [$tag] $msg"
        Log.d(tag, msg)
        lines.addLast(line)
        if (lines.size > MAX_LINES) lines.removeFirst()
        try {
            outputStream?.write((line + "\n").toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e("AppLog", "Errore scrittura: ${e.message}")
        }
    }

    @Synchronized
    fun get(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        add("AppLog", "Log pulito")
    }

    @Synchronized
    fun close() {
        try {
            outputStream?.close()
            outputStream = null
        } catch (_: Exception) {}
    }
}