package com.example.airmouse.sensor

import android.os.Trace
import com.example.airmouse.MousePacket
import java.util.Locale
import kotlin.math.abs

enum class FilterMode { MADGWICK, COMPLEMENTARY_SIMPLE }
enum class MovementSource { GYRO_RATE, ORIENTATION_DELTA }
enum class GestureKind { CLICK, SCROLL_UP, SCROLL_DOWN }

data class SensorFrame(
    val sensorType: Int,
    val timestampNanos: Long,
    val values: FloatArray
)

data class MotionOutput(
    val rawAccel: Vec3,
    val rawGyro: Vec3,
    val rawMag: Vec3,
    val correctedAccel: Vec3,
    val correctedGyro: Vec3,
    val correctedMag: Vec3,
    val linearAccel: Vec3,
    val eulerRadians: Vec3,
    val deltaX: Float,
    val deltaY: Float,
    val packet: MousePacket?,
    val gesture: GestureKind?,
    val status: String,
    val debugDetails: String,
    val filterMode: FilterMode
)

class MotionProcessor(
    private val packetFactory: (Long, Float, Float, Boolean, Int, Boolean) -> MousePacket,
    private val filterMode: FilterMode = FilterMode.MADGWICK,
    private val movementSource: MovementSource = MovementSource.GYRO_RATE
) {
    private var rawAccel = Vec3.ZERO
    private var rawGyro = Vec3.ZERO
    private var rawMag = Vec3.ZERO
    private var correctedAccel = Vec3.ZERO
    private var correctedGyro = Vec3.ZERO
    private var correctedMag = Vec3.ZERO
    private var linearAccel = Vec3.ZERO
    private var lastGyroTimestamp = 0L
    private var lastMovementPacketNanos = 0L
    private var lastClickNanos = 0L
    private var lastScrollNanos = 0L
    private var previousEuler: Vec3? = null
    private var scrollArmed = true

    private val madgwick = MadgwickAhrs()
    private val complementary = ComplementaryAhrs()
    private val gravityFilter = HighPassGravityFilter(0.08f)
    private val gyroSmoothing = LowPassFilter3D(GYRO_SMOOTHING_ALPHA)
    private val linearAccelSmoothing = LowPassFilter3D(0.35f)

    private var lastCalibrationStatus = "Calibration: waiting"
    private var lastMovementDebug = "Movement: waiting for gyro"
    private var lastGestureDebug = "Gesture: waiting"
    private var lastPacketDebug = "Packet: none"

    fun reset() {
        rawAccel = Vec3.ZERO
        rawGyro = Vec3.ZERO
        rawMag = Vec3.ZERO
        correctedAccel = Vec3.ZERO
        correctedGyro = Vec3.ZERO
        correctedMag = Vec3.ZERO
        linearAccel = Vec3.ZERO
        lastGyroTimestamp = 0L
        lastMovementPacketNanos = 0L
        lastClickNanos = 0L
        lastScrollNanos = 0L
        previousEuler = null
        scrollArmed = true
        lastMovementDebug = "Movement: reset"
        lastGestureDebug = "Gesture: reset"
        lastPacketDebug = "Packet: none"
        madgwick.reset()
        complementary.reset()
        gravityFilter.reset()
        gyroSmoothing.reset()
        linearAccelSmoothing.reset()
    }

    fun onFrame(frame: SensorFrame): MotionOutput? {
        Trace.beginSection("AirMouse_MotionProcessor")
        return try {
            val values = frame.values
            if (values.size < 3) return null
            val raw = Vec3(values[0], values[1], values[2])
            when (frame.sensorType) {
                android.hardware.Sensor.TYPE_ACCELEROMETER,
                android.hardware.Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                    rawAccel = raw
                    val corrected = SensorCalibration.correctAccel(raw)
                    correctedAccel = corrected.first
                    val gravityAndLinear = gravityFilter.update(correctedAccel)
                    linearAccel = linearAccelSmoothing.update(gravityAndLinear.second).deadband(LINEAR_ACCEL_DEADBAND)
                    lastCalibrationStatus = corrected.second
                    output(frame.timestampNanos, 0f, 0f, null, null)
                }

                android.hardware.Sensor.TYPE_MAGNETIC_FIELD,
                android.hardware.Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                    rawMag = raw
                    val corrected = SensorCalibration.correctMag(raw)
                    correctedMag = corrected.first
                    lastCalibrationStatus = corrected.second
                    output(frame.timestampNanos, 0f, 0f, null, null)
                }

                android.hardware.Sensor.TYPE_GYROSCOPE,
                android.hardware.Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                    rawGyro = raw
                    val corrected = SensorCalibration.correctGyro(raw)
                    correctedGyro = gyroSmoothing.update(corrected.first).deadband(GYRO_FILTER_DEADBAND)
                    lastCalibrationStatus = corrected.second

                    val dt = safeDt(frame.timestampNanos, lastGyroTimestamp, 0.02f)
                    lastGyroTimestamp = frame.timestampNanos
                    val q = when (filterMode) {
                        FilterMode.MADGWICK -> {
                            if (correctedMag.norm() > 0.01f) {
                                madgwick.updateMARG(correctedGyro, correctedAccel, correctedMag, dt)
                            } else {
                                madgwick.updateIMU(correctedGyro, correctedAccel, dt)
                            }
                        }
                        FilterMode.COMPLEMENTARY_SIMPLE -> complementary.update(correctedGyro, correctedAccel, dt)
                    }

                    val movement = computeMovement(q.toEulerRadians(), dt)
                    val gesture = detectGesture(frame.timestampNanos)
                    val packet = createPacketIfNeeded(frame.timestampNanos, movement.deltaX, movement.deltaY, gesture)
                    output(frame.timestampNanos, movement.deltaX, movement.deltaY, packet, gesture)
                }

                else -> null
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun computeMovement(euler: Vec3, dt: Float): MovementComputation {
        val sourceMovement = when (movementSource) {
            MovementSource.GYRO_RATE -> {
                val horizontalRate = correctedGyro.z * HORIZONTAL_SIGN
                val verticalRate = correctedGyro.x * VERTICAL_SIGN
                val dx = deadband(horizontalRate, MOVEMENT_GYRO_DEADBAND) * HORIZONTAL_SENSITIVITY
                val dy = deadband(verticalRate, MOVEMENT_GYRO_DEADBAND) * VERTICAL_SENSITIVITY
                SourceMovement(horizontalRate, verticalRate, dx, dy)
            }
            MovementSource.ORIENTATION_DELTA -> {
                val previous = previousEuler
                previousEuler = euler
                if (previous == null || dt <= 0f) {
                    SourceMovement(0f, 0f, 0f, 0f)
                } else {
                    val horizontalDelta = (euler.z - previous.z) * HORIZONTAL_SIGN
                    val verticalDelta = (euler.x - previous.x) * VERTICAL_SIGN
                    val dx = deadband(horizontalDelta, ORIENTATION_DEADBAND) * ORIENTATION_SENSITIVITY
                    val dy = deadband(verticalDelta, ORIENTATION_DEADBAND) * ORIENTATION_SENSITIVITY
                    SourceMovement(horizontalDelta, verticalDelta, dx, dy)
                }
            }
        }

        var dx = sourceMovement.scaledX
        var dy = sourceMovement.scaledY
        var suppressedAxis = "none"
        val absX = abs(dx)
        val absY = abs(dy)
        if (absX > 0f || absY > 0f) {
            if (absX > absY * MOVEMENT_AXIS_DOMINANCE_RATIO && absY <= AXIS_LEAKAGE_MAX_DELTA) {
                dy = 0f
                suppressedAxis = "Y leakage"
            } else if (absY > absX * MOVEMENT_AXIS_DOMINANCE_RATIO && absX <= AXIS_LEAKAGE_MAX_DELTA) {
                dx = 0f
                suppressedAxis = "X leakage"
            }
        }

        val clampedX = clamp(dx, -MAX_DELTA, MAX_DELTA)
        val clampedY = clamp(dy, -MAX_DELTA, MAX_DELTA)
        val clamped = clampedX != dx || clampedY != dy

        lastMovementDebug = String.format(
            Locale.US,
            "Movement cfg: hSign %.0f vSign %.0f sensX %.1f sensY %.1f deadband %.3f smoothAlpha %.2f clamp %.1f interval %.0fms\n" +
                    "Movement raw gyro X %.3f Z %.3f signed rates X %.3f Y %.3f scaled %.2f/%.2f final %.2f/%.2f suppressed=%s clamped=%s",
            HORIZONTAL_SIGN,
            VERTICAL_SIGN,
            HORIZONTAL_SENSITIVITY,
            VERTICAL_SENSITIVITY,
            MOVEMENT_GYRO_DEADBAND,
            GYRO_SMOOTHING_ALPHA,
            MAX_DELTA,
            MOVEMENT_PACKET_INTERVAL_NANOS / 1_000_000f,
            correctedGyro.x,
            correctedGyro.z,
            sourceMovement.signedX,
            sourceMovement.signedY,
            sourceMovement.scaledX,
            sourceMovement.scaledY,
            clampedX,
            clampedY,
            suppressedAxis,
            clamped
        )

        return MovementComputation(clampedX, clampedY)
    }

    private fun detectGesture(nowNanos: Long): GestureKind? {
        val gyro = correctedGyro
        val accel = linearAccel
        val yGyroDominant = abs(gyro.y) > abs(gyro.x) * AXIS_DOMINANCE_RATIO &&
                abs(gyro.y) > abs(gyro.z) * AXIS_DOMINANCE_RATIO
        val clickSignValue = gyro.y * CLICK_SIGN
        val clickAllowed = nowNanos - lastClickNanos > GESTURE_COOLDOWN_NANOS &&
                nowNanos - lastScrollNanos > GESTURE_MUTEX_NANOS

        if (clickAllowed && yGyroDominant && clickSignValue > CLICK_THRESHOLD_RAD_PER_SEC) {
            lastClickNanos = nowNanos
            lastGestureDebug = String.format(
                Locale.US,
                "Gesture CLICK: gyroY %.3f signed %.3f threshold %.2f sign %.0f",
                gyro.y,
                clickSignValue,
                CLICK_THRESHOLD_RAD_PER_SEC,
                CLICK_SIGN
            )
            return GestureKind.CLICK
        }

        val yAccelDominant = abs(accel.y) > abs(accel.x) * AXIS_DOMINANCE_RATIO &&
                abs(accel.y) > abs(accel.z) * AXIS_DOMINANCE_RATIO
        val rotating = abs(gyro.x) + abs(gyro.y) + abs(gyro.z) > ROTATION_SUPPRESSION_THRESHOLD
        val scrollAllowed = nowNanos - lastScrollNanos > GESTURE_COOLDOWN_NANOS &&
                nowNanos - lastClickNanos > GESTURE_MUTEX_NANOS
        val scrollSignal = accel.y * SCROLL_SIGN
        val scrollMagnitude = abs(scrollSignal)

        if (scrollMagnitude < SCROLL_REARM_ACCEL_THRESHOLD) {
            scrollArmed = true
        }

        if (scrollAllowed && scrollArmed && yAccelDominant && !rotating && scrollMagnitude > SCROLL_ACCEL_THRESHOLD) {
            lastScrollNanos = nowNanos
            scrollArmed = false
            val gesture = if (scrollSignal > 0f) GestureKind.SCROLL_UP else GestureKind.SCROLL_DOWN
            lastGestureDebug = String.format(
                Locale.US,
                "Gesture %s: linearY raw %.3f signed %.3f threshold %.2f sign %.0f cooldown %.0fms rearmBelow %.2f",
                gesture,
                accel.y,
                scrollSignal,
                SCROLL_ACCEL_THRESHOLD,
                SCROLL_SIGN,
                GESTURE_COOLDOWN_NANOS / 1_000_000f,
                SCROLL_REARM_ACCEL_THRESHOLD
            )
            return gesture
        }

        lastGestureDebug = String.format(
            Locale.US,
            "Gesture idle: clickY %.3f signed %.3f allowed=%s | scrollY raw %.3f signed %.3f dom=%s rotating=%s allowed=%s armed=%s",
            gyro.y,
            clickSignValue,
            clickAllowed,
            accel.y,
            scrollSignal,
            yAccelDominant,
            rotating,
            scrollAllowed,
            scrollArmed
        )
        return null
    }

    private fun createPacketIfNeeded(
        sensorTimestampNanos: Long,
        deltaX: Float,
        deltaY: Float,
        gesture: GestureKind?
    ): MousePacket? {
        if (gesture != null) {
            val packet = when (gesture) {
                GestureKind.CLICK -> packetFactory(sensorTimestampNanos, 0f, 0f, true, 0, true)
                GestureKind.SCROLL_UP -> packetFactory(sensorTimestampNanos, 0f, 0f, false, 1, true)
                GestureKind.SCROLL_DOWN -> packetFactory(sensorTimestampNanos, 0f, 0f, false, -1, true)
            }
            lastPacketDebug = "Packet: reliable ${gesture.name} seq=${packet.sequenceNumber}"
            return packet
        }

        if (sensorTimestampNanos - lastMovementPacketNanos < MOVEMENT_PACKET_INTERVAL_NANOS) {
            lastPacketDebug = "Packet: movement held for rate limit"
            return null
        }
        if (abs(deltaX) < MOVEMENT_PACKET_DEADZONE && abs(deltaY) < MOVEMENT_PACKET_DEADZONE) {
            lastPacketDebug = String.format(
                Locale.US,
                "Packet: movement suppressed by packet deadzone %.2f",
                MOVEMENT_PACKET_DEADZONE
            )
            return null
        }
        lastMovementPacketNanos = sensorTimestampNanos
        val packet = packetFactory(sensorTimestampNanos, deltaX, deltaY, false, 0, false)
        lastPacketDebug = String.format(Locale.US, "Packet: movement seq=%d dx %.2f dy %.2f", packet.sequenceNumber, deltaX, deltaY)
        return packet
    }

    private fun output(
        timestampNanos: Long,
        deltaX: Float,
        deltaY: Float,
        packet: MousePacket?,
        gesture: GestureKind?
    ): MotionOutput {
        val q = when (filterMode) {
            FilterMode.MADGWICK -> madgwick.quaternion
            FilterMode.COMPLEMENTARY_SIMPLE -> complementary.quaternion
        }
        val status = String.format(
            Locale.US,
            "%s | linearY %.2f | q %.3f %.3f %.3f %.3f",
            lastCalibrationStatus,
            linearAccel.y,
            q.w,
            q.x,
            q.y,
            q.z
        )
        return MotionOutput(
            rawAccel = rawAccel,
            rawGyro = rawGyro,
            rawMag = rawMag,
            correctedAccel = correctedAccel,
            correctedGyro = correctedGyro,
            correctedMag = correctedMag,
            linearAccel = linearAccel,
            eulerRadians = q.toEulerRadians(),
            deltaX = deltaX,
            deltaY = deltaY,
            packet = packet,
            gesture = gesture,
            status = status,
            debugDetails = "$lastMovementDebug\n$lastGestureDebug\n$lastPacketDebug",
            filterMode = filterMode
        )
    }

    private data class SourceMovement(
        val signedX: Float,
        val signedY: Float,
        val scaledX: Float,
        val scaledY: Float
    )

    private data class MovementComputation(
        val deltaX: Float,
        val deltaY: Float
    )

    companion object {
        private const val HORIZONTAL_SIGN = -1f
        private const val VERTICAL_SIGN = -1f
        private const val SCROLL_SIGN = -1f
        private const val CLICK_SIGN = -1f
        private const val HORIZONTAL_SENSITIVITY = 14.0f
        private const val VERTICAL_SENSITIVITY = 14.0f
        private const val ORIENTATION_SENSITIVITY = 300.0f
        private const val MAX_DELTA = 14.0f
        private const val GYRO_SMOOTHING_ALPHA = 0.60f
        private const val GYRO_FILTER_DEADBAND = 0.004f
        private const val MOVEMENT_GYRO_DEADBAND = 0.008f
        private const val ORIENTATION_DEADBAND = 0.0015f
        private const val LINEAR_ACCEL_DEADBAND = 0.20f
        private const val MOVEMENT_PACKET_DEADZONE = 0.03f
        private const val MOVEMENT_PACKET_INTERVAL_NANOS = 16_000_000L
        private const val GESTURE_COOLDOWN_NANOS = 700_000_000L
        private const val GESTURE_MUTEX_NANOS = 250_000_000L
        private const val CLICK_THRESHOLD_RAD_PER_SEC = 2.5f
        private const val SCROLL_ACCEL_THRESHOLD = 2.8f
        private const val SCROLL_REARM_ACCEL_THRESHOLD = 1.0f
        private const val ROTATION_SUPPRESSION_THRESHOLD = 1.2f
        private const val AXIS_DOMINANCE_RATIO = 1.8f
        private const val MOVEMENT_AXIS_DOMINANCE_RATIO = 2.2f
        private const val AXIS_LEAKAGE_MAX_DELTA = 3.0f
    }
}
