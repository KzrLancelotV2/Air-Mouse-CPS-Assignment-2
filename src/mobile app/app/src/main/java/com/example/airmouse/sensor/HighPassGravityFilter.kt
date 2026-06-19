package com.example.airmouse.sensor

import com.example.airmouse.TraceUtils

class HighPassGravityFilter(private val gravityAlpha: Float = 0.08f) {
    private val gravityFilter = LowPassFilter3D(gravityAlpha)

    fun reset() {
        gravityFilter.reset()
    }

    fun update(accel: Vec3): Pair<Vec3, Vec3> {
        return TraceUtils.section("AirMouse_GravityFilter") {
            val gravity = gravityFilter.update(accel)
            gravity to (accel - gravity)
        }
    }
}
