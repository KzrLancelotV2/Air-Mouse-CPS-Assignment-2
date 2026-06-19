package com.example.airmouse

import org.json.JSONObject

data class MousePacket(
    val sequenceNumber: Long,
    val timestamp: Long,
    val sensorTimestampNanos: Long,
    val deltaX: Float,
    val deltaY: Float,
    val click: Boolean,
    val scroll: Int,
    val requiresAck: Boolean
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("seq", sequenceNumber)
            .put("timestamp", timestamp)
            .put("sensorTimestampNanos", sensorTimestampNanos)
            .put("deltaX", deltaX)
            .put("deltaY", deltaY)
            .put("click", click)
            .put("scroll", scroll)
            .put("requiresAck", requiresAck)
            .toString()
    }
}
