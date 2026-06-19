# AirMouse Final Report and Assignment Questions

## Implementation Summary

The project turns an Android phone into an air mouse. The Android app reads phone motion sensors, applies calibration and filtering, detects movement/click/scroll gestures, sends UDP JSON packets to a laptop, and the laptop Python server moves the real mouse with PyAutoGUI.

Implemented behavior:

- Phone Z-axis rotation/movement maps to horizontal mouse movement.
- Phone X-axis rotation/movement maps to vertical mouse movement.
- Rapid left rotation around Y maps to left click.
- Rapid movement along Y maps to scroll up/down.
- Movement packets are relative deltas and may be dropped.
- Click/scroll packets are reliable and require ACK from the laptop.

## Main Files

- `FirstFragment.kt`: main runtime screen, sensor registration, UI status, unsupported-gyroscope guard, UDP lifecycle, and sensor callback dispatch.
- `SecondFragment.kt`: calibration screen for gyroscope, accelerometer, and magnetometer.
- `MousePacket.kt`: JSON-compatible packet model.
- `sensor/Vec3.kt`: vector math, deadband, clamp, finite-value checks, and safe `dt` helpers.
- `sensor/Quaternion.kt`: quaternion normalization, Hamilton product, conjugate, gyro integration, and Euler conversion.
- `sensor/SensorCalibration.kt`: applies gyro, accelerometer, and magnetometer calibration.
- `sensor/MadgwickAhrs.kt`: manually implemented AHRS filter with IMU and MARG update paths.
- `sensor/ComplementaryAhrs.kt`: simple fallback orientation filter.
- `sensor/HighPassGravityFilter.kt`: low-pass gravity estimate and linear acceleration extraction.
- `sensor/MotionProcessor.kt`: filtering, movement deltas, click/scroll decisions, deadband, cooldown, and packet creation.
- `network/UdpMouseClient.kt`: UDP send, ACK receive, retry, and bounded reliable queue.
- `laptop_server/air_mouse_server.py`: laptop UDP receiver and PyAutoGUI mouse control.

## Sensor Discovery and Unsupported Device Behavior

The app prefers uncalibrated/raw sensors:

- `TYPE_ACCELEROMETER_UNCALIBRATED`, falling back to `TYPE_ACCELEROMETER`.
- `TYPE_GYROSCOPE_UNCALIBRATED`, falling back to `TYPE_GYROSCOPE`.
- `TYPE_MAGNETIC_FIELD_UNCALIBRATED`, falling back to `TYPE_MAGNETIC_FIELD`.

The app requires a physical gyroscope. If neither uncalibrated nor normal gyroscope exists:

- AirMouse sensor processing is not started.
- UDP packets are not generated.
- Gyroscope calibration is disabled.
- The UI shows `Gyroscope: Missing / Unsupported`.
- The app displays: "This phone does not have a gyroscope. This AirMouse implementation requires a gyroscope, so this device is not supported."

## Threading and Timing

Sensors are registered only while AirMouse is running or while calibration is active. The app uses `SensorManager.SENSOR_DELAY_GAME`, which is suitable for interactive movement without requesting the maximum sampling rate.

`onSensorChanged()` is intentionally short. It copies:

- sensor type
- sensor timestamp in nanoseconds
- first three sensor values

Then it dispatches processing to a single background `ExecutorService`. UI updates are posted back to the main thread with a `Handler`. UDP send, receive, retry, and ACK handling run in `UdpMouseClient` background executor threads.

The filter uses real sensor timestamps:

```text
dt = (currentTimestampNanos - previousTimestampNanos) * 1e-9
```

Invalid or very large `dt` values are clamped to a safe fallback around 0.02 seconds.

## Calibration

### Gyroscope

The user keeps the phone still and collects stationary gyroscope samples. The average sample is stored as bias:

```text
correctedGyro = rawGyro - gyroBias
```

For uncalibrated gyroscope events, Android may also provide estimated drift in values 3-5, but this implementation uses its own stationary calibration bias for assignment correctness.

### Accelerometer

The user saves six physical phone positions. The implementation computes per-axis min/max from the saved samples:

```text
offset = (max + min) / 2
scale = (max - min) / 2
corrected = (raw - offset) * (9.80665 / scale)
```

