package com.example.airmouse.sensor

import com.example.airmouse.TraceUtils
import kotlin.math.sqrt

class MadgwickAhrs(
    private val imuBeta: Float = 0.033f,
    private val margBeta: Float = 0.041f
) {
    var quaternion: Quaternion = Quaternion.IDENTITY
        private set

    fun reset() {
        quaternion = Quaternion.IDENTITY
    }

    fun updateIMU(gyro: Vec3, accel: Vec3, dt: Float): Quaternion {
        return TraceUtils.section("AirMouse_Madgwick_Update") {
            val accelUnit = accel.normalizedOrNull()
            if (accelUnit == null) {
                quaternion = quaternion.integrateGyro(gyro, dt)
                return@section quaternion
            }

            var q = quaternion
            val q1 = q.w
            val q2 = q.x
            val q3 = q.y
            val q4 = q.z
            val ax = accelUnit.x
            val ay = accelUnit.y
            val az = accelUnit.z

            val f1 = 2f * (q2 * q4 - q1 * q3) - ax
            val f2 = 2f * (q1 * q2 + q3 * q4) - ay
            val f3 = 2f * (0.5f - q2 * q2 - q3 * q3) - az

            var s1 = -2f * q3 * f1 + 2f * q2 * f2
            var s2 = 2f * q4 * f1 + 2f * q1 * f2 - 4f * q2 * f3
            var s3 = -2f * q1 * f1 + 2f * q4 * f2 - 4f * q3 * f3
            var s4 = 2f * q2 * f1 + 2f * q3 * f2

            val recipNorm = invNorm(s1, s2, s3, s4)
            if (recipNorm > 0f) {
                s1 *= recipNorm
                s2 *= recipNorm
                s3 *= recipNorm
                s4 *= recipNorm
            }

            val qDot = q * Quaternion(0f, gyro.x, gyro.y, gyro.z)
            // Beta controls correction strength from accel/mag versus gyro integration.
            q = Quaternion(
                q.w + (0.5f * qDot.w - imuBeta * s1) * dt,
                q.x + (0.5f * qDot.x - imuBeta * s2) * dt,
                q.y + (0.5f * qDot.y - imuBeta * s3) * dt,
                q.z + (0.5f * qDot.z - imuBeta * s4) * dt
            ).normalized()
            quaternion = if (q.isUsable()) q else Quaternion.IDENTITY
            quaternion
        }
    }

    fun updateMARG(gyro: Vec3, accel: Vec3, mag: Vec3, dt: Float): Quaternion {
        return TraceUtils.section("AirMouse_Madgwick_Update") {
            val accelUnit = accel.normalizedOrNull() ?: return@section updateIMU(gyro, accel, dt)
            val magUnit = mag.normalizedOrNull() ?: return@section updateIMU(gyro, accel, dt)

            var q0 = quaternion.w
            var q1 = quaternion.x
            var q2 = quaternion.y
            var q3 = quaternion.z
            val ax = accelUnit.x
            val ay = accelUnit.y
            val az = accelUnit.z
            val mx = magUnit.x
            val my = magUnit.y
            val mz = magUnit.z

            val twoQ0mx = 2f * q0 * mx
            val twoQ0my = 2f * q0 * my
            val twoQ0mz = 2f * q0 * mz
            val twoQ1mx = 2f * q1 * mx
            val twoQ0 = 2f * q0
            val twoQ1 = 2f * q1
            val twoQ2 = 2f * q2
            val twoQ3 = 2f * q3
            val q0q0 = q0 * q0
            val q0q1 = q0 * q1
            val q0q2 = q0 * q2
            val q0q3 = q0 * q3
            val q1q1 = q1 * q1
            val q1q2 = q1 * q2
            val q1q3 = q1 * q3
            val q2q2 = q2 * q2
            val q2q3 = q2 * q3
            val q3q3 = q3 * q3

            val hx = mx * q0q0 - twoQ0my * q3 + twoQ0mz * q2 + mx * q1q1 +
                    twoQ1 * my * q2 + twoQ1 * mz * q3 - mx * q2q2 - mx * q3q3
            val hy = twoQ0mx * q3 + my * q0q0 - twoQ0mz * q1 + twoQ1mx * q2 -
                    my * q1q1 + my * q2q2 + twoQ2 * mz * q3 - my * q3q3
            val twoBx = sqrt(hx * hx + hy * hy)
            val twoBz = -twoQ0mx * q2 + twoQ0my * q1 + mz * q0q0 + twoQ1mx * q3 -
                    mz * q1q1 + twoQ2 * my * q3 - mz * q2q2 + mz * q3q3
            val fourBx = 2f * twoBx
            val fourBz = 2f * twoBz

            val f1 = 2f * (q1q3 - q0q2) - ax
            val f2 = 2f * (q0q1 + q2q3) - ay
            val f3 = 1f - 2f * (q1q1 + q2q2) - az
            val f4 = twoBx * (0.5f - q2q2 - q3q3) + twoBz * (q1q3 - q0q2) - mx
            val f5 = twoBx * (q1q2 - q0q3) + twoBz * (q0q1 + q2q3) - my
            val f6 = twoBx * (q0q2 + q1q3) + twoBz * (0.5f - q1q1 - q2q2) - mz

            var s0 = -twoQ2 * f1 + twoQ1 * f2 - twoBz * q2 * f4 +
                    (-twoBx * q3 + twoBz * q1) * f5 + twoBx * q2 * f6
            var s1 = twoQ3 * f1 + twoQ0 * f2 - 4f * q1 * f3 + twoBz * q3 * f4 +
                    (twoBx * q2 + twoBz * q0) * f5 + (twoBx * q3 - fourBz * q1) * f6
            var s2 = -twoQ0 * f1 + twoQ3 * f2 - 4f * q2 * f3 +
                    (-fourBx * q2 - twoBz * q0) * f4 + (twoBx * q1 + twoBz * q3) * f5 +
                    (twoBx * q0 - fourBz * q2) * f6
            var s3 = twoQ1 * f1 + twoQ2 * f2 + (-fourBx * q3 + twoBz * q1) * f4 +
                    (-twoBx * q0 + twoBz * q2) * f5 + twoBx * q1 * f6

            val recipNorm = invNorm(s0, s1, s2, s3)
            if (recipNorm > 0f) {
                s0 *= recipNorm
                s1 *= recipNorm
                s2 *= recipNorm
                s3 *= recipNorm
            }

            val qDot = quaternion * Quaternion(0f, gyro.x, gyro.y, gyro.z)
            // Beta controls correction strength from accel/mag versus gyro integration.
            q0 += (0.5f * qDot.w - margBeta * s0) * dt
            q1 += (0.5f * qDot.x - margBeta * s1) * dt
            q2 += (0.5f * qDot.y - margBeta * s2) * dt
            q3 += (0.5f * qDot.z - margBeta * s3) * dt

            val updated = Quaternion(q0, q1, q2, q3).normalized()
            quaternion = if (updated.isUsable()) updated else Quaternion.IDENTITY
            quaternion
        }
    }

    private fun invNorm(a: Float, b: Float, c: Float, d: Float): Float {
        val n = sqrt(a * a + b * b + c * c + d * d)
        return if (n.isFinite() && n > Vec3.EPSILON) 1f / n else 0f
    }
}
