package com.example.calma.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.PassiveListenerConfig
import com.example.calma.detection.*
import kotlinx.coroutines.*
import kotlin.math.sqrt

/*
 * DEPENDENCIAS (build.gradle del módulo wear):
 *   implementation("androidx.health:health-services-client:1.0.0")
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 *
 * PERMISOS (AndroidManifest.xml):
 *   <uses-permission android:name="android.permission.BODY_SENSORS"/>
 *   <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION"/>
 *   <uses-permission android:name="android.permission.VIBRATE"/>
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *
 *   <service android:name=".wear.PassiveHealthService" android:exported="true"
 *            android:permission="com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING">
 *       <intent-filter>
 *           <action android:name="hs.passivemonitoring.DATA_UPDATE"/>
 *       </intent-filter>
 *   </service>
 *
 * NOTA: Health Services NO entrega HRV en todos los relojes. Aquí HRV va como null;
 * si tu dispositivo lo expone (p. ej. Samsung Health Sensor SDK), pásalo en Sample.hrvMs.
 */

/** Singleton simple con el detector (en producción usa Hilt + persistencia de la línea base). */
object DetectorHolder {
    val baseline = Baseline()
    val detector = StressDetector(baseline)
    @Volatile var lastMotion: Double = 0.0
}

/** 1) Registro del monitoreo pasivo en segundo plano. */
suspend fun registerPassiveMonitoring(context: Context) {
    val client = HealthServices.getClient(context).passiveMonitoringClient
    val config = PassiveListenerConfig.builder()
        .setDataTypes(setOf(DataType.HEART_RATE_BPM))
        .build()
    client.setPassiveListenerServiceAsync(PassiveHealthService::class.java, config)
}

/** 2) Servicio que recibe las lecturas de frecuencia cardíaca. */
class PassiveHealthService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val hr = dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull() ?: return

        val sample = Sample(
            timestampMs = System.currentTimeMillis(),
            bpm = hr.value,
            hrvMs = null,                            // completar si el hardware lo expone
            motion = DetectorHolder.lastMotion
        )

        when (DetectorHolder.detector.onSample(sample)) {
            AlertLevel.LEVEL_1_MILD -> showGentleReminder(applicationContext)
            AlertLevel.LEVEL_2_IMMINENT -> startBreathingIntervention(applicationContext)
            AlertLevel.NONE -> Unit
        }
    }
}

/** 3) Movimiento: desviación estándar de la magnitud del acelerómetro en ventanas de ~5 s. */
class MotionTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val buffer = ArrayList<Double>()
    private var windowStart = 0L

    fun start() {
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        if (windowStart == 0L) windowStart = now
        buffer.add(sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()))

        if (now - windowStart >= 5_000 && buffer.size > 5) {
            val mean = buffer.average()
            val variance = buffer.sumOf { (it - mean) * (it - mean) } / buffer.size
            DetectorHolder.lastMotion = sqrt(variance)
            buffer.clear(); windowStart = now
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
}

/** 4) Guía háptica de respiración. Patrón configurable (caja 4-4-4-4 o exhalación larga 4-6). */
data class BreathPattern(val inhaleMs: Long, val holdMs: Long, val exhaleMs: Long, val restMs: Long, val cycles: Int) {
    companion object {
        val BOX = BreathPattern(4000, 4000, 4000, 4000, cycles = 6)
        val SOFT_4_6 = BreathPattern(4000, 0, 6000, 0, cycles = 8)   // más suave, sin retención
    }
}

class HapticBreathingGuide(context: Context) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var job: Job? = null

    fun start(scope: CoroutineScope, p: BreathPattern, onPhase: (String) -> Unit = {}) {
        job = scope.launch {
            repeat(p.cycles) {
                onPhase("Inhala");  continuousBuzz(p.inhaleMs)           // vibración continua = inhalar
                if (p.holdMs > 0) { onPhase("Sostén"); delay(p.holdMs) }  // pausa = retener
                onPhase("Exhala");  pulsedBuzz(p.exhaleMs)               // pulsos = exhalar
                if (p.restMs > 0) { onPhase("Pausa"); delay(p.restMs) }
            }
            onPhase("Listo")
        }
    }

    fun stop() { job?.cancel(); vibrator.cancel() }

    private suspend fun continuousBuzz(ms: Long) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, 80))  // intensidad suave (1-255)
        delay(ms)
    }

    private suspend fun pulsedBuzz(ms: Long) {
        val pulse = 250L; val gap = 250L
        val n = (ms / (pulse + gap)).toInt().coerceAtLeast(1)
        val timings = LongArray(n * 2) { if (it % 2 == 0) gap else pulse }.also { it[0] = 0 }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        delay(ms)
    }
}

/** Ganchos de UI: reemplaza por notificación / Activity de Compose for Wear OS. */
fun showGentleReminder(context: Context) {
    // Notificación silenciosa: "Tómate una pausa y bebe agua" + botón "No fue ansiedad".
}

fun startBreathingIntervention(context: Context) {
    // Lanzar Activity con fondo oscuro, animación de círculo y HapticBreathingGuide.
    // Botones: "Anclaje 5-4-3-2-1", "Contactar a alguien", "Detener".
    // Al terminar: preguntar "¿Te ayudó?" y llamar DetectorHolder.detector.onUserFeedback(...)
}
