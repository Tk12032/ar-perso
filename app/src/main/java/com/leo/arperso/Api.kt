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
) {
    companion object {
        fun from(o: JSONObject) = Zone(
            id = o.getInt("id"),
            name = o.getString("name"),
            kind = o.getString("kind"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            radiusM = o.getDouble("radius_m"),
            distanceM = if (o.has("distance_m")) o.getDouble("distance_m") else null,
        )
    }
}

/**
 * Une chasse. [present] = false : la zone est vide pour l'instant.
 * [lairLat]/[lairLon] : cachette de la créature (null = à choisir par le téléphone, mode test).
 * [immediate] : la créature apparaît tout de suite, sans phase de recherche.
 */
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
        )
    }

    suspend fun pullCommands(token: String): List<Command> {
        val arr = call("GET", "/api/commands", token).getJSONArray("commands")
        return List(arr.length()) { arr.getJSONObject(it).let { o -> Command(o.getInt("id"), o.getString("kind")) } }
    }

    private fun enc(d: Double) = URLEncoder.encode(d.toString(), "UTF-8")
    private fun JSONArray.toStrings() = List(length()) { getString(it) }
}
