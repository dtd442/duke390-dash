package com.davidewp.duke390dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class ObdManager(private val context: Context) {

    companion object {
        const val TAG = "ObdManager"

        val SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        val CHAR_UUID    = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // TODO: rendere universale — scan BLE con filtro Service UUID 000018f0,
        //       lista device nel dialog impostazioni, MAC salvato in SharedPreferences.
        //       Vedi opzione C discussa in chat.
        const val DEVICE_NAME         = "vLinker MC-IOS"
        const val READ_INTERVAL_MS    = 250L
        const val PID_TIMEOUT_MS      = 600L
        const val MAX_SPEED_DELTA     = 80f
        const val MAX_PLAUSIBLE_SPEED = 220f
        const val TARGET_MTU          = 512

        // Keep-alive integrato nel loop: ogni KEEPALIVE_EVERY cicli viene letto
        // ATRV (tensione batteria) invece di un comando AT nudo.
        // Dentro il mutex del loop — zero rischio di inquinare il buffer.
        // 30 cicli x ~250ms = ~7.5s tra ogni keep-alive.
        const val KEEPALIVE_EVERY = 30
    }

    private val _obdState = MutableStateFlow(ObdData())
    val obdState: StateFlow<ObdData> = _obdState

    private val _peaks = MutableStateFlow(SessionPeaks())
    val peaks: StateFlow<SessionPeaks> = _peaks

    private val scope = CoroutineScope(Dispatchers.IO)
    private var scanJob: Job? = null
    private var readJob: Job? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var lastValidSpeed = -1f  // -1 = nessun campione ancora accettato
    private var loopCount = 0

    // ── Sincronizzazione GATT deterministica ─────────────────────────────────
    // gattMutex: un solo comando GATT in volo alla volta.
    // writeAck: CompletableDeferred completato da onCharacteristicWrite.
    // responseDeferred: CompletableDeferred completato da onCharacteristicChanged.
    private val gattMutex = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var responseDeferred: CompletableDeferred<String>? = null

    // Buffer notifiche BLE accumulato fino a '>'
    private val responseBuffer = StringBuilder()

    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        AppLog.add(TAG, "Avviato — scan per $DEVICE_NAME")
        startScan()
    }

    fun stop() {
        AppLog.add(TAG, "Fermato")
        stopScan()
        readJob?.cancel(); readJob = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
        writeChar = null
        writeAck?.cancel()
        responseDeferred?.cancel()
        lastValidSpeed = -1f
        _obdState.value = ObdData()
    }

    fun restart() {
        stop()
        start()
    }

    // ─── BLE Scan ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = btManager.adapter?.bluetoothLeScanner ?: run {
            AppLog.add(TAG, "BLE scanner non disponibile")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanJob = scope.launch {
            scanner.startScan(null, settings, scanCallback)
            AppLog.add(TAG, "BLE scan avviato — cerco $DEVICE_NAME")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanJob?.cancel(); scanJob = null
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name != DEVICE_NAME) return
            AppLog.add(TAG, "Trovato $DEVICE_NAME — connessione GATT...")
            stopScan()
            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            AppLog.add(TAG, "Scan fallito errorCode=$errorCode")
        }
    }

    // ─── GATT Callback ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AppLog.add(TAG, "GATT connesso — request MTU $TARGET_MTU...")
                    this@ObdManager.gatt = gatt
                    gatt.requestMtu(TARGET_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AppLog.add(TAG, "GATT disconnesso status=$status")
                    isConnected = false
                    writeChar = null
                    readJob?.cancel()
                    writeAck?.cancel()
                    responseDeferred?.cancel()
                    lastValidSpeed = -1f
                    _obdState.value = ObdData()
                    gatt.close()
                    this@ObdManager.gatt = null
                    scope.launch {
                        delay(3000)
                        startScan()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            AppLog.add(TAG, "MTU negoziato: $mtu (status=$status) — discover services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.add(TAG, "discoverServices fallito status=$status")
                return
            }
            val service = gatt.getService(SERVICE_UUID) ?: run {
                AppLog.add(TAG, "Service OBD non trovato!")
                return
            }
            val char = service.getCharacteristic(CHAR_UUID) ?: run {
                AppLog.add(TAG, "Characteristic non trovata!")
                return
            }
            writeChar = char
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            AppLog.add(TAG, "Notifiche abilitate — inizializzo ELM327")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            AppLog.add(TAG, "Descriptor write status=$status — avvio ELM327 init")
            scope.launch {
                initElm327()
                isConnected = true
                _obdState.value = _obdState.value.copy(connected = true)



                //testpid
                testSupportedPids()




                startReadLoop()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            val chunk = String(data)
            responseBuffer.append(chunk)
            if (responseBuffer.contains('>')) {
                val response = responseBuffer.toString()
                    .replace("\r", "")
                    .replace("\n", "")
                    .replace(">", "")
                    .trim()
                responseBuffer.clear()
                AppLog.add(TAG, "RAW: [$response]")
                responseDeferred?.complete(response)
                responseDeferred = null
            }
        }
    }

    // ─── ELM327 Init ──────────────────────────────────────────────────────────

    private suspend fun initElm327() {
        sendCmd("ATZ");     delay(1000)
        sendCmd("ATE0");    delay(200)
        sendCmd("ATL0");    delay(200)
        sendCmd("ATS0");    delay(200)
        sendCmd("ATSP6");   delay(500)
        sendCmd("ATSH7E0"); delay(300)
        AppLog.add(TAG, "ELM327 init completato — protocollo CAN 11bit/500k, header 7E0")
    }

    // ─── Read Loop ────────────────────────────────────────────────────────────

    private fun startReadLoop() {
        loopCount = 0
        readJob?.cancel()
        readJob = scope.launch {
            while (isActive && isConnected) {
                readAllPids()
                delay(READ_INTERVAL_MS)
            }
        }
    }

    private suspend fun readAllPids() {
        loopCount++

        // ── PID ad ogni ciclo ─────────────────────────────────────────────────
        val tps      = readPid("0111")?.let { parsePercent(it) }
        val rpm      = readPid("010C")?.let { parseRpm(it) }
        val speed    = readPid("010D")?.let { parseSpeed(it) }?.let { validateSpeed(it) }
        val load     = readPid("0104")?.let { parsePercent(it) }
        val iat      = readPid("010F")?.let { parseTemp(it) }
        val ignAdv   = readPid("010E")?.let { parseIgnitionAdvance(it) }
        val coolant  = readPid("0105")?.let { parseTemp(it) }
        val fuelTrim = readPid("0106")?.let { parseFuelTrim(it) }
        val map      = readPid("010B")?.let { parseKpa(it) }
        val accel    = readPid("015A")?.let { parsePercent(it) }

        // ── PID a bassa frequenza (ogni 10 cicli ≈ 2.5s) ─────────────────────
        // Baro e DTC cambiano lentamente, non serve leggerli ad ogni ciclo.
        var baro    = _obdState.value.baroKpa
        var dtc     = _obdState.value.dtcCount
        var mil     = _obdState.value.milOn
        var voltage = _obdState.value.batteryVoltage

        if (loopCount % 10 == 0) {
            readPid("0133")?.let { parseKpa(it) }?.let { baro = it }
            readPid("0101")?.let { parseMonitorStatus(it) }?.let { (count, milOn) ->
                dtc = count; mil = milOn
            }
        }

        // ── Keep-alive integrato (ogni KEEPALIVE_EVERY cicli ≈ 7.5s) ─────────
        // ATRV legge la tensione via ELM327 — risposta sempre valida.
        // Dentro il mutex del loop: nessun rischio di inquinare il buffer.
        if (loopCount % KEEPALIVE_EVERY == 0) {
            readPid("ATRV")?.let { parseVoltage(it) }?.let { voltage = it }
            AppLog.add(TAG, "Keep-alive ATRV — tensione=${voltage}V")
        }

        val consumption = if (speed != null && rpm != null && speed > 5f)
            calculateConsumption(rpm, speed) else null

        val current = _obdState.value
        _obdState.value = current.copy(
            tpsPercent          = tps         ?: current.tpsPercent,
            engineLoadPercent   = load        ?: current.engineLoadPercent,
            iatCelsius          = iat         ?: current.iatCelsius,
            ignitionAdvance     = ignAdv      ?: current.ignitionAdvance,
            fuelConsumptionL100 = consumption ?: current.fuelConsumptionL100,
            fuelTrimPct         = fuelTrim    ?: current.fuelTrimPct,
            coolantTempCelsius  = coolant     ?: current.coolantTempCelsius,
            speedKmh            = speed       ?: current.speedKmh,
            rpmValue            = rpm         ?: current.rpmValue,
            mapKpa              = map         ?: current.mapKpa,
            accelPedalPct       = accel       ?: current.accelPedalPct,
            baroKpa             = baro,
            batteryVoltage      = voltage,
            dtcCount            = dtc,
            milOn               = mil,
            connected           = true
        )

        val p = _peaks.value
        _peaks.value = p.copy(
            maxSpeedKmh   = maxOf(p.maxSpeedKmh,   speed ?: 0f),
            maxRpm        = maxOf(p.maxRpm,         rpm   ?: 0f),
            maxEngineLoad = maxOf(p.maxEngineLoad,  load  ?: 0f),
            maxMapKpa     = maxOf(p.maxMapKpa,      map   ?: 0f)
        )
    }

    // ─── BLE Write ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun rawSendCmd(cmd: String) {
        val char = writeChar ?: return
        val bytes = "$cmd\r".toByteArray()
        char.value = bytes
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ack = CompletableDeferred<Boolean>()
        writeAck = ack
        gatt?.writeCharacteristic(char)
        withTimeoutOrNull(1000L) { ack.await() }
            ?: AppLog.add(TAG, "writeAck TIMEOUT per cmd=$cmd")
    }

    private suspend fun sendCmd(cmd: String) {
        gattMutex.withLock { rawSendCmd(cmd) }
    }

    private suspend fun readPid(pid: String): String? {
        if (!isConnected || writeChar == null) return null
        return try {
            gattMutex.withLock {
                responseBuffer.clear()
                responseDeferred?.cancel()

                val deferred = CompletableDeferred<String>()
                responseDeferred = deferred

                rawSendCmd(pid)

                val response = withTimeoutOrNull(PID_TIMEOUT_MS) { deferred.await() }

                if (response == null) {
                    responseDeferred = null
                    responseBuffer.clear()
                }

                val clean = response?.replace(" ", "")?.trim()
                AppLog.add(TAG, "PID $pid -> ${clean ?: "TIMEOUT"}")
                if (clean.isNullOrEmpty() || clean.contains("NODATA") || clean.contains("ERROR")) null
                else clean
            }
        } catch (e: Exception) {
            AppLog.add(TAG, "PID $pid exception: ${e.message}")
            responseDeferred = null
            responseBuffer.clear()
            null
        }
    }

    //testpid
    suspend fun testSupportedPids() {
        val cmds = listOf("0100", "0120", "0140", "0160")

        for (cmd in cmds) {
            val resp = readPid(cmd)
            AppLog.add(TAG, "TEST PID $cmd -> $resp")
        }
    }

    // ─── Validazione velocità ─────────────────────────────────────────────────

    private fun validateSpeed(rawSpeed: Float): Float? {
        if (rawSpeed > MAX_PLAUSIBLE_SPEED) return null
        if (lastValidSpeed >= 0f && Math.abs(rawSpeed - lastValidSpeed) > MAX_SPEED_DELTA) return null
        lastValidSpeed = rawSpeed
        return rawSpeed
    }

    // ─── Parser PID ───────────────────────────────────────────────────────────

    // (A x 100) / 255 — TPS, engine load, pedale acceleratore
    private fun parsePercent(raw: String): Float? = try {
        raw.takeLast(2).toInt(16) * 100f / 255f
    } catch (e: Exception) { null }

    // A - 40 — IAT e coolant
    private fun parseTemp(raw: String): Float? = try {
        raw.takeLast(2).toInt(16) - 40f
    } catch (e: Exception) { null }

    // (A / 2) - 64 — gradi BTDC
    private fun parseIgnitionAdvance(raw: String): Float? = try {
        raw.takeLast(2).toInt(16) / 2f - 64f
    } catch (e: Exception) { null }

    // A — km/h diretto
    private fun parseSpeed(raw: String): Float? = try {
        raw.takeLast(2).toInt(16).toFloat()
    } catch (e: Exception) { null }

    // ((A x 256) + B) / 4 — RPM
    private fun parseRpm(raw: String): Float? = try {
        val a = raw.substring(raw.length - 4, raw.length - 2).toInt(16)
        val b = raw.takeLast(2).toInt(16)
        (a * 256 + b) / 4f
    } catch (e: Exception) { null }

    // (A / 1.28) - 100 — fuel trim %
    private fun parseFuelTrim(raw: String): Float? = try {
        raw.takeLast(2).toInt(16) / 1.28f - 100f
    } catch (e: Exception) { null }

    // A — kPa diretto (MAP 010B e baro 0133)
    private fun parseKpa(raw: String): Float? = try {
        raw.takeLast(2).toInt(16).toFloat()
    } catch (e: Exception) { null }

    // AT RV restituisce "12.3V" — strip "V" e parse float
    private fun parseVoltage(raw: String): Float? = try {
        raw.replace("V", "").replace("v", "").trim().toFloat()
    } catch (e: Exception) { null }

    // 0101: byte A — bit7=MIL accesa, bit6-0=numero DTC attivi
    private fun parseMonitorStatus(raw: String): Pair<Int, Boolean>? = try {
        val hex = raw.replace(" ", "")
        val idx = hex.indexOf("4101")
        if (idx < 0 || hex.length < idx + 6) null
        else {
            val byteA    = hex.substring(idx + 4, idx + 6).toInt(16)
            val milOn    = (byteA and 0x80) != 0
            val dtcCount = byteA and 0x7F
            Pair(dtcCount, milOn)
        }
    } catch (e: Exception) { null }

    private fun calculateConsumption(rpm: Float, speedKmh: Float): Float {
        val injectorDuty = rpm / 6000f
        val fuelPerHour  = injectorDuty * 4.5f
        return if (speedKmh > 0) (fuelPerHour / speedKmh) * 100f else 0f
    }
}