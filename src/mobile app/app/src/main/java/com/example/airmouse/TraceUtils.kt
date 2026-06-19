package com.example.airmouse

import android.hardware.Sensor
import android.os.Build
import android.os.Trace
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object TraceUtils {
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    fun counter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.setCounter(name, value)
        }
    }

    fun sensorName(sensorType: Int): String {
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "ACCEL"
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "GYRO"
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "MAG"
            else -> "TYPE_$sensorType"
        }
    }

    fun namedThreadFactory(prefix: String): ThreadFactory {
        val counter = AtomicInteger(1)
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.getAndIncrement()}")
        }
    }

    inline fun <T> withThreadName(name: String, block: () -> T): T {
        val thread = Thread.currentThread()
        val oldName = thread.name
        thread.name = name
        return try {
            block()
        } finally {
            thread.name = oldName
        }
    }
}
