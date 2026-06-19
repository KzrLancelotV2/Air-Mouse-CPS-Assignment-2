package com.example.airmouse

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.airmouse.databinding.FragmentFirstBinding
import com.example.airmouse.network.UdpMouseClient
import com.example.airmouse.network.UdpStatus
import com.example.airmouse.sensor.GestureKind
import com.example.airmouse.sensor.MotionOutput
import com.example.airmouse.sensor.MotionProcessor
import com.example.airmouse.sensor.SensorFrame
import com.example.airmouse.sensor.Vec3
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToLong

data class SensorSample(
    val timestamp: Long,
    val sensorType: Int,
    val x: Float,
    val y: Float,
    val z: Float
)

class FirstFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var sensorManager: SensorManager
    private var sensorExecutor: ExecutorService? = null
    private var udpClient: UdpMouseClient? = null

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    private var isReadingSensors = false
    private var userWantsMouseRunning = false
    private var gyroSupported = false
    private var packetSequenceNumber = 0L
    private var rawSampleCount = 0
    private var lastUiUpdateMs = 0L
    private var lastPacketSummary = "none"
    private var udpStatus = UdpStatus(false, "stopped", "none", null, 0, null)
    private var previewX = 0f
    private var previewY = 0f
    private var lastAccelSensorTimestampNanos = 0L
    private var lastGyroSensorTimestampNanos = 0L
    private var lastMagSensorTimestampNanos = 0L

    private val sensorSamples = ArrayDeque<SensorSample>()
    private val maxSamples = 1000
    private val uiHandler = Handler(Looper.getMainLooper())
    private val motionProcessor = MotionProcessor(
        packetFactory = { sensorTimestampNanos, deltaX, deltaY, click, scroll, requiresAck ->
            MousePacket(
                sequenceNumber = packetSequenceNumber++,
                timestamp = System.currentTimeMillis(),
                sensorTimestampNanos = sensorTimestampNanos,
                deltaX = deltaX,
                deltaY = deltaY,
                click = click,
                scroll = scroll,
                requiresAck = requiresAck
            )
        }
    )

    private val greenColor = Color.rgb(76, 175, 80)
    private val clickColor = Color.rgb(244, 67, 54)
    private val scrollUpColor = Color.rgb(33, 150, 243)
    private val scrollDownColor = Color.rgb(255, 152, 0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        discoverSensors()

        udpClient = UdpMouseClient { status ->
            uiHandler.post {
                udpStatus = status
                updateDebugInfo()
            }
        }

        updateSensorAvailabilityText()
        updateCalibrationStatus()
        updateDebugInfo()

        binding.startButton.setOnClickListener {
            if (!gyroSupported) {
                showUnsupportedGyroDialog()
                return@setOnClickListener
            }

            if (!isReadingSensors) {
                startAirMouse()
            } else {
                userWantsMouseRunning = false
                stopAirMouse()
            }
        }

        binding.calibrateButton.setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }

        if (!gyroSupported) {
            binding.statusText.text = "Status: unsupported device"
            showUnsupportedGyroDialog()
        }
    }

    private fun discoverSensors() {
        accelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        gyroscope =
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        magnetometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        gyroSupported = gyroscope != null
    }

    private fun startAirMouse() {
        val host = binding.ipAddressEditText.text?.toString()?.trim().orEmpty()
        if (!isValidHost(host)) {
            binding.statusText.text = "Status: enter a valid laptop IP address before starting"
            return
        }

        userWantsMouseRunning = true
        motionProcessor.reset()
        resetMovementPreview()
        sensorExecutor?.shutdownNow()
        sensorExecutor = Executors.newSingleThreadExecutor(
            TraceUtils.namedThreadFactory("AirMouse-SensorProc")
        )

        if (udpClient?.start(host, DEFAULT_UDP_PORT) != true) {
            binding.statusText.text = "Status: UDP could not start. Check laptop IP."
            sensorExecutor?.shutdownNow()
            sensorExecutor = null
            return
        }

        sendStartupUdpProbe()
        startReadingSensors()
        binding.startButton.text = "STOP MOUSE"
        binding.statusText.text = "Status: AirMouse running"
    }

    private fun sendStartupUdpProbe() {
        val packet = MousePacket(
            sequenceNumber = packetSequenceNumber++,
            timestamp = System.currentTimeMillis(),
            sensorTimestampNanos = System.nanoTime(),
            deltaX = 0f,
            deltaY = 0f,
            click = false,
            scroll = 0,
            requiresAck = false
        )
        lastPacketSummary = "startup UDP probe: ${packet.toJsonString()}"
        udpClient?.send(packet)
    }

    private fun stopAirMouse() {
        stopReadingSensors()
        udpClient?.stop()
        sensorExecutor?.shutdownNow()
        sensorExecutor = null
        binding.startButton.text = "START MOUSE"
        binding.statusText.text = "Status: stopped"
        binding.clickStatusText.text = "Click: not detected"
        binding.scrollStatusText.text = "Scroll: not detected"
        resetMovementPreview()
        updateDebugInfo()
    }

    private fun startReadingSensors() {
        if (isReadingSensors || !gyroSupported) return

        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        isReadingSensors = true
    }

    private fun stopReadingSensors() {
        if (!isReadingSensors) return
        sensorManager.unregisterListener(this)
        isReadingSensors = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isReadingSensors || !gyroSupported) return

        val frame = TraceUtils.section("AirMouse_SensorEvent_Copy") {
            TraceUtils.section("AirMouse_SensorEvent_${TraceUtils.sensorName(event.sensor.type)}") {
                val values = FloatArray(minOf(3, event.values.size))
                for (i in values.indices) values[i] = event.values[i]
                SensorFrame(event.sensor.type, event.timestamp, values)
            }
        }
        recordSensorIntervalCounter(frame.sensorType, frame.timestampNanos)
        storeSample(frame.sensorType, frame.values)
        sensorExecutor?.execute {
            val output = motionProcessor.onFrame(frame) ?: return@execute
            TraceUtils.counter("AirMouse_FinalDeltaX", (output.deltaX * 1000f).roundToLong())
            TraceUtils.counter("AirMouse_FinalDeltaY", (output.deltaY * 1000f).roundToLong())
            output.packet?.let {
                TraceUtils.counter("AirMouse_MotionPacketSeq", it.sequenceNumber)
                lastPacketSummary = it.toJsonString()
                udpClient?.send(it)
            }
            maybePostMotionOutput(output)
        }
    }

    private fun recordSensorIntervalCounter(sensorType: Int, timestampNanos: Long) {
        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                emitDtCounter("AirMouse_AccelDtMs", lastAccelSensorTimestampNanos, timestampNanos)
                lastAccelSensorTimestampNanos = timestampNanos
            }
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                emitDtCounter("AirMouse_GyroDtMs", lastGyroSensorTimestampNanos, timestampNanos)
                lastGyroSensorTimestampNanos = timestampNanos
            }
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                emitDtCounter("AirMouse_MagDtMs", lastMagSensorTimestampNanos, timestampNanos)
                lastMagSensorTimestampNanos = timestampNanos
            }
        }
    }

    private fun emitDtCounter(name: String, previousNanos: Long, currentNanos: Long) {
        if (previousNanos <= 0L || currentNanos <= previousNanos) return
        TraceUtils.counter(name, (currentNanos - previousNanos) / 1_000_000L)
    }

    private fun storeSample(sensorType: Int, values: FloatArray) {
        rawSampleCount++
        sensorSamples.addLast(
            SensorSample(
                timestamp = System.currentTimeMillis(),
                sensorType = sensorType,
                x = values.getOrElse(0) { 0f },
                y = values.getOrElse(1) { 0f },
                z = values.getOrElse(2) { 0f }
            )
        )
        while (sensorSamples.size > maxSamples) sensorSamples.removeFirst()
    }

    private fun maybePostMotionOutput(output: MotionOutput) {
        val now = System.currentTimeMillis()
        if (output.packet == null && now - lastUiUpdateMs < UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateMs = now
        uiHandler.post {
            if (_binding == null) return@post
            updateMotionUi(output)
        }
    }

    private fun updateMotionUi(output: MotionOutput) {
        TraceUtils.section("AirMouse_UI_StatusUpdate") {
            binding.accelText.text =
                "Raw ${formatVec(output.rawAccel)}\nCorrected ${formatVec(output.correctedAccel)}\nLinear ${formatVec(output.linearAccel)}"
            binding.gyroText.text =
                "Raw ${formatVec(output.rawGyro)}\nCorrected/filter ${formatVec(output.correctedGyro)}"
            binding.magText.text =
                "Raw ${formatVec(output.rawMag)}\nCorrected ${formatVec(output.correctedMag)}"

            handleGestureUi(output)

            binding.movementLabelText.text = String.format(
                Locale.US,
                "Movement Debug Preview: final deltaX %.2f, deltaY %.2f\n%s\nThis square accumulates the same signed deltas used for movement packets. Real cursor movement happens on the laptop through UDP.",
                output.deltaX,
                output.deltaY,
                output.debugDetails
            )

            updateCalibrationStatus(output.status, output.filterMode.name)
            updateDebugInfo()
        }
        updateMovementIndicator(output.deltaX, output.deltaY)
    }

    private fun updateMovementIndicator(deltaX: Float, deltaY: Float) {
        val currentBinding = _binding ?: return
        currentBinding.movementArea.post {
            TraceUtils.section("AirMouse_UI_PreviewUpdate") {
                val maxX = ((currentBinding.movementArea.width - currentBinding.movementSquare.width) / 2f)
                    .coerceAtLeast(0f)
                val maxY = ((currentBinding.movementArea.height - currentBinding.movementSquare.height) / 2f)
                    .coerceAtLeast(0f)
                previewX = (previewX + deltaX * PREVIEW_SCALE).coerceIn(-maxX, maxX)
                previewY = (previewY + deltaY * PREVIEW_SCALE).coerceIn(-maxY, maxY)
                currentBinding.movementSquare.translationX = previewX
                currentBinding.movementSquare.translationY = previewY
            }
        }
    }

    private fun resetMovementPreview() {
        previewX = 0f
        previewY = 0f
        if (_binding != null) {
            binding.movementSquare.translationX = 0f
            binding.movementSquare.translationY = 0f
        }
    }

    private fun handleGestureUi(output: MotionOutput) {
        when (output.gesture) {
            GestureKind.CLICK -> {
                binding.clickStatusText.text =
                    String.format(Locale.US, "Click: LEFT CLICK detected, gyroY = %.3f", output.correctedGyro.y)
                flashSquareForGesture(clickColor)
                uiHandler.postDelayed({
                    if (_binding != null) binding.clickStatusText.text = "Click: not detected"
                }, GESTURE_FEEDBACK_MS)
            }
            GestureKind.SCROLL_UP, GestureKind.SCROLL_DOWN -> {
                val directionText = if (output.gesture == GestureKind.SCROLL_UP) "UP" else "DOWN"
                binding.scrollStatusText.text =
                    String.format(Locale.US, "Scroll: %s detected, linearY = %.3f", directionText, output.linearAccel.y)
                flashSquareForGesture(if (output.gesture == GestureKind.SCROLL_UP) scrollUpColor else scrollDownColor)
                uiHandler.postDelayed({
                    if (_binding != null) binding.scrollStatusText.text = "Scroll: not detected"
                }, GESTURE_FEEDBACK_MS)
            }
            null -> Unit
        }
    }

    private fun flashSquareForGesture(color: Int) {
        val currentBinding = _binding ?: return
        currentBinding.movementSquare.setBackgroundColor(color)
        currentBinding.movementSquare.scaleX = 1.25f
        currentBinding.movementSquare.scaleY = 1.25f
        uiHandler.postDelayed({
            if (_binding != null) {
                binding.movementSquare.setBackgroundColor(greenColor)
                binding.movementSquare.scaleX = 1.0f
                binding.movementSquare.scaleY = 1.0f
            }
        }, 250)
    }

    private fun updateSensorAvailabilityText() {
        val accelStatus = sensorStatus(accelerometer)
        val gyroStatus = if (gyroscope == null) "Missing / Unsupported" else sensorStatus(gyroscope)
        val magStatus = sensorStatus(magnetometer)
        binding.sensorAvailabilityText.text =
            "Accelerometer: $accelStatus\nGyroscope: $gyroStatus\nMagnetometer: $magStatus"
    }

    private fun sensorStatus(sensor: Sensor?): String {
        if (sensor == null) return "Missing"
        val rawWarning = when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD ->
                " (fallback calibrated Android sensor)"
            else -> ""
        }
        return "${sensor.name}$rawWarning"
    }

    private fun updateCalibrationStatus(
        processorStatus: String = "",
        filterMode: String = "MADGWICK"
    ) {
        TraceUtils.section("AirMouse_UI_StatusUpdate") {
            val gyro = if (CalibrationStore.gyroBias != null) "gyro calibrated" else "gyro not calibrated"
            val accel = if (CalibrationStore.hasValidAccelerometerCalibration()) {
                "accel calibrated"
            } else {
                "accel incomplete"
            }
            val mag = if (CalibrationStore.magOffset != null && CalibrationStore.magScale != null) {
                "mag calibrated"
            } else {
                "mag incomplete"
            }
            val detail = if (processorStatus.isBlank()) "" else "\n$processorStatus"
            binding.statusText.text =
                "Status: ${if (isReadingSensors) "AirMouse running" else "idle"} | Filter: $filterMode | $gyro, $accel, $mag$detail"
        }
    }

    private fun updateDebugInfo() {
        if (_binding == null) return
        TraceUtils.section("AirMouse_UI_StatusUpdate") {
            val error = udpStatus.error?.let { "\nUDP error: $it" } ?: ""
            binding.sampleCountText.text =
                "Stored raw sensor samples: ${sensorSamples.size} (total $rawSampleCount)\n" +
                        "UDP: ${if (udpStatus.running) "running" else "stopped"}\n" +
                        "UDP target: ${udpStatus.target}\n" +
                        "Last sent packet: ${udpStatus.lastSent.take(MAX_DEBUG_PACKET_CHARS)}\n" +
                        "Last ACK: ${udpStatus.lastAck ?: "none"}\n" +
                        "Pending reliable ACK packets: ${udpStatus.pendingReliableCount}\n" +
                        "Last generated packet: ${lastPacketSummary.take(MAX_DEBUG_PACKET_CHARS)}$error"
        }
    }

    private fun showUnsupportedGyroDialog() {
        if (_binding == null) return
        AlertDialog.Builder(requireContext())
            .setTitle("Unsupported phone")
            .setMessage("This phone does not have a gyroscope. This AirMouse implementation requires a gyroscope, so this device is not supported.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank()) return false
        return try {
            InetAddress.getByName(host)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Accuracy changes are shown indirectly through raw/corrected debug values.
    }

    override fun onPause() {
        super.onPause()
        stopAirMouse()
    }

    override fun onResume() {
        super.onResume()
        discoverSensors()
        if (_binding != null) {
            updateSensorAvailabilityText()
            updateCalibrationStatus()
        }
        if (userWantsMouseRunning && gyroSupported && _binding != null) {
            startAirMouse()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        userWantsMouseRunning = false
        stopReadingSensors()
        udpClient?.stop()
        sensorExecutor?.shutdownNow()
        sensorExecutor = null
        udpClient = null
        _binding = null
    }

    private fun formatVec(v: Vec3): String {
        return String.format(Locale.US, "X: %.3f  Y: %.3f  Z: %.3f", v.x, v.y, v.z)
    }

    companion object {
        private const val DEFAULT_UDP_PORT = 5000
        private const val PREVIEW_SCALE = 4.0f
        private const val UI_UPDATE_INTERVAL_MS = 33L
        private const val GESTURE_FEEDBACK_MS = 700L
        private const val MAX_DEBUG_PACKET_CHARS = 220
    }
}
