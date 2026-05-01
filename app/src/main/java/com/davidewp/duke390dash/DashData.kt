package com.davidewp.duke390dash

data class TpmsData(
    val pressureBar:  Float   = 0f,
    val tempC:        Float   = 0f,
    val batteryPct:   Int     = 0,
    val alarm:        Boolean = false,
    val staleWarning: Boolean = false   // true = ⚠ giallo (niente segnale da troppo tempo)
    // pressureBar == 0f → mai ricevuto nulla → UI mostra N/A
    // connected rimosso — non serve più
)

data class ObdData(
    // ── Esistenti ─────────────────────────────────────────────────────────────
    val tpsPercent:           Float   = 0f,
    val engineLoadPercent:    Float   = 0f,
    val iatCelsius:           Float   = 0f,
    val ignitionAdvance:      Float   = 0f,   // gradi anticipo accensione
    val fuelConsumptionL100:  Float   = 0f,   // consumo calcolato
    val fuelTrimPct:          Float   = 0f,   // fuel trim %
    val coolantTempCelsius:   Float   = 0f,
    val speedKmh:             Float   = 0f,
    val rpmValue:             Float   = 0f,
    // ── Nuovi ─────────────────────────────────────────────────────────────────
    val mapKpa:               Float   = 0f,   // 010B — pressione collettore (MAP)
    val baroKpa:              Float   = 0f,   // 0133 — pressione barometrica
    val batteryVoltage:       Float   = 0f,   // ATRV — tensione batteria/ECU
    val accelPedalPct:        Float   = 0f,   // 015A — posizione pedale acceleratore
    val dtcCount:             Int     = 0,    // 0101 — numero DTC attivi
    val milOn:                Boolean = false, // 0101 — spia MIL (Check Engine) accesa
    // ─────────────────────────────────────────────────────────────────────────
    val connected:            Boolean = false
)

data class SessionPeaks(
    val maxSpeedKmh:     Float = 0f,
    val maxRpm:          Float = 0f,
    val maxEngineLoad:   Float = 0f,
    val maxMapKpa:       Float = 0f
)

data class DashState(
    val tpmsAnt:  TpmsData     = TpmsData(),
    val tpmsPost: TpmsData     = TpmsData(),
    val obd:      ObdData      = ObdData(),
    val peaks:    SessionPeaks = SessionPeaks()
)