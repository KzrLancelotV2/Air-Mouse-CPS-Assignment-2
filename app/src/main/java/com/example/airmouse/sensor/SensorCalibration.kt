package com.example.airmouse.sensor

import android.hardware.SensorManager
import com.example.airmouse.CalibrationStore
import com.example.airmouse.TraceUtils
import kotlin.math.abs

object SensorCalibration {
    fun correctGyro(raw: Vec3): Pair<Vec3, String> {
        return TraceUtils.section("AirMouse_Calibration_Apply") {
            val bias = CalibrationStore.gyroBias
            if (bias == null || bias.size < 3) {
                raw.sanitize() to "Gyro not calibrated"
            } else {
                Vec3(raw.x - bias[0], raw.y - bias[1], raw.z - bias[2]).sanitize() to "Gyro calibrated"
            }
        }
    }

    fun correctAccel(raw: Vec3): Pair<Vec3, String> {
        return TraceUtils.section("AirMouse_Calibration_Apply") {
            val offset = CalibrationStore.accelOffset
            val scale = CalibrationStore.accelScale
            if (offset == null || scale == null || offset.size < 3 || scale.size < 3) {
                raw.sanitize() to "Accel calibration incomplete"
            } else if (scale.any { !it.isFinite() || abs(it) < 0.5f }) {
                raw.sanitize() to "Accel calibration invalid"
            } else {
                val g = SensorManager.GRAVITY_EARTH
                Vec3(
                    (raw.x - offset[0]) * (g / scale[0]),
                    (raw.y - offset[1]) * (g / scale[1]),
                    (raw.z - offset[2]) * (g / scale[2])
                ).sanitize() to "Accel calibrated"
            }
        }
    }

    fun correctMag(raw: Vec3): Pair<Vec3, String> {
        return TraceUtils.section("AirMouse_Calibration_Apply") {
            val offset = CalibrationStore.magOffset
            val scale = CalibrationStore.magScale
            if (offset == null || scale == null || offset.size < 3 || scale.size < 3) {
                raw.sanitize() to "Mag calibration incomplete"
            } else if (scale.any { !it.isFinite() || abs(it) < 0.01f }) {
                raw.sanitize() to "Mag calibration invalid"
            } else {
                Vec3(
                    (raw.x - offset[0]) / scale[0],
                    (raw.y - offset[1]) / scale[1],
                    (raw.z - offset[2]) / scale[2]
                ).sanitize() to "Mag calibrated"
            }
        }
    }

    private fun Vec3.sanitize(): Vec3 {
        return if (isFinite()) this else Vec3.ZERO
    }
}
