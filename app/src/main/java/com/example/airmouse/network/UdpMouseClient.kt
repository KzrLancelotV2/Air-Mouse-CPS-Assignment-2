package com.example.airmouse.network

import android.os.SystemClock
import com.example.airmouse.MousePacket
import com.example.airmouse.TraceUtils
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class UdpStatus(
    val running: Boolean,
    val target: String,
    val lastSent: String,
    val lastAck: Long?,
    val pendingReliableCount: Int,
    val error: String?
)

class UdpMouseClient(
    private val statusCallback: (UdpStatus) -> Unit
) {
    private val reliablePackets = linkedMapOf<Long, PendingReliablePacket>()
    private var executor: ScheduledExecutorService? = null
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 5000
    private var running = false
    private var lastSent = "none"
    private var lastAck: Long? = null
    private var lastError: String? = null

    @Synchronized
    fun start(host: String, port: Int = 5000): Boolean {
        stop()
        return try {
            targetAddress = InetAddress.getByName(host)
            targetPort = port
            val newSocket = DatagramSocket()
            newSocket.soTimeout = 100
            socket = newSocket
            running = true
            executor = Executors.newScheduledThreadPool(
                2,
                TraceUtils.namedThreadFactory("AirMouse-Udp")
            ).also { exec ->
                exec.execute { TraceUtils.withThreadName("AirMouse-AckRecv") { receiveLoop() } }
                exec.scheduleAtFixedRate({
                    TraceUtils.withThreadName("AirMouse-Retry") { retryReliablePackets() }
                }, 150, 150, TimeUnit.MILLISECONDS)
            }
            lastError = null
            emitStatus()
            true
        } catch (t: Throwable) {
            running = false
            lastError = t.message ?: t.javaClass.simpleName
            emitStatus()
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        socket?.close()
        socket = null
        executor?.shutdownNow()
        executor = null
        reliablePackets.clear()
        emitStatus()
    }

    @Synchronized
    fun send(packet: MousePacket) {
        if (!running) return
        if (packet.requiresAck) {
            if (reliablePackets.size >= MAX_RELIABLE_PENDING) {
                val oldest = reliablePackets.keys.firstOrNull()
                if (oldest != null) reliablePackets.remove(oldest)
            }
            reliablePackets[packet.sequenceNumber] = PendingReliablePacket(packet)
            TraceUtils.counter("AirMouse_PendingReliablePackets", reliablePackets.size.toLong())
        }
        executor?.execute { sendNow(packet, retry = false) }
    }

    @Synchronized
    fun pendingReliableCount(): Int = reliablePackets.size

    private fun sendNow(packet: MousePacket, retry: Boolean) {
        TraceUtils.withThreadName("AirMouse-UdpSend") {
            try {
                TraceUtils.section("AirMouse_UDPSend") {
                    val activeSocket = socket ?: return@section
                    val address = targetAddress ?: return@section
                    val sentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val json = packet.toJsonString(sentElapsedRealtimeNanos)
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    activeSocket.send(DatagramPacket(bytes, bytes.size, address, targetPort))
                    synchronized(this) {
                        reliablePackets[packet.sequenceNumber]?.let {
                            it.lastSendMillis = System.currentTimeMillis()
                            it.retries += if (retry) 1 else 0
                        }
                        lastSent = json
                        lastError = null
                        TraceUtils.counter("AirMouse_MotionPacketSeq", packet.sequenceNumber)
                        TraceUtils.counter("AirMouse_PendingReliablePackets", reliablePackets.size.toLong())
                        emitStatus()
                    }
                }
            } catch (t: Throwable) {
                synchronized(this) {
                    lastError = t.message ?: t.javaClass.simpleName
                    emitStatus()
                }
            }
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(512)
        while (running) {
            try {
                val activeSocket = socket ?: break
                val datagram = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(datagram)
                TraceUtils.section("AirMouse_UDP_AckReceive") {
                    val text = String(datagram.data, 0, datagram.length, Charsets.UTF_8)
                    val ack = JSONObject(text).optLong("ack", Long.MIN_VALUE)
                    if (ack != Long.MIN_VALUE) {
                        synchronized(this) {
                            reliablePackets.remove(ack)
                            lastAck = ack
                            lastError = null
                            TraceUtils.counter("AirMouse_PendingReliablePackets", reliablePackets.size.toLong())
                            emitStatus()
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Normal polling timeout so stop() can close promptly.
            } catch (t: Throwable) {
                if (running) {
                    synchronized(this) {
                        lastError = t.message ?: t.javaClass.simpleName
                        emitStatus()
                    }
                }
            }
        }
    }

    private fun retryReliablePackets() {
        TraceUtils.section("AirMouse_UDP_Retry") {
            val now = System.currentTimeMillis()
            val toRetry = mutableListOf<MousePacket>()
            synchronized(this) {
                val iterator = reliablePackets.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val pending = entry.value
                    if (pending.retries >= MAX_RETRIES) {
                        iterator.remove()
                        lastError = "Dropped reliable packet ${entry.key}: no ACK"
                        continue
                    }
                    if (now - pending.lastSendMillis >= RETRY_INTERVAL_MS) {
                        toRetry.add(pending.packet)
                    }
                }
                TraceUtils.counter("AirMouse_PendingReliablePackets", reliablePackets.size.toLong())
                emitStatus()
            }
            toRetry.forEach { sendNow(it, retry = true) }
        }
    }

    @Synchronized
    private fun emitStatus() {
        val address = targetAddress?.hostAddress ?: "none"
        statusCallback(
            UdpStatus(
                running = running,
                target = if (running) "$address:$targetPort" else "stopped",
                lastSent = lastSent,
                lastAck = lastAck,
                pendingReliableCount = reliablePackets.size,
                error = lastError
            )
        )
    }

    private data class PendingReliablePacket(
        val packet: MousePacket,
        var retries: Int = 0,
        var lastSendMillis: Long = 0L
    )

    companion object {
        private const val MAX_RELIABLE_PENDING = 16
        private const val MAX_RETRIES = 25
        private const val RETRY_INTERVAL_MS = 250L
    }
}
