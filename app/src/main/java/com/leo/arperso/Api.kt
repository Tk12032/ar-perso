package com.leo.arperso

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val SERVER_URL = "https://foundfootage.leo-mangez.be"

class ApiException(val code: Int, message: String) : Exception(message)

data class Zone(
    val id: Int,
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val distanceM: Double? = null,
    /** Contour d'une zone dessinée (lat, lon), null pour une zone ronde. Jamais affiché au joueur. */
    val polygon: List<Pair<Double, Double>>? = null,
    /** Légende de la créature, écrite par l'admin. */
    val legend: String? = null,
) {
    /** Le point (lat, lon) est-il dans la zone ? */
    fun contains(lat: Double, lon: Double): Boolean {
        val poly = polygon
        if (poly == null || poly.size < 3) return distanceM(lat, lon, this.lat, this.lon) <= radiusM
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (yi, xi) = poly[i]
            val (yj, xj) = poly[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        fun from(o: JSONObject) = Zone(
            id = o.getInt("id"),
            name = o.getString("name"),
            kind = o.getString("kind"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            radiusM = o.getDouble("radius_m"),
            distanceM = if (o.has("distance_m")) o.getDouble("distance_m") else null,
            polygon = o.optJSONArray("polygon")?.let { a ->
                List(a.length()) { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }
            },
            legend = o.optString("legend").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

/**
 * Une chasse. [present] = false : la zone est vide pour l'instant.
 * [lairLat]/[lairLon] : cachette de la créature (null = à choisir par le téléphone, mode test).
 * [immediate] : la créature apparaît tout de suite, sans phase de recherche.
 */
/** Comportement d'une créature (réglé sur le serveur). Vitesses en m/s, distances en m. */
data class Behavior(
    val mode: String = "hunt", // keep | hunt | stalk
    val speed: Float = 1.0f,
    val wander: Float = 0.3f,
    val keepMin: Float = 0f,
    val keepMax: Float = 0f,
    val jump: Boolean = true,
) {
    companion object {
        fun from(o: JSONObject?) = if (o == null) Behavior() else Behavior(
            mode = o.optString("mode", "hunt"),
            speed = o.optDouble("speed", 1.0).toFloat(),
            wander = o.optDouble("wander", 0.3).toFloat(),
            keepMin = o.optDouble("keep_min", 0.0).toFloat(),
            keepMax = o.optDouble("keep_max", 0.0).toFloat(),
            jump = o.optBoolean("jump", true),
        )

        /** Comportements par défaut si le serveur ne répond pas (même table que le serveur). */
        fun forCreature(c: String) = when (c) {
            "pale" -> Behavior("keep", 0.7f, 0.3f, 8f, 12f, false)
            "black" -> Behavior("stalk", 0.5f, 0.25f, 0f, 0f, true)
            else -> Behavior("hunt", 1.6f, 0.4f, 0f, 0f, true)
        }
    }
}

data class Hunt(
    val id: Int,
    val present: Boolean,
    val creature: String,
    val color: Int,
    val evidences: List<String>,
    val zone: Zone?,
    val lairLat: Double? = null,
    val lairLon: Double? = null,
    val immediate: Boolean = false,
    val behavior: Behavior = Behavior.forCreature(creature),
)

data class Command(val id: Int, val kind: String)

/** Joueur enregistré sur ce téléphone (pseudo + jeton). */
data class Player(val pseudo: String, val token: String) {
    companion object {
        private const val PREFS = "player"
        fun load(context: Context): Player? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = p.getString("token", null) ?: return null
            return Player(p.getString("pseudo", "") ?: "", token)
        }

        fun save(context: Context, player: Player) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("pseudo", player.pseudo).putString("token", player.token).apply()
        }
    }
}

/** Client minimal du serveur Found Footage (JSON sur HTTPS). */
object Api {
    private suspend fun call(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(SERVER_URL + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (code !in 200..299) {
                    val detail = json.opt("detail")
                    throw ApiException(code, if (detail is String) detail else "Erreur serveur ($code)")
                }
                json
            } finally {
                conn.disconnect()
            }
        }

    suspend fun register(pseudo: String): Player {
        val r = call("POST", "/api/players", body = JSONObject().put("pseudo", pseudo))
        return Player(r.getString("pseudo"), r.getString("token"))
    }

    suspend fun nearbyZones(lat: Double, lon: Double, limit: Int = 20): List<Zone> {
        val q = "lat=${enc(lat)}&lon=${enc(lon)}&limit=$limit&within_km=100"
        val arr = call("GET", "/api/zones?$q").getJSONArray("zones")
        return List(arr.length()) { Zone.from(arr.getJSONObject(it)) }
    }

    /** Démarre une chasse. [zoneId] = 0 avec [test] = true : zone de test du téléphone. */
    suspend fun startHunt(token: String, zoneId: Int, lat: Double?, lon: Double?, test: Boolean): Hunt {
        val body = JSONObject().put("zone_id", zoneId).put("test", test)
        if (lat != null && lon != null) body.put("lat", lat).put("lon", lon)
        val r = call("POST", "/api/hunts", token, body)
        val zone = r.optJSONObject("zone")?.let { Zone.from(it) }
        if (!r.optBoolean("present", true)) return Hunt(-1, false, "", 0, emptyList(), zone)
        val lair = r.optJSONObject("lair")
        return Hunt(
            id = r.getInt("hunt_id"),
            present = true,
            creature = r.getString("creature"),
            color = android.graphics.Color.parseColor(r.getString("color")),
            evidences = r.getJSONArray("evidences").toStrings(),
            zone = zone,
            lairLat = lair?.getDouble("lat"),
            lairLon = lair?.getDouble("lon"),
            behavior = r.optJSONObject("behavior")?.let { Behavior.from(it) } ?: Behavior.forCreature(r.getString("creature")),
        )
    }

    suspend fun pullCommands(token: String): List<Command> {
        val arr = call("GET", "/api/commands", token).getJSONArray("commands")
        return List(arr.length()) { arr.getJSONObject(it).let { o -> Command(o.getInt("id"), o.getString("kind")) } }
    }

    private fun enc(d: Double) = URLEncoder.encode(d.toString(), "UTF-8")
    private fun JSONArray.toStrings() = List(length()) { getString(it) }
}
