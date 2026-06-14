package com.example.airmouse

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.airmouse.databinding.FragmentFirstBinding
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class SensorSample(
    val timestamp: Long,
    val sensorType: Int,
    val x: Float,
    val y: Float,
    val z: Float
)

data class MousePacket(
    val sequenceNumber: Long,
    val timestamp: Long,
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
            .put("deltaX", deltaX)
            .put("deltaY", deltaY)
            .put("click", click)
            .put("scroll", scroll)
            .put("requiresAck", requiresAck)
            .toString()
    }
}

class FirstFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    private var isReadingSensors = false
    private var userWantsMouseRunning = false

    private val sensorSamples = mutableListOf<SensorSample>()
    private val maxSamples = 1000

    private val movementPackets = mutableListOf<MousePacket>()
    private val pendingAckPackets = mutableListOf<MousePacket>()
    private val maxMovementPackets = 200

    private var packetSequenceNumber = 0L
    private var lastPacketSummary = "none"
    private var lastMovementPacketTime = 0L

    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var latestMag = FloatArray(3)

    private var lastClickTime = 0L
    private var lastScrollTime = 0L

    private var previousAccel: FloatArray? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val movementSensitivity = 35f

    // This controls how large movement packets become.
    // The next teammate can tune this before UDP sending.
    private val packetMovementScale = 8f

    private val movementPacketDeadZone = 0.05f
    private val minMovementPacketIntervalMs = 20L

    private val clickThresholdY = -2.5f
    private val scrollDeltaThresholdY = 3.5f
    private val gestureCooldownMs = 700L

    private val rotationIgnoreThreshold = 1.2f
    private val axisDominanceRatio = 1.8f

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

        accelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        gyroscope =
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        magnetometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        updateSensorAvailabilityText()
        updateDebugInfo()

        binding.startButton.setOnClickListener {
            if (!isReadingSensors) {
                userWantsMouseRunning = true
                startReadingSensors()
                binding.startButton.text = "STOP MOUSE"
                binding.statusText.text = "Status: reading raw sensors and creating packets"
            } else {
                userWantsMouseRunning = false
                stopReadingSensors()
                binding.startButton.text = "START MOUSE"
                binding.statusText.text = "Status: stopped"
            }
        }

        binding.calibrateButton.setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }
    }
    private fun applyGyroCalibration(x: Float, y: Float, z: Float): FloatArray {
        val bias = CalibrationStore.gyroBias

        return if (bias != null) {
            floatArrayOf(
                x - bias[0],
                y - bias[1],
                z - bias[2]
            )
        } else {
            floatArrayOf(x, y, z)
        }
    }

    private fun updateSensorAvailabilityText() {
        val accelStatus = accelerometer?.name ?: "Missing"
        val gyroStatus = gyroscope?.name ?: "Missing"
        val magStatus = magnetometer?.name ?: "Missing"

        binding.sensorAvailabilityText.text =
            "Accelerometer: $accelStatus\nGyroscope: $gyroStatus\nMagnetometer: $magStatus"
    }

    private fun startReadingSensors() {
        if (isReadingSensors) return

        previousAccel = null

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
    private fun applyAccelerometerCalibration(x: Float, y: Float, z: Float): FloatArray {
        val offset = CalibrationStore.accelOffset
        val scale = CalibrationStore.accelScale

        return if (offset != null && scale != null) {
            floatArrayOf(
                (x - offset[0]) * scale[0],
                (y - offset[1]) * scale[1],
                (z - offset[2]) * scale[2]
            )
        } else {
            floatArrayOf(x, y, z)
        }
    }

    private fun stopReadingSensors() {
        if (!isReadingSensors) return

        sensorManager.unregisterListener(this)
        isReadingSensors = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val currentBinding = _binding ?: return
        if (event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        storeSample(event.sensor.type, x, y, z)

        val valuesText = formatSensorValues(x, y, z)

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                val corrected = applyAccelerometerCalibration(x, y, z)

                latestAccel[0] = corrected[0]
                latestAccel[1] = corrected[1]
                latestAccel[2] = corrected[2]

                currentBinding.accelText.text = formatSensorValues(
                    corrected[0],
                    corrected[1],
                    corrected[2]
                )

                detectScrollFromYAxis(
                    accelX = corrected[0],
                    accelY = corrected[1],
                    accelZ = corrected[2]
                )
            }


            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                val corrected = applyGyroCalibration(x, y, z)

                latestGyro[0] = corrected[0]
                latestGyro[1] = corrected[1]
                latestGyro[2] = corrected[2]

                currentBinding.gyroText.text = formatSensorValues(
                    corrected[0],
                    corrected[1],
                    corrected[2]
                )

                updateMovementIndicatorFromXZ(
                    xAxis = corrected[0],
                    zAxis = corrected[2]
                )

                createMovementPacketFromXZ(
                    xAxis = corrected[0],
                    zAxis = corrected[2]
                )

                detectClickFromYAxis(
                    gyroX = corrected[0],
                    gyroY = corrected[1],
                    gyroZ = corrected[2]
                )
            }


            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                val corrected = applyMagnetometerCalibration(x, y, z)

                latestMag[0] = corrected[0]
                latestMag[1] = corrected[1]
                latestMag[2] = corrected[2]

                currentBinding.magText.text = formatSensorValues(
                    corrected[0],
                    corrected[1],
                    corrected[2]
                )
            }

        }
    }
    private fun applyMagnetometerCalibration(x: Float, y: Float, z: Float): FloatArray {
        val offset = CalibrationStore.magOffset
        val scale = CalibrationStore.magScale

        return if (offset != null && scale != null) {
            floatArrayOf(
                if (scale[0] != 0f) (x - offset[0]) / scale[0] else x,
                if (scale[1] != 0f) (y - offset[1]) / scale[1] else y,
                if (scale[2] != 0f) (z - offset[2]) / scale[2] else z
            )
        } else {
            floatArrayOf(x, y, z)
        }
    }

    private fun storeSample(sensorType: Int, x: Float, y: Float, z: Float) {
        sensorSamples.add(
            SensorSample(
                timestamp = System.currentTimeMillis(),
                sensorType = sensorType,
                x = x,
                y = y,
                z = z
            )
        )

        if (sensorSamples.size > maxSamples) {
            sensorSamples.removeAt(0)
        }

        updateDebugInfo()
    }

    private fun updateMovementIndicatorFromXZ(xAxis: Float, zAxis: Float) {
        val currentBinding = _binding ?: return

        currentBinding.movementArea.post {
            val maxX =
                ((currentBinding.movementArea.width - currentBinding.movementSquare.width) / 2f)
                    .coerceAtLeast(0f)

            val maxY =
                ((currentBinding.movementArea.height - currentBinding.movementSquare.height) / 2f)
                    .coerceAtLeast(0f)

            val moveX = (zAxis * movementSensitivity).coerceIn(-maxX, maxX)
            val moveY = (-xAxis * movementSensitivity).coerceIn(-maxY, maxY)

            currentBinding.movementSquare.translationX = moveX
            currentBinding.movementSquare.translationY = moveY

            currentBinding.movementLabelText.text =
                String.format(
                    Locale.US,
                    "Movement: Z→horizontal %.2f, X→vertical %.2f",
                    zAxis,
                    xAxis
                )
        }
    }

    private fun createMovementPacketFromXZ(xAxis: Float, zAxis: Float) {
        val now = System.currentTimeMillis()

        if (now - lastMovementPacketTime < minMovementPacketIntervalMs) return

        val deltaX = zAxis * packetMovementScale
        val deltaY = -xAxis * packetMovementScale

        if (abs(deltaX) < movementPacketDeadZone && abs(deltaY) < movementPacketDeadZone) return

        lastMovementPacketTime = now

        val packet = MousePacket(
            sequenceNumber = packetSequenceNumber++,
            timestamp = now,
            deltaX = deltaX,
            deltaY = deltaY,
            click = false,
            scroll = 0,
            requiresAck = false
        )

        enqueueMovementPacket(packet)
    }

    private fun detectClickFromYAxis(gyroX: Float, gyroY: Float, gyroZ: Float) {
        val now = System.currentTimeMillis()

        val isYAxisDominant =
            abs(gyroY) > abs(gyroX) * axisDominanceRatio &&
                    abs(gyroY) > abs(gyroZ) * axisDominanceRatio

        val isLeftRotation =
            gyroY < clickThresholdY

        if (isYAxisDominant && isLeftRotation && now - lastClickTime > gestureCooldownMs) {
            lastClickTime = now

            binding.clickStatusText.text =
                String.format(Locale.US, "Click: LEFT CLICK detected, gyroY = %.3f", gyroY)

            val packet = MousePacket(
                sequenceNumber = packetSequenceNumber++,
                timestamp = now,
                deltaX = 0f,
                deltaY = 0f,
                click = true,
                scroll = 0,
                requiresAck = true
            )

            enqueueReliablePacket(packet)

            flashSquareForGesture(
                color = clickColor,
                labelText = "CLICK"
            )
        }
    }

    private fun detectScrollFromYAxis(accelX: Float, accelY: Float, accelZ: Float) {
        val previous = previousAccel
        previousAccel = floatArrayOf(accelX, accelY, accelZ)

        if (previous == null) return

        val deltaX = accelX - previous[0]
        val deltaY = accelY - previous[1]
        val deltaZ = accelZ - previous[2]

        val now = System.currentTimeMillis()

        val isYAxisDominant =
            abs(deltaY) > abs(deltaX) * axisDominanceRatio &&
                    abs(deltaY) > abs(deltaZ) * axisDominanceRatio

        val isPhoneRotating =
            abs(latestGyro[0]) + abs(latestGyro[1]) + abs(latestGyro[2]) > rotationIgnoreThreshold

        if (
            abs(deltaY) > scrollDeltaThresholdY &&
            isYAxisDominant &&
            !isPhoneRotating &&
            now - lastScrollTime > gestureCooldownMs
        ) {
            lastScrollTime = now

            val scrollDirection = if (deltaY > 0) 1 else -1
            val directionText = if (scrollDirection == 1) "UP" else "DOWN"

            binding.scrollStatusText.text =
                String.format(
                    Locale.US,
                    "Scroll: %s detected, ΔY = %.3f",
                    directionText,
                    deltaY
                )

            val packet = MousePacket(
                sequenceNumber = packetSequenceNumber++,
                timestamp = now,
                deltaX = 0f,
                deltaY = 0f,
                click = false,
                scroll = scrollDirection,
                requiresAck = true
            )

            enqueueReliablePacket(packet)

            val scrollColor =
                if (scrollDirection == 1) scrollUpColor else scrollDownColor

            flashSquareForGesture(
                color = scrollColor,
                labelText = "SCROLL $directionText"
            )
        }
    }

    private fun enqueueMovementPacket(packet: MousePacket) {
        movementPackets.add(packet)

        if (movementPackets.size > maxMovementPackets) {
            movementPackets.removeAt(0)
        }

        lastPacketSummary = packet.toJsonString()
        updateDebugInfo()
    }

    private fun enqueueReliablePacket(packet: MousePacket) {
        pendingAckPackets.add(packet)

        lastPacketSummary = packet.toJsonString()
        updateDebugInfo()
    }

    /*
     * This function is for the teammate who will implement UDP.
     *
     * Movement packets can be sent and then removed/dropped if needed.
     * Click/scroll packets are also returned here because they require ACK.
     */
    fun getPacketsReadyForTransmission(): List<MousePacket> {
        return pendingAckPackets + movementPackets
    }

    /*
     * This function is for the teammate who will implement UDP.
     * After movement packets are sent once, they can be cleared.
     */
    fun clearSentMovementPackets() {
        movementPackets.clear()
        updateDebugInfo()
    }

    /*
     * This function is for the teammate who will implement UDP ACK handling.
     * When the laptop confirms a click/scroll packet, remove it from pending ACK.
     */
    fun markPacketAcknowledged(sequenceNumber: Long) {
        pendingAckPackets.removeAll { it.sequenceNumber == sequenceNumber }
        updateDebugInfo()
    }

    private fun updateDebugInfo() {
        if (_binding == null) return

        binding.sampleCountText.text =
            "Stored raw sensor samples: ${sensorSamples.size}\n" +
                    "Movement packets ready: ${movementPackets.size}\n" +
                    "Reliable packets waiting for ACK: ${pendingAckPackets.size}\n" +
                    "Last packet: $lastPacketSummary"
    }

    private fun flashSquareForGesture(color: Int, labelText: String) {
        val currentBinding = _binding ?: return

        currentBinding.movementSquare.setBackgroundColor(color)
        currentBinding.movementSquare.scaleX = 1.25f
        currentBinding.movementSquare.scaleY = 1.25f
        currentBinding.movementLabelText.text = labelText

        uiHandler.removeCallbacksAndMessages(null)

        uiHandler.postDelayed({
            if (_binding != null) {
                binding.movementSquare.setBackgroundColor(greenColor)
                binding.movementSquare.scaleX = 1.0f
                binding.movementSquare.scaleY = 1.0f
            }
        }, 250)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this stage.
    }

    private fun formatSensorValues(x: Float, y: Float, z: Float): String {
        return String.format(
            Locale.US,
            "X: %.3f  Y: %.3f  Z: %.3f",
            x,
            y,
            z
        )
    }

    override fun onPause() {
        super.onPause()
        stopReadingSensors()
    }

    override fun onResume() {
        super.onResume()

        if (userWantsMouseRunning && _binding != null) {
            startReadingSensors()
            binding.startButton.text = "STOP MOUSE"
            binding.statusText.text = "Status: reading raw sensors and creating packets"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopReadingSensors()
        _binding = null
    }
}