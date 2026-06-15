package com.example.airmouse.sensor

class HighPassGravityFilter(private val gravityAlpha: Float = 0.08f) {
    private val gravityFilter = LowPassFilter3D(gravityAlpha)

    fun reset() {
        gravityFilter.reset()
    }

    fun update(accel: Vec3): Pair<Vec3, Vec3> {
        val gravity = gravityFilter.update(accel)
        return gravity to (accel - gravity)
    }
}
