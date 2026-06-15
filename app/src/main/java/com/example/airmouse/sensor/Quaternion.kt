package com.example.airmouse.sensor

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (!n.isFinite() || n < Vec3.EPSILON) return IDENTITY
        return Quaternion(w / n, x / n, y / n, z / n)
    }

    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w * other.w - x * other.x - y * other.y - z * other.z,
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w
        )
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    fun integrateGyro(gyro: Vec3, dt: Float): Quaternion {
        val qDot = this * Quaternion(0f, gyro.x, gyro.y, gyro.z)
        return Quaternion(
            w + 0.5f * qDot.w * dt,
            x + 0.5f * qDot.x * dt,
            y + 0.5f * qDot.y * dt,
            z + 0.5f * qDot.z * dt
        ).normalized()
    }

    fun toEulerRadians(): Vec3 {
        val roll = atan2(
            2f * (w * x + y * z),
            1f - 2f * (x * x + y * y)
        )
        val pitchInput = (2f * (w * y - z * x)).coerceIn(-1f, 1f)
        val pitch = asin(pitchInput)
        val yaw = atan2(
            2f * (w * z + x * y),
            1f - 2f * (y * y + z * z)
        )
        return Vec3(roll, pitch, yaw)
    }

    fun isUsable(): Boolean = w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)
    }
}
