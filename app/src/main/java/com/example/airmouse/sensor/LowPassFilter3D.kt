package com.example.airmouse.sensor

class LowPassFilter3D(private val alpha: Float, initial: Vec3 = Vec3.ZERO) {
    private var state = initial
    private var initialized = false

    fun reset(value: Vec3 = Vec3.ZERO) {
        state = value
        initialized = false
    }

    fun update(input: Vec3): Vec3 {
        if (!initialized) {
            state = input
            initialized = true
            return state
        }
        state = state * (1f - alpha) + input * alpha
        return state
    }

    fun current(): Vec3 = state
}
