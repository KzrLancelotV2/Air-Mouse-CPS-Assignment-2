# AirMouse CPS Assignment 2 Final Report

## Project Summary

AirMouse turns an Android phone into a wireless mouse. The Android app reads accelerometer, gyroscope, and magnetometer data, applies calibration and filtering, generates relative mouse movement plus click/scroll gestures, and sends JSON packets over UDP to a laptop. The Python laptop receiver parses the packets and applies the requested action with PyAutoGUI.

The implementation uses relative cursor deltas, not absolute screen coordinates. Movement packets are best-effort UDP packets. Click and scroll packets are reliable packets that require an ACK from the laptop server.

## Implemented Features

- Main Android runtime screen with sensor status, calibration status, UDP status, packet debug text, and a movement preview square.
- Calibration screen for gyroscope stationary bias, accelerometer six-position calibration, and magnetometer figure-8 min/max calibration.
- Preference for uncalibrated Android sensors with fallback to calibrated Android sensors when raw sensors are unavailable.
- Unsupported-device guard for phones without a gyroscope.
- Manual Madgwick AHRS implementation with IMU and MARG paths.
- Complementary AHRS fallback implementation.
- Gravity removal by low-pass gravity estimation and linear acceleration extraction.
- Gyroscope-based cursor movement with deadband, nonlinear precision curve, smoothing, axis leakage suppression, clamping, and packet rate limiting.
- Click detection from dominant Y-axis gyroscope rotation.
- Scroll detection from dominant Y-axis linear acceleration.
- Gesture cooldown/freeze logic so click/scroll gestures do not fight cursor movement.
- UDP JSON packet sender with ACK receive, reliable retry, bounded pending reliable queue, and clear debug status.
- Python UDP server with real mode, dry-run mode, gesture debug mode, movement scaling, stronger scroll burst application, and optional timing logs.
- Perfetto trace sections, counters, and thread names for measurement.

## Important Source Files

- `app/src/main/java/com/example/airmouse/FirstFragment.kt`: main runtime screen, sensor registration, sensor callback copy, preview UI, packet dispatch, and UDP lifecycle.
- `app/src/main/java/com/example/airmouse/SecondFragment.kt`: calibration UI and calibration sample collection.
- `app/src/main/java/com/example/airmouse/MousePacket.kt`: JSON-compatible packet model.
- `app/src/main/java/com/example/airmouse/TraceUtils.kt`: lightweight trace-section, trace-counter, sensor-name, and thread-name helpers.
- `app/src/main/java/com/example/airmouse/sensor/SensorCalibration.kt`: runtime gyro, accelerometer, and magnetometer correction.
- `app/src/main/java/com/example/airmouse/sensor/MadgwickAhrs.kt`: manual Madgwick filter.
- `app/src/main/java/com/example/airmouse/sensor/ComplementaryAhrs.kt`: complementary fallback filter.
- `app/src/main/java/com/example/airmouse/sensor/HighPassGravityFilter.kt`: gravity estimate and linear acceleration extraction.
- `app/src/main/java/com/example/airmouse/sensor/MotionProcessor.kt`: movement deltas, click/scroll decisions, packet creation, and debug details.
- `app/src/main/java/com/example/airmouse/network/UdpMouseClient.kt`: UDP send, ACK receive, retry, bounded reliable queue, and network trace markers.
- `laptop_server/air_mouse_server.py`: laptop UDP receiver and PyAutoGUI action layer.
- `tools/analyze_airmouse_perfetto.py`: helper script for Perfetto trace summaries.
- `perfetto/airmouse_config.pbtx`: Perfetto config for app trace sections, scheduling, CPU frequency, CPU idle, process stats, and system stats.

## Build and Run

Build the Android debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Install it on a connected Android phone:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Install the laptop dependency:

```powershell
pip install pyautogui
```

Run the laptop server in real mouse-control mode:

```powershell
python laptop_server\air_mouse_server.py --port 5000
```

Run without applying mouse actions:

```powershell
python laptop_server\air_mouse_server.py --dry-run
```

Run with movement applied but click/scroll printed only:

```powershell
python laptop_server\air_mouse_server.py --debug
```

Run with timing logs for end-to-end measurement:

```powershell
python laptop_server\air_mouse_server.py --measure-timing
```

