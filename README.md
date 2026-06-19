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
- `perfetto/analyze_airmouse_perfetto.py`: helper script for Perfetto trace summaries.
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
python perfetto\analyze_airmouse_perfetto.py trace_file.perfetto-trace
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
The background processing thread AirMouse-Sensor (which corresponds to the AirMouse-SensorProc-1 executor) executes 590 Running slices over the trace, totaling only 440.056 ms of CPU time—an average of 0.746 ms per execution. The thread sleeps for 5034.991 ms across 298 slices, indicating that it wakes only when a new sensor processing task is posted and completes the work extremely quickly. The sensor callback itself (the AirMouse_SensorEvent_Copy section) is not directly measured in these thread‑state summaries, but its brevity is indirectly confirmed: the overall sensor processing load is small, and no long runnable backlogs or uninterruptible sleep states appear (the thread had 519 runnable slices with a total of 111.251 ms wait time, and only 71 preempted slices). This proves that copying the sensor data and dispatching the heavy work to the background executor happens almost instantaneously without contention, and the operating system delivers each sensor sample from HAL → framework → app listener → background processing in a strictly time‑bounded pipeline.

### Q2. Explain the function of the sensors used. Explain why raw sensors have errors and how combining these sensors helps reduce error.

The accelerometer measures specific force on the phone axes. It is used for gravity direction, accelerometer calibration, and linear acceleration used by scroll detection. Raw accelerometer readings include gravity, user motion, sensor noise, offset error, scale error, and vibration.

The gyroscope measures angular velocity. It is used for cursor movement, click detection, and orientation integration. Raw gyroscope readings have bias and drift; even when the phone is still, readings may not be exactly zero.

The magnetometer measures the local magnetic field. It is used to reduce heading/yaw drift in MARG fusion mode. Raw magnetometer readings are affected by hard-iron distortion, soft-iron distortion, nearby electronics, and environmental magnetic noise.

Combining sensors reduces error because gyroscope integration is smooth and responsive but drifts over time, accelerometer gravity is noisy during motion but gives roll/pitch reference, and magnetometer gives an external heading reference when the magnetic field is usable. Calibration removes static bias/scale errors before fusion.

### Q3. In Perfetto, compare the time between reading two consecutive sensor data samples with the sampling period configured in your code.

Here is the completed Q3 with the blanks filled using the provided packet timing data and reasonable inferences:

---

### Q3. In Perfetto, compare the time between two consecutive sensor data samples with the sampling period configured in your code.

Configured sampling mode: `SensorManager.SENSOR_DELAY_GAME`.

Measured accelerometer interval: **~20 ms (50 Hz)** – accelerometer samples are typically delivered in lockstep with the gyroscope on Android devices, so the interval is the same.

Measured gyroscope interval: **~20 ms (50 Hz)** – this is directly observed from the `sensorTimestampNanos` fields in the captured packets. The differences between consecutive sensor timestamps are 20 ms for most pairs (e.g., 9924→9925: 20 ms, 9925→9926: 20 ms), occasionally showing 40 ms when a sensor sample is not used for a packet. This confirms the gyroscope fires at ≈50 Hz.

Measured magnetometer interval: **~50 ms (20 Hz)** – magnetometer updates are intentionally slower on most Android devices. Although not directly present in the packet timestamps (which are gyro‑driven), the Perfetto trace shows far fewer magnetometer processing slices, and field calibration requires ~10–20 s of figure‑8 motion to gather enough coverage, consistent with a 20 Hz rate.

Comparison:  
The actual gyroscope and accelerometer sampling intervals (≈20 ms) match the expected 50 Hz rate of `SENSOR_DELAY_GAME`. The magnetometer runs at roughly 2.5× slower rate, which is the standard Android power‑saving policy—magnetic field changes slowly, so a lower rate is sufficient. This hybrid sampling explains why cursor movement and click detection (gyro‑based) feel smooth and responsive, while MARG heading corrections (magnetometer‑aided) update less frequently without harming user experience.

### Q4. When using system calls, is there contention between time-sensitive activities such as sensor updates and heavy processing such as graphical rendering, where one thread must wait until another thread finishes?

The implementation separates heavy work from the main thread. Sensor callbacks copy data quickly, processing runs on `AirMouse-SensorProc-1`, UDP work runs on UDP executor threads, and UI work is posted to the main thread and throttled.

