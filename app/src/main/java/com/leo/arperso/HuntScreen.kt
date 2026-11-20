package com.leo.arperso

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val Green = Color(0xFF7CFF8A)
private val DimGreen = Color(0xFF2E6B36)
private val Red = Color(0xFFFF4040)
private val PanelBg = Color(0xE6000000)

/** Zone de test créée par le téléphone lui-même (id 0). */
private fun localTestZone(lat: Double, lon: Double) = Zone(0, "Zone de test", "test", lat, lon, 40.0)

/**
 * Écran de chasse : carte des zones proches, radar vers la zone choisie,
 * et lancement de la rencontre quand on entre dans la zone.
 */
@Composable
fun HuntScreen(player: Player, onEncounter: (Hunt) -> Unit) {
    val context = LocalContext.current
    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = it[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            it[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!hasLocation) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val fix by rememberLocation(hasLocation)
    val heading by rememberHeading(fix)
    val scope = rememberCoroutineScope()

    var zones by remember { mutableStateOf<List<Zone>>(emptyList()) }
    var lastFetch by remember { mutableStateOf<Fix?>(null) }
    var selected by remember { mutableStateOf<Zone?>(null) }
    var forceInside by remember { mutableStateOf(false) }
    var showRadar by remember { mutableStateOf(false) }
    var showTest by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }

    // Zones proches : au premier point GPS, puis tous les 300 m.
    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        val last = lastFetch
        if (last != null && distanceM(last.lat, last.lon, f.lat, f.lon) < 300) return@LaunchedEffect
        lastFetch = f
        runCatching { Api.nearbyZones(f.lat, f.lon) }
            .onSuccess { list -> zones = list; message = null }
            .onFailure { message = "Serveur injoignable : ${it.message}" }
    }

    fun startEncounter(zone: Zone?, test: Boolean) {
        if (starting) return
        starting = true
        scope.launch {
            val f = fix
            val hunt = runCatching {
                Api.startHunt(player.token, zone?.id ?: 0, f?.lat, f?.lon, test = test || zone == null || zone.id == 0)
            }.getOrElse {
                // Hors ligne ou refus : on joue quand même, avec une ghoule au hasard (sans score).
                message = "Chasse hors ligne : ${it.message}"
                val c = listOf("pale" to 0xFF9FB59A.toInt(), "red" to 0xFF8A1C1C.toInt(), "black" to 0xFF1D2433.toInt())
                    .random()
                Hunt(-1, c.first, c.second, emptyList())
            }
            starting = false
            forceInside = false
            onEncounter(hunt)
        }
    }

    // Commandes de test envoyées depuis l'admin (toutes les 10 s).
    LaunchedEffect(player.token) {
        while (true) {
            runCatching { Api.pullCommands(player.token) }.getOrNull()?.forEach { cmd ->
                when (cmd.kind) {
                    "zone_near" -> fix?.let { f ->
                        val (la, lo) = offset(f.lat, f.lon, 300.0, Random.nextDouble(360.0))
                        selected = localTestZone(la, lo); showRadar = true
                        message = "Commande admin : zone de test à 300 m"
                    }
                    "in_zone" -> { forceInside = true; showRadar = true; message = "Commande admin : tu es dans la zone" }
                    "spawn_now" -> startEncounter(selected, test = true)
                }
            }
            delay(10_000)
        }
    }

    val zone = selected
    val dist = if (zone != null && fix != null) distanceM(fix!!.lat, fix!!.lon, zone.lat, zone.lon) else null
    val inside = zone != null && (forceInside || (dist != null && dist <= zone.radiusM))

    // Entrée dans la zone : signal, puis la rencontre se lance toute seule.
    LaunchedEffect(inside) {
        if (inside) {
            delay(3000)
            startEncounter(selected, test = forceInside)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasLocation) {
            Hint("L'application a besoin de ta position pour trouver les zones.", Modifier.align(Alignment.Center))
        } else if (showRadar && zone != null) {
            RadarView(zone, fix, heading, dist, inside, onBack = { showRadar = false })
        } else {
            Column(Modifier.fillMaxSize()) {
                ZoneMap(
                    fix = fix,
                    zones = zones,
                    selected = zone,
                    onSelect = { selected = it; showRadar = true },
                    modifier = Modifier.weight(1f)
                )
                ZoneList(fix, zones, onSelect = { selected = it; showRadar = true })
            }
        }

        // En-tête : pseudo, menu test
        Row(
            Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chip("● ${player.pseudo}", Green)
            Spacer(Modifier.weight(1f))
            Chip(if (showTest) "✕ TEST" else "TEST", Color.White) { showTest = !showTest }
        }
        if (showTest) {
            Column(
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(top = 64.dp, end = 12.dp)
                    .background(PanelBg, RoundedCornerShape(12.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TestButton("Zone de test à 300 m") {
                    fix?.let { f ->
                        val (la, lo) = offset(f.lat, f.lon, 300.0, Random.nextDouble(360.0))
                        selected = localTestZone(la, lo); showRadar = true
                    } ?: run { message = "Pas encore de position GPS" }
                    showTest = false
                }
                TestButton("Je suis dans la zone") {
                    if (selected == null) fix?.let { selected = localTestZone(it.lat, it.lon) }
                    forceInside = true; showRadar = true; showTest = false
                }
                TestButton("Apparition maintenant") { showTest = false; startEncounter(selected, test = true) }
            }
        }

        message?.let {
            Text(
                it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(16.dp)
                    .background(Color(0xCC5A0000), RoundedCornerShape(10.dp)).padding(10.dp)
                    .clickable { message = null }
            )
        }
    }
}

@Composable
private fun ZoneMap(fix: Fix?, zones: List<Zone>, selected: Zone?, onSelect: (Zone) -> Unit, modifier: Modifier) {
    var centered by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(50.64, 4.67))
            }
        },
        update = { map ->
            map.overlays.clear()
            val all = if (selected != null && selected.id == 0) zones + selected else zones
            for (z in all) {
                val circle = Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(z.lat, z.lon), z.radiusM)
                    fillPaint.color = if (z == selected) 0x55FF4040 else 0x337CFF8A
                    outlinePaint.color = if (z == selected) 0xFFFF4040.toInt() else 0xFF2E6B36.toInt()
                    outlinePaint.strokeWidth = 4f
                    setOnClickListener { _, _, _ -> onSelect(z); true }
                }
                map.overlays.add(circle)
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(z.lat, z.lon)
                    title = z.name
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> onSelect(z); true }
                })
            }
            fix?.let { f ->
                map.overlays.add(Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(f.lat, f.lon), max(f.accuracyM.toDouble(), 6.0))
                    fillPaint.color = 0x553D8BFF
                    outlinePaint.color = 0xFF3D8BFF.toInt()
                    outlinePaint.strokeWidth = 3f
                    title = "Toi"
                })
                if (!centered) {
                    map.controller.setCenter(GeoPoint(f.lat, f.lon))
                    centered = true
                }
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() }
    )
}

@Composable
private fun ZoneList(fix: Fix?, zones: List<Zone>, onSelect: (Zone) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 10.dp)
            .systemBarsPadding()
    ) {
        Text("ZONES LES PLUS PROCHES", color = DimGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        when {
            fix == null -> Mono("Recherche du signal GPS…", Color.Gray)
            zones.isEmpty() -> Mono("Aucune zone dans les 100 km. Utilise le menu TEST.", Color.Gray)
            else -> Column(Modifier.verticalScroll(rememberScrollState()).height(150.dp)) {
                zones.take(8).forEach { z ->
                    val d = distanceM(fix.lat, fix.lon, z.lat, z.lon)
                    Row(Modifier.fillMaxWidth().clickable { onSelect(z) }.padding(vertical = 7.dp)) {
                        Mono(z.name, Color.White, Modifier.weight(1f))
                        Mono(formatDistance(d), Green)
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarView(zone: Zone, fix: Fix?, heading: Float, dist: Double?, inside: Boolean, onBack: () -> Unit) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "a"
    )
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        0.2f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "b"
    )
    val bearing = fix?.let { bearingDeg(it.lat, it.lon, zone.lat, zone.lon) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        Mono(zone.name.uppercase(), Color.White, size = 18)
        Spacer(Modifier.height(4.dp))
        Mono(
            when {
                inside -> "SIGNAL DÉTECTÉ"
                dist == null -> "RECHERCHE GPS…"
                else -> formatDistance(dist)
            },
            if (inside) Red.copy(alpha = blink) else Green, size = 34
        )
        fix?.let { Mono("précision GPS ± ${it.accuracyM.roundToInt()} m", DimGreen, size = 12) }
        Spacer(Modifier.height(20.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 * 0.95f
            drawCircle(Color(0xFF031A07), r, c)
            for (k in 1..4) drawCircle(DimGreen, r * k / 4, c, style = Stroke(2f))
            drawLine(DimGreen, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
            drawLine(DimGreen, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
            rotate(sweep, c) {
                drawLine(Green.copy(alpha = 0.8f), c, Offset(c.x, c.y - r), 4f)
            }
            if (bearing != null && dist != null) {
                // Échelle logarithmique : 20 m au centre, 5 km au bord.
                val k = ((ln(max(dist, 20.0)) - ln(20.0)) / (ln(5000.0) - ln(20.0))).coerceIn(0.0, 1.0)
                val rel = Math.toRadians((bearing - heading).toDouble())
                val p = Offset(c.x + (sin(rel) * k * r).toFloat(), c.y - (cos(rel) * k * r).toFloat())
                drawCircle((if (inside) Red else Green).copy(alpha = blink), 18f, p)
            }
            drawCircle(Color.White, 7f, c)
        }
        Spacer(Modifier.height(16.dp))
        if (bearing != null && !inside) {
            Mono(directionHint(bearing - heading), Color.White, size = 16)
        }
        if (inside) Mono("Ne bouge plus…", Red, size = 16)
        Spacer(Modifier.weight(1f))
        Chip("◀ CARTE", Color.White, onBack)
    }
}

private fun directionHint(relDeg: Double): String {
    val a = ((relDeg % 360) + 540) % 360 - 180 // -180..180
    return when {
        kotlin.math.abs(a) < 15 -> "▲ Tout droit"
        kotlin.math.abs(a) > 150 -> "▼ Derrière toi"
        a > 0 -> "▶ Tourne à droite"
        else -> "◀ Tourne à gauche"
    }
}

private fun formatDistance(m: Double) = if (m < 1000) "${m.roundToInt()} m" else String.format("%.1f km", m / 1000)

@Composable
private fun Mono(text: String, color: Color, modifier: Modifier = Modifier, size: Int = 14) =
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = FontWeight.Bold, modifier = modifier)

@Composable
private fun Chip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(10.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun TestButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 14.sp,
        modifier = Modifier.background(Color(0xFF333333), RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}