This maps each axis approximately to +/- gravity. If six samples are incomplete or an axis scale is too small, the app falls back safely to raw acceleration and reports that accelerometer calibration is incomplete/invalid.

### Magnetometer

The user moves the phone in a figure-8 pattern. The app records min/max values and computes:

```text
offset = (max + min) / 2
scale = (max - min) / 2
corrected = (raw - offset) / scale
```

If figure-8 capture is incomplete or has tiny ranges, the app falls back safely and reports that magnetometer calibration is incomplete/invalid.

## Filtering and Sensor Fusion

The default filter mode is `MADGWICK`. The implementation manually maintains a quaternion initialized as identity:

```text
q = [1, 0, 0, 0]
```

The Madgwick implementation supports:

- IMU update: gyroscope + accelerometer.
- MARG update: gyroscope + accelerometer + magnetometer.

The gyroscope integrates angular velocity into the orientation quaternion. Accelerometer correction reduces roll/pitch drift by comparing the estimated gravity direction to measured gravity. Magnetometer correction improves heading/yaw when magnetic data is valid. The beta gain controls how strongly accel/mag correction pulls against pure gyro integration.

A complementary/simple fallback is also implemented. It integrates gyro orientation and blends roll/pitch with an accelerometer gravity estimate. Linear acceleration is computed by low-pass estimating gravity and subtracting it from corrected acceleration.

Perfetto trace markers were added:

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

Perfetto counters were added for sensor sample intervals, final movement deltas, packet sequence, and pending reliable packet count.

## Movement, Click, and Scroll

Cursor movement uses filtered, calibrated gyroscope rates:

```text
deltaX = filteredGyro.z * sensitivity
deltaY = -filteredGyro.x * sensitivity
```

The processor applies deadband, smoothing, clamping, and packet rate limiting. If both deltas are near zero, no movement packet is sent.

Click detection uses filtered gyroscope Y:

- Y rotation must cross the configured left-click threshold.
- Y must dominate X and Z.
- Cooldown must have passed.
- A scroll gesture must not be active at the same time.

Scroll detection uses corrected/smoothed linear acceleration Y, not raw accelerometer delta:

- `abs(linearAccel.y)` must exceed threshold.
- Y must dominate X and Z.
- total rotation must be below a suppression threshold.
- cooldown must have passed.
- click and scroll are mutually suppressed for a short period.

Each click or scroll gesture creates exactly one reliable packet per gesture window.

## UDP Protocol

Android sends JSON UDP packets to the laptop IP entered in the app. Default port is 5000.

Example:

```json
{
  "seq": 12,
  "timestamp": 1717420000000,
  "sensorTimestampNanos": 123456789,
  "createdElapsedRealtimeNanos": 9876543210,
  "sentElapsedRealtimeNanos": 9876550000,
  "deltaX": 3.5,
  "deltaY": -1.2,
  "click": false,
  "scroll": 0,
  "requiresAck": false
}
```

Movement packets are non-critical. They are sent once and may be dropped.

Click and scroll packets are reliable. The laptop sends:

```json
{"ack": 12}
```

The Android client stores reliable packets in a bounded queue, retries them for a limited time, and drops them after the retry limit to prevent unlimited ACK backlog when the laptop is disconnected.

## Laptop Receiver

`laptop_server/air_mouse_server.py` binds UDP on `0.0.0.0:5000` by default. It parses JSON packets and uses PyAutoGUI:

- `pyautogui.moveRel(deltaX, deltaY, duration=0)`
- `pyautogui.click(button="left")`
- `pyautogui.scroll(scroll * scroll_scale)`

It supports `--host`, `--port`, `--move-scale`, `--scroll-scale`, and `--dry-run`.

## Run Instructions

1. Install laptop dependency: `pip install pyautogui`
2. Run the laptop server: `python laptop_server/air_mouse_server.py --port 5000`
3. Find laptop IP on Windows: `ipconfig`
4. Build Android: `.\gradlew.bat assembleDebug`
5. Install/run the app on a physical phone with a gyroscope.
6. Calibrate gyro while the phone is still.
7. Capture six accelerometer positions.
8. Capture magnetometer figure-8.
9. Enter laptop IP in the app.
10. Press `START MOUSE`.
11. Test movement, click, and scroll.

## Assignment Questions Q1-Q11

### Q1. From the time a request is made to read data from a sensor until the data is received, what happens at the operating-system level? Explain and justify your answer using Perfetto output. You may use Perfetto UI for this question.