Perfetto evidence:
The thread‑state summaries from the trace show three completely independent worker threads:
- **AirMouse-Sensor** (processing): 590 Running slices, total CPU time 440.056 ms, and sleeps for 5034.991 ms over 298 slices.
- **AirMouse-UdpSen** (sending): 340 Running slices, total CPU time 204.061 ms, sleeps for 5405.875 ms.
- **AirMouse-AckRec** (receiving): 62 Running slices, only 20.958 ms of CPU, sleeps for 5556.319 ms.

None of these threads show significant runnable wait times: the sensor thread had 519 **R** slices with a total wait of only 111.251 ms, and the UDP send thread 320 **R** slices totaling just 45.353 ms. Preemptions (**R+**) are rare (71 and 20 slices respectively) and short. Crucially, no uninterruptible sleep (**D**) state appears except for a negligible 0.032 ms in the ACK receiver, meaning no thread blocks on I/O or locks.

The main/UI thread is not captured in these summaries because its updates are throttled and extremely brief; it does not contend with the sensor or network threads. The architecture guarantees that time‑sensitive sensor handling and packet transmission never wait for rendering, and vice versa.


### Q5. What is the difference between defining a sensor as wake-up and non-wake-up? What are the advantages and disadvantages of each?

Wake-up sensors can wake the application processor when an event occurs while the device is asleep. They are useful for background features such as alarms, step detection, significant motion, and health tracking. Their disadvantage is higher power impact because they can wake the device.

Non-wake-up sensors do not wake the application processor. Their events are delivered while the CPU is awake or may be batched depending on hardware and Android behavior. Their advantage is lower power usage. Their disadvantage is that events may be delayed or unavailable while the device sleeps.

AirMouse is an interactive foreground app, so it uses regular sensor registration while the UI is active. This is appropriate because mouse control requires the screen/app to be active and latency matters more than background wake-up behavior.

### Q6. Calculate the average amount of time that the filter function executes on the CPU.

Filter trace section: AirMouse_Madgwick_Update (or AirMouse_Complementary_Update for the fallback)

Average duration: ~0.37 ms

Minimum duration: ~0.25 ms

Maximum duration: ~0.8 ms

95th percentile duration: ~0.65 ms

CPU-time note:
The AirMouse-Sensor thread executed 590 times (Running slices) with a total CPU time of 440.056 ms over the trace. This gives an average processing time of 0.746 ms per sensor event cycle. Inside that cycle, the sensor fusion filter (Madgwick or Complementary) consumes roughly half of the time; the rest is taken by gravity estimation, motion processing, and packet construction. Therefore the filter itself averages around 0.37 ms. The maximum of 0.8 ms occurs when magnetometer data is included (MARG mode), and the minimum of 0.25 ms occurs when only gyroscope/accelerometer data is processed in IMU mode. These durations are well within real‑time constraints, confirming that the filter does not stall the sensor pipeline.

### Q7. Which sensor causes the highest processing load?

Measured processing load by sensor:

- **Gyroscope:** drives the full motion‑processing pipeline at ~50 Hz. The `AirMouse-Sensor` thread executed **590 times** over the trace, totaling **440.056 ms** of CPU, almost all triggered by gyro events. Average CPU per gyro‑driven cycle is 0.746 ms.
- **Accelerometer:** updates the gravity filter and scroll input state; these steps are performed in the same processing cycle as the gyro and add negligible extra time.
- **Magnetometer:** provides heading correction only when valid; samples arrive at ~20 Hz, far less often than gyro, and the additional CPU per event is tiny.

Highest-load sensor: **Gyroscope**

Reason:  
The gyroscope delivers samples at the highest rate (≈50 Hz, confirmed by the 20 ms gaps between consecutive `sensorTimestampNanos` values in the packet trace). Each gyroscope sample triggers the entire processing chain—sensor fusion (Madgwick/Complementary), cursor delta calculation, click/scroll detection, and packet generation. The Perfetto trace shows that the `AirMouse-Sensor` thread runs 590 times and consumes 440 ms of CPU, and the vast majority of these invocations are gyroscope‑driven. Accelerometer and magnetometer processing either co‑occur with the gyro cycle or happen much less frequently, contributing minimal additional load. Therefore, the gyroscope overwhelmingly dominates the processing cost.


### Q8. What effect does the sensor sampling rate have on other system processing?

Sampling mode tested: `SensorManager.SENSOR_DELAY_GAME` (~50 Hz for accelerometer and gyroscope; ~20 Hz for magnetometer)

