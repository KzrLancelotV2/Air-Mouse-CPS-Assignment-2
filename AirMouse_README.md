# AirMouse Android Module Documentation

## Overview

This document describes the Android-side implementation completed for the **AirMouse** project. The goal of the project is to use an Android phone as an air mouse: the phone reads motion sensors, interprets movement, visualizes/debugs the behavior on screen, and prepares mouse-control data for transmission to a laptop.

This documentation focuses mainly on the files modified for the Android UI and sensor modules:

- `FirstFragment.kt`
- `SecondFragment.kt`
- `fragment_first.xml`
- `fragment_second.xml`
- `MainActivity.kt`
- `AndroidManifest.xml`

The implementation provides the Android-side foundation for raw sensor acquisition, live debugging, movement visualization, click/scroll detection feedback, calibration data collection, and packet preparation for the networking teammate.

---

## Current Scope

The current implementation covers:

1. Android UI for testing and debugging.
2. Reading raw sensor data from the phone.
3. Displaying live accelerometer, gyroscope, and magnetometer values.
4. Visualizing phone movement using a green square.
5. Detecting click and scroll gestures for UI feedback.
6. Collecting calibration data for gyroscope, accelerometer, and magnetometer.
7. Preparing mouse-control packets in a JSON-compatible structure for future UDP transmission.

The following are intentionally left for later integration:

- actual UDP socket transmission
- receiving ACK packets from the laptop
- laptop-side mouse control
- final filtering/sensor fusion such as Madgwick, Mahony, Kalman, or complementary filter
- final calibrated motion model used for production-quality cursor movement

---

## Modified Files

| File | Purpose |
|---|---|
| `FirstFragment.kt` | Main AirMouse runtime screen: reads sensors, displays debug values, detects movement/click/scroll, creates mouse packets. |
| `SecondFragment.kt` | Calibration screen: collects raw calibration data for gyroscope, accelerometer, and magnetometer. |
| `fragment_first.xml` | UI layout for the main AirMouse screen. |
| `fragment_second.xml` | UI layout for the calibration screen. |
| `MainActivity.kt` | Cleaned default Android template UI by hiding toolbar/floating button. |
| `AndroidManifest.xml` | Added network settings and locked orientation for stable sensor testing. |

---

# 1. Main Screen: `FirstFragment.kt`

## Purpose

`FirstFragment.kt` is the main runtime screen of the Android application. It reads sensor data, displays live debug values, visualizes phone motion, detects simple gestures, and prepares mouse-control packets.

The fragment implements:

```kotlin
class FirstFragment : Fragment(), SensorEventListener
```

so it can directly receive Android sensor events through `onSensorChanged()`.

---

## Sensor Access

The fragment uses Android's built-in `SensorManager` API:

```kotlin
sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
```

The implementation prefers raw/uncalibrated sensors when available:

```kotlin
TYPE_ACCELEROMETER_UNCALIBRATED
TYPE_GYROSCOPE_UNCALIBRATED
TYPE_MAGNETIC_FIELD_UNCALIBRATED
```

with fallback to normal Android sensors if the uncalibrated sensor is unavailable:

```kotlin
sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
    ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
```

This makes the app usable both on emulator and real phones, while still prioritizing raw sensor access.

---

## Start / Stop Behavior

The `START MOUSE` button controls sensor reading.

When pressed:

- sensors are registered
- live sensor updates begin
- the button changes to `STOP MOUSE`
- status text changes to show that sensors are running

When pressed again:

- sensors are unregistered
- updates stop
- the button returns to `START MOUSE`

Main state variables:

```kotlin
private var isReadingSensors = false
private var userWantsMouseRunning = false
```

---

## Raw Sensor Sample Storage

A raw sensor sample is represented by:

```kotlin
data class SensorSample(
    val timestamp: Long,
    val sensorType: Int,
    val x: Float,
    val y: Float,
    val z: Float
)
```

Samples are stored in:

```kotlin
private val sensorSamples = mutableListOf<SensorSample>()
private val maxSamples = 1000
```

The app keeps the most recent 1000 raw samples. This is useful for debugging and for later calibration/filtering work.

Stored data includes:

- timestamp
- Android sensor type
- x-axis value
- y-axis value
- z-axis value

