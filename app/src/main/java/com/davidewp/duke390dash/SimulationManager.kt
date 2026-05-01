package com.davidewp.duke390dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SimulationManager {

    private val _simState = MutableStateFlow<DashState?>(null)
    val simState: StateFlow<DashState?> = _simState
    val isRunning get() = simJob != null

    private var simJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (simJob != null) return
        simJob = scope.launch {
            val rng = java.util.Random()

            // Esistenti
            var speed     = 0f;   var rpm      = 0f;   var tps     = 0f;  var load    = 0f
            var iat       = 25f;  var ignAdv   = 15f;  var coolant = 30f; var fuelTrim = 0f
            var antPress  = 2.2f; var postPress = 2.5f
            var antTemp   = 20f;  var postTemp  = 22f
            var maxSpeed  = 0f;   var maxRpm   = 0f;   var maxLoad = 0f

            // Nuovi
            var mapKpa      = 32f   // pressione collettore — bassa a riposo, alta a gas aperto
            var accelPedal  = 0f    // segue tps con leggero ritardo (simula ride-by-wire)
            var baroKpa     = 101f  // costante — la pressione barometrica non cambia in sessione
            var maxMapKpa   = 0f

            while (isActive) {
                tps        = (tps      + (rng.nextFloat() - 0.4f) * 10f).coerceIn(0f, 100f)
                load       = (load     + (rng.nextFloat() - 0.4f) * 8f).coerceIn(0f, 100f)
                rpm        = (rpm      + (rng.nextFloat() - 0.4f) * 500f).coerceIn(1000f, 10000f)
                speed      = (speed    + (rng.nextFloat() - 0.45f) * 15f).coerceIn(0f, 160f)
                iat        = (iat      + (rng.nextFloat() - 0.5f) * 0.5f).coerceIn(15f, 60f)
                ignAdv     = (ignAdv   + (rng.nextFloat() - 0.5f) * 1f).coerceIn(0f, 40f)
                coolant    = (coolant  + 0.2f + (rng.nextFloat() - 0.5f) * 0.1f).coerceIn(20f, 100f)
                fuelTrim   = (fuelTrim + (rng.nextFloat() - 0.5f) * 0.5f).coerceIn(-15f, 15f)
                antTemp    = (antTemp  + 0.1f).coerceIn(20f, 80f)
                postTemp   = (postTemp + 0.15f).coerceIn(20f, 80f)
                antPress   = (antPress  + (rng.nextFloat() - 0.5f) * 0.02f).coerceIn(1.5f, 3.5f)
                postPress  = (postPress + (rng.nextFloat() - 0.5f) * 0.02f).coerceIn(1.5f, 3.5f)

                // MAP segue il carico motore: bassa a farfalla chiusa (~30kPa), alta a gas aperto (~102kPa)
                val mapTarget = 30f + (load / 100f) * 72f
                mapKpa     = (mapKpa + (mapTarget - mapKpa) * 0.3f + (rng.nextFloat() - 0.5f) * 2f).coerceIn(25f, 105f)

                // Pedale acceleratore anticipa leggermente la farfalla (ride-by-wire)
                // A volte diverge per simulare intervento TC/cornering ABS
                val accelTarget = (tps + (rng.nextFloat() - 0.3f) * 15f).coerceIn(0f, 100f)
                accelPedal = (accelPedal + (accelTarget - accelPedal) * 0.4f).coerceIn(0f, 100f)

                maxSpeed  = maxOf(maxSpeed,  speed)
                maxRpm    = maxOf(maxRpm,    rpm)
                maxLoad   = maxOf(maxLoad,   load)
                maxMapKpa = maxOf(maxMapKpa, mapKpa)

                val consumption = if (speed > 5f) (rpm / 6000f * 4.5f / speed * 100f) else 0f

                if (!isActive) break

                _simState.value = DashState(
                    tpmsAnt  = TpmsData(antPress,  antTemp,  95, antPress  < 1.8f, true),
                    tpmsPost = TpmsData(postPress, postTemp, 92, postPress < 1.8f, true),
                    obd = ObdData(
                        tpsPercent          = tps,
                        engineLoadPercent   = load,
                        iatCelsius          = iat,
                        ignitionAdvance     = ignAdv,
                        fuelConsumptionL100 = consumption,
                        fuelTrimPct         = fuelTrim,
                        coolantTempCelsius  = coolant,
                        speedKmh            = speed,
                        rpmValue            = rpm,
                        mapKpa              = mapKpa,
                        accelPedalPct       = accelPedal,
                        baroKpa             = baroKpa,
                        batteryVoltage      = 0f,   // non simulato — viene da ATRV reale
                        dtcCount            = 0,
                        milOn               = false,
                        connected           = true
                    ),
                    peaks = SessionPeaks(
                        maxSpeedKmh   = maxSpeed,
                        maxRpm        = maxRpm,
                        maxEngineLoad = maxLoad,
                        maxMapKpa     = maxMapKpa
                    )
                )
                delay(500)
            }
        }
    }

    fun stop() {
        simJob?.cancel()
        simJob = null
        _simState.value = null
    }
}