### Q2. Explain the function of the sensors used. Explain why raw sensors have errors and how combining these sensors helps reduce error.

The accelerometer measures specific force on the phone axes. It is useful for estimating gravity direction and detecting linear acceleration for scroll gestures. However, raw accelerometer readings include gravity, user motion, noise, scale error, and offset.

The gyroscope measures angular velocity. It is useful for smooth relative cursor movement and detecting rapid Y-axis rotation for click. Gyroscopes have bias and drift: even when the phone is still, the reading may not be exactly zero, and integration over time accumulates error.

The magnetometer measures the local magnetic field. It helps correct heading/yaw drift in MARG mode, but it is affected by hard-iron distortion, soft-iron distortion, nearby electronics, and environmental magnetic noise.

The implementation reduces these errors by calibration and fusion:

- Gyro stationary bias is subtracted from live gyro readings.
- Accelerometer six-position calibration corrects offset and scale.
- Magnetometer figure-8 calibration corrects hard-iron offset and approximate scale.
- Madgwick AHRS integrates gyro orientation while using accelerometer gravity direction to reduce roll/pitch drift.
- In MARG mode, magnetometer data helps reduce heading drift.
- Complementary fallback uses low-pass gravity and high-pass linear acceleration to separate gravity from movement.

### Q3. In Perfetto, compare the time between reading two consecutive sensor data samples with the sampling period configured in your code.

### Q4. When using system calls, is there contention between time-sensitive activities, such as sensor updates, and heavy processing, such as graphical rendering, where one thread must wait until another thread finishes? Justify your answer.

### Q5. What is the difference between defining a sensor as wake-up and non-wake-up? What are the advantages and disadvantages of each?

Wake-up sensors can wake the application processor when an event occurs while the device is asleep. They are useful for alarms, step detection, significant motion, and background features that must react even when the screen or CPU is suspended. Their disadvantage is higher power impact because they may wake the device more often.

Non-wake-up sensors do not wake the application processor. Events may be delivered only while the CPU is awake, or they may be batched depending on hardware and Android behavior. Their advantage is lower power usage. Their disadvantage is that events can be delayed or unavailable while the device sleeps.

This AirMouse app is interactive and runs while the user is actively holding the phone, so it uses normal `SensorManager` registration while the UI is active. The exact wake-up/non-wake-up type exposed by the tested phone should be confirmed from the device sensor list or Perfetto/device diagnostics.

### Q6. Calculate the average amount of time that the filter function executes on the CPU.

### Q7. Which sensor has the highest processing consumption?

### Q8. What effect does the sensor sampling rate have on other system processing?

### Q9. From the time sensor information is ready until it is sent to the server/computer and cursor movement is observed, what is the average time consumed?

### Q10. How are the main threads, sensor threads, communication threads, processing threads, and UI threads separated? Check what type of events each sensor produces, which threads execute these events, and what effect they have on the Main Thread.

Thread separation in this implementation:

- Main/UI thread: button clicks, text labels, debug square updates, navigation, AlertDialog.
- Android sensor callback path: Android invokes `onSensorChanged()` for accelerometer, gyroscope, and magnetometer events. The callback copies data and returns quickly.
- Sensor processing executor: calibration correction, Madgwick/complementary filtering, gravity removal, movement deltas, click/scroll detection.
- UDP client executor threads: packet send, ACK receive loop, reliable packet retry/drop.
- Python laptop process: UDP receive, JSON parsing, PyAutoGUI mouse actions, ACK send.

Sensor event types:

- Accelerometer events update raw/corrected acceleration, gravity estimate, and linear acceleration.
- Gyroscope events update raw/corrected gyro, AHRS orientation, movement deltas, click detection, and packet creation.
- Magnetometer events update raw/corrected magnetic data used by MARG heading correction.

Effect on Main Thread:

- Sensor filtering does not run directly on the main thread.
- Network I/O does not run on the main thread.
- UI updates are posted back to the main thread and rate-limited.
- The main thread can still be affected by too many UI updates, so the app throttles debug updates.

Perfetto still needs to confirm exact scheduling and blocking on the target phone.

### Q11. What is the difference between slow and sudden movement of the mobile phone in the performed processing? Is mouse cursor movement latency different in the two cases?