Find the laptop IPv4 address on Windows:

```powershell
ipconfig
```

In the Android app:

1. Open the calibration screen.
2. Keep the phone still and collect gyroscope bias.
3. Capture six accelerometer positions.
4. Capture magnetometer figure-8 data.
5. Return to the main screen.
6. Enter the laptop IPv4 address.
7. Press `START MOUSE`.

If packets do not arrive at the laptop, Windows Firewall must allow Python UDP traffic on the active network.

## Sensor Selection

The app requests these sensors:

- Accelerometer: `TYPE_ACCELEROMETER_UNCALIBRATED`, fallback `TYPE_ACCELEROMETER`.
- Gyroscope: `TYPE_GYROSCOPE_UNCALIBRATED`, fallback `TYPE_GYROSCOPE`.
- Magnetometer: `TYPE_MAGNETIC_FIELD_UNCALIBRATED`, fallback `TYPE_MAGNETIC_FIELD`.

The app requires a gyroscope. If no gyroscope is available, AirMouse processing is not started and no mouse packets are generated.

Sensors are registered with:

```kotlin
SensorManager.SENSOR_DELAY_GAME
```

This is an interactive sampling rate suitable for a hand-controlled pointer without requesting the fastest possible sampling rate.

## Calibration

### Gyroscope

The user keeps the phone still. The app collects stationary gyroscope samples and stores the mean value as bias:

```text
correctedGyro = rawGyro - gyroBias
```

### Accelerometer

The user captures six physical phone orientations. For each axis:

```text
offset = (max + min) / 2
scale = (max - min) / 2
corrected = (raw - offset) * (9.80665 / scale)
```

Invalid accelerometer calibration is not saved as valid calibration. If validation fails, the button remains usable as a retry path, previous samples are cleared on retry, and the user can capture all six positions again.

### Magnetometer

The user moves the phone in a figure-8. The app tracks min/max values:

```text
offset = (max + min) / 2
scale = (max - min) / 2
corrected = (raw - offset) / scale
```

If a range is too small, the magnetometer calibration is rejected and the raw value is used safely.

## Sensor Calibration and Gesture Instructions

#### 1. Gyroscope Calibration

For gyroscope calibration:

- Put the phone on a table.
- Keep it completely still.
- Do not touch or move the phone.
- Press `Calibrate`.

That is all.

The gyroscope calibration must be done while the phone is fully stable.

#### 2. Accelerometer Six-Direction Calibration

For accelerometer calibration, capture the phone in six different physical directions.

Use these six positions:

- Phone flat, screen facing up
- Phone flat, screen facing down
- Phone standing on its right edge
- Phone standing on its left edge
- Phone standing on its top edge
- Phone standing on its bottom edge

For each position:

- Place the phone in that direction.
- Wait about 2 seconds until it becomes stable.
- Press `Save` or `Capture`.
- Do not move the phone while the sample is being recorded.

If the calibration becomes invalid, it is usually because:

- the phone moved during sampling
- the six directions were not actually different
- the same direction was captured twice
- the phone was held by hand and hand vibration affected the sample

#### 3. Magnetometer Figure-8 Calibration

For magnetometer calibration, use the figure-8 movement.

Steps:

- Press `Start Magnetometer Figure-8 Capture`.
- Hold the phone in your hand, not on the table.
- Move the phone in the air as if you are drawing a large 8 shape.
- While moving it, slowly rotate your wrist so the phone faces different angles.
- Continue the movement for about 10 to 20 seconds.
- Press the magnetometer button again to stop the capture.

Think of it like this:

Draw a big 8 in the air while slowly twisting the phone around.

Do not only move the phone flat in one direction. The phone must face different angles during the movement, such as:

- screen facing up
- screen facing slightly down
- left edge tilted up
- right edge tilted up
- top edge tilted
- bottom edge tilted

The goal is not to draw one perfect 8. The goal is to expose the magnetometer to different orientations so it can record the minimum and maximum values on the X, Y, and Z axes. Then the app uses these values to calculate the magnetometer offset and scale.

#### 4. Mouse Movement Gestures

Hold the phone like a flat tray.

- Screen facing up
- Top side of the phone pointing toward the monitor

Move Mouse Left and Right

