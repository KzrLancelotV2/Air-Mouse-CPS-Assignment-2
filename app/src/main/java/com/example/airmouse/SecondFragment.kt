package com.example.airmouse

import android.app.AlertDialog
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.airmouse.databinding.FragmentSecondBinding
import com.example.airmouse.TraceUtils
import java.util.Locale
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

object CalibrationStore {
    var gyroBias: FloatArray? = null

    val accelSixPositions = mutableListOf<FloatArray>()
    var accelOffset: FloatArray? = null
    var accelScale: FloatArray? = null
    var accelLastValidationError: String? = null

    var magMin = FloatArray(3) { Float.POSITIVE_INFINITY }
    var magMax = FloatArray(3) { Float.NEGATIVE_INFINITY }
    var magOffset: FloatArray? = null
    var magScale: FloatArray? = null

    fun resetAccelerometer() {
        accelSixPositions.clear()
        accelOffset = null
        accelScale = null
        accelLastValidationError = null
    }

    fun hasValidAccelerometerCalibration(): Boolean {
        val offset = accelOffset
        val scale = accelScale
        return offset != null &&
                scale != null &&
                offset.size >= 3 &&
                scale.size >= 3 &&
                scale.all { it.isFinite() && abs(it) >= 0.5f }
    }

    fun resetMagnetometer() {
        magMin = FloatArray(3) { Float.POSITIVE_INFINITY }
        magMax = FloatArray(3) { Float.NEGATIVE_INFINITY }
        magOffset = null
        magScale = null
    }
}


class SecondFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    private var latestAccel: FloatArray? = null
    private val gyroSamples = mutableListOf<FloatArray>()

    private var collectingGyro = false
    private var collectingMag = false
    private var sensorsStarted = false

    private val gyroTargetSamples = 200

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
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

        startSensors()
        updateSensorSupportText()

        binding.gyroCalibrateButton.setOnClickListener {
            startGyroBiasCollection()
        }

        binding.accelCaptureButton.setOnClickListener {
            saveAccelerometerPosition()
        }

        binding.magStartButton.setOnClickListener {
            toggleMagnetometerCollection()
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        updateInstructionText()
        updateAccelerometerUiFromState()
    }

    private fun updateInstructionText() {
        binding.calibrationInstructionText.text =
            "Step 1: Put the phone on a stable surface and press the gyroscope button.\n\n" +
                    "Step 2: Place the phone in 6 different physical orientations and press the accelerometer button once in each position.\n\n" +
                    "Step 3: Press the magnetometer button, move the phone in a figure-8 shape, then press the button again to stop."
    }

    private fun updateSensorSupportText() {
        if (gyroscope == null) {
            binding.gyroCalibrateButton.isEnabled = false
            binding.gyroResultText.text = "Gyro bias: unavailable. This phone is not supported."
            binding.calibrationStatusText.text =
                "Status: Gyroscope: Missing / Unsupported. AirMouse requires a physical gyroscope."
            showUnsupportedGyroDialog()
        }
    }

    private fun computeAccelerometerCalibrationIfReady(): Boolean {
        return TraceUtils.section("AirMouse_AccelCalibration") {
            val positions = CalibrationStore.accelSixPositions

            if (positions.size < 6) return@section false

            val minValues = FloatArray(3) { Float.POSITIVE_INFINITY }
            val maxValues = FloatArray(3) { Float.NEGATIVE_INFINITY }

            positions.forEach { sample ->
                for (i in 0..2) {
                    minValues[i] = min(minValues[i], sample[i])
                    maxValues[i] = max(maxValues[i], sample[i])
                }
            }

            val offsetX = (maxValues[0] + minValues[0]) / 2f
            val offsetY = (maxValues[1] + minValues[1]) / 2f
            val offsetZ = (maxValues[2] + minValues[2]) / 2f

            val scaleX = (maxValues[0] - minValues[0]) / 2f
            val scaleY = (maxValues[1] - minValues[1]) / 2f
            val scaleZ = (maxValues[2] - minValues[2]) / 2f

            if (listOf(scaleX, scaleY, scaleZ).any { !it.isFinite() || abs(it) < 0.5f }) {
                CalibrationStore.accelOffset = null
                CalibrationStore.accelScale = null
                CalibrationStore.accelLastValidationError =
                    "One or more axes did not show a clear +/- gravity range."
                binding.accelResultText.text =
                    buildAccelerometerDebugText(
                        minValues,
                        maxValues,
                        floatArrayOf(offsetX, offsetY, offsetZ),
                        floatArrayOf(scaleX, scaleY, scaleZ),
                        "Accelerometer calibration invalid. Please retry all six positions. Keep the phone still for each sample."
                    )
                binding.calibrationStatusText.text =
                    "Status: accelerometer calibration invalid. Press retry to clear these samples and start from position 1."
                binding.accelCaptureButton.text = "Retry Accelerometer 6 Positions"
                return@section false
            }

            CalibrationStore.accelOffset = floatArrayOf(offsetX, offsetY, offsetZ)
            CalibrationStore.accelScale = floatArrayOf(scaleX, scaleY, scaleZ)
            CalibrationStore.accelLastValidationError = null

            binding.accelResultText.text =
                String.format(
                    Locale.US,
                    "Accelerometer calibration completed:\n" +
                            "Offset X %.3f  Y %.3f  Z %.3f\n" +
                            "Half-range scale X %.3f  Y %.3f  Z %.3f\n" +
                            "Runtime correction maps each half-range to %.5f m/s^2.",
                    offsetX,
                    offsetY,
                    offsetZ,
                    scaleX,
                    scaleY,
                    scaleZ,
                    SensorManager.GRAVITY_EARTH
                )

            binding.calibrationStatusText.text =
                "Status: accelerometer calibration completed."
            binding.accelCaptureButton.text =
                "2. Accelerometer 6 Positions Completed"
            true
        }
    }

    private fun startSensors() {
        if (sensorsStarted) return

        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        sensorsStarted = true
    }

    private fun stopSensors() {
        if (!sensorsStarted) return
        sensorManager.unregisterListener(this)
        sensorsStarted = false
    }

    private fun startGyroBiasCollection() {
        if (gyroscope == null) {
            showUnsupportedGyroDialog()
            return
        }

        gyroSamples.clear()
        collectingGyro = true

        binding.calibrationStatusText.text =
            "Status: collecting gyroscope samples. Keep the phone completely still."

        binding.gyroResultText.text =
            "Gyro bias: collecting 0/$gyroTargetSamples"
    }

    private fun saveAccelerometerPosition() {
        if (CalibrationStore.accelSixPositions.size >= 6 &&
            !CalibrationStore.hasValidAccelerometerCalibration()
        ) {
            CalibrationStore.resetAccelerometer()
            binding.accelCaptureButton.text = "2. Save Accelerometer Position 1/6"
            binding.accelResultText.text = "Accelerometer positions saved: 0/6"
            binding.calibrationStatusText.text =
                "Status: cleared invalid accelerometer samples. Capture position 1/6."
            return
        }

        val currentAccel = latestAccel

        if (currentAccel == null) {
            binding.calibrationStatusText.text =
                "Status: no accelerometer data yet. Wait a second and try again."
            return
        }

        if (CalibrationStore.accelSixPositions.size >= 6) {
            binding.calibrationStatusText.text =
                if (CalibrationStore.hasValidAccelerometerCalibration()) {
                    "Status: accelerometer calibration already completed."
                } else {
                    "Status: accelerometer calibration invalid. Press retry to restart."
                }
            return
        }

        CalibrationStore.accelSixPositions.add(currentAccel.clone())

        val count = CalibrationStore.accelSixPositions.size

        binding.accelResultText.text =
            "Accelerometer positions saved: $count/6"

        if (count < 6) {
            binding.accelCaptureButton.text =
                "2. Save Accelerometer Position ${count + 1}/6"
            binding.calibrationStatusText.text =
                "Status: saved accelerometer position $count."
        } else {
            val valid = computeAccelerometerCalibrationIfReady()
            if (!valid) return
        }
    }

    private fun updateAccelerometerUiFromState() {
        val count = CalibrationStore.accelSixPositions.size
        when {
            CalibrationStore.hasValidAccelerometerCalibration() -> {
                binding.accelCaptureButton.text = "2. Accelerometer 6 Positions Completed"
                val offset = CalibrationStore.accelOffset!!
                val scale = CalibrationStore.accelScale!!
                binding.accelResultText.text =
                    String.format(
                        Locale.US,
                        "Accelerometer calibration completed:\n" +
                                "Captured samples: %d/6\n" +
                                "Offset X %.3f  Y %.3f  Z %.3f\n" +
                                "Half-range scale X %.3f  Y %.3f  Z %.3f",
                        count,
                        offset[0],
                        offset[1],
                        offset[2],
                        scale[0],
                        scale[1],
                        scale[2]
                    )
            }
            count >= 6 -> {
                binding.accelCaptureButton.text = "Retry Accelerometer 6 Positions"
                binding.accelResultText.text =
                    "Accelerometer calibration invalid. Please retry all six positions. Keep the phone still for each sample.\n" +
                            "Captured samples: $count/6\n" +
                            "Reason: ${CalibrationStore.accelLastValidationError ?: "invalid calibration result"}"
            }
            else -> {
                binding.accelCaptureButton.text = "2. Save Accelerometer Position ${count + 1}/6"
                binding.accelResultText.text = "Accelerometer positions saved: $count/6"
            }
        }
    }

    private fun buildAccelerometerDebugText(
        minValues: FloatArray,
        maxValues: FloatArray,
        offsets: FloatArray,
        scales: FloatArray,
        message: String
    ): String {
        val samples = CalibrationStore.accelSixPositions.mapIndexed { index, sample ->
            String.format(
                Locale.US,
                "%d: X %.3f  Y %.3f  Z %.3f",
                index + 1,
                sample[0],
                sample[1],
                sample[2]
            )
        }.joinToString(separator = "\n")

        return String.format(
            Locale.US,
            "%s\nCaptured samples: %d/6\n%s\n" +
                    "Min X %.3f  Y %.3f  Z %.3f\n" +
                    "Max X %.3f  Y %.3f  Z %.3f\n" +
                    "Computed offset X %.3f  Y %.3f  Z %.3f\n" +
                    "Computed half-range scale X %.3f  Y %.3f  Z %.3f\n" +
                    "Reason: %s",
            message,
            CalibrationStore.accelSixPositions.size,
            samples,
            minValues[0],
            minValues[1],
            minValues[2],
            maxValues[0],
            maxValues[1],
            maxValues[2],
            offsets[0],
            offsets[1],
            offsets[2],
            scales[0],
            scales[1],
            scales[2],
            CalibrationStore.accelLastValidationError ?: "invalid calibration result"
        )
    }
    private fun computeMagnetometerCalibration() {
        TraceUtils.section("AirMouse_MagCalibration") {
            val minValues = CalibrationStore.magMin
            val maxValues = CalibrationStore.magMax

            val offsetX = (maxValues[0] + minValues[0]) / 2f
            val offsetY = (maxValues[1] + minValues[1]) / 2f
            val offsetZ = (maxValues[2] + minValues[2]) / 2f

            val scaleX = (maxValues[0] - minValues[0]) / 2f
            val scaleY = (maxValues[1] - minValues[1]) / 2f
            val scaleZ = (maxValues[2] - minValues[2]) / 2f

            if (listOf(scaleX, scaleY, scaleZ).any { !it.isFinite() || abs(it) < 0.01f }) {
                binding.magResultText.text =
                    "Mag calibration invalid. Move the phone through a wider figure-8 and try again."
                binding.calibrationStatusText.text =
                    "Status: magnetometer calibration failed because one axis range was too small."
                return@section
            }

            CalibrationStore.magOffset = floatArrayOf(offsetX, offsetY, offsetZ)
            CalibrationStore.magScale = floatArrayOf(scaleX, scaleY, scaleZ)

            binding.magResultText.text =
                String.format(
                    Locale.US,
                    "Mag calibration completed:\n" +
                            "Offset X %.2f Y %.2f Z %.2f\n" +
                            "Scale X %.2f Y %.2f Z %.2f",
                    offsetX,
                    offsetY,
                    offsetZ,
                    scaleX,
                    scaleY,
                    scaleZ
                )
        }
    }


    private fun toggleMagnetometerCollection() {
        if (!collectingMag) {
            CalibrationStore.resetMagnetometer()
            collectingMag = true

            binding.magStartButton.text =
                "Stop Magnetometer Figure-8 Capture"

            binding.calibrationStatusText.text =
                "Status: collecting magnetometer min/max. Move phone in a figure-8."
        } else {
            collectingMag = false

            binding.magStartButton.text =
                "3. Start Magnetometer Figure-8 Capture"

            binding.calibrationStatusText.text =
                "Status: magnetometer capture stopped."

            computeMagnetometerCalibration()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || _binding == null) return

        val values = TraceUtils.section("AirMouse_SensorEvent_Copy") {
            TraceUtils.section("AirMouse_SensorEvent_${TraceUtils.sensorName(event.sensor.type)}") {
                floatArrayOf(event.values[0], event.values[1], event.values[2])
            }
        }
        val x = values[0]
        val y = values[1]
        val z = values[2]

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                latestAccel = floatArrayOf(x, y, z)
            }

            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (collectingGyro) {
                    collectGyroSample(x, y, z)
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                if (collectingMag) {
                    updateMagnetometerMinMax(x, y, z)
                }
            }
        }
    }

    private fun collectGyroSample(x: Float, y: Float, z: Float) {
        TraceUtils.section("AirMouse_GyroCalibration") {
            gyroSamples.add(floatArrayOf(x, y, z))

            binding.gyroResultText.text =
                "Gyro bias: collecting ${gyroSamples.size}/$gyroTargetSamples"

            if (gyroSamples.size >= gyroTargetSamples) {
                collectingGyro = false

                val biasX = gyroSamples.map { it[0] }.average().toFloat()
                val biasY = gyroSamples.map { it[1] }.average().toFloat()
                val biasZ = gyroSamples.map { it[2] }.average().toFloat()

                CalibrationStore.gyroBias = floatArrayOf(biasX, biasY, biasZ)

                binding.gyroResultText.text =
                    String.format(
                        Locale.US,
                        "Gyro bias saved: X %.5f  Y %.5f  Z %.5f",
                        biasX,
                        biasY,
                        biasZ
                    )

                binding.calibrationStatusText.text =
                    "Status: gyroscope bias collection completed."
            }
        }
    }

    private fun updateMagnetometerMinMax(x: Float, y: Float, z: Float) {
        TraceUtils.section("AirMouse_MagCalibration") {
            CalibrationStore.magMin[0] = min(CalibrationStore.magMin[0], x)
            CalibrationStore.magMin[1] = min(CalibrationStore.magMin[1], y)
            CalibrationStore.magMin[2] = min(CalibrationStore.magMin[2], z)

            CalibrationStore.magMax[0] = max(CalibrationStore.magMax[0], x)
            CalibrationStore.magMax[1] = max(CalibrationStore.magMax[1], y)
            CalibrationStore.magMax[2] = max(CalibrationStore.magMax[2], z)
        }

        updateMagnetometerText()
    }

    private fun updateMagnetometerText() {
        binding.magResultText.text =
            String.format(
                Locale.US,
                "Mag min: X %.2f  Y %.2f  Z %.2f\nMag max: X %.2f  Y %.2f  Z %.2f",
                CalibrationStore.magMin[0],
                CalibrationStore.magMin[1],
                CalibrationStore.magMin[2],
                CalibrationStore.magMax[0],
                CalibrationStore.magMax[1],
                CalibrationStore.magMax[2]
            )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this stage.
    }

    override fun onPause() {
        super.onPause()
        stopSensors()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) startSensors()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSensors()
        _binding = null
    }

    private fun showUnsupportedGyroDialog() {
        if (_binding == null) return
        AlertDialog.Builder(requireContext())
            .setTitle("Unsupported phone")
            .setMessage("This phone does not have a gyroscope. This AirMouse implementation requires a gyroscope, so this device is not supported.")
            .setPositiveButton("OK", null)
            .show()
    }
}
