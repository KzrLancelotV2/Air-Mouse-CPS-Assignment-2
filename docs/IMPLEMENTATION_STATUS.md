# AirMouse Implementation Status

## Previously Implemented

- Android UI for the main AirMouse screen.
- Raw accelerometer, gyroscope, and magnetometer display.
- Calibration page and calibration sample collection.
- Debug square preview for phone-side movement feedback.
- Basic click/scroll labels.
- JSON packet model skeleton.

## Newly Implemented

- Unsupported-device guard when a phone has no gyroscope.
- Preference for uncalibrated/raw sensors with fallback status warnings.
- Runtime application of gyro, accelerometer, and magnetometer calibration.
- Manual Madgwick AHRS with IMU and MARG update paths.
- Simple complementary fallback filter implementation.
- Gravity low-pass removal and smoothed linear acceleration for scroll detection.
- Deadband, smoothing, axis dominance, cooldowns, and gesture mutexes for click/scroll.
- Relative gyro-based cursor movement deltas with rate limiting and clamping.
- Android UDP sender on a background thread.
- ACK receive path for reliable click/scroll packets.
- Bounded reliable packet queue with retry/drop behavior.
- Python UDP receiver using PyAutoGUI, ACKs, CLI arguments, and dry-run mode.
- Perfetto trace markers around sensor event copy, calibration correction, Madgwick update, motion processing, and UDP send.

## Still Requires Real-Device Tuning

- Movement sensitivity.
- Click threshold and click sign.
- Scroll threshold and scroll sign.
- Drift behavior after several minutes of holding the phone still.
- Perfetto trace measurements for filter execution time and thread scheduling.
- Final video demo and measured packet behavior on the target phone/laptop.

## Run Instructions

1. Start the Python server on the laptop:
   `python laptop_server/air_mouse_server.py --port 5000`
2. Find the laptop IP address with `ipconfig` on Windows.
3. Enter the laptop IP in the Android app.
4. Calibrate the gyro while the phone is completely still.
5. Capture six accelerometer positions.
6. Capture magnetometer figure-8 min/max values.
7. Press `START MOUSE`.
8. Test movement, click, and scroll.

## Manual Test Checklist

- Use a phone with a physical gyroscope.
- Confirm the Python server receives packets.
- Confirm ACKs are printed for click/scroll packets.
- Move/rotate around Z and confirm laptop cursor moves horizontally.
- Move/rotate around X and confirm laptop cursor moves vertically.
- Rapid left rotation around Y should produce one left click.
- Rapid movement along Y should produce one scroll packet.
- Hold the phone still and confirm cursor drift is acceptable.
- Disconnect the server briefly and confirm pending ACK count stays bounded.