To move the mouse left and right:

Rotate the phone slightly like a steering wheel.

That means rotating it clockwise or counterclockwise while keeping it mostly flat in your hand.

Move Mouse Up and Down

To move the mouse up and down:

Tilt the top side of the phone slightly up or down.

It is like pointing the top of the phone a little higher or lower toward the monitor.

Click Gesture

For click:

- Do not push the phone forward or backward.
- Instead, quickly twist the phone for a moment, like turning a door handle.

That means:

- the left edge goes up and the right edge goes down

or

- the right edge goes up and the left edge goes down

This quick twisting motion is detected as a click.

Scroll Gesture

For scroll:

- Do not twist the phone.
- Move the whole phone quickly along its own length.

If the top side of the phone is pointing toward the monitor:

- quickly push the phone toward the monitor
- or quickly pull the phone back toward yourself

This forward/backward movement is detected as scroll.

## Filtering and Movement

The default filter mode is Madgwick. The filter maintains a quaternion initialized as:

```text
q = [1, 0, 0, 0]
```

Madgwick IMU mode uses gyroscope plus accelerometer. MARG mode uses gyroscope, accelerometer, and magnetometer when magnetometer data is valid. The gyroscope integrates angular velocity, accelerometer correction reduces roll/pitch drift, and magnetometer correction reduces heading drift.

The complementary fallback integrates gyro orientation and blends roll/pitch with accelerometer gravity.

Cursor movement uses filtered gyroscope rates:

```text
horizontalRate = correctedGyro.z * HORIZONTAL_SIGN
verticalRate = correctedGyro.x * VERTICAL_SIGN
deltaX = precisionCurve(horizontalRate) * HORIZONTAL_SENSITIVITY
deltaY = precisionCurve(verticalRate) * VERTICAL_SENSITIVITY
```

Current signs:

```text
HORIZONTAL_SIGN = -1
VERTICAL_SIGN = -1
SCROLL_SIGN = -1
CLICK_SIGN = -1
```

Current main movement constants:

```text
HORIZONTAL_SENSITIVITY = 12.4
VERTICAL_SENSITIVITY = 12.4
MOVEMENT_GYRO_DEADBAND = 0.012
PRECISION_ZONE_RAD_PER_SEC = 0.12
LOW_SPEED_GAIN = 0.36
HIGH_SPEED_GAIN = 1.00
RESPONSE_EXPONENT = 1.45
MAX_DELTA = 12.0
MOVEMENT_PACKET_INTERVAL_NANOS = 16 ms
```

Click detection uses dominant Y-axis gyro rotation:

```text
CLICK_THRESHOLD_RAD_PER_SEC = 2.25
CLICK_CANDIDATE_THRESHOLD_RAD_PER_SEC = 1.25
CLICK_FREEZE_NANOS = 95 ms
CLICK_MAX_SETTLE_NANOS = 300 ms
```

Scroll detection uses dominant Y-axis linear acceleration with peak and impulse support:

```text
SCROLL_UP_ACCEL_THRESHOLD = 1.72
SCROLL_UP_PEAK_THRESHOLD = 1.82
SCROLL_UP_IMPULSE_THRESHOLD = 0.105
SCROLL_DOWN_ACCEL_THRESHOLD = 2.05
SCROLL_DOWN_PEAK_THRESHOLD = 2.15
SCROLL_DOWN_IMPULSE_THRESHOLD = 0.13
SCROLL_WINDOW_NANOS = 140 ms
SCROLL_FREEZE_NANOS = 240 ms
SCROLL_MAX_SETTLE_NANOS = 750 ms
```

Click and scroll packets are reliable. Normal movement packets are not reliable because stale cursor movement should not be retried.

## UDP Packet Format

Android sends JSON UDP packets to the laptop IP and port 5000 by default:

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

`sentElapsedRealtimeNanos` is added at send time. It is present in transmitted packets and may be absent from packet text displayed before send.

Reliable click/scroll packets set `requiresAck: true`. The laptop replies:

```json
{"ack": 12}
```

The Android client stores reliable packets in a bounded queue and retries them until ACK or retry limit.

## Laptop Receiver

The Python server binds to `0.0.0.0:5000` by default.

Movement:

```python
pyautogui.moveRel(deltaX * move_scale, deltaY * move_scale, duration=0)
```

Click:

```python
pyautogui.click(button="left")
```

Scroll:

```python
pyautogui.scroll(scroll_step)
```

The server applies each Android scroll packet as a scroll burst. Defaults:

```text
move_scale = 10.0
scroll_scale = 180
scroll_steps = 10
scroll_duration = 0.22 s
```

The ACK is sent before applying click/scroll/move action so long PyAutoGUI actions do not delay reliable ACK delivery.

## Threading Model

Main/UI thread:

- Button clicks.
- Navigation.
- Text/status updates.
- Movement preview square updates.
- Dialogs.

Sensor callback path:

- `onSensorChanged()` copies sensor type, sensor timestamp, and first three sensor values.
- The callback returns quickly.
- Runtime processing is dispatched to a single background executor.

Sensor processing thread:

- Named `AirMouse-SensorProc-1`.
- Applies calibration correction.
- Updates gravity filtering and sensor fusion.
- Computes cursor deltas.
- Detects click/scroll gestures.
- Creates packets.

UDP threads:

- `AirMouse-UdpSend`: sends JSON packets.
- `AirMouse-AckRecv`: receives and parses ACK packets.
- `AirMouse-Retry`: retries pending reliable packets.

Python process:

- Receives UDP packets.
- Parses JSON.
- Sends ACKs for reliable packets.
- Applies PyAutoGUI movement/click/scroll.

## Perfetto Instrumentation

Current trace sections:

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
- `AirMouse_UDPSend`
- `AirMouse_UDP_AckReceive`
- `AirMouse_UDP_Retry`
- `AirMouse_UI_StatusUpdate`
- `AirMouse_UI_PreviewUpdate`

Current trace counters:

- `AirMouse_GyroDtMs`
- `AirMouse_AccelDtMs`
- `AirMouse_MagDtMs`
- `AirMouse_MotionPacketSeq`
- `AirMouse_PendingReliablePackets`
- `AirMouse_FinalDeltaX`
- `AirMouse_FinalDeltaY`

`AirMouse_FinalDeltaX` and `AirMouse_FinalDeltaY` are stored as delta times 1000 because Perfetto counters are integer values.

Capture command:

```powershell
python record_android_trace -o trace_file.perfetto-trace -t 10s sched freq idle wm gfx view app
```

The `app` category is required for Android app trace sections.

Config-based capture, if the local trace tool accepts a textproto config:

```powershell
python record_android_trace -c perfetto\airmouse_config.pbtx -o airmouse_trace.perfetto-trace
```

Trace analysis helper:

```powershell
python tools\analyze_airmouse_perfetto.py trace_file.perfetto-trace
```

For PC-side timing logs:

```powershell
python laptop_server\air_mouse_server.py --measure-timing
```

## Assignment Questions

### Q1. From the time a request is made to read data from a sensor until the data is received, what happens at the operating-system level? Explain and justify your answer using Perfetto output.

The app does not synchronously request each sample. It registers a `SensorEventListener` with Android `SensorManager`. After registration, the Android sensor framework configures the requested sensors and sampling delay. The sensor hardware and sensor HAL produce samples, Android delivers those samples through the sensor service/framework, and the app receives them through `onSensorChanged()`.

In this implementation, `onSensorChanged()` performs only a short copy of the sensor type, sensor timestamp, and first three values. That copy is traced as `AirMouse_SensorEvent_Copy` and also as `AirMouse_SensorEvent_ACCEL`, `AirMouse_SensorEvent_GYRO`, or `AirMouse_SensorEvent_MAG`. Heavier processing is then dispatched to `AirMouse-SensorProc-1`.

Perfetto evidence:


### Q2. Explain the function of the sensors used. Explain why raw sensors have errors and how combining these sensors helps reduce error.

The accelerometer measures specific force on the phone axes. It is used for gravity direction, accelerometer calibration, and linear acceleration used by scroll detection. Raw accelerometer readings include gravity, user motion, sensor noise, offset error, scale error, and vibration.

The gyroscope measures angular velocity. It is used for cursor movement, click detection, and orientation integration. Raw gyroscope readings have bias and drift; even when the phone is still, readings may not be exactly zero.