These are raw sensor readings, not final mouse commands.

---

## Live Sensor Display

`onSensorChanged()` receives sensor events and updates the UI with:

- accelerometer X/Y/Z
- gyroscope X/Y/Z
- magnetometer X/Y/Z

The values are formatted using:

```kotlin
String.format(Locale.US, "X: %.3f  Y: %.3f  Z: %.3f", x, y, z)
```

This makes the UI useful for debugging and verifying sensor behavior during testing.

---

## Movement Visualization

The assignment maps movement as:

- Z-axis movement controls horizontal cursor movement.
- X-axis movement controls vertical cursor movement.

The implementation visualizes this with a green square.

Relevant function:

```kotlin
private fun updateMovementIndicatorFromXZ(xAxis: Float, zAxis: Float)
```

Mapping:

```kotlin
val moveX = (zAxis * movementSensitivity).coerceIn(-maxX, maxX)
val moveY = (-xAxis * movementSensitivity).coerceIn(-maxY, maxY)
```

| Sensor axis | Visual effect |
|---|---|
| Z axis | Square moves left/right |
| X axis | Square moves up/down |

The square is a debug visualization and does not directly move the laptop cursor yet.

---

## Click Detection

The assignment describes click as a fast left rotation around the Y axis.

The implementation detects click from gyroscope Y-axis data:

```kotlin
private fun detectClickFromYAxis(gyroX: Float, gyroY: Float, gyroZ: Float)
```

To reduce false positives, it checks:

1. Y-axis rotation exceeds a threshold.
2. Y-axis motion is dominant compared to X and Z.
3. A cooldown has passed since the previous click.

Dominance check:

```kotlin
val isYAxisDominant =
    abs(gyroY) > abs(gyroX) * axisDominanceRatio &&
    abs(gyroY) > abs(gyroZ) * axisDominanceRatio
```

Left rotation check:

```kotlin
val isLeftRotation = gyroY < clickThresholdY
```

When click is detected:

- the UI label updates
- the green square flashes red
- a reliable mouse packet is created with `click = true`

---

## Scroll Detection

The assignment describes scroll as fast movement along the Y axis.

The implementation detects scroll from accelerometer Y-axis change:

```kotlin
private fun detectScrollFromYAxis(accelX: Float, accelY: Float, accelZ: Float)
```

It uses the difference between the current and previous acceleration values:

```kotlin
val deltaY = accelY - previous[1]
```

To reduce false positives, it checks:

1. Y-axis acceleration change exceeds a threshold.
2. Y-axis change is dominant compared to X and Z.
3. The phone is not mainly rotating.
4. A cooldown has passed since the previous scroll.

Rotation rejection:

```kotlin
val isPhoneRotating =
    abs(latestGyro[0]) + abs(latestGyro[1]) + abs(latestGyro[2]) > rotationIgnoreThreshold
```

Direction mapping:

```kotlin
val scrollDirection = if (deltaY > 0) 1 else -1
```

| Scroll value | Meaning |
|---|---|
| `1` | Scroll up |
| `-1` | Scroll down |
| `0` | No scroll |

When scroll is detected:

- the UI label updates
- the square flashes blue for scroll up
- the square flashes orange for scroll down
- a reliable mouse packet is created

---

## Visual Gesture Feedback

The movement square is used for both movement visualization and gesture feedback.

| Event | Square color |
|---|---|
| Normal movement | Green |
| Click | Red |
| Scroll up | Blue |
| Scroll down | Orange |

Relevant function:

```kotlin
private fun flashSquareForGesture(color: Int, labelText: String)
```

The square briefly changes color and scales up, then returns to green.

---

## Mouse Packet Preparation

A transmission-ready packet is represented by:

```kotlin
data class MousePacket(
    val sequenceNumber: Long,
    val timestamp: Long,
    val deltaX: Float,
    val deltaY: Float,
    val click: Boolean,
    val scroll: Int,
    val requiresAck: Boolean
)
```

The packet can be converted to JSON:

```kotlin
fun toJsonString(): String
```

Example packet:

```json
{
  "seq": 12,
  "timestamp": 1717420000000,
  "deltaX": 3.5,
  "deltaY": -1.2,
  "click": false,
  "scroll": 0,
  "requiresAck": false
}
```

