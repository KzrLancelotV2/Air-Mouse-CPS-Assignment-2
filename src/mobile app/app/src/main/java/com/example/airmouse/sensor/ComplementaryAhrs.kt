package com.example.airmouse.sensor

import com.example.airmouse.TraceUtils

class ComplementaryAhrs(private val gyroBlend: Float = 0.98f) {
    var quaternion: Quaternion = Quaternion.IDENTITY
        private set

    fun reset() {
        quaternion = Quaternion.IDENTITY
    }

    fun update(gyro: Vec3, accel: Vec3, dt: Float): Quaternion {
        return TraceUtils.section("AirMouse_Complementary_Update") {
            val gyroOnly = quaternion.integrateGyro(gyro, dt)
            val a = accel.normalizedOrNull()
            if (a == null) {
                quaternion = gyroOnly
                return@section quaternion
            }

            val roll = kotlin.math.atan2(a.y, a.z)
            val pitch = kotlin.math.atan2(-a.x, kotlin.math.sqrt(a.y * a.y + a.z * a.z))
            val accelQ = fromEuler(roll, pitch, gyroOnly.toEulerRadians().z)
            quaternion = Quaternion(
                gyroOnly.w * gyroBlend + accelQ.w * (1f - gyroBlend),
                gyroOnly.x * gyroBlend + accelQ.x * (1f - gyroBlend),
                gyroOnly.y * gyroBlend + accelQ.y * (1f - gyroBlend),
                gyroOnly.z * gyroBlend + accelQ.z * (1f - gyroBlend)
            ).normalized()
            quaternion
        }
    }

    private fun fromEuler(roll: Float, pitch: Float, yaw: Float): Quaternion {
        val cy = kotlin.math.cos(yaw * 0.5f)
        val sy = kotlin.math.sin(yaw * 0.5f)
        val cp = kotlin.math.cos(pitch * 0.5f)
        val sp = kotlin.math.sin(pitch * 0.5f)
        val cr = kotlin.math.cos(roll * 0.5f)
        val sr = kotlin.math.sin(roll * 0.5f)
        return Quaternion(
            cr * cp * cy + sr * sp * sy,
            sr * cp * cy - cr * sp * sy,
            cr * sp * cy + sr * cp * sy,
            cr * cp * sy - sr * sp * cy
        ).normalized()
    }
}