The magnetometer measures the local magnetic field. It is used to reduce heading/yaw drift in MARG fusion mode. Raw magnetometer readings are affected by hard-iron distortion, soft-iron distortion, nearby electronics, and environmental magnetic noise.

Combining sensors reduces error because gyroscope integration is smooth and responsive but drifts over time, accelerometer gravity is noisy during motion but gives roll/pitch reference, and magnetometer gives an external heading reference when the magnetic field is usable. Calibration removes static bias/scale errors before fusion.

### Q3. In Perfetto, compare the time between reading two consecutive sensor data samples with the sampling period configured in your code.

Configured sampling mode: `SensorManager.SENSOR_DELAY_GAME`.

Measured accelerometer interval:

Measured gyroscope interval:

Measured magnetometer interval:

Comparison:


### Q4. When using system calls, is there contention between time-sensitive activities such as sensor updates and heavy processing such as graphical rendering, where one thread must wait until another thread finishes?

The implementation separates heavy work from the main thread. Sensor callbacks copy data quickly, processing runs on `AirMouse-SensorProc-1`, UDP work runs on UDP executor threads, and UI work is posted to the main thread and throttled.

Perfetto evidence:


### Q5. What is the difference between defining a sensor as wake-up and non-wake-up? What are the advantages and disadvantages of each?

Wake-up sensors can wake the application processor when an event occurs while the device is asleep. They are useful for background features such as alarms, step detection, significant motion, and health tracking. Their disadvantage is higher power impact because they can wake the device.

Non-wake-up sensors do not wake the application processor. Their events are delivered while the CPU is awake or may be batched depending on hardware and Android behavior. Their advantage is lower power usage. Their disadvantage is that events may be delayed or unavailable while the device sleeps.

AirMouse is an interactive foreground app, so it uses regular sensor registration while the UI is active. This is appropriate because mouse control requires the screen/app to be active and latency matters more than background wake-up behavior.

### Q6. Calculate the average amount of time that the filter function executes on the CPU.

Filter trace section:

Average duration:

Minimum duration:

Maximum duration:

95th percentile duration:

CPU-time note:


### Q7. Which sensor causes the highest processing load?

Measured processing load by sensor:

Highest-load sensor:

Reason:


### Q8. What effect does the sensor sampling rate have on other system processing?

Sampling mode tested:

Observed scheduling/CPU effect:

Effect on UI/rendering:

Effect on other processes:


### Q9. From the time sensor information is ready until it is sent to the server/computer and cursor movement is observed, what is the average time consumed?

Android-side latency can be measured from sensor event trace sections and packet timestamps to `AirMouse_UDPSend`.

Full PC cursor latency uses Android packet timestamps plus Python `--measure-timing` logs.

Average Android sensor-to-send latency:

Average server receive-to-action latency:

Average end-to-end latency:


### Q10. How are the main threads, sensor threads, communication threads, processing threads, and UI threads separated?

The main thread handles UI events and rendering. Sensor callbacks arrive through Android's sensor delivery path and perform only a fast copy. Sensor processing is run by the single `AirMouse-SensorProc-1` executor thread. UDP sending, ACK receiving, and retry processing run separately as `AirMouse-UdpSend`, `AirMouse-AckRecv`, and `AirMouse-Retry`.

Accelerometer events update raw/corrected acceleration, gravity estimate, linear acceleration, and scroll input state. Gyroscope events update raw/corrected gyro, filter orientation, cursor movement, click detection, and packet creation. Magnetometer events update raw/corrected magnetic field for MARG heading correction.

This separation keeps sensor filtering and network I/O off the main thread. The UI thread receives throttled status and preview updates.

Perfetto evidence:


### Q11. What is the difference between slow and sudden movement of the mobile phone in the performed processing? Is mouse cursor movement latency different in the two cases?

Slow movement usually produces smaller gyroscope rates. The precision curve applies deadband and lower gain near zero, so small intentional movement becomes small cursor deltas and tiny tremor is suppressed.

Sudden movement produces larger gyroscope rates or linear acceleration spikes. Cursor movement is clamped by `MAX_DELTA`, while click and scroll gestures can suppress cursor movement and emit reliable gesture packets.

Measured slow-movement latency:

Measured sudden-movement latency:

Comparison:

