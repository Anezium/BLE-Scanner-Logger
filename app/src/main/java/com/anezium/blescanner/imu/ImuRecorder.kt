package com.anezium.blescanner.imu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.anezium.blescanner.data.BleCsvLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Enregistre les capteurs utiles au PDR sur un thread dédié. */
class ImuRecorder(
    context: Context,
    private val logger: BleCsvLogger
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hasStepPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    private val thread = HandlerThread("ble-imu-recorder")
    private lateinit var handler: Handler
    private val registeredSensors = mutableListOf<Sensor>()
    private val stats = ConcurrentHashMap<Int, SensorStats>()

    @Volatile
    private var running = false

    fun start(): ImuStartResult {
        if (running) return currentResult()
        thread.start()
        handler = Handler(thread.looper)
        running = true

        val unavailable = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        REQUESTS.forEach { request ->
            val sensor = request.candidateTypes
                .firstNotNullOfOrNull { type -> sensorManager.getDefaultSensor(type) }
            val permissionMissing = request.requiresStepPermission && !hasStepPermission
            val registered = when {
                sensor == null -> false
                permissionMissing -> false
                else -> runCatching {
                    stats[sensor.type] = SensorStats()
                    val success = sensorManager.registerListener(
                        this,
                        sensor,
                        request.samplingPeriodUs,
                        MAX_REPORT_LATENCY_US,
                        handler
                    )
                    if (!success) stats.remove(sensor.type)
                    success
                }.getOrElse {
                    stats.remove(sensor.type)
                    false
                }
            }
            val reason = when {
                sensor == null -> "unavailable"
                permissionMissing -> "activity_recognition_permission_missing"
                registered -> "registered"
                else -> "registration_failed"
            }
            logger.logSensorMetadata(
                channel = request.channel,
                sensor = sensor,
                requestedPeriodUs = request.samplingPeriodUs,
                registered = registered,
                reason = reason
            )
            when {
                sensor == null -> unavailable += request.channel
                !registered -> skipped += request.channel
                else -> registeredSensors += sensor
            }
        }
        return ImuStartResult(
            active = registeredSensors.map { BleCsvLogger.sensorEventType(it.type) },
            unavailable = unavailable,
            skipped = skipped
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        stats[event.sensor.type]?.record(event.timestamp)
        logger.logSensor(event)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (!running) return
        logger.logSensorAccuracy(sensor, accuracy)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)

        // Le runnable passe après le dernier callback déjà en cours sur ce Looper.
        val summariesWritten = CountDownLatch(1)
        if (handler.post {
                try {
                    registeredSensors.forEach { sensor ->
                        val sensorStats = stats[sensor.type] ?: return@forEach
                        logger.logSensorSummary(sensor, sensorStats.count, sensorStats.observedRateHz())
                    }
                } finally {
                    summariesWritten.countDown()
                }
            }
        ) {
            runCatching { summariesWritten.await(STOP_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } else {
            registeredSensors.forEach { sensor ->
                val sensorStats = stats[sensor.type] ?: return@forEach
                logger.logSensorSummary(sensor, sensorStats.count, sensorStats.observedRateHz())
            }
        }
        thread.quitSafely()
    }

    private fun currentResult(): ImuStartResult = ImuStartResult(
        active = registeredSensors.map { BleCsvLogger.sensorEventType(it.type) },
        unavailable = emptyList(),
        skipped = emptyList()
    )

    private data class SensorStats(
        var count: Long = 0L,
        var firstTimestampNanos: Long = 0L,
        var lastTimestampNanos: Long = 0L
    ) {
        fun record(timestampNanos: Long) {
            if (count == 0L) firstTimestampNanos = timestampNanos
            lastTimestampNanos = timestampNanos
            count += 1L
        }

        fun observedRateHz(): Double? {
            if (count < 2L || lastTimestampNanos <= firstTimestampNanos) return null
            return (count - 1L) * NANOS_PER_SECOND.toDouble() /
                (lastTimestampNanos - firstTimestampNanos).toDouble()
        }
    }

    private data class SensorRequest(
        val channel: String,
        val candidateTypes: List<Int>,
        val samplingPeriodUs: Int,
        val requiresStepPermission: Boolean = false
    )

    companion object {
        private const val HZ_100_PERIOD_US = 10_000
        private const val HZ_50_PERIOD_US = 20_000
        private const val HZ_10_PERIOD_US = 100_000
        private const val STEP_EVENT_PERIOD_US = 200_000
        private const val MAX_REPORT_LATENCY_US = 0
        private const val STOP_DRAIN_TIMEOUT_MS = 2_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * Pour accel/gyro/magnéto, la variante non calibrée est privilégiée:
         * elle conserve les valeurs brutes ainsi que le biais estimé. La
         * variante calibrée sert de fallback sur les téléphones qui n'en ont pas.
         */
        private val REQUESTS = listOf(
            SensorRequest(
                "accelerometer",
                listOf(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER),
                HZ_100_PERIOD_US
            ),
            SensorRequest(
                "gyroscope",
                listOf(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE),
                HZ_100_PERIOD_US
            ),
            SensorRequest("rotation_vector", listOf(Sensor.TYPE_ROTATION_VECTOR), HZ_50_PERIOD_US),
            SensorRequest("game_rotation_vector", listOf(Sensor.TYPE_GAME_ROTATION_VECTOR), HZ_50_PERIOD_US),
            SensorRequest(
                "magnetic_field",
                listOf(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD),
                HZ_50_PERIOD_US
            ),
            SensorRequest("pressure", listOf(Sensor.TYPE_PRESSURE), HZ_10_PERIOD_US),
            SensorRequest(
                "step_detector",
                listOf(Sensor.TYPE_STEP_DETECTOR),
                STEP_EVENT_PERIOD_US,
                requiresStepPermission = true
            ),
            SensorRequest(
                "step_counter",
                listOf(Sensor.TYPE_STEP_COUNTER),
                STEP_EVENT_PERIOD_US,
                requiresStepPermission = true
            )
        )
    }
}

data class ImuStartResult(
    val active: List<String>,
    val unavailable: List<String>,
    val skipped: List<String>
) {
    fun statusMessage(): String = buildString {
        append("IMU: ${active.size} flux actif(s)")
        if (unavailable.isNotEmpty()) append(" · absents: ${unavailable.joinToString()}")
        if (skipped.isNotEmpty()) append(" · ignorés: ${skipped.joinToString()}")
    }
}
