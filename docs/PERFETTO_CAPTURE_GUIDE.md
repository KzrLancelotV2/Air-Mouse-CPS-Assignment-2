# AirMouse Perfetto Capture Guide

This guide captures traces for the assignment questions using real device measurements instead of visual guessing.

## One-Time Setup

1. Enable Developer Options and USB Debugging on the Android phone.
2. Connect the phone over USB and verify ADB sees it:

```powershell
adb devices
```

3. Build and install the debug APK:

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. Start the AirMouse app.
5. Calibrate:
   - Gyroscope: keep the phone still.
   - Accelerometer: capture six physical orientations.
   - Magnetometer: capture a figure-8 motion.
6. Return to the main screen, enter the PC IP if you are testing UDP, and press `START MOUSE`.

## Capture With Assignment Command

The assignment command is useful for quick 10 second traces:

```powershell
python record_android_trace -o trace_file.perfetto-trace -t 10s sched freq idle wm gfx view app
```

The `app` category is important because AirMouse custom sections use `android.os.Trace.beginSection()`.

## Capture With Repo Config

This repo also includes:

```text
perfetto/airmouse_config.pbtx
```

Use it with a Perfetto/record script that accepts a textproto config. Example shape:

```powershell
python record_android_trace -c perfetto\airmouse_config.pbtx -o airmouse_trace.perfetto-trace
```

If your local `record_android_trace` does not support `-c`, use the assignment command above and include the `app` category.

## Test Scenarios

Capture separate traces when possible. Short focused traces are easier to analyze than one long mixed trace.

1. Stationary phone for 10 seconds.
2. Slow smooth cursor movement for 10 seconds.
3. Sudden phone movement for 10 seconds.
4. Click gestures for 10 seconds.
5. Scroll gestures for 10 seconds.
6. Optional UDP/server run for end-to-end timing.

For UDP timing, run the Python server with measurement logs:

```powershell
python laptop_server\air_mouse_server.py --measure-timing
```

Use `--dry-run --measure-timing` if you want packet timing without applying mouse actions.

## Trace Markers To Look For

- `AirMouse_SensorEvent_Copy`
- `AirMouse_SensorEvent_ACCEL`
- `AirMouse_SensorEvent_GYRO`
- `AirMouse_SensorEvent_MAG`
- `AirMouse_Calibration_Apply`
- `AirMouse_GyroCalibration`
- `AirMouse_AccelCalibration`
- `AirMouse_MagCalibration`
- `AirMouse_GravityFilter`
- `AirMouse_Madgwick_Update`
- `AirMouse_Complementary_Update`
- `AirMouse_MotionProcessor`
- `AirMouse_CursorDelta`
- `AirMouse_ClickDetect`
- `AirMouse_ScrollDetect`
- `AirMouse_PacketCreate`
- `AirMouse_UDPSend`
- `AirMouse_UDP_AckReceive`
- `AirMouse_UDP_Retry`
- `AirMouse_UI_StatusUpdate`
- `AirMouse_UI_PreviewUpdate`

Useful counters:

- `AirMouse_GyroDtMs`
- `AirMouse_AccelDtMs`
- `AirMouse_MagDtMs`
- `AirMouse_MotionPacketSeq`
- `AirMouse_PendingReliablePackets`
- `AirMouse_FinalDeltaX`
- `AirMouse_FinalDeltaY`

`AirMouse_FinalDeltaX` and `AirMouse_FinalDeltaY` are stored as delta times 1000 so fractional movement is preserved in integer Perfetto counters.

## Analysis

Use Perfetto TraceProcessor or the helper script:

```powershell
python tools\analyze_airmouse_perfetto.py trace_file.perfetto-trace
```

The script reports slice counts and wall durations for AirMouse markers, sensor interval counters if present, AirMouse thread names, and a basic scheduling summary.

Numerical answers should come from TraceProcessor/Python analysis, not only manual visual inspection in the Perfetto UI.

## Latency Limitation

Android-only Perfetto can measure from Android sensor callback/processing to `AirMouse_UDPSend`. It cannot prove full laptop cursor movement latency by itself. For Q9 full end-to-end latency, combine Android Perfetto with Python `--measure-timing` receive/action timestamps.
