package com.example.calma.detection

import kotlin.math.max
import kotlin.math.sqrt

/** Una lectura combinada de sensores. hrvMs puede ser null si el reloj no la expone. */
data class Sample(
    val timestampMs: Long,
    val bpm: Double,
    val hrvMs: Double?,      // RMSSD o SDNN en ms (null si no disponible)
    val motion: Double       // desviación estándar de la magnitud del acelerómetro (m/s²)
)

enum class AlertLevel { NONE, LEVEL_1_MILD, LEVEL_2_IMMINENT }

/** Estadística incremental (Welford) para la línea base. */
class RunningStats {
    var n = 0L; private set
    var mean = 0.0; private set
    private var m2 = 0.0
    val std: Double get() = if (n > 1) sqrt(m2 / (n - 1)) else 0.0

    fun add(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
    }

    fun restore(n: Long, mean: Double, std: Double) {
        this.n = n; this.mean = mean
        this.m2 = std * std * max(n - 1, 0)
    }
}

/**
 * Línea base personal: solo aprende de muestras en reposo real (poco movimiento).
 * Considera la base "lista" tras minSamples (p. ej. varios días de uso).
 */
class Baseline(private val minSamples: Long = 2_000) {
    val bpm = RunningStats()
    val hrv = RunningStats()

    val isReady: Boolean get() = bpm.n >= minSamples

    fun learn(s: Sample, restMotionMax: Double) {
        if (s.motion > restMotionMax) return
        bpm.add(s.bpm)
        s.hrvMs?.let { hrv.add(it) }
    }

    /** z-score del BPM (positivo = por encima del reposo). */
    fun bpmZ(bpmNow: Double): Double {
        val sd = max(bpm.std, 3.0) // piso para evitar divisiones por casi-cero
        return (bpmNow - bpm.mean) / sd
    }

    /** z-score del HRV (negativo = HRV por debajo de lo normal). */
    fun hrvZ(hrvNow: Double): Double? {
        if (hrv.n < 30) return null
        val sd = max(hrv.std, 3.0)
        return (hrvNow - hrv.mean) / sd
    }
}

data class DetectorConfig(
    val windowMs: Long = 3 * 60_000L,          // ventana de evaluación
    val minSamplesInWindow: Int = 12,
    val restMotionMax: Double = 0.35,          // "casi inmóvil" (no exactamente 0)
    val activeMotion: Double = 1.5,            // movimiento claro => descartar
    // Entrada / salida (histéresis)
    val l1EnterZ: Double = 1.5, val l1ExitZ: Double = 0.8,
    val l2EnterZ: Double = 2.5, val l2ExitZ: Double = 1.5,
    val hrvLowZ: Double = -1.0,                // HRV "baja" para refuerzo
    val sustainedFraction: Double = 0.7,       // % de la ventana que debe cumplir
    // Cooldown
    val cooldownL1Ms: Long = 20 * 60_000L,
    val cooldownL2Ms: Long = 10 * 60_000L
)

class StressDetector(
    private val baseline: Baseline,
    private val cfg: DetectorConfig = DetectorConfig()
) {
    private val window = ArrayDeque<Sample>()
    private var current = AlertLevel.NONE
    private var lastL1Ms = Long.MIN_VALUE / 2
    private var lastL2Ms = Long.MIN_VALUE / 2

    /** 1.0 = normal; >1 = menos sensible (tras "no fue ansiedad"); <1 = más sensible. */
    private var sensitivity = 1.0

    /** Procesa una muestra y devuelve el nivel de alerta que debe dispararse (o NONE). */
    fun onSample(s: Sample): AlertLevel {
        // 1) Aprender línea base solo en reposo y mientras no estemos en alerta
        if (current == AlertLevel.NONE) baseline.learn(s, cfg.restMotionMax)

        // 2) Mantener ventana
        window.addLast(s)
        while (window.isNotEmpty() && s.timestampMs - window.first().timestampMs > cfg.windowMs) {
            window.removeFirst()
        }
        if (!baseline.isReady || window.size < cfg.minSamplesInWindow) return AlertLevel.NONE

        // 3) Contexto de movimiento: si hay actividad clara en la ventana, se descarta
        val activeShare = window.count { it.motion > cfg.activeMotion }.toDouble() / window.size
        if (activeShare > 0.2) {
            current = AlertLevel.NONE
            return AlertLevel.NONE
        }
        val restShare = window.count { it.motion <= cfg.restMotionMax * 2 }.toDouble() / window.size
        if (restShare < cfg.sustainedFraction) return AlertLevel.NONE

        // 4) Evaluar condición sostenida con histéresis
        val l1Enter = cfg.l1EnterZ * sensitivity
        val l2Enter = cfg.l2EnterZ * sensitivity

        val l1Frac = fractionWhere { bpmZ(it) > (if (current != AlertLevel.NONE) cfg.l1ExitZ else l1Enter) }
        val l2Frac = fractionWhere { bpmZ(it) > (if (current == AlertLevel.LEVEL_2_IMMINENT) cfg.l2ExitZ else l2Enter) }
        val hrvLowFrac = fractionHrvLow()

        val l2Met = l2Frac >= cfg.sustainedFraction && (hrvLowFrac == null || hrvLowFrac >= 0.5)
        val l1Met = l1Frac >= cfg.sustainedFraction && (hrvLowFrac == null || hrvLowFrac >= 0.3)

        val target = when {
            l2Met -> AlertLevel.LEVEL_2_IMMINENT
            l1Met -> AlertLevel.LEVEL_1_MILD
            else -> AlertLevel.NONE
        }

        // 5) Cooldown: solo disparar si pasó el tiempo; el estado interno sí se actualiza
        val now = s.timestampMs
        current = target
        return when (target) {
            AlertLevel.LEVEL_2_IMMINENT ->
                if (now - lastL2Ms >= cfg.cooldownL2Ms) { lastL2Ms = now; target } else AlertLevel.NONE
            AlertLevel.LEVEL_1_MILD ->
                if (now - lastL1Ms >= cfg.cooldownL1Ms && now - lastL2Ms >= cfg.cooldownL2Ms) {
                    lastL1Ms = now; target
                } else AlertLevel.NONE
            AlertLevel.NONE -> AlertLevel.NONE
        }
    }

    /** Feedback del usuario tras una alerta: ajusta la sensibilidad. */
    fun onUserFeedback(wasAnxiety: Boolean) {
        sensitivity = if (wasAnxiety) max(0.8, sensitivity - 0.05) else minOf(1.5, sensitivity + 0.1)
    }

    /** Activación manual: ignora el algoritmo (el UI debe lanzar la intervención directamente). */
    fun resetState() { current = AlertLevel.NONE; window.clear() }

    private fun bpmZ(s: Sample) = baseline.bpmZ(s.bpm)

    private fun fractionWhere(pred: (Sample) -> Boolean): Double =
        window.count(pred).toDouble() / window.size

    /** Fracción de muestras con HRV baja, o null si no hay datos de HRV suficientes. */
    private fun fractionHrvLow(): Double? {
        val withHrv = window.filter { it.hrvMs != null }
        if (withHrv.size < 3) return null
        val z = withHrv.mapNotNull { baseline.hrvZ(it.hrvMs!!) }
        if (z.isEmpty()) return null
        return z.count { it < cfg.hrvLowZ }.toDouble() / z.size
    }
}
