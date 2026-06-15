# AirMouse CPS Assignment 2

Android/Kotlin AirMouse homework project. The app reads phone motion sensors, applies calibration/filtering, sends mouse-control packets over UDP, and a Python laptop receiver controls the real cursor with PyAutoGUI.

## Implemented

- Main Android AirMouse UI with raw/corrected sensor debug values.
- Calibration page for gyro stationary bias, accelerometer six-position capture, and magnetometer figure-8 min/max capture.
- Unsupported-phone guard when no physical gyroscope is available.
- Calibration applied to live gyro, accelerometer, and magnetometer readings.
- Manual Madgwick AHRS with IMU and MARG paths plus a complementary/simple fallback implementation.
- Gravity removal and smoothed linear acceleration for scroll detection.
- Click/scroll detection with deadband, axis dominance, gesture cooldown, and false-positive suppression.
- Relative movement packets from filtered gyro X/Z.
- Android UDP sender with ACK handling for click/scroll packets.
- Python laptop UDP receiver with PyAutoGUI and dry-run mode.
- Perfetto trace markers for sensor copy, calibration, filter, motion processing, and UDP send sections.

## Android Run

Build/install with the existing Gradle project configuration. Do not change Gradle or dependency versions for this homework.

Typical debug build:

```powershell
.\gradlew.bat assembleDebug
```

In the app:

1. Open the calibration screen.
2. Keep the phone still and collect gyroscope bias.
3. Save six accelerometer positions.
4. Capture magnetometer figure-8 data.
5. Return to the main screen.
6. Enter the laptop IPv4 address.
7. Press `START MOUSE`.

## Laptop Server

Install:

```bash
pip install pyautogui
```

Dry-run first:

```bash
python laptop_server/air_mouse_server.py --dry-run
```

Run for real cursor control:

```bash
python laptop_server/air_mouse_server.py --port 5000
```

Find the laptop IP on Windows:

```powershell
ipconfig
```

The phone and laptop must be on the same Wi-Fi/network. Windows Firewall may need to allow Python UDP traffic.

## Packet Protocol

Android sends JSON UDP packets like:

```json
{
  "seq": 12,
  "timestamp": 1717420000000,
  "sensorTimestampNanos": 123456789,
  "deltaX": 3.5,
  "deltaY": -1.2,
  "click": false,
  "scroll": 0,
  "requiresAck": false
}
```

Movement packets are non-critical and may be dropped. Click and scroll packets set `requiresAck: true`; the laptop replies:

```json
{"ack": 12}
```

## Documentation

- Current implementation details: `docs/IMPLEMENTATION_STATUS.md`
- Full implementation report and Q1-Q11 answers: `docs/REPORT_AND_QUESTIONS.md`
- Laptop receiver instructions: `laptop_server/README.md`

## Remaining TODOs

- Tune thresholds and sensitivity on the final physical phone.
- Confirm click/scroll signs with the user's real hand posture.
- Capture Perfetto traces and compute actual filter execution time.
- Record the final demo video and measured behavior.