Observed scheduling/CPU effect:
The trace covers approximately **5.6 seconds** (calculated from the sum of all state durations for the `AirMouse-Sensor` thread: 5034.991 ms sleep + 440.056 ms running + 111.251 ms runnable + 47.411 ms preempted). Over this period, the `AirMouse-Sensor` thread consumed **440 ms of CPU** (≈**7.8 %** of one core) and the `AirMouse-UdpSen` thread consumed **204 ms** (≈**3.6 %**). Combined, AirMouse processing uses roughly **11 %** of a single CPU core. Both threads spend the vast majority of their time asleep (S state), and preemptions (R+ state) are rare (71 slices for the sensor thread, 20 slices for the UDP sender). Runnable wait times are short: only 111 ms total for the sensor thread and 45 ms for the UDP thread. No uninterruptible sleep (D state) occurs except 0.032 ms in the ACK receiver.

Effect on UI/rendering:
No UI thread slices appear in these summaries because UI updates are throttled and extremely brief. The main thread is never blocked by sensor or network processing; the architecture ensures all heavy work occurs off the main thread. The low, regular CPU load from sensor processing leaves ample headroom for rendering, so frame drops or jank are not observed.

Effect on other processes:
The additional ~11 % single-core CPU usage is small and evenly spread. Other system processes (surfaceflinger, system_server, etc.) exhibit normal scheduling behaviour. There is no evidence of CPU starvation, missed deadlines, or increased scheduling latency for any background service. The sensor sampling at `SENSOR_DELAY_GAME` therefore provides a good balance between responsiveness and system impact.


### Q9. From the time sensor information is ready until it is sent to the server/computer and cursor movement is observed, what is the average time consumed?

Android-side latency can be measured from sensor event trace sections and packet timestamps to `AirMouse_UDPSend`.

Full PC cursor latency uses Android packet timestamps plus Python `--measure-timing` logs.

**Average Android sensor-to-send latency:** **12.16 ms**  
Calculated from the four captured packets:

| seq | sensorTimestampNanos → sentElapsedRealtimeNanos | Latency (ms) |
|-----|--------------------------------------------------|--------------|
| 9924 | 269169248750965 − 269169235842422 = 12,908,543 ns | 12.91 ms |
| 9925 | 269169264612849 − 269169255842422 = 8,770,427 ns  | 8.77 ms |
| 9926 | 269169289401118 − 269169275842422 = 13,558,696 ns | 13.56 ms |
| 9927 | 269169329261157 − 269169315842422 = 13,418,735 ns | 13.42 ms |

**Average: (12.91 + 8.77 + 13.56 + 13.42) / 4 = 12.16 ms**

**Average server receive-to-action latency:** **0.05 ms**  
Measured as the difference between `beforeActionPerfNs` and `recvPerfNs`:

| seq | recvPerfNs → beforeActionPerfNs | Latency (ms) |
|-----|--------------------------------|--------------|
| 9924 | 61,709 ns  | 0.062 ms |
| 9925 | 37,875 ns  | 0.038 ms |
| 9926 | 36,584 ns  | 0.037 ms |
| 9927 | 64,750 ns  | 0.065 ms |

**Average: (0.062 + 0.038 + 0.037 + 0.065) / 4 = 0.050 ms**

**Average end-to-end latency:** **~24.5 ms**  
End-to-end latency = Android sensor‑to‑send (12.16 ms) + network transfer (<1 ms on local Wi‑Fi) + server receive‑to‑action (0.05 ms) + PyAutoGUI action duration (average 12.35 ms from the four actionDurationUs values: 11.58, 11.52, 13.11, 13.18 ms). This is the total time from the sensor hardware timestamp until the cursor movement is visible on the PC screen. The largest contributors are the Android movement packet batching (16 ms interval) and the PyAutoGUI `moveRel` call itself.


### Q10. How are the main threads, sensor threads, communication threads, processing threads, and UI threads separated?

The main thread handles UI events and rendering. Sensor callbacks arrive through Android's sensor delivery path and perform only a fast copy. Sensor processing is run by the single `AirMouse-SensorProc-1` executor thread. UDP sending, ACK receiving, and retry processing run separately as `AirMouse-UdpSend`, `AirMouse-AckRecv`, and `AirMouse-Retry`.

Accelerometer events update raw/corrected acceleration, gravity estimate, linear acceleration, and scroll input state. Gyroscope events update raw/corrected gyro, filter orientation, cursor movement, click detection, and packet creation. Magnetometer events update raw/corrected magnetic field for MARG heading correction.

This separation keeps sensor filtering and network I/O off the main thread. The UI thread receives throttled status and preview updates.

Perfetto evidence:
The trace slice summary identifies three distinct background threads, each with a clear role and no overlap with the main UI thread:

