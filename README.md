# Duke 390 Dash 🏍️

> ⚠️ **Disclaimer** — I'm not a professional developer. This is a personal amateur project built for my own KTM Duke 390. The code was written with massive help from [Claude](https://claude.ai) (Anthropic's AI assistant) — honestly, Claude did most of the heavy lifting. I provided the ideas, the hardware, the test rides and the bug reports. The UI layout still needs a proper redesign, and the OBD2 adapter is currently hardcoded in the source (`DEVICE_NAME = "vLinker MC-IOS"` in `ObdManager.kt`). Use at your own risk, pull requests welcome.

Android dashboard app for the KTM Duke 390, designed to run on a phone mounted on the handlebar.
Connects via Bluetooth LE to an OBD2 adapter and TPMS sensors, logging telemetry data in real time.

---

## Features

- **OBD2 via BLE** — RPM, speed, TPS, engine load, coolant temp, IAT, MAP, ignition advance, fuel trim, accelerator pedal position, battery voltage, DTC count and MIL status
- **TPMS via BLE** — tire pressure and temperature for front and rear wheels, stale warning if sensor goes silent
- **Lateral G-force** — accelerometer with low-pass filter, dead zone, and calibration offset
- **Gyroscope** — all 3 axes (roll, pitch, yaw) in rad/s logged to telemetry, low-pass filtered with dead zone
- **GPS telemetry** — position, speed, native altitude and bearing logged alongside OBD data; network provider fallback for fast initial fix
- **Background GPS** — GPS and all sensors remain active with screen off thanks to `ACCESS_BACKGROUND_LOCATION` and `foregroundServiceType="connectedDevice|location"`
- **Session logging** — CSV and JSON saved to Downloads folder, start/stop from UI or Quick Settings tile; logged at fixed 2 Hz independent of OBD polling rate
- **Quick Settings tile** — start and stop recording directly from the notification shade, even with the app fully closed; the service runs autonomously in background and shuts itself down when recording stops
- **Foreground service** — owns all sensor managers (OBD, TPMS, GPS, IMU); keeps everything alive with screen off via `PARTIAL_WAKE_LOCK`

---

## Hardware Required

| Device | Details |
|--------|---------|
| OBD2 adapter | vLinker MC-IOS (BLE, service UUID `e7810a71`) |
| TPMS sensors | BLE advertising on UUID `FBB0`, manufacturer data at key `0x0100` |
| Android phone | Android 10+ (API 29), Bluetooth 5.0 recommended |

---

## Setup

1. Clone the repo and open in **Android Studio Hedgehog** or newer
2. Build and install on your Android device
3. Grant permissions when prompted: **Bluetooth**, **Location**, **Notifications**
4. After granting Location, also grant **"Allow all the time"** for background GPS — Android will open the system settings screen directly. This is required for the tile to work with the app closed
5. Mount phone on handlebar and start the bike
6. Long press anywhere on the dashboard to open **Settings**
7. Enter your TPMS sensor IDs (6-character hex, e.g. `13912F` and `238D2A`)
   - IDs are logged on first run — open Settings → Show Log to find them
8. The app connects automatically to the vLinker MC-IOS by name

---

## Usage

**Long press** anywhere on the dashboard to open the Settings panel. From there you can:

- Enter your TPMS sensor IDs
- Start / stop telemetry recording
- View the debug log (connection events, BLE packets, OBD responses)
- Calibrate the G-force sensor offset (keep the bike upright and tap Set Offset)
- Switch app language
- Exit the app

**Quick Settings tile** — add the Duke 390 Dash tile to your notification shade. Tap once to start recording (launches the service if not running), tap again to stop recording and shut everything down. Works with the app fully closed and screen off.

---

## Output Files

The app saves files to your phone's **Downloads** folder automatically.

### 📊 Telemetry — `duke390_YYYY-MM-DD_HH-mm-ss.csv` + `.json`
Recorded while logging is active (start/stop from Settings or Quick Settings tile). Contains all OBD2 channels, TPMS pressure and temperature, G-force, gyroscope and GPS. Use the included `analyzer.html` to visualize the JSON file in your browser — no server needed, just open it locally.

### 🔧 Debug log — `duke390_log_YYYY-MM-DD_HH-mm-ss.txt`
Saved automatically on every app run. Contains all BLE connection events, OBD raw responses, TPMS packets, errors and timing. Useful for diagnosing connection problems or finding your TPMS sensor IDs (they appear in the log the first time the sensors are detected). You can also view it live inside the app: Settings → **Show Log**.

---

## Architecture

```
DashForegroundService   — source of truth; owns ALL managers
  ├── TpmsManager       — BLE advertising scan, UUID FBB0
  ├── ObdManager        — BLE GATT client, ELM327 over vLinker MC-IOS
  ├── GpsManager        — LocationManager (GPS + Network fallback), background-safe
  ├── GSensor           — accelerometer X axis + gyroscope all 3 axes
  └── SessionLogger     — CSV + JSON to Downloads (Android Q+ MediaStore)
                          fixed 2 Hz loop, independent of OBD polling rate

DashViewModel           — read-only UI mirror; collects StateFlows from service companion object
MainActivity            — main UI, View Binding, XML layouts; binds to service for controls
AppLog                  — in-memory log + file, visible in Settings dialog
DashTileService         — Quick Settings tile; communicates directly with service via Intent
```

