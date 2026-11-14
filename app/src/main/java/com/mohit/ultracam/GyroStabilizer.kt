package com.mohit.ultracam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.exp

class GyroStabilizer(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var shiftX: Float = 0f
        private set

    @Volatile
    var shiftY: Float = 0f
        private set

    @Volatile
    var rollRad: Float = 0f
        private set

    private var lastTimestampNs: Long = 0L
    private var integratedX = 0f
    private var integratedY = 0f
    private var integratedRoll = 0f
    private var registered = false

    fun start() {
        if (registered || gyroSensor == null) return
        lastTimestampNs = 0L
        reset()
        try {
            registered = sensorManager.registerListener(
                this,
                gyroSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        } catch (_: SecurityException) {
            registered = sensorManager.registerListener(
                this,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        reset()
    }

    fun reset() {
        integratedX = 0f
        integratedY = 0f
        integratedRoll = 0f
        shiftX = 0f
        shiftY = 0f
        rollRad = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled) {
            shiftX = 0f
            shiftY = 0f
            rollRad = 0f
            return
        }

        if (lastTimestampNs == 0L) {
            lastTimestampNs = event.timestamp
            return
        }

        val dt = (event.timestamp - lastTimestampNs) * 1e-9f
        lastTimestampNs = event.timestamp
        if (dt <= 0f || dt > 0.1f) return

        // Gyro axes in portrait orientation: X=pitch (vertical), Y=yaw (horizontal), Z=roll
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        // High-pass spring return to center so smooth panning follows naturally
        val decay = exp(-4.8f * dt)
        integratedX = (integratedX + gy * dt) * decay
        integratedY = (integratedY + gx * dt) * decay
        integratedRoll = (integratedRoll + gz * dt) * exp(-5.5f * dt)

        // Clamp within safe 5.5% border margin (inside 1.12x gimbal crop buffer)
        val maxShift = 0.055f
        val maxRoll = 0.045f

        val targetX = (-integratedX * 0.72f).coerceIn(-maxShift, maxShift)
        val targetY = (integratedY * 0.72f).coerceIn(-maxShift, maxShift)
        val targetRoll = (-integratedRoll * 0.65f).coerceIn(-maxRoll, maxRoll)

        // Smooth low-pass interpolation for silky motion
        shiftX += (targetX - shiftX) * 0.45f
        shiftY += (targetY - shiftY) * 0.45f
        rollRad += (targetRoll - rollRad) * 0.40f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