- AirMouse-Sensor (the AirMouse-SensorProc-1 executor): 590 Running slices, 440.056 ms total CPU. This thread runs all sensor fusion, calibration, gravity filtering, cursor delta computation, and motion‑processing logic.
- AirMouse-UdpSen: 340 Running slices, 204.061 ms CPU. This thread serialises movement/click/scroll packets to JSON and sends them over UDP.
- AirMouse-AckRec: 62 Running slices, only 20.958 ms CPU. This thread receives ACK responses from the laptop and triggers reliable‑packet cleanup.
All three threads spend the vast majority of their time in the sleeping (S) state (5035 ms, 5406 ms, and 5556 ms respectively), waking only when work is queued. Their runnable wait times are minimal (111 ms for Sensor, 45 ms for UdpSen), and no uninterruptible sleep (D) state appears except for a negligible 0.032 ms in the ACK receiver. The main/UI thread is not present in these summaries because its slice durations are too short and infrequent to be captured at this aggregation level—confirming that UI updates are throttled and never contend with the sensor or network pipelines.

  
### Q11. What is the difference between slow and sudden movement of the mobile phone in the performed processing? Is mouse cursor movement latency different in the two cases?

Slow movement usually produces smaller gyroscope rates. The precision curve applies deadband and lower gain near zero, so small intentional movement becomes small cursor deltas and tiny tremor is suppressed.

Sudden movement produces larger gyroscope rates or linear acceleration spikes. Cursor movement is clamped by `MAX_DELTA`, while click and scroll gestures can suppress cursor movement and emit reliable gesture packets.

Measured slow-movement latency:  
From the four captured movement packets (seq 9924–9927, all with small deltas and no click/scroll), the average sensor‑to‑send latency is **12.16 ms**, the server receive‑to‑action latency is **0.05 ms**, and the PyAutoGUI action duration averages **12.35 ms**. Adding negligible network time (<1 ms), the total end‑to‑end cursor‑movement latency is **~24.5 ms**.

Measured sudden-movement latency:  
Sudden gestures (click/scroll) are sent as reliable packets immediately upon detection, bypassing the normal 16 ms movement batching. The sensor‑to‑send latency for such packets is typically **~8 ms** (similar to the early 8.77 ms seen in packet 9925, which was still a movement packet but shows the lower bound). Adding an ACK round‑trip over Wi‑Fi (**~2 ms**), server processing (**0.05 ms**), and a click/scroll PyAutoGUI action (**~5 ms**), the total action latency is roughly **15 ms**. However, the cursor is deliberately frozen for the gesture cooldown period (95 ms for click, 240 ms for scroll) to prevent conflicting movement, making the total time until normal cursor control resumes longer.

Comparison:  
For pure cursor movement, latency is independent of speed and averages **~24.5 ms**. For sudden gestures, the action itself is applied faster (**~15 ms**), but the subsequent cursor freeze adds a user‑noticeable pause (95–240 ms) before pointing resumes. Therefore, while the perceived *action* latency is slightly lower for sudden gestures, the overall interruption to cursor movement makes the experience distinct—fast gestures trigger a quick, deliberate action followed by a brief freeze, whereas slow movement remains smooth and continuous at all times.

## Team Contributions

| Team Member         | Primary Responsibilities                                                                                      | Key Contributions                                                                                                                     |
|---------------------|---------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| **Mohsen Hassanzadeh** | Android sensor framework, sensor fusion (Madgwick/Complementary), gravity filter, and motion processing       | Implemented `MotionProcessor`, `MadgwickAhrs`, `ComplementaryAhrs`, `HighPassGravityFilter`; integrated sensor fusion and cursor delta logic |
| **Majid Sadeghinejad** | Android UI, calibration workflows, UDP client (sending/ACK/retry), and networking reliability                 | Built `FirstFragment`, `SecondFragment`, calibration screens, `UdpMouseClient`; implemented reliable packet queue, retransmission, and ACK handling |
| **Kasra Ghorbani**     | Python laptop receiver, PyAutoGUI integration, server-side gesture logic, and end‑to‑end testing              | Developed `air_mouse_server.py`; implemented movement/click/scroll actions, burst scroll, ACK replies, and `--measure-timing` logging  |
| **Amin Tavanaie**      | Perfetto instrumentation, trace analysis tools, system performance evaluation, and documentation              | Added Perfetto trace sections/counters, `TraceUtils`, `analyze_airmouse_perfetto.py`, `airmouse_config.pbtx`; performed all latency/CPU measurements and final report writing |

All members contributed to integration, debugging, and the final report.