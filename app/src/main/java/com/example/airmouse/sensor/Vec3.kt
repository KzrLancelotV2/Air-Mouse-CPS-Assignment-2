package com.example.airmouse.sensor

import kotlin.math.abs
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)

    fun norm(): Float = sqrt(x * x + y * y + z * z)
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    fun normalizedOrNull(): Vec3? {
        val n = norm()
        if (!n.isFinite() || n < EPSILON) return null
        return Vec3(x / n, y / n, z / n)
    }

    fun deadband(threshold: Float): Vec3 {
        return Vec3(deadband(x, threshold), deadband(y, threshold), deadband(z, threshold))
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        const val EPSILON = 1.0e-6f
    }
}

fun deadband(value: Float, threshold: Float): Float {
    return if (abs(value) < threshold) 0f else value
}

fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

fun safeDt(currentNanos: Long, previousNanos: Long, fallback: Float = 0.02f): Float {
    if (previousNanos <= 0L || currentNanos <= previousNanos) return fallback
    val dt = (currentNanos - previousNanos) * 1.0e-9f
    return if (dt.isFinite() && dt > 0f && dt <= 0.2f) dt else fallback
}
