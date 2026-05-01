package com.davidewp.duke390dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class TpmsManager(private val context: Context) {

    companion object {
        const val TAG = "TpmsManager"
        const val TPMS_UUID = "0000fbb0-0000-1000-8000-00805f9b34fb"

        // Stale warning: ! giallo se non riceve da questi intervalli
        const val STALE_ANT_MS   = 3 * 60 * 1000L   // 3 minuti
        const val STALE_POST_MS  = 5 * 60 * 1000L   // 5 minuti
        const val STALE_CHECK_MS = 15_000L
    }

    private val _antState  = MutableStateFlow(TpmsData())
    val antState:  StateFlow<TpmsData> = _antState

    private val _postState = MutableStateFlow(TpmsData())
    val postState: StateFlow<TpmsData> = _postState

    private var idAnt  = ""
    private var idPost = ""

    private var bleScanner:   android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var staleJob:     Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var lastAntAccepted  = 0L
    private var lastPostAccepted = 0L

    // ─────────────────────────────────────────────
    //  Ciclo di vita
    // ─────────────────────────────────────────────

    fun start(idAnt: String, idPost: String) {
        this.idAnt  = idAnt
        this.idPost = idPost
        startScanner()
        startStaleChecker()
        AppLog.add(TAG, "Avviato — ant=$idAnt post=$idPost")
    }

    fun stop() {
        stopScanner()
        staleJob?.cancel()
        staleJob = null
        // NON resettiamo i valori: l'UI mostra l'ultimo dato ricevuto
        AppLog.add(TAG, "Fermato")
    }

    fun restart(idAnt: String, idPost: String) {
        stop()
        start(idAnt, idPost)
    }

    // ─────────────────────────────────────────────
    //  Scanner BLE — sempre acceso
    // ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startScanner() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter   = btManager.adapter ?: return

        scope.launch {
            while (isActive) {
                if (adapter.bluetoothLeScanner != null) break
                AppLog.add(TAG, "BLE scanner non pronto, riprovo in 5s...")
                delay(5000)
            }
            if (!isActive) return@launch

            bleScanner = adapter.bluetoothLeScanner
            doStartScanner()
        }
    }

    @SuppressLint("MissingPermission")
    private fun doStartScanner() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter ?: return
        bleScanner  = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(TPMS_UUID)))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                parseAdvertisement(result)
            }
            override fun onScanFailed(errorCode: Int) {
                AppLog.add(TAG, "SCAN FAILED errorCode=$errorCode")
            }
        }

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        AppLog.add(TAG, "BLE scanner avviato")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanner() {
        try { scanCallback?.let { bleScanner?.stopScan(it) } } catch (_: Exception) {}
        scanCallback = null
        bleScanner   = null
    }

    // ─────────────────────────────────────────────
    //  Stale checker
    // ─────────────────────────────────────────────

    private fun startStaleChecker() {
        staleJob?.cancel()
        staleJob = scope.launch {
            while (isActive) {
                delay(STALE_CHECK_MS)
                val now = System.currentTimeMillis()
                if (lastAntAccepted > 0L) {
                    val stale = (now - lastAntAccepted) > STALE_ANT_MS
                    if (_antState.value.staleWarning != stale)
                        _antState.value = _antState.value.copy(staleWarning = stale)
                }
                if (lastPostAccepted > 0L) {
                    val stale = (now - lastPostAccepted) > STALE_POST_MS
                    if (_postState.value.staleWarning != stale)
                        _postState.value = _postState.value.copy(staleWarning = stale)
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    //  Parsing advertising TPMS
    // ─────────────────────────────────────────────

    private fun parseAdvertisement(result: ScanResult) {
        try {
            val record = result.scanRecord ?: return
            val raw    = record.getManufacturerSpecificData(0x0100) ?: return
            if (raw.size < 16) return

            @SuppressLint("MissingPermission")
            val deviceName = result.device.name ?: ""
            val sensorId = if (deviceName.contains("_")) {
                deviceName.substringAfterLast("_").uppercase().take(6)
            } else {
                result.device.address.split(":").takeLast(3).joinToString("").uppercase()
            }

            val sensorNum = when {
                idAnt.isNotEmpty() && idPost.isNotEmpty() -> when (sensorId) {
                    idAnt.uppercase()  -> 1
                    idPost.uppercase() -> 2
                    else               -> return
                }
                idAnt.isNotEmpty() -> if (sensorId == idAnt.uppercase()) 1 else return
                else               -> 1
            }

            val now         = System.currentTimeMillis()
            val pressKpa    = (ByteBuffer.wrap(raw, 6, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) / 1000f
            val tempC       = (ByteBuffer.wrap(raw, 10, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) / 100f
            val battery     = raw[14].toInt() and 0xFF
            val alarm       = (raw[15].toInt() and 0xFF) != 0
            val pressureBar = pressKpa / 100f

            val tpms = TpmsData(
                pressureBar  = pressureBar,
                tempC        = tempC.toFloat(),
                batteryPct   = battery,
                alarm        = alarm,
                staleWarning = false
            )

            if (sensorNum == 1) {
                _antState.value  = tpms
                lastAntAccepted  = now
                AppLog.add(TAG, "ANT press=${pressureBar}bar temp=${tempC}°C")
            } else {
                _postState.value = tpms
                lastPostAccepted = now
                AppLog.add(TAG, "POST press=${pressureBar}bar temp=${tempC}°C")
            }

        } catch (e: Exception) {
            AppLog.add(TAG, "parseAdvertisement error: ${e.message}")
        }
    }
}