package com.leo.arperso

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Repère local en mètres autour d'un point d'ancrage (le centre de la zone) : x vers l'est, y vers le nord.
 * À l'échelle d'une zone (quelques centaines de mètres), l'approximation plane est largement suffisante.
 */
class LocalFrame(private val lat0: Double, private val lon0: Double) {
    private val mPerDegLat = 110_540.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
    fun toXY(lat: Double, lon: Double) = Vec((lon - lon0) * mPerDegLon, (lat - lat0) * mPerDegLat)
    fun toLatLon(v: Vec) = (lat0 + v.y / mPerDegLat) to (lon0 + v.x / mPerDegLon)
}

data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec(x * k, y * k)
    val length get() = hypot(x, y)
    fun normalized() = if (length < 1e-9) Vec(0.0, 0.0) else this * (1.0 / length)
    /** Cap (degrés, 0 = nord, sens horaire) de ce vecteur. */
    val bearing get() = (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
}

/**
 * Position du joueur, mètre par mètre :
 * - chaque pas détecté (podomètre du téléphone, ou à défaut l'accéléromètre) fait avancer la position
 *   d'une longueur de pas dans la direction où l'on marche ;
 * - chaque point GPS tire doucement la position vers lui, pour corriger la dérive sans les sauts du GPS.
 */
class PlayerTracker(context: Context, private val frame: LocalFrame, phoneHeight: Float) {
    @Volatile var position: Vec? = null
        private set
    @Volatile var steps = 0
        private set
    @Volatile var lastStepAt = 0L
        private set
    @Volatile var gpsAccuracy = 0f
        private set
    @Volatile var usingStepSensor = false
        private set
    /** Cap de marche en degrés (fourni par la boussole de l'écran). */
    @Volatile var heading = 0f

    // Taille ≈ hauteur du téléphone / 0,75 ; longueur de pas ≈ 0,415 × taille.
    val stepLength = (0.55f * phoneHeight).coerceIn(0.5f, 0.9f).toDouble()
    private val recentSteps = ArrayDeque<Long>()

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var accFiltered = 0.0
    private var accAbove = false
    private var lastAccStep = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> onStep()
                Sensor.TYPE_LINEAR_ACCELERATION -> if (!usingStepSensor) {
                    // Détection de pas de secours : pic de l'accélération (hors gravité).
                    val a = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                    accFiltered = accFiltered * 0.8 + a * 0.2
                    val now = System.currentTimeMillis()
                    if (!accAbove && accFiltered > 1.6 && now - lastAccStep > 280) {
                        accAbove = true; lastAccStep = now; onStep()
                    } else if (accAbove && accFiltered < 0.8) accAbove = false
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** [stepPermission] : autorisation « activité physique » accordée (nécessaire au podomètre). */
    fun start(stepPermission: Boolean) {
        if (stepPermission && stepSensor != null) {
            usingStepSensor = sm.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
        if (!usingStepSensor) accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(listener)

    private fun onStep() {
        val now = System.currentTimeMillis()
        steps++
        lastStepAt = now
        synchronized(recentSteps) {
            recentSteps.addLast(now)
            while (recentSteps.isNotEmpty() && now - recentSteps.first() > 3000) recentSteps.removeFirst()
        }
        val p = position ?: return
        val h = Math.toRadians(heading.toDouble())
        position = p + Vec(sin(h), cos(h)) * stepLength
    }

    fun onFix(fix: Fix) {
        gpsAccuracy = fix.accuracyM
        val g = frame.toXY(fix.lat, fix.lon)
        val p = position
        if (p == null || (g - p).length > 40) { // premier point, ou dérive énorme : on repart du GPS
            position = g
            return
        }
        // Plus le GPS est précis, plus il compte ; un GPS à 5 m corrige ~25 % de l'écart par seconde.
        val k = (1.2 / fix.accuracyM.coerceAtLeast(3f)).coerceIn(0.02, 0.3)
        // Pendant la marche, les pas sont plus fiables à court terme : on écoute moins le GPS.
        val kk = if (isMoving()) k * 0.5 else k
        position = p + (g - p) * kk
    }

    /** Un pas dans les 1,5 dernières secondes. */
    fun isMoving() = System.currentTimeMillis() - lastStepAt < 1500

    /** Vitesse de marche estimée (m/s) sur les 3 dernières secondes. */
    fun speed(): Double {
        val now = System.currentTimeMillis()
        val n = synchronized(recentSteps) { recentSteps.count { now - it <= 3000 } }
        return n * stepLength / 3.0
    }

    /** Pour les tests : place le joueur directement. */
    fun teleport(v: Vec) { position = v }
}
