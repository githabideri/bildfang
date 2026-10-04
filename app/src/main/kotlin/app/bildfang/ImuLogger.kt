package app.bildfang

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * P8: raw IMU logging.
 *
 * Preserves the raw [SensorEvent] samples of the accelerometer and
 * gyroscope with their native timestamps — domain `android_monotonic`
 * (`SensorEvent.timestamp` is `SystemClock.elapsedRealtimeNanos()` on
 * Android; docs/capture-format.md clocks table). No resampling in the
 * preservation layer: the sample rate is *measured* from the data and
 * persisted (device.json), not assumed to be 50 Hz.
 *
 * Sensors can stop delivering while the device is suspended (gaps in the
 * data, not in the clock — see capture-format.md "Interruptions"); gaps
 * are preserved as-is.
 *
 * Threading: `start`/`stop` from any thread; events are delivered on the
 * main looper; appends and snapshots are under a lock.
 */
class ImuLogger(private val sensorManager: SensorManager) {

    /** One raw sensor sample in its native units: accel m/s² (gravity
     *  included), gyro rad/s. [tsNs] is `SensorEvent.timestamp`. */
    data class Sample(val tsNs: Long, val x: Float, val y: Float, val z: Float)

    private val accel = ArrayList<Sample>()
    private val gyro = ArrayList<Sample>()
    private val lock = ReentrantLock()

    private val accSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyrSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val accelerometerPresent: Boolean get() = accSensor != null
    val gyroscopePresent: Boolean get() = gyrSensor != null
    val accelerometerName: String get() = accSensor?.name ?: ""
    val gyroscopeName: String get() = gyrSensor?.name ?: ""

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lock.withLock {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER ->
                        accel.add(Sample(event.timestamp, event.values[0], event.values[1], event.values[2]))
                    Sensor.TYPE_GYROSCOPE ->
                        gyro.add(Sample(event.timestamp, event.values[0], event.values[1], event.values[2]))
                    else -> {} // other sensors are never registered
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Starts capturing. `SENSOR_DELAY_GAME` (nominal 50 Hz); the actually
     * achieved rate is measured from the captured samples and persisted.
     * A missing sensor is tolerated: the stream stays empty and the CSV
     * carries empty columns for it (spec: "If one sensor is missing on a
     * device, the affected columns are empty").
     */
    fun start() {
        accSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyrSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        // explicit cast: unregisterListener has a SensorEventListener /
        // SensorListener overload pair
        sensorManager.unregisterListener(listener as SensorEventListener)
    }

    /** Copies of the raw streams (order = delivery order). */
    fun snapshot(): Pair<List<Sample>, List<Sample>> =
        lock.withLock { accel.toList() to gyro.toList() }

    /**
     * Measured rate of a stream: (n-1) samples over the timestamp span.
     * Returns 0.0 for fewer than two samples (undecidable, not assumed).
     */
    fun rateHz(samples: List<Sample>): Double {
        if (samples.size < 2) return 0.0
        val spanNs = samples.last().tsNs - samples.first().tsNs
        return if (spanNs <= 0L) 0.0 else (samples.size - 1) / (spanNs / 1e9)
    }
}
