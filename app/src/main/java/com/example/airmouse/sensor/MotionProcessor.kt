package com.example.airmouse.sensor

import android.os.Trace
import com.example.airmouse.MousePacket
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

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
    private var clickFreezeUntilNanos = 0L
    private var scrollFreezeUntilNanos = 0L
    private var lastScrollAccelNanos = 0L
    private var scrollPeakSignedY = 0f
    private var scrollImpulseSignedY = 0f
    private var activeGestureSettle = ActiveGestureSettle.NONE
    private var gestureSettleMaxUntilNanos = 0L
    private var gestureStableSinceNanos = 0L
    private var baselineGyroNorm = 0f
    private var baselineAccelNorm = 0f
    private var baselineInitialized = false
    private var settleReferenceGyroNorm = 0f
    private var settleReferenceAccelNorm = 0f
    private var lastSettleDebug = "Settle: idle"
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
        clickFreezeUntilNanos = 0L
        scrollFreezeUntilNanos = 0L
        lastScrollAccelNanos = 0L
        scrollPeakSignedY = 0f
        scrollImpulseSignedY = 0f
        activeGestureSettle = ActiveGestureSettle.NONE
        gestureSettleMaxUntilNanos = 0L
        gestureStableSinceNanos = 0L
        baselineGyroNorm = 0f
        baselineAccelNorm = 0f
        baselineInitialized = false
        settleReferenceGyroNorm = 0f
        settleReferenceAccelNorm = 0f
        lastSettleDebug = "Settle: reset"
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
                    updateScrollWindow(frame.timestampNanos)
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

                    val gestureState = detectGestureState(frame.timestampNanos)
                    val movement = computeMovement(q.toEulerRadians(), dt, gestureState)
                    val gesture = gestureState.gesture
                    val packet = createPacketIfNeeded(frame.timestampNanos, movement.deltaX, movement.deltaY, gesture)
                    output(frame.timestampNanos, movement.deltaX, movement.deltaY, packet, gesture)
                }

                else -> null
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun updateScrollWindow(nowNanos: Long) {
        val signedY = linearAccel.y * SCROLL_SIGN
        val dt = safeDt(nowNanos, lastScrollAccelNanos, 0.02f)
        val elapsedNanos = if (lastScrollAccelNanos <= 0L) 0L else nowNanos - lastScrollAccelNanos
        val decay = if (lastScrollAccelNanos <= 0L || elapsedNanos >= SCROLL_WINDOW_NANOS) {
            0f
        } else {
            1f - (elapsedNanos.toFloat() / SCROLL_WINDOW_NANOS.toFloat()).coerceIn(0f, 1f)
        }

        scrollPeakSignedY *= decay
        if (abs(signedY) > abs(scrollPeakSignedY)) {
            scrollPeakSignedY = signedY
        }

        scrollImpulseSignedY = scrollImpulseSignedY * decay + signedY * dt
        lastScrollAccelNanos = nowNanos
    }

    private fun clearScrollWindow() {
        scrollPeakSignedY = 0f
        scrollImpulseSignedY = 0f
        lastScrollAccelNanos = 0L
    }

    private fun computeMovement(
        euler: Vec3,
        dt: Float,
        gestureState: GestureState
    ): MovementComputation {
        val sourceMovement = when (movementSource) {
            MovementSource.GYRO_RATE -> {
                val horizontalRate = correctedGyro.z * HORIZONTAL_SIGN
                val verticalRate = correctedGyro.x * VERTICAL_SIGN
                val dx = precisionCurve(horizontalRate) * HORIZONTAL_SENSITIVITY
                val dy = precisionCurve(verticalRate) * VERTICAL_SENSITIVITY
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
        var suppressionReason = "none"
        val absX = abs(dx)
        val absY = abs(dy)
        if (gestureState.suppressMovement) {
            dx = 0f
            dy = 0f
            suppressionReason = gestureState.suppressionReason
        } else if (absX > 0f || absY > 0f) {
            if (absX > absY * MOVEMENT_AXIS_DOMINANCE_RATIO && absY <= AXIS_LEAKAGE_MAX_DELTA) {
                dy = 0f
                suppressionReason = "Y leakage"
            } else if (absY > absX * MOVEMENT_AXIS_DOMINANCE_RATIO && absX <= AXIS_LEAKAGE_MAX_DELTA) {
                dx = 0f
                suppressionReason = "X leakage"
            }
        }

        val clampedX = clamp(dx, -MAX_DELTA, MAX_DELTA)
        val clampedY = clamp(dy, -MAX_DELTA, MAX_DELTA)
        val clamped = clampedX != dx || clampedY != dy

        lastMovementDebug = String.format(
            Locale.US,
            "Movement cfg: hSign %.0f vSign %.0f sensX %.1f sensY %.1f deadband %.3f precision %.2f lowGain %.2f highGain %.2f exponent %.2f smoothAlpha %.2f clamp %.1f interval %.0fms\n" +
                    "Movement mode=%s suppressed=%s raw gyro X %.3f Z %.3f signed rates X %.3f Y %.3f scaled %.2f/%.2f final %.2f/%.2f clamped=%s",
            HORIZONTAL_SIGN,
            VERTICAL_SIGN,
            HORIZONTAL_SENSITIVITY,
            VERTICAL_SENSITIVITY,
            MOVEMENT_GYRO_DEADBAND,
            PRECISION_ZONE_RAD_PER_SEC,
            LOW_SPEED_GAIN,
            HIGH_SPEED_GAIN,
            RESPONSE_EXPONENT,
            GYRO_SMOOTHING_ALPHA,
            MAX_DELTA,
            MOVEMENT_PACKET_INTERVAL_NANOS / 1_000_000f,
            gestureState.mode,
            suppressionReason,
            correctedGyro.x,
            correctedGyro.z,
            sourceMovement.signedX,
            sourceMovement.signedY,
            sourceMovement.scaledX,
            sourceMovement.scaledY,
            clampedX,
            clampedY,
            clamped
        )

        return MovementComputation(clampedX, clampedY)
    }

    private fun precisionCurve(value: Float): Float {
        val sign = if (value < 0f) -1f else 1f
        val magnitude = abs(value)
        if (magnitude <= MOVEMENT_GYRO_DEADBAND) return 0f

        val adjusted = magnitude - MOVEMENT_GYRO_DEADBAND
        val precisionPortion = (adjusted / PRECISION_ZONE_RAD_PER_SEC).coerceIn(0f, 1f)
        val curvedNearZero = precisionPortion
            .toDouble()
            .pow(RESPONSE_EXPONENT.toDouble())
            .toFloat() * PRECISION_ZONE_RAD_PER_SEC * LOW_SPEED_GAIN
        val fastPortion = max(0f, adjusted - PRECISION_ZONE_RAD_PER_SEC) * HIGH_SPEED_GAIN
        return sign * (curvedNearZero + fastPortion)
    }

    private fun updateIdleBaseline() {
        val gyroNorm = correctedGyro.norm()
        val accelNorm = linearAccel.norm()
        if (!baselineInitialized) {
            baselineGyroNorm = gyroNorm
            baselineAccelNorm = accelNorm
            baselineInitialized = true
            return
        }
        baselineGyroNorm = baselineGyroNorm * (1f - SETTLE_BASELINE_ALPHA) + gyroNorm * SETTLE_BASELINE_ALPHA
        baselineAccelNorm = baselineAccelNorm * (1f - SETTLE_BASELINE_ALPHA) + accelNorm * SETTLE_BASELINE_ALPHA
    }

    private fun startGestureSettle(
        kind: ActiveGestureSettle,
        nowNanos: Long,
        minFreezeNanos: Long,
        maxSettleNanos: Long
    ) {
        if (!baselineInitialized) {
            baselineGyroNorm = 0f
            baselineAccelNorm = 0f
            baselineInitialized = true
        }
        activeGestureSettle = kind
        gestureSettleMaxUntilNanos = nowNanos + maxSettleNanos
        gestureStableSinceNanos = 0L
        settleReferenceGyroNorm = baselineGyroNorm
        settleReferenceAccelNorm = baselineAccelNorm
        when (kind) {
            ActiveGestureSettle.CLICK -> clickFreezeUntilNanos = nowNanos + minFreezeNanos
            ActiveGestureSettle.SCROLL -> scrollFreezeUntilNanos = nowNanos + minFreezeNanos
            ActiveGestureSettle.NONE -> Unit
        }
    }

    private fun activeSettleState(nowNanos: Long): GestureState? {
        val kind = activeGestureSettle
        if (kind == ActiveGestureSettle.NONE) return null

        val minUntilNanos = when (kind) {
            ActiveGestureSettle.CLICK -> clickFreezeUntilNanos
            ActiveGestureSettle.SCROLL -> scrollFreezeUntilNanos
            ActiveGestureSettle.NONE -> 0L
        }
        val minRemainingMs = remainingMs(minUntilNanos, nowNanos)
        val maxRemainingMs = remainingMs(gestureSettleMaxUntilNanos, nowNanos)
        val gyroNorm = correctedGyro.norm()
        val accelNorm = linearAccel.norm()
        val gyroLimit = max(settleReferenceGyroNorm + SETTLE_GYRO_MARGIN, SETTLE_GYRO_ABSOLUTE_LIMIT)
        val accelLimit = max(settleReferenceAccelNorm + SETTLE_ACCEL_MARGIN, SETTLE_ACCEL_ABSOLUTE_LIMIT)
        val stableNow = gyroNorm <= gyroLimit && accelNorm <= accelLimit

        if (stableNow) {
            if (gestureStableSinceNanos <= 0L) gestureStableSinceNanos = nowNanos
        } else {
            gestureStableSinceNanos = 0L
        }

        val stableDurationNanos = if (gestureStableSinceNanos <= 0L) {
            0L
        } else {
            nowNanos - gestureStableSinceNanos
        }
        val stableEnough = stableDurationNanos >= SETTLE_STABLE_NANOS
        val minFreezeDone = minRemainingMs <= 0f
        val maxWaitExpired = maxRemainingMs <= 0f

        lastSettleDebug = String.format(
            Locale.US,
            "Settle kind=%s active=%s minRemainingMs %.0f maxRemainingMs %.0f stable=%s stableForMs %.0f gyroNorm %.3f limit %.3f accelNorm %.3f limit %.3f baseline %.3f/%.3f",
            kind.name,
            !minFreezeDone || (!stableEnough && !maxWaitExpired),
            minRemainingMs,
            maxRemainingMs,
            stableNow,
            stableDurationNanos / 1_000_000f,
            gyroNorm,
            gyroLimit,
            accelNorm,
            accelLimit,
            settleReferenceGyroNorm,
            settleReferenceAccelNorm
        )

        if (!minFreezeDone || (!stableEnough && !maxWaitExpired)) {
            val mode = when (kind) {
                ActiveGestureSettle.CLICK -> if (!minFreezeDone) "clickFreeze" else "clickSettle"
                ActiveGestureSettle.SCROLL -> if (!minFreezeDone) "scrollFreeze" else "scrollSettle"
                ActiveGestureSettle.NONE -> "cursor"
            }
            val reason = when (kind) {
                ActiveGestureSettle.CLICK -> if (!minFreezeDone) "click freeze" else "click sensor settle"
                ActiveGestureSettle.SCROLL -> if (!minFreezeDone) "scroll freeze" else "scroll sensor settle"
                ActiveGestureSettle.NONE -> "none"
            }
            return GestureState(null, true, reason, mode)
        }

        activeGestureSettle = ActiveGestureSettle.NONE
        gestureStableSinceNanos = 0L
        updateIdleBaseline()
        if (kind == ActiveGestureSettle.SCROLL) {
            clearScrollWindow()
            scrollArmed = false
        }
        return GestureState(null, true, "${kind.name.lowercase(Locale.US)} settle release", "${kind.name.lowercase(Locale.US)}SettleRelease")
    }

    private fun detectGestureState(nowNanos: Long): GestureState {
        activeSettleState(nowNanos)?.let { settleState ->
            val clickSignValue = correctedGyro.y * CLICK_SIGN
            lastGestureDebug = gestureDebug(
                mode = settleState.mode,
                clickSignValue = clickSignValue,
                clickCandidate = false,
                clickAllowed = false,
                scrollCandidate = false,
                scrollAllowed = false,
                scrollBlockedReason = settleState.suppressionReason,
                clickFreezeRemainingMs = remainingMs(clickFreezeUntilNanos, nowNanos),
                scrollFreezeRemainingMs = remainingMs(scrollFreezeUntilNanos, nowNanos)
            )
            return settleState
        }

        val gyro = correctedGyro
        val accel = linearAccel
        val yGyroDominant = abs(gyro.y) > abs(gyro.x) * AXIS_DOMINANCE_RATIO &&
                abs(gyro.y) > abs(gyro.z) * AXIS_DOMINANCE_RATIO
        val clickSignValue = gyro.y * CLICK_SIGN
        val clickAllowed = nowNanos - lastClickNanos > GESTURE_COOLDOWN_NANOS &&
                nowNanos - lastScrollNanos > GESTURE_MUTEX_NANOS
        val clickFreezeRemainingMs = remainingMs(clickFreezeUntilNanos, nowNanos)
        val clickCandidate = yGyroDominant && clickSignValue > CLICK_CANDIDATE_THRESHOLD_RAD_PER_SEC

        if (clickFreezeRemainingMs > 0f) {
            lastGestureDebug = gestureDebug(
                mode = "clickFreeze",
                clickSignValue = clickSignValue,
                clickCandidate = clickCandidate,
                clickAllowed = clickAllowed,
                scrollCandidate = false,
                scrollAllowed = false,
                scrollBlockedReason = "click freeze active",
                clickFreezeRemainingMs = clickFreezeRemainingMs,
                scrollFreezeRemainingMs = remainingMs(scrollFreezeUntilNanos, nowNanos)
            )
            return GestureState(null, true, "click freeze", "clickFreeze")
        }

        if (clickAllowed && yGyroDominant && clickSignValue > CLICK_THRESHOLD_RAD_PER_SEC) {
            lastClickNanos = nowNanos
            startGestureSettle(
                ActiveGestureSettle.CLICK,
                nowNanos,
                CLICK_FREEZE_NANOS,
                CLICK_MAX_SETTLE_NANOS
            )
            lastGestureDebug = gestureDebug(
                mode = "clickFreeze",
                clickSignValue = clickSignValue,
                clickCandidate = true,
                clickAllowed = true,
                scrollCandidate = false,
                scrollAllowed = false,
                scrollBlockedReason = "click emitted",
                clickFreezeRemainingMs = CLICK_FREEZE_NANOS / 1_000_000f,
                scrollFreezeRemainingMs = remainingMs(scrollFreezeUntilNanos, nowNanos)
            )
            return GestureState(GestureKind.CLICK, true, "click freeze", "clickFreeze")
        }

        if (clickCandidate) {
            lastGestureDebug = gestureDebug(
                mode = "clickCandidate",
                clickSignValue = clickSignValue,
                clickCandidate = true,
                clickAllowed = clickAllowed,
                scrollCandidate = false,
                scrollAllowed = false,
                scrollBlockedReason = "click candidate has priority",
                clickFreezeRemainingMs = 0f,
                scrollFreezeRemainingMs = remainingMs(scrollFreezeUntilNanos, nowNanos)
            )
            return GestureState(null, true, "click candidate", "clickCandidate")
        }

        val yAccelDominant = abs(accel.y) > abs(accel.x) * AXIS_DOMINANCE_RATIO &&
                abs(accel.y) > abs(accel.z) * AXIS_DOMINANCE_RATIO
        val heavyRotation = abs(gyro.x) + abs(gyro.y) + abs(gyro.z) > SCROLL_HEAVY_ROTATION_SUPPRESSION_THRESHOLD
        val scrollAllowed = nowNanos - lastScrollNanos > SCROLL_GESTURE_COOLDOWN_NANOS &&
                nowNanos - lastClickNanos > GESTURE_MUTEX_NANOS
        val scrollSignal = accel.y * SCROLL_SIGN
        val scrollMagnitude = abs(scrollSignal)
        val scrollPeakMagnitude = abs(scrollPeakSignedY)
        val scrollImpulseMagnitude = abs(scrollImpulseSignedY)
        val scrollCandidate = scrollArmed &&
                yAccelDominant &&
                (exceedsDirectionalThreshold(scrollSignal, SCROLL_UP_CANDIDATE_ACCEL_THRESHOLD, SCROLL_DOWN_CANDIDATE_ACCEL_THRESHOLD) ||
                        exceedsDirectionalThreshold(scrollPeakSignedY, SCROLL_UP_CANDIDATE_ACCEL_THRESHOLD, SCROLL_DOWN_CANDIDATE_ACCEL_THRESHOLD) ||
                        exceedsDirectionalThreshold(scrollImpulseSignedY, SCROLL_UP_CANDIDATE_IMPULSE_THRESHOLD, SCROLL_DOWN_CANDIDATE_IMPULSE_THRESHOLD))
        val scrollTrigger = scrollArmed &&
                yAccelDominant &&
                !heavyRotation &&
                (exceedsDirectionalThreshold(scrollSignal, SCROLL_UP_ACCEL_THRESHOLD, SCROLL_DOWN_ACCEL_THRESHOLD) ||
                        exceedsDirectionalThreshold(scrollPeakSignedY, SCROLL_UP_PEAK_THRESHOLD, SCROLL_DOWN_PEAK_THRESHOLD) ||
                        exceedsDirectionalThreshold(scrollImpulseSignedY, SCROLL_UP_IMPULSE_THRESHOLD, SCROLL_DOWN_IMPULSE_THRESHOLD))
        val scrollFreezeRemainingMs = remainingMs(scrollFreezeUntilNanos, nowNanos)

        if (scrollMagnitude < SCROLL_REARM_ACCEL_THRESHOLD &&
            scrollPeakMagnitude < SCROLL_UP_CANDIDATE_ACCEL_THRESHOLD &&
            scrollImpulseMagnitude < SCROLL_UP_CANDIDATE_IMPULSE_THRESHOLD
        ) {
            scrollArmed = true
        }

        if (scrollFreezeRemainingMs > 0f) {
            lastGestureDebug = gestureDebug(
                mode = "scrollFreeze",
                clickSignValue = clickSignValue,
                clickCandidate = false,
                clickAllowed = clickAllowed,
                scrollCandidate = scrollCandidate,
                scrollAllowed = scrollAllowed,
                scrollBlockedReason = "scroll freeze active",
                clickFreezeRemainingMs = 0f,
                scrollFreezeRemainingMs = scrollFreezeRemainingMs,
                yAccelDominant = yAccelDominant,
                heavyRotation = heavyRotation
            )
            return GestureState(null, true, "scroll freeze", "scrollFreeze")
        }

        if (scrollAllowed && scrollTrigger) {
            lastScrollNanos = nowNanos
            startGestureSettle(
                ActiveGestureSettle.SCROLL,
                nowNanos,
                SCROLL_FREEZE_NANOS,
                SCROLL_MAX_SETTLE_NANOS
            )
            scrollArmed = false
            clearScrollWindow()
            val gesture = if (strongestScrollSignal(scrollSignal) > 0f) GestureKind.SCROLL_UP else GestureKind.SCROLL_DOWN
            lastGestureDebug = gestureDebug(
                mode = "scrollFreeze",
                clickSignValue = clickSignValue,
                clickCandidate = false,
                clickAllowed = clickAllowed,
                scrollCandidate = true,
                scrollAllowed = true,
                scrollBlockedReason = "scroll emitted",
                clickFreezeRemainingMs = 0f,
                scrollFreezeRemainingMs = SCROLL_FREEZE_NANOS / 1_000_000f,
                yAccelDominant = yAccelDominant,
                heavyRotation = heavyRotation
            )
            return GestureState(gesture, true, "scroll freeze", "scrollFreeze")
        }

        if (scrollCandidate) {
            lastGestureDebug = gestureDebug(
                mode = "scrollCandidate",
                clickSignValue = clickSignValue,
                clickCandidate = false,
                clickAllowed = clickAllowed,
                scrollCandidate = true,
                scrollAllowed = scrollAllowed,
                scrollBlockedReason = scrollBlockedReason(scrollAllowed, yAccelDominant, heavyRotation),
                clickFreezeRemainingMs = 0f,
                scrollFreezeRemainingMs = 0f,
                yAccelDominant = yAccelDominant,
                heavyRotation = heavyRotation
            )
            return GestureState(null, true, "scroll candidate", "scrollCandidate")
        }

        updateIdleBaseline()
        lastGestureDebug = gestureDebug(
            mode = "cursor",
            clickSignValue = clickSignValue,
            clickCandidate = false,
            clickAllowed = clickAllowed,
            scrollCandidate = false,
            scrollAllowed = scrollAllowed,
            scrollBlockedReason = scrollBlockedReason(scrollAllowed, yAccelDominant, heavyRotation),
            clickFreezeRemainingMs = 0f,
            scrollFreezeRemainingMs = 0f,
            yAccelDominant = yAccelDominant,
            heavyRotation = heavyRotation
        )
        return GestureState(null, false, "none", "cursor")
    }

    private fun strongestScrollSignal(currentSignal: Float): Float {
        val peak = scrollPeakSignedY
        val impulse = scrollImpulseSignedY / SCROLL_IMPULSE_DIRECTION_NORMALIZER
        return listOf(currentSignal, peak, impulse).maxByOrNull { abs(it) } ?: currentSignal
    }

    private fun exceedsDirectionalThreshold(
        signedValue: Float,
        upThreshold: Float,
        downThreshold: Float
    ): Boolean {
        return if (signedValue >= 0f) {
            signedValue > upThreshold
        } else {
            abs(signedValue) > downThreshold
        }
    }

    private fun scrollBlockedReason(
        scrollAllowed: Boolean,
        yAccelDominant: Boolean,
        heavyRotation: Boolean
    ): String {
        return when {
            !scrollAllowed -> "cooldown/mutex"
            !scrollArmed -> "not rearmed"
            !yAccelDominant -> "Y not dominant"
            heavyRotation -> "heavy rotation"
            else -> "below threshold"
        }
    }

    private fun gestureDebug(
        mode: String,
        clickSignValue: Float,
        clickCandidate: Boolean,
        clickAllowed: Boolean,
        scrollCandidate: Boolean,
        scrollAllowed: Boolean,
        scrollBlockedReason: String,
        clickFreezeRemainingMs: Float,
        scrollFreezeRemainingMs: Float,
        yAccelDominant: Boolean = false,
        heavyRotation: Boolean = false
    ): String {
        return String.format(
            Locale.US,
            "Gesture mode=%s cursorFrozenForClick=%s clickCandidate=%s clickY raw %.3f signed %.3f threshold %.2f candidate %.2f allowed=%s clickFreezeRemainingMs %.0f\n" +
                    "Scroll candidate=%s linearY raw %.3f signed %.3f peakY %.3f impulseY %.3f upThresholds %.2f/%.2f/%.2f downThresholds %.2f/%.2f/%.2f armed=%s allowed=%s dom=%s heavyRotation=%s scrollFreezeRemainingMs %.0f scrollBlockedReason=%s\n%s",
            mode,
            mode.startsWith("click"),
            clickCandidate,
            correctedGyro.y,
            clickSignValue,
            CLICK_THRESHOLD_RAD_PER_SEC,
            CLICK_CANDIDATE_THRESHOLD_RAD_PER_SEC,
            clickAllowed,
            clickFreezeRemainingMs,
            scrollCandidate,
            linearAccel.y,
            linearAccel.y * SCROLL_SIGN,
            scrollPeakSignedY,
            scrollImpulseSignedY,
            SCROLL_UP_ACCEL_THRESHOLD,
            SCROLL_UP_PEAK_THRESHOLD,
            SCROLL_UP_IMPULSE_THRESHOLD,
            SCROLL_DOWN_ACCEL_THRESHOLD,
            SCROLL_DOWN_PEAK_THRESHOLD,
            SCROLL_DOWN_IMPULSE_THRESHOLD,
            scrollArmed,
            scrollAllowed,
            yAccelDominant,
            heavyRotation,
            scrollFreezeRemainingMs,
            scrollBlockedReason,
            lastSettleDebug
        )
    }

    private fun remainingMs(untilNanos: Long, nowNanos: Long): Float {
        return if (untilNanos <= nowNanos) 0f else (untilNanos - nowNanos) / 1_000_000f
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

    private data class GestureState(
        val gesture: GestureKind?,
        val suppressMovement: Boolean,
        val suppressionReason: String,
        val mode: String
    )

    private enum class ActiveGestureSettle { NONE, CLICK, SCROLL }

    companion object {
        private const val HORIZONTAL_SIGN = -1f
        private const val VERTICAL_SIGN = -1f
        private const val SCROLL_SIGN = -1f
        private const val CLICK_SIGN = -1f
        private const val HORIZONTAL_SENSITIVITY = 12.4f
        private const val VERTICAL_SENSITIVITY = 12.4f
        private const val ORIENTATION_SENSITIVITY = 300.0f
        private const val MAX_DELTA = 12.0f
        private const val GYRO_SMOOTHING_ALPHA = 0.58f
        private const val GYRO_FILTER_DEADBAND = 0.004f
        private const val MOVEMENT_GYRO_DEADBAND = 0.012f
        private const val PRECISION_ZONE_RAD_PER_SEC = 0.12f
        private const val LOW_SPEED_GAIN = 0.36f
        private const val HIGH_SPEED_GAIN = 1.00f
        private const val RESPONSE_EXPONENT = 1.45f
        private const val ORIENTATION_DEADBAND = 0.0015f
        private const val LINEAR_ACCEL_DEADBAND = 0.12f
        private const val MOVEMENT_PACKET_DEADZONE = 0.03f
        private const val MOVEMENT_PACKET_INTERVAL_NANOS = 16_000_000L
        private const val GESTURE_COOLDOWN_NANOS = 700_000_000L
        private const val SCROLL_GESTURE_COOLDOWN_NANOS = 950_000_000L
        private const val GESTURE_MUTEX_NANOS = 250_000_000L
        private const val CLICK_THRESHOLD_RAD_PER_SEC = 2.25f
        private const val CLICK_CANDIDATE_THRESHOLD_RAD_PER_SEC = 1.25f
        private const val CLICK_FREEZE_NANOS = 95_000_000L
        private const val CLICK_MAX_SETTLE_NANOS = 300_000_000L
        private const val SCROLL_UP_ACCEL_THRESHOLD = 1.72f
        private const val SCROLL_UP_PEAK_THRESHOLD = 1.82f
        private const val SCROLL_UP_IMPULSE_THRESHOLD = 0.105f
        private const val SCROLL_DOWN_ACCEL_THRESHOLD = 2.05f
        private const val SCROLL_DOWN_PEAK_THRESHOLD = 2.15f
        private const val SCROLL_DOWN_IMPULSE_THRESHOLD = 0.13f
        private const val SCROLL_UP_CANDIDATE_ACCEL_THRESHOLD = 0.85f
        private const val SCROLL_UP_CANDIDATE_IMPULSE_THRESHOLD = 0.05f
        private const val SCROLL_DOWN_CANDIDATE_ACCEL_THRESHOLD = 1.15f
        private const val SCROLL_DOWN_CANDIDATE_IMPULSE_THRESHOLD = 0.07f
        private const val SCROLL_REARM_ACCEL_THRESHOLD = 0.65f
        private const val SCROLL_WINDOW_NANOS = 140_000_000L
        private const val SCROLL_FREEZE_NANOS = 240_000_000L
        private const val SCROLL_MAX_SETTLE_NANOS = 750_000_000L
        private const val SCROLL_HEAVY_ROTATION_SUPPRESSION_THRESHOLD = 3.2f
        private const val SCROLL_IMPULSE_DIRECTION_NORMALIZER = 0.08f
        private const val SETTLE_BASELINE_ALPHA = 0.08f
        private const val SETTLE_STABLE_NANOS = 55_000_000L
        private const val SETTLE_GYRO_ABSOLUTE_LIMIT = 0.24f
        private const val SETTLE_GYRO_MARGIN = 0.11f
        private const val SETTLE_ACCEL_ABSOLUTE_LIMIT = 0.75f
        private const val SETTLE_ACCEL_MARGIN = 0.35f
        private const val AXIS_DOMINANCE_RATIO = 1.8f
        private const val MOVEMENT_AXIS_DOMINANCE_RATIO = 2.2f
        private const val AXIS_LEAKAGE_MAX_DELTA = 2.2f
    }
}