Data flow:
```
TpmsManager ─┐
ObdManager  ─┼─► combine() ─► _dashState (StateFlow companion) ─► loop log 2Hz ─► JSON/CSV
GpsManager  ─┘                                                  ─► ViewModel ─► MainActivity UI
GSensor ──────────────────────────────────────────────────────► _gLateral (StateFlow companion)

Tile click ─► startService(ACTION_TOGGLE_LOG) ─► DashForegroundService.onStartCommand()
```

---

## OBD2 PIDs

| PID | Description | Every cycle |
|-----|-------------|-------------|
| `0111` | Throttle position (TPS) | ✅ |
| `010C` | Engine RPM | ✅ |
| `010D` | Vehicle speed | ✅ |
| `0104` | Engine load | ✅ |
| `010F` | Intake air temperature | ✅ |
| `010E` | Ignition advance | ✅ |
| `0105` | Coolant temperature | ✅ |
| `0106` | Short-term fuel trim | ✅ |
| `010B` | MAP pressure | ✅ |
| `015A` | Accelerator pedal position | ✅ |
| `0133` | Barometric pressure | every 10 cycles |
| `0101` | Monitor status / DTC count | every 10 cycles |
| `ATRV` | Battery voltage (keep-alive) | every 30 cycles |

Protocol: **ISO 15765-4 CAN 11bit/500kbps** (`ATSP6`), header `7E0` (`ATSH7E0`)

---

## Log Format

### CSV columns
```
timestamp,
ant_pressure_bar, ant_temp_c, ant_battery_pct, ant_alarm,
post_pressure_bar, post_temp_c, post_battery_pct, post_alarm,
tps_pct, engine_load_pct, iat_c, ignition_advance_deg, fuel_l100,
fuel_trim_pct, coolant_temp_c, speed_kmh, rpm, obd_connected,
g_lateral, gyro_x, gyro_y, gyro_z,
latitude, longitude, altitude_m, bearing_deg, gps_speed_kmh, gps_accuracy_m
```

### JSON structure
```json
{
  "timestamp": "2026-05-01 18:20:31.123",
  "tpms_ant":  { "pressure_bar": 1.87, "temp_c": 32.0, "battery_pct": 85, "alarm": false },
  "tpms_post": { "pressure_bar": 1.92, "temp_c": 34.0, "battery_pct": 82, "alarm": false },
  "obd": {
    "tps_pct": 14.1, "engine_load_pct": 27.0, "iat_c": 25.0,
    "ignition_advance_deg": 46.0, "fuel_l100": 4.2,
    "fuel_trim_pct": 1.5, "coolant_temp_c": 85.0,
    "speed_kmh": 63.0, "rpm": 4250.0,
    "map_kpa": 45.0, "accel_pedal_pct": 14.1, "baro_kpa": 99.0,
    "battery_voltage": 14.1, "dtc_count": 0, "mil_on": false, "connected": true
  },
  "sensors": {
    "g_lateral": -0.12,
    "gyro_x": 0.03,
    "gyro_y": -0.08,
    "gyro_z": 0.01,
    "latitude": 28.123, "longitude": -15.456,
    "altitude_m": 142.0,
    "bearing_deg": 273.5,
    "gps_speed_kmh": 63.2, "gps_accuracy_m": 4.0, "gps_available": true
  }
}
```

#### Gyroscope axes reference (phone vertical, screen facing you)
| Field | Axis | Meaning |
|-------|------|---------|
| `gyro_x` | X — roll | lean angle when mounted on handlebar |
| `gyro_y` | Y — pitch | braking / acceleration |
| `gyro_z` | Z — yaw | steering / direction change |

Values in **rad/s**. Which axis is meaningful depends on how you mount the phone.
All three are logged raw so you can decide in post-processing.

---

## Known Issues / TODO

- [ ] OBD adapter selection — currently hardcoded to `vLinker MC-IOS` by name. Plan: BLE scan with service UUID `000018f0` filter, device picker in settings, MAC saved to SharedPreferences
- [ ] `onCharacteristicChanged` deprecated on API 33+ — add new signature override
- [ ] Occasional GATT disconnect (status=0) after ~15–20 minutes — vLinker internal timeout suspected, auto-reconnect in place
- [ ] Starting the service from the tile requires "Allow all the time" location permission — on Android 14+ starting a `foregroundServiceType=location` service from background without this permission causes a crash
- [ ] UI layout redesign pending

---

## Permissions

```xml
BLUETOOTH_SCAN
        BLUETOOTH_CONNECT
        WAKE_LOCK
        FOREGROUND_SERVICE
        FOREGROUND_SERVICE_CONNECTED_DEVICE
        FOREGROUND_SERVICE_LOCATION
        POST_NOTIFICATIONS
        ACCESS_FINE_LOCATION
        ACCESS_COARSE_LOCATION
        ACCESS_BACKGROUND_LOCATION       <!-- required for GPS with screen off and tile usage without app -->
        WRITE_EXTERNAL_STORAGE           (maxSdkVersion 28)
        READ_EXTERNAL_STORAGE            (maxSdkVersion 32)
```

---

## License

Personal project — not affiliated with KTM or any hardware manufacturer.