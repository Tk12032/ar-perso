package com.leo.arperso

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Fix(val lat: Double, val lon: Double, val accuracyM: Float)

/** Distance en mètres entre deux points GPS. */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val out = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, out)
    return out[0].toDouble()
}

/** Cap (degrés, 0 = nord, sens horaire) pour aller d'un point à un autre. */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** Point situé à [distanceM] mètres dans la direction [bearing] (degrés). */
fun offset(lat: Double, lon: Double, distanceM: Double, bearing: Double): Pair<Double, Double> {
    val r = 6_371_000.0
    val d = distanceM / r
    val b = Math.toRadians(bearing)
    val p1 = Math.toRadians(lat)
    val l1 = Math.toRadians(lon)
    val p2 = kotlin.math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
    val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    return Math.toDegrees(p2) to Math.toDegrees(l2)
}

/** Position GPS du joueur, mise à jour en continu tant que l'écran est affiché. */
@SuppressLint("MissingPermission")
@Composable
fun rememberLocation(enabled: Boolean): State<Fix?> {
    val context = LocalContext.current
    val fix = remember { mutableStateOf<Fix?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc ->
            val current = fix.value
            // On garde un point réseau imprécis seulement tant qu'on n'a pas mieux.
            if (current == null || loc.provider == LocationManager.GPS_PROVIDER || loc.accuracy <= current.accuracyM) {
                fix.value = Fix(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) }
                    lm.requestLocationUpdates(p, 1000L, 1f, listener, Looper.getMainLooper())
                }
            }
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return fix
}

/**
 * Cap vers lequel pointe le téléphone (degrés par rapport au nord géographique) :
 * la direction de la caméra quand il est tenu droit, le haut du téléphone quand il est à plat.
 */
@Composable
fun rememberHeading(fix: Fix?): State<Float> {
    val context = LocalContext.current
    val heading = remember { mutableFloatStateOf(0f) }
    val declination = fix?.let {
        GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination
    } ?: 0f
    DisposableEffect(declination) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val r = FloatArray(9)
        var smoothed = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                // Repère monde : X est, Y nord, Z haut. Colonne 2 = axe Z du téléphone, colonne 1 = axe Y.
                var e = -r[2]
                var n = -r[5]
                if (hypot(e, n) < 0.35f) { // téléphone presque à plat : on prend le haut de l'écran
                    e = r[1]
                    n = r[4]
                }
                val h = ((Math.toDegrees(atan2(e, n).toDouble()).toFloat() + declination) + 360f) % 360f
                // Lissage léger (en tenant compte du passage 359° -> 0°)
                smoothed = if (smoothed.isNaN()) h else {
                    var d = h - smoothed
                    if (d > 180) d -= 360f
                    if (d < -180) d += 360f
                    (smoothed + d * 0.15f + 360f) % 360f
                }
                heading.floatValue = smoothed
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}
