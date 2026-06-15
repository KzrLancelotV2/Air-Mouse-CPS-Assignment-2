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

    private val madgwick = MadgwickAhrs()
    private val complementary = ComplementaryAhrs()
    private val gravityFilter = HighPassGravityFilter(0.08f)
    private val gyroSmoothing = LowPassFilter3D(0.35f)
    private val linearAccelSmoothing = LowPassFilter3D(0.35f)

    private var lastCalibrationStatus = "Calibration: waiting"

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
                    correctedGyro = gyroSmoothing.update(corrected.first).deadband(GYRO_DEADBAND)
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
                    val packet = createPacketIfNeeded(frame.timestampNanos, movement.first, movement.second, gesture)
                    output(frame.timestampNanos, movement.first, movement.second, packet, gesture)
                }

                else -> null
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun computeMovement(euler: Vec3, dt: Float): Pair<Float, Float> {
        val movement = when (movementSource) {
            MovementSource.GYRO_RATE -> {
                val dx = deadband(correctedGyro.z, MOVEMENT_GYRO_DEADBAND) * MOVEMENT_SENSITIVITY
                val dy = -deadband(correctedGyro.x, MOVEMENT_GYRO_DEADBAND) * MOVEMENT_SENSITIVITY
                dx to dy
            }
            MovementSource.ORIENTATION_DELTA -> {
                val previous = previousEuler
                previousEuler = euler
                if (previous == null || dt <= 0f) {
                    0f to 0f
                } else {
                    val dx = deadband(euler.z - previous.z, ORIENTATION_DEADBAND) * ORIENTATION_SENSITIVITY
                    val dy = -deadband(euler.x - previous.x, ORIENTATION_DEADBAND) * ORIENTATION_SENSITIVITY
                    dx to dy
                }
            }
        }

        return clamp(movement.first, -MAX_DELTA, MAX_DELTA) to
                clamp(movement.second, -MAX_DELTA, MAX_DELTA)
    }

    private fun detectGesture(nowNanos: Long): GestureKind? {
        val gyro = correctedGyro
        val accel = linearAccel
        val yGyroDominant = abs(gyro.y) > abs(gyro.x) * AXIS_DOMINANCE_RATIO &&
                abs(gyro.y) > abs(gyro.z) * AXIS_DOMINANCE_RATIO
        val clickSignValue = gyro.y * LEFT_CLICK_SIGN
        val clickAllowed = nowNanos - lastClickNanos > GESTURE_COOLDOWN_NANOS &&
                nowNanos - lastScrollNanos > GESTURE_MUTEX_NANOS

        if (clickAllowed && yGyroDominant && clickSignValue > CLICK_THRESHOLD_RAD_PER_SEC) {
            lastClickNanos = nowNanos
            return GestureKind.CLICK
        }

        val yAccelDominant = abs(accel.y) > abs(accel.x) * AXIS_DOMINANCE_RATIO &&
                abs(accel.y) > abs(accel.z) * AXIS_DOMINANCE_RATIO
        val rotating = abs(gyro.x) + abs(gyro.y) + abs(gyro.z) > ROTATION_SUPPRESSION_THRESHOLD
        val scrollAllowed = nowNanos - lastScrollNanos > GESTURE_COOLDOWN_NANOS &&
                nowNanos - lastClickNanos > GESTURE_MUTEX_NANOS

        if (scrollAllowed && yAccelDominant && !rotating && abs(accel.y) > SCROLL_ACCEL_THRESHOLD) {
            lastScrollNanos = nowNanos
            val positiveMeansUp = !INVERT_SCROLL_DIRECTION
            return if ((accel.y > 0f) == positiveMeansUp) GestureKind.SCROLL_UP else GestureKind.SCROLL_DOWN
        }

        return null
    }

    private fun createPacketIfNeeded(
        sensorTimestampNanos: Long,
        deltaX: Float,
        deltaY: Float,
        gesture: GestureKind?
    ): MousePacket? {
        if (gesture != null) {
            return when (gesture) {
                GestureKind.CLICK -> packetFactory(sensorTimestampNanos, 0f, 0f, true, 0, true)
                GestureKind.SCROLL_UP -> packetFactory(sensorTimestampNanos, 0f, 0f, false, 1, true)
                GestureKind.SCROLL_DOWN -> packetFactory(sensorTimestampNanos, 0f, 0f, false, -1, true)
            }
        }

        if (sensorTimestampNanos - lastMovementPacketNanos < MOVEMENT_PACKET_INTERVAL_NANOS) return null
        if (abs(deltaX) < MOVEMENT_PACKET_DEADZONE && abs(deltaY) < MOVEMENT_PACKET_DEADZONE) return null
        lastMovementPacketNanos = sensorTimestampNanos
        return packetFactory(sensorTimestampNanos, deltaX, deltaY, false, 0, false)
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
            filterMode = filterMode
        )
    }

    companion object {
        private const val MOVEMENT_SENSITIVITY = 8.0f
        private const val ORIENTATION_SENSITIVITY = 300.0f
        private const val MAX_DELTA = 18.0f
        private const val GYRO_DEADBAND = 0.015f
        private const val MOVEMENT_GYRO_DEADBAND = 0.025f
        private const val ORIENTATION_DEADBAND = 0.0015f
        private const val LINEAR_ACCEL_DEADBAND = 0.20f
        private const val MOVEMENT_PACKET_DEADZONE = 0.08f
        private const val MOVEMENT_PACKET_INTERVAL_NANOS = 20_000_000L
        private const val GESTURE_COOLDOWN_NANOS = 700_000_000L
        private const val GESTURE_MUTEX_NANOS = 250_000_000L
        private const val CLICK_THRESHOLD_RAD_PER_SEC = 2.5f
        private const val SCROLL_ACCEL_THRESHOLD = 3.2f
        private const val ROTATION_SUPPRESSION_THRESHOLD = 1.2f
        private const val AXIS_DOMINANCE_RATIO = 1.8f
        private const val LEFT_CLICK_SIGN = -1f
        private const val INVERT_SCROLL_DIRECTION = false
    }
}
