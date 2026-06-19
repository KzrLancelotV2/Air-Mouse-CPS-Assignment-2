package com.example.airmouse

import android.os.SystemClock
import org.json.JSONObject

data class MousePacket(
    val sequenceNumber: Long,
    val timestamp: Long,
    val sensorTimestampNanos: Long,
    val deltaX: Float,
    val deltaY: Float,
    val click: Boolean,
    val scroll: Int,
    val requiresAck: Boolean,
    val createdElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    val sentElapsedRealtimeNanos: Long? = null
) {
    fun toJsonString(sentElapsedRealtimeNanos: Long? = this.sentElapsedRealtimeNanos): String {
        val json = JSONObject()
            .put("seq", sequenceNumber)
            .put("timestamp", timestamp)
            .put("sensorTimestampNanos", sensorTimestampNanos)
            .put("createdElapsedRealtimeNanos", createdElapsedRealtimeNanos)
            .put("deltaX", deltaX)
            .put("deltaY", deltaY)
            .put("click", click)
            .put("scroll", scroll)
            .put("requiresAck", requiresAck)
        if (sentElapsedRealtimeNanos != null) {
            json.put("sentElapsedRealtimeNanos", sentElapsedRealtimeNanos)
        }
        return json.toString()
    }
}
