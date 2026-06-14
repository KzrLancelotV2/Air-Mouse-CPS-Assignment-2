package com.example.airmouse

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
import java.util.Locale
import kotlin.math.min
import kotlin.math.max

object CalibrationStore {
    var gyroBias: FloatArray? = null

    val accelSixPositions = mutableListOf<FloatArray>()
    var accelOffset: FloatArray? = null
    var accelScale: FloatArray? = null

    var magMin = FloatArray(3) { Float.POSITIVE_INFINITY }
    var magMax = FloatArray(3) { Float.NEGATIVE_INFINITY }
    var magOffset: FloatArray? = null
    var magScale: FloatArray? = null

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
    }

    private fun updateInstructionText() {
        binding.calibrationInstructionText.text =
            "Step 1: Put the phone on a stable surface and press the gyroscope button.\n\n" +
                    "Step 2: Place the phone in 6 different physical orientations and press the accelerometer button once in each position.\n\n" +
                    "Step 3: Press the magnetometer button, move the phone in a figure-8 shape, then press the button again to stop."
    }
    private fun computeAccelerometerCalibrationIfReady() {
        val positions = CalibrationStore.accelSixPositions

        if (positions.size < 6) return

        val g = SensorManager.GRAVITY_EARTH

        val xPlus = positions[0][0]
        val xMinus = positions[1][0]

        val yPlus = positions[2][1]
        val yMinus = positions[3][1]

        val zPlus = positions[4][2]
        val zMinus = positions[5][2]

        val offsetX = (xPlus + xMinus) / 2f
        val offsetY = (yPlus + yMinus) / 2f
        val offsetZ = (zPlus + zMinus) / 2f

        val scaleX = g / ((xPlus - xMinus) / 2f)
        val scaleY = g / ((yPlus - yMinus) / 2f)
        val scaleZ = g / ((zPlus - zMinus) / 2f)

        CalibrationStore.accelOffset = floatArrayOf(offsetX, offsetY, offsetZ)
        CalibrationStore.accelScale = floatArrayOf(scaleX, scaleY, scaleZ)

        binding.accelResultText.text =
            String.format(
                Locale.US,
                "Accelerometer calibration completed:\n" +
                        "Offset X %.3f  Y %.3f  Z %.3f\n" +
                        "Scale X %.3f  Y %.3f  Z %.3f",
                offsetX,
                offsetY,
                offsetZ,
                scaleX,
                scaleY,
                scaleZ
            )

        binding.calibrationStatusText.text =
            "Status: accelerometer calibration completed."
    }

    private fun startSensors() {
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    private fun startGyroBiasCollection() {
        gyroSamples.clear()
        collectingGyro = true

        binding.calibrationStatusText.text =
            "Status: collecting gyroscope samples. Keep the phone completely still."

        binding.gyroResultText.text =
            "Gyro bias: collecting 0/$gyroTargetSamples"
    }

    private fun saveAccelerometerPosition() {
        val currentAccel = latestAccel

        if (currentAccel == null) {
            binding.calibrationStatusText.text =
                "Status: no accelerometer data yet. Wait a second and try again."
            return
        }

        if (CalibrationStore.accelSixPositions.size >= 6) {
            binding.calibrationStatusText.text =
                "Status: already saved 6 accelerometer positions."
            return
        }

        CalibrationStore.accelSixPositions.add(currentAccel.clone())

        val count = CalibrationStore.accelSixPositions.size

        binding.accelResultText.text =
            "Accelerometer positions saved: $count/6"

        if (count < 6) {
            binding.accelCaptureButton.text =
                "2. Save Accelerometer Position ${count + 1}/6"
        } else {
            binding.accelCaptureButton.text =
                "2. Accelerometer 6 Positions Completed"

            computeAccelerometerCalibrationIfReady()
        }

        binding.calibrationStatusText.text =
            "Status: saved accelerometer position $count."
    }
    private fun computeMagnetometerCalibration() {
        val minValues = CalibrationStore.magMin
        val maxValues = CalibrationStore.magMax

        val offsetX = (maxValues[0] + minValues[0]) / 2f
        val offsetY = (maxValues[1] + minValues[1]) / 2f
        val offsetZ = (maxValues[2] + minValues[2]) / 2f

        val scaleX = (maxValues[0] - minValues[0]) / 2f
        val scaleY = (maxValues[1] - minValues[1]) / 2f
        val scaleZ = (maxValues[2] - minValues[2]) / 2f

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

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

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

    private fun updateMagnetometerMinMax(x: Float, y: Float, z: Float) {
        CalibrationStore.magMin[0] = min(CalibrationStore.magMin[0], x)
        CalibrationStore.magMin[1] = min(CalibrationStore.magMin[1], y)
        CalibrationStore.magMin[2] = min(CalibrationStore.magMin[2], z)

        CalibrationStore.magMax[0] = max(CalibrationStore.magMax[0], x)
        CalibrationStore.magMax[1] = max(CalibrationStore.magMax[1], y)
        CalibrationStore.magMax[2] = max(CalibrationStore.magMax[2], z)

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

    override fun onDestroyView() {
        super.onDestroyView()
        stopSensors()
        _binding = null
    }
}