This matches the assignment's suggested structure using movement deltas, click status, and scroll status.

---

## Movement Packets

Movement packets are created from gyroscope X and Z values:

```kotlin
private fun createMovementPacketFromXZ(xAxis: Float, zAxis: Float)
```

Mapping:

```kotlin
val deltaX = zAxis * packetMovementScale
val deltaY = -xAxis * packetMovementScale
```

Movement packets:

- do not require ACK
- may be dropped if necessary
- are stored in a limited queue

Queue:

```kotlin
private val movementPackets = mutableListOf<MousePacket>()
private val maxMovementPackets = 200
```

This follows the assignment idea that pure movement packets can be dropped if the phone and laptop operate at different rates.

---

## Reliable Packets for Click and Scroll

Click and scroll packets require ACK.

They are stored separately:

```kotlin
private val pendingAckPackets = mutableListOf<MousePacket>()
```

Reliable packets are created for:

- click events
- scroll events

These packets have:

```kotlin
requiresAck = true
```

They remain in the queue until the networking module confirms that the laptop acknowledged them.

---

## Handoff Functions for UDP Teammate

The following helper functions are included for the teammate implementing UDP:

```kotlin
fun getPacketsReadyForTransmission(): List<MousePacket>
```

Returns both reliable packets and movement packets.

```kotlin
fun clearSentMovementPackets()
```

Clears movement packets after they are sent once.

```kotlin
fun markPacketAcknowledged(sequenceNumber: Long)
```

Removes a reliable packet from the pending ACK queue once the laptop acknowledges it.

This cleanly separates packet creation from actual network transmission.

---

## Debug Information

The main screen shows packet/debug status through `sampleCountText`.

It displays:

- number of stored raw sensor samples
- number of movement packets ready
- number of reliable packets waiting for ACK
- last generated packet

Example:

```text
Stored raw sensor samples: 1000
Movement packets ready: 15
Reliable packets waiting for ACK: 1
Last packet: {"seq":17,"timestamp":...,"deltaX":0.0,"deltaY":0.0,"click":true,"scroll":0,"requiresAck":true}
```

---

# 2. Calibration Screen: `SecondFragment.kt`

## Purpose

`SecondFragment.kt` implements the calibration workflow screen.

It collects raw calibration data for:

- gyroscope
- accelerometer
- magnetometer

It also displays instructions so the user knows what to do at each stage.

---

## Calibration Store

Calibration-related values are stored in:

```kotlin
object CalibrationStore
```

Current stored data:

```kotlin
var gyroBias: FloatArray? = null
val accelSixPositions = mutableListOf<FloatArray>()
var magMin = FloatArray(3) { Float.POSITIVE_INFINITY }
var magMax = FloatArray(3) { Float.NEGATIVE_INFINITY }
```

| Variable | Purpose |
|---|---|
| `gyroBias` | Average gyroscope reading while phone is stationary. |
| `accelSixPositions` | Six accelerometer samples from six physical orientations. |
| `magMin` | Minimum magnetometer values during figure-8 movement. |
| `magMax` | Maximum magnetometer values during figure-8 movement. |

---

## Calibration UI Instructions

The calibration screen shows three steps:

1. Put the phone on a stable surface and collect gyroscope samples.
2. Place the phone in six different physical orientations and save accelerometer readings.
3. Move the phone in a figure-8 shape to collect magnetometer min/max values.

This satisfies the requirement for a separate calibration page with textual guidance.

---

## Sensor Registration

The calibration screen also uses `SensorManager` and registers:

- accelerometer
- gyroscope
- magnetometer

using:

```kotlin
SensorManager.SENSOR_DELAY_GAME
```

Sensor listeners start when the calibration screen opens and stop in lifecycle callbacks to prevent leaks.

---

## Gyroscope Bias Collection

The gyroscope workflow follows the assignment requirement:

> Place the phone still, collect several gyroscope samples, then compute the mean.

Button:

```kotlin
gyroCalibrateButton
```

When pressed, the app:

1. Clears old gyroscope samples.
2. Sets `collectingGyro = true`.
3. Collects 200 gyroscope samples.
4. Computes average X/Y/Z values.
5. Stores them in `CalibrationStore.gyroBias`.

Computation:

```kotlin
val biasX = gyroSamples.map { it[0] }.average().toFloat()
val biasY = gyroSamples.map { it[1] }.average().toFloat()
val biasZ = gyroSamples.map { it[2] }.average().toFloat()
```

This bias can later be used as:

```text
correctedGyro = rawGyro - gyroBias
```

---

## Accelerometer Six-Position Capture

The accelerometer workflow stores six raw readings:

```kotlin
CalibrationStore.accelSixPositions.add(currentAccel.clone())
```

The assignment requires six physical orientations where one axis should read approximately `+9.81` or `-9.81`.

Current implementation:

- stores six raw accelerometer samples
- updates the UI after each saved position
- stops after six positions

Current status:

- data collection is implemented
- final offset/scale calculation can be completed by the calibration/filtering teammate

The saved data is ready for:

```text
offset and scale calculation using six-position calibration
```

---

## Magnetometer Figure-8 Capture

The magnetometer workflow follows the assignment instruction:

> Move the phone in a figure-8 shape and store minimum and maximum values for each axis.

When magnetometer capture starts, the app:

1. Resets previous min/max values.
2. Starts collecting values.
3. Updates min/max for X/Y/Z continuously.
4. Stops collection when the button is pressed again.

Min/max update example:

```kotlin
CalibrationStore.magMin[0] = min(CalibrationStore.magMin[0], x)
CalibrationStore.magMax[0] = max(CalibrationStore.magMax[0], x)
```

The collected values can later be used to compute:

```text
offset = (max + min) / 2
scale = (max - min) / 2
corrected = (raw - offset) / scale
```

Current status:

- min/max collection is implemented
- full correction application is left for later integration

---

# 3. Layout Files

## `fragment_first.xml`

This layout defines the main AirMouse UI.

Implemented UI elements include:

- `AirMouse` title
- status label
- laptop IP input field
- `CALIBRATE` button
- `START MOUSE` button
- sensor availability text
- movement debug area
- green movement square
- movement label
- click status label
- scroll status label
- accelerometer value text
- gyroscope value text
- magnetometer value text
- sample/packet debug text

The layout uses a scrollable vertical structure so it remains usable on different screen sizes.

---

## `fragment_second.xml`

This layout defines the calibration page.

Implemented UI elements include:

- `Calibration` title
- instruction text
- calibration status text
- gyroscope calibration button
- gyroscope result label
- accelerometer capture button
- accelerometer result label
- magnetometer figure-8 button
- magnetometer result label
- back button

This satisfies the requirement for a separate calibration screen with step-by-step instructions.

---

# 4. `MainActivity.kt`

The default Android Studio template included unnecessary UI elements such as:

- toolbar title
- floating action button
- default menu

These were removed or hidden to make the app behave like a focused AirMouse interface.

Implemented changes:

- toolbar hidden
- floating action button hidden
- default menu functionality removed

---

# 5. `AndroidManifest.xml`

The manifest was updated for project requirements.

Expected network permission:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Expected cleartext setting:

```xml
android:usesCleartextTraffic="true"
```

The activity orientation was also locked to portrait:

```xml
android:screenOrientation="portrait"
```

Reason:

- rotating the device caused Android to recreate the Activity
- this reset runtime sensor state
- portrait lock makes testing and demo behavior more stable

---

# 6. Completed Feature Checklist

## UI

- [x] Main AirMouse screen
- [x] Calibration screen
- [x] START MOUSE button
- [x] CALIBRATE button
- [x] Laptop IP input field
- [x] Sensor debug values
- [x] Movement visualization using green square
- [x] Click feedback
- [x] Scroll feedback
- [x] Responsive scrollable layout

## Sensor Reading

- [x] Accelerometer reading
- [x] Gyroscope reading
- [x] Magnetometer reading
- [x] Preference for uncalibrated/raw sensors
- [x] Live sensor display
- [x] Raw sample storage

## Gesture Detection

- [x] Z-axis movement mapped to horizontal movement visualization
- [x] X-axis movement mapped to vertical movement visualization
- [x] Y-axis gyroscope used for click detection
- [x] Y-axis accelerometer change used for scroll detection
- [x] Cooldown added to reduce repeated false triggers
- [x] Axis-dominance checks added to reduce accidental click/scroll detection

