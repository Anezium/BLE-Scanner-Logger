package com.anezium.blescanner.data

import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import com.anezium.blescanner.parser.BeaconParser
import com.anezium.blescanner.parser.HexUtils
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Journal événementiel unique de la session BLE + IMU.
 *
 * Les lignes BLE et capteurs gardent leur cadence propre et partagent la même
 * base de temps monotone Android. Les champs non applicables restent vides.
 */
class BleCsvLogger(
    private val context: Context
) {
    val directory: File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ble_logs")
    val sessionStamp: String
    private val rawWriter: RotatingCsvWriter
    private var sequence = 0L
    private var closed = false

    init {
        directory.mkdirs()
        sessionStamp = FILE_STAMP_FORMAT.format(Instant.now())
        rawWriter = RotatingCsvWriter(
            directory = directory,
            baseName = "ble_imu_session_$sessionStamp",
            header = RAW_HEADER
        )
        logSessionMetadata("session_state", "started")
        logSessionMetadata("format_version", FORMAT_VERSION)
        logSessionMetadata("device_manufacturer", Build.MANUFACTURER)
        logSessionMetadata("device_model", Build.MODEL)
        logSessionMetadata("device_product", Build.PRODUCT)
        logSessionMetadata("android_sdk", Build.VERSION.SDK_INT)
        logSessionMetadata("android_release", Build.VERSION.RELEASE)
        logSessionMetadata("app_version", appVersion())
        logSessionMetadata("event_clock", "SystemClock.elapsedRealtimeNanos")
        logSessionMetadata("sampling_profile", "accel=100Hz;gyro=100Hz;rotation=50Hz;mag=50Hz;pressure=10Hz")
    }

    fun log(result: ScanResult): ScanPreview {
        val receivedTimeNanos = SystemClock.elapsedRealtimeNanos()
        val receivedWallTimeMs = System.currentTimeMillis()
        val record = result.scanRecord
        val parsed = BeaconParser.parse(record, result.device.address)
        val payloadHex = HexUtils.bytesToHex(record?.bytes)
        val row = emptyRow()
        row[COL_ADDRESS] = result.device.address
        row[COL_RSSI_DBM] = result.rssi
        row[COL_RAW_SCAN_RECORD_HEX] = payloadHex
        row[COL_DEVICE_NAME] = record?.deviceName.orEmpty()
        row[COL_MANUFACTURER_DATA] = parsed.manufacturerData
        row[COL_SERVICE_UUIDS] = parsed.serviceUuids
        row[COL_SERVICE_DATA] = parsed.serviceData
        row[COL_IBEACON_UUID] = parsed.iBeaconUuid
        row[COL_IBEACON_MAJOR] = parsed.iBeaconMajor
        row[COL_IBEACON_MINOR] = parsed.iBeaconMinor
        row[COL_IBEACON_TX_POWER] = parsed.iBeaconTxPower
        row[COL_EDDYSTONE_UID_NAMESPACE] = parsed.eddystoneUidNamespace
        row[COL_EDDYSTONE_UID_INSTANCE] = parsed.eddystoneUidInstance
        row[COL_EDDYSTONE_UID_TX_POWER] = parsed.eddystoneUidTxPower
        row[COL_EDDYSTONE_TLM_BATTERY_MV] = parsed.eddystoneTlmBatteryMv
        row[COL_EDDYSTONE_TLM_TEMPERATURE_C] = parsed.eddystoneTlmTemperatureC
        row[COL_EDDYSTONE_TLM_ADV_COUNT] = parsed.eddystoneTlmAdvCount
        row[COL_EDDYSTONE_TLM_SEC_COUNT] = parsed.eddystoneTlmSecCount
        row[COL_DATI_ROOM] = parsed.datiRoom
        row[COL_DATI_AUTONOMY] = parsed.datiAutonomy
        row[COL_DATI_TEMPERATURE_C] = parsed.datiTemperatureC
        row[COL_DATI_FLAGS] = parsed.datiFlags
        row[COL_DATI_FIRMWARE_VERSION] = parsed.datiFirmwareVersion
        writeRow(
            row = row,
            eventType = "ble",
            eventTimeNanos = result.timestampNanos,
            receivedTimeNanos = receivedTimeNanos,
            receivedWallTimeMs = receivedWallTimeMs
        )
        return ScanPreview(
            address = result.device.address,
            rssi = result.rssi,
            deviceName = record?.deviceName.orEmpty(),
            beaconKey = parsed.beaconKey,
            payloadHex = payloadHex,
            parserLabel = parserLabel(parsed),
            category = parserCategory(parsed)
        )
    }

    fun logSensor(event: SensorEvent) {
        val receivedTimeNanos = SystemClock.elapsedRealtimeNanos()
        val receivedWallTimeMs = System.currentTimeMillis()
        val sensor = event.sensor
        val type = sensorEventType(sensor.type)
        val values = event.values
        val row = emptyRow()
        fillSensorIdentity(row, sensor)
        row[COL_SENSOR_ACCURACY] = event.accuracy
        row[COL_SENSOR_UNIT] = sensorUnit(sensor.type)
        row[COL_SENSOR_VALUES_RAW] = values.joinToString("|")

        when (sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val quaternion = FloatArray(4)
                SensorManager.getQuaternionFromVector(quaternion, values)
                row[COL_SENSOR_X] = quaternion[1]
                row[COL_SENSOR_Y] = quaternion[2]
                row[COL_SENSOR_Z] = quaternion[3]
                row[COL_SENSOR_W] = quaternion[0]
                row[COL_SENSOR_HEADING_ACCURACY_RAD] = values.getOrNull(4)
            }
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_STEP_COUNTER -> row[COL_SENSOR_SCALAR] = values.getOrNull(0)
            else -> {
                row[COL_SENSOR_X] = values.getOrNull(0)
                row[COL_SENSOR_Y] = values.getOrNull(1)
                row[COL_SENSOR_Z] = values.getOrNull(2)
                if (isUncalibrated(sensor.type)) {
                    row[COL_SENSOR_BIAS_X] = values.getOrNull(3)
                    row[COL_SENSOR_BIAS_Y] = values.getOrNull(4)
                    row[COL_SENSOR_BIAS_Z] = values.getOrNull(5)
                }
            }
        }
        writeRow(row, type, event.timestamp, receivedTimeNanos, receivedWallTimeMs)
    }

    fun logSensorMetadata(
        channel: String,
        sensor: Sensor?,
        requestedPeriodUs: Int,
        registered: Boolean,
        reason: String
    ) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val row = emptyRow()
        row[COL_SENSOR_CHANNEL] = channel
        row[COL_SENSOR_REQUESTED_PERIOD_US] = requestedPeriodUs
        row[COL_SENSOR_AVAILABLE] = sensor != null
        row[COL_SENSOR_REGISTERED] = registered
        row[COL_METADATA_KEY] = "registration"
        row[COL_METADATA_VALUE] = reason
        if (sensor != null) {
            fillSensorIdentity(row, sensor)
            row[COL_SENSOR_VERSION] = sensor.version
            row[COL_SENSOR_RESOLUTION] = sensor.resolution
            row[COL_SENSOR_MAX_RANGE] = sensor.maximumRange
            row[COL_SENSOR_POWER_MA] = sensor.power
            row[COL_SENSOR_MIN_DELAY_US] = sensor.minDelay
            row[COL_SENSOR_MAX_DELAY_US] = sensor.maxDelay
            row[COL_SENSOR_REPORTING_MODE] = sensor.reportingMode
            row[COL_SENSOR_IS_WAKE_UP] = sensor.isWakeUpSensor
        }
        writeRow(row, "sensor_metadata", nowNanos, nowNanos, System.currentTimeMillis())
    }

    fun logSensorAccuracy(sensor: Sensor, accuracy: Int) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val row = emptyRow()
        fillSensorIdentity(row, sensor)
        row[COL_SENSOR_ACCURACY] = accuracy
        writeRow(row, "sensor_accuracy", nowNanos, nowNanos, System.currentTimeMillis())
    }

    fun logSensorSummary(sensor: Sensor, sampleCount: Long, observedRateHz: Double?) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val row = emptyRow()
        fillSensorIdentity(row, sensor)
        row[COL_SENSOR_SAMPLE_COUNT] = sampleCount
        row[COL_SENSOR_OBSERVED_RATE_HZ] = observedRateHz
        writeRow(row, "sensor_summary", nowNanos, nowNanos, System.currentTimeMillis())
    }

    fun logSessionMetadata(key: String, value: Any?) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val row = emptyRow()
        row[COL_METADATA_KEY] = key
        row[COL_METADATA_VALUE] = value
        writeRow(row, "session_metadata", nowNanos, nowNanos, System.currentTimeMillis())
    }

    @Synchronized
    fun close() {
        if (closed) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val row = emptyRow()
        row[COL_METADATA_KEY] = "session_state"
        row[COL_METADATA_VALUE] = "stopped"
        writeRow(row, "session_metadata", nowNanos, nowNanos, System.currentTimeMillis())
        closed = true
        rawWriter.flush()
        rawWriter.close()
    }

    @Synchronized
    private fun writeRow(
        row: MutableList<Any?>,
        eventType: String,
        eventTimeNanos: Long,
        receivedTimeNanos: Long,
        receivedWallTimeMs: Long
    ) {
        if (closed) return
        val safeEventTimeNanos = if (eventTimeNanos > 0L) eventTimeNanos else receivedTimeNanos
        val latencyNanos = (receivedTimeNanos - safeEventTimeNanos).coerceAtLeast(0L)
        val eventWallTimeMs = receivedWallTimeMs - latencyNanos / NANOS_PER_MILLISECOND
        sequence += 1L
        row[COL_SESSION_ID] = sessionStamp
        row[COL_SEQUENCE] = sequence
        row[COL_EVENT_TYPE] = eventType
        row[COL_WALL_TIME_ISO] = iso(eventWallTimeMs)
        row[COL_WALL_TIME_LOCAL] = localIso(eventWallTimeMs)
        row[COL_WALL_TIME_MS_EPOCH] = eventWallTimeMs
        row[COL_EVENT_TIME_NANOS] = safeEventTimeNanos
        row[COL_RECEIVED_TIME_NANOS] = receivedTimeNanos
        row[COL_CALLBACK_LATENCY_NANOS] = latencyNanos
        rawWriter.write(row)
    }

    private fun fillSensorIdentity(row: MutableList<Any?>, sensor: Sensor) {
        row[COL_SENSOR_CHANNEL] = sensorChannel(sensor.type)
        row[COL_SENSOR_TYPE] = sensorEventType(sensor.type)
        row[COL_SENSOR_ANDROID_TYPE] = sensor.type
        row[COL_SENSOR_NAME] = sensor.name
        row[COL_SENSOR_VENDOR] = sensor.vendor
    }

    private fun emptyRow(): MutableList<Any?> = MutableList(RAW_HEADER.size) { null }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($versionCode)"
    }.getOrDefault("unknown")

    private fun iso(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

    private fun localIso(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun parserLabel(parsed: com.anezium.blescanner.parser.ParsedAdvertising): String =
        when {
            parsed.datiRoom.isNotBlank() -> "DATI ${parsed.datiRoom}"
            parsed.iBeaconUuid.isNotBlank() -> "iBeacon ${parsed.iBeaconMajor}/${parsed.iBeaconMinor}"
            parsed.eddystoneUidNamespace.isNotBlank() -> "Eddystone UID"
            parsed.eddystoneTlmBatteryMv != null -> "Eddystone TLM"
            else -> parsed.beaconKey
        }

    private fun parserCategory(parsed: com.anezium.blescanner.parser.ParsedAdvertising): String =
        when {
            parsed.datiRoom.isNotBlank() -> "dati"
            parsed.iBeaconUuid.isNotBlank() -> "ibeacon"
            parsed.eddystoneUidNamespace.isNotBlank() -> "eddystone_uid"
            parsed.eddystoneTlmBatteryMv != null -> "eddystone_tlm"
            else -> "ble"
        }

    companion object {
        private const val FORMAT_VERSION = 2
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val FILE_STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)

        fun sensorEventType(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accelerometer_uncalibrated"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyroscope_uncalibrated"
            Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetic_field"
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "magnetic_field_uncalibrated"
            Sensor.TYPE_PRESSURE -> "pressure"
            Sensor.TYPE_STEP_DETECTOR -> "step_detector"
            Sensor.TYPE_STEP_COUNTER -> "step_counter"
            else -> "sensor_$type"
        }

        private fun sensorChannel(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accelerometer"
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "magnetic_field"
            else -> sensorEventType(type)
        }

        private fun sensorUnit(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "m/s^2"
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "uT"
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "quaternion"
            Sensor.TYPE_PRESSURE -> "hPa"
            Sensor.TYPE_STEP_DETECTOR -> "step"
            Sensor.TYPE_STEP_COUNTER -> "steps_since_boot"
            else -> ""
        }

        private fun isUncalibrated(type: Int): Boolean =
            type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ||
                type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED ||
                type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED

        private val RAW_HEADER = listOf(
            "session_id", "sequence", "event_type", "wall_time_iso", "wall_time_local",
            "wall_time_ms_epoch", "event_time_nanos", "received_time_nanos", "callback_latency_nanos",
            "sensor_channel", "sensor_type", "sensor_android_type", "sensor_name", "sensor_vendor",
            "sensor_accuracy", "sensor_x", "sensor_y", "sensor_z", "sensor_w", "sensor_bias_x",
            "sensor_bias_y", "sensor_bias_z", "sensor_heading_accuracy_rad", "sensor_scalar",
            "sensor_unit", "sensor_values_raw", "sensor_requested_period_us", "sensor_version",
            "sensor_resolution", "sensor_max_range", "sensor_power_ma", "sensor_min_delay_us",
            "sensor_max_delay_us", "sensor_reporting_mode", "sensor_is_wake_up", "sensor_available",
            "sensor_registered", "sensor_sample_count", "sensor_observed_rate_hz", "metadata_key",
            "metadata_value", "address", "rssi_dbm", "raw_scan_record_hex", "device_name",
            "manufacturer_data", "service_uuids", "service_data", "ibeacon_uuid", "ibeacon_major",
            "ibeacon_minor", "ibeacon_tx_power", "eddystone_uid_namespace", "eddystone_uid_instance",
            "eddystone_uid_tx_power", "eddystone_tlm_battery_mv", "eddystone_tlm_temperature_c",
            "eddystone_tlm_adv_count", "eddystone_tlm_sec_count", "dati_room", "dati_autonomy",
            "dati_temperature_c", "dati_flags", "dati_firmware_version"
        )

        private const val COL_SESSION_ID = 0
        private const val COL_SEQUENCE = 1
        private const val COL_EVENT_TYPE = 2
        private const val COL_WALL_TIME_ISO = 3
        private const val COL_WALL_TIME_LOCAL = 4
        private const val COL_WALL_TIME_MS_EPOCH = 5
        private const val COL_EVENT_TIME_NANOS = 6
        private const val COL_RECEIVED_TIME_NANOS = 7
        private const val COL_CALLBACK_LATENCY_NANOS = 8
        private const val COL_SENSOR_CHANNEL = 9
        private const val COL_SENSOR_TYPE = 10
        private const val COL_SENSOR_ANDROID_TYPE = 11
        private const val COL_SENSOR_NAME = 12
        private const val COL_SENSOR_VENDOR = 13
        private const val COL_SENSOR_ACCURACY = 14
        private const val COL_SENSOR_X = 15
        private const val COL_SENSOR_Y = 16
        private const val COL_SENSOR_Z = 17
        private const val COL_SENSOR_W = 18
        private const val COL_SENSOR_BIAS_X = 19
        private const val COL_SENSOR_BIAS_Y = 20
        private const val COL_SENSOR_BIAS_Z = 21
        private const val COL_SENSOR_HEADING_ACCURACY_RAD = 22
        private const val COL_SENSOR_SCALAR = 23
        private const val COL_SENSOR_UNIT = 24
        private const val COL_SENSOR_VALUES_RAW = 25
        private const val COL_SENSOR_REQUESTED_PERIOD_US = 26
        private const val COL_SENSOR_VERSION = 27
        private const val COL_SENSOR_RESOLUTION = 28
        private const val COL_SENSOR_MAX_RANGE = 29
        private const val COL_SENSOR_POWER_MA = 30
        private const val COL_SENSOR_MIN_DELAY_US = 31
        private const val COL_SENSOR_MAX_DELAY_US = 32
        private const val COL_SENSOR_REPORTING_MODE = 33
        private const val COL_SENSOR_IS_WAKE_UP = 34
        private const val COL_SENSOR_AVAILABLE = 35
        private const val COL_SENSOR_REGISTERED = 36
        private const val COL_SENSOR_SAMPLE_COUNT = 37
        private const val COL_SENSOR_OBSERVED_RATE_HZ = 38
        private const val COL_METADATA_KEY = 39
        private const val COL_METADATA_VALUE = 40
        private const val COL_ADDRESS = 41
        private const val COL_RSSI_DBM = 42
        private const val COL_RAW_SCAN_RECORD_HEX = 43
        private const val COL_DEVICE_NAME = 44
        private const val COL_MANUFACTURER_DATA = 45
        private const val COL_SERVICE_UUIDS = 46
        private const val COL_SERVICE_DATA = 47
        private const val COL_IBEACON_UUID = 48
        private const val COL_IBEACON_MAJOR = 49
        private const val COL_IBEACON_MINOR = 50
        private const val COL_IBEACON_TX_POWER = 51
        private const val COL_EDDYSTONE_UID_NAMESPACE = 52
        private const val COL_EDDYSTONE_UID_INSTANCE = 53
        private const val COL_EDDYSTONE_UID_TX_POWER = 54
        private const val COL_EDDYSTONE_TLM_BATTERY_MV = 55
        private const val COL_EDDYSTONE_TLM_TEMPERATURE_C = 56
        private const val COL_EDDYSTONE_TLM_ADV_COUNT = 57
        private const val COL_EDDYSTONE_TLM_SEC_COUNT = 58
        private const val COL_DATI_ROOM = 59
        private const val COL_DATI_AUTONOMY = 60
        private const val COL_DATI_TEMPERATURE_C = 61
        private const val COL_DATI_FLAGS = 62
        private const val COL_DATI_FIRMWARE_VERSION = 63
    }
}