## Calibration Data Collection

- [x] Gyroscope stationary sample collection
- [x] Gyroscope average bias calculation
- [x] Accelerometer six-position sample collection
- [x] Magnetometer figure-8 min/max collection

## Packet Preparation

- [x] `MousePacket` data model
- [x] JSON conversion
- [x] `deltaX` / `deltaY` movement packets
- [x] click event packets
- [x] scroll event packets
- [x] reliable ACK queue for click/scroll
- [x] droppable movement queue
- [x] handoff functions for UDP teammate

---

# 7. Not Implemented Yet

## UDP Communication

Not implemented yet:

- opening UDP socket
- sending packets to laptop IP
- receiving ACKs
- retransmitting reliable packets

Prepared interface exists:

```kotlin
getPacketsReadyForTransmission()
clearSentMovementPackets()
markPacketAcknowledged(sequenceNumber)
```

## Laptop-Side Server

Not implemented in this Android module.

Expected later implementation:

- Python UDP server
- parse JSON packets
- move mouse using PyAutoGUI
- send ACK for click/scroll packets

## Full Calibration Correction

Partially implemented.

Implemented:

- data collection
- gyroscope bias computation
- magnetometer min/max storage
- accelerometer six-position storage

Not fully implemented:

- applying calibration to live sensor readings
- accelerometer offset/scale computation
- magnetometer offset/scale correction
- final corrected sensor pipeline

## Sensor Fusion / Drift Removal

Not implemented yet.

Possible future methods:

- complementary filter
- Madgwick AHRS
- Mahony filter
- Kalman filter

Ready-made filter libraries are not allowed by the assignment. If one of these methods is selected, it must be implemented manually.

---

# 8. Notes for Teammates

## For the UDP teammate

Use the packet structure:

```kotlin
MousePacket(
    sequenceNumber,
    timestamp,
    deltaX,
    deltaY,
    click,
    scroll,
    requiresAck
)
```

Use:

```kotlin
packet.toJsonString()
```

to serialize the packet.

Movement packets:

```kotlin
requiresAck = false
```

can be sent once and dropped if necessary.

Click/scroll packets:

```kotlin
requiresAck = true
```

must remain in `pendingAckPackets` until acknowledged.

---

## For the calibration/filtering teammate

Raw calibration data is available in:

```kotlin
CalibrationStore.gyroBias
CalibrationStore.accelSixPositions
CalibrationStore.magMin
CalibrationStore.magMax
```

Recommended next steps:

1. Apply gyroscope correction:

```text
correctedGyro = rawGyro - gyroBias
```

2. Compute accelerometer offset and scale from six-position data.

3. Compute magnetometer offset and scale from min/max values:

```text
offset = (max + min) / 2
scale = (max - min) / 2
corrected = (raw - offset) / scale
```

4. Feed corrected values into the final motion/filtering pipeline.

---

## For the report teammate

Mention that the Android side currently:

- uses Android `SensorManager`
- prefers raw/uncalibrated sensors
- implements UI-based debugging
- collects calibration data according to the assignment workflow
- prepares JSON-compatible mouse packets
- separates droppable movement packets from reliable click/scroll packets

---

# 9. Testing Notes

## Emulator

The emulator can be used for basic UI and sensor simulation, but it is not ideal for final motion testing.

Known limitations:

- sensor values may not behave exactly like a real phone
- gyroscope/magnetometer behavior may be limited
- gesture detection thresholds may need retuning on real hardware

## Real Phone

Final testing should be done on a physical Android phone because:

- real gyroscope behavior is needed for click detection
- real acceleration changes are needed for scroll detection
- figure-8 magnetometer calibration requires actual phone movement
- final mouse control needs stable real sensor data

---

# 10. Summary

The Android module now provides a functional foundation for the AirMouse project.

Completed work includes:

- custom AirMouse UI
- live raw sensor reading
- debug sensor display
- movement visualization
- click and scroll feedback
- calibration workflow page
- gyroscope bias collection
- accelerometer six-position collection
- magnetometer min/max collection
- packet-generation infrastructure for UDP transmission

The implementation is ready for integration with:

- calibration correction/filtering
- UDP networking
- laptop-side mouse control
