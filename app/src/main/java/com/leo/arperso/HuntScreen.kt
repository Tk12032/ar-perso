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
import androidx.compose.foundation.layout.width
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
    var emptyZoneId by remember { mutableStateOf<Int?>(null) } // zone déjà fouillée, vide cette heure-ci

    // Zones proches : dès le premier point GPS, puis quand on a bougé de 300 m, et toutes les 2 minutes
    // (pour voir les zones ajoutées depuis l'admin). Une seule boucle, qui n'est pas relancée à chaque
    // nouvelle position : sinon chaque point GPS annulait la requête en cours.
    LaunchedEffect(hasLocation) {
        var lastTime = 0L
        while (true) {
            val f = fix
            val last = lastFetch
            val due = last == null ||
                distanceM(last.lat, last.lon, f?.lat ?: last.lat, f?.lon ?: last.lon) >= 300 ||
                System.currentTimeMillis() - lastTime > 120_000
            if (f != null && due) {
                try {
                    zones = Api.nearbyZones(f.lat, f.lon)
                    lastFetch = f
                    lastTime = System.currentTimeMillis()
                    message = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Serveur injoignable : ${e.message}"
                }
            }
            delay(if (lastFetch == null) 3_000 else 10_000)
        }
    }

    fun startEncounter(zone: Zone?, test: Boolean, immediate: Boolean = false) {
        if (starting) return
        starting = true
        scope.launch {
            val f = fix
            val isTest = test || immediate || zone == null || zone.id == 0
            var hunt = try {
                Api.startHunt(player.token, zone?.id ?: 0, f?.lat, f?.lon, test = isTest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Hors ligne ou refus : on joue quand même, avec une ghoule au hasard (sans score).
                message = "Chasse hors ligne : ${e.message}"
                val c = listOf("pale" to 0xFF9FB59A.toInt(), "red" to 0xFF8A1C1C.toInt(), "black" to 0xFF1D2433.toInt())
                    .random()
                Hunt(-1, true, c.first, c.second, emptyList(), zone)
            }
            starting = false
            forceInside = false
            if (!hunt.present) {
                emptyZoneId = zone?.id
                message = "Aucune présence détectée dans « ${zone?.name} ». Reviens plus tard…"
                return@launch
            }
            if (hunt.lairLat == null && f != null && !immediate) {
                // Mode test : la créature se cache à 25-40 m du joueur, dans une direction au hasard.
                val (la, lo) = offset(f.lat, f.lon, 25.0 + Random.nextDouble(15.0), Random.nextDouble(360.0))
                hunt = hunt.copy(lairLat = la, lairLon = lo)
            }
            onEncounter(hunt.copy(immediate = immediate || hunt.lairLat == null))
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
                    "spawn_now" -> startEncounter(selected, test = true, immediate = true)
                }
            }
            delay(10_000)
        }
    }

    val zone = selected
    val dist = if (zone != null && fix != null) distanceM(fix!!.lat, fix!!.lon, zone.lat, zone.lon) else null
    val inside = zone != null && (forceInside || fix?.let { zone.contains(it.lat, it.lon) } == true)

    // Entrée dans la zone : signal, puis la rencontre se lance toute seule.
    LaunchedEffect(inside, zone?.id) {
        if (inside && (forceInside || zone?.id != emptyZoneId)) {
            delay(1500)
            startEncounter(selected, test = forceInside)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasLocation) {
            Hint("L'application a besoin de ta position pour trouver les zones.", Modifier.align(Alignment.Center))
        } else if (showRadar && zone != null) {
            RadarView(zone, fix, heading, dist, inside, onBack = { showRadar = false })
        } else {
            // Moitié haute : la carte et ses points. Moitié basse : les lieux les plus proches.
            Column(Modifier.fillMaxSize()) {
                ZoneMap(
                    fix = fix,
                    zones = zones,
                    selected = zone,
                    onSelect = { selected = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                ZoneList(
                    fix = fix,
                    zones = zones,
                    selected = zone,
                    onSelect = { selected = if (selected?.id == it.id) null else it },
                    onTrack = { selected = it; showRadar = true },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
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
                TestButton("Apparition maintenant") { showTest = false; startEncounter(selected, test = true, immediate = true) }
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
    val icons = remember { InkIcons() }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(50.64, 4.67))
                    // Teinte « vieux papier » : la carte ressemble à un relevé à l'encre plutôt qu'à un plan routier.
                    overlayManager.tilesOverlay.setColorFilter(parchmentFilter())
                    overlayManager.tilesOverlay.loadingBackgroundColor = 0xFFD9C9A3.toInt()
                    overlayManager.tilesOverlay.loadingLineColor = 0xFFC9B68C.toInt()
                }
            },
            update = { map ->
                map.overlays.clear()
                val all = if (selected != null && selected.id == 0) zones + selected else zones
                // Seul un point est montré : le contour de la zone reste un secret de l'admin.
                for (z in all) {
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(z.lat, z.lon)
                        icon = android.graphics.drawable.BitmapDrawable(
                            map.resources, if (selected?.id == z.id) icons.selected else icons.point
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setInfoWindow(null)
                        setOnMarkerClickListener { _, _ -> onSelect(z); true }
                    })
                }
                fix?.let { f ->
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(f.lat, f.lon)
                        icon = android.graphics.drawable.BitmapDrawable(map.resources, icons.player)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setInfoWindow(null)
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
        PaperGrain(Modifier.fillMaxSize())
    }
}

/** Matrice de couleur « parchemin » : niveaux de gris réchauffés, contraste adouci. */
private fun parchmentFilter(): android.graphics.ColorMatrixColorFilter {
    val m = android.graphics.ColorMatrix(
        floatArrayOf(
            0.36f, 0.62f, 0.12f, 0f, 22f,
            0.31f, 0.55f, 0.10f, 0f, 14f,
            0.22f, 0.40f, 0.08f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    return android.graphics.ColorMatrixColorFilter(m)
}

/** Grain de papier et bords assombris par-dessus la carte (ne bloque pas les gestes). */
@Composable
private fun PaperGrain(modifier: Modifier) {
    val specks = remember {
        val r = Random(7)
        List(900) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    Canvas(modifier) {
        for ((x, y, k) in specks) {
            drawCircle(
                Color(0xFF3B2A14).copy(alpha = 0.05f + 0.10f * k),
                radius = 0.6f + 1.6f * k,
                center = Offset(x * size.width, y * size.height)
            )
        }
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color.Transparent, Color(0x663B2A14)),
                center = Offset(size.width / 2, size.height / 2),
                radius = maxOf(size.width, size.height) * 0.75f
            )
        )
    }
}

/** Icônes « dessinées à l'encre » : croix rouge pour un lieu, rond griffonné pour le joueur. */
private class InkIcons {
    val point = cross(0xFF7A1010.toInt(), 54)
    val selected = cross(0xFFD01818.toInt(), 76, circled = true)
    val player = player()

    private fun ink(color: Int, width: Float) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    private fun cross(color: Int, size: Int, circled: Boolean = false): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = ink(color, size * 0.09f)
        val m = size * 0.28f
        // Traits légèrement de travers, comme tracés à la main.
        c.drawLine(m, m * 1.05f, size - m * 0.95f, size - m, p)
        c.drawLine(size - m, m * 0.9f, m * 1.1f, size - m * 1.05f, p)
        if (circled) {
            val q = ink(color, size * 0.05f)
            c.drawOval(size * 0.08f, size * 0.12f, size * 0.93f, size * 0.9f, q)
        }
        return bmp
    }

    private fun player(): android.graphics.Bitmap {
        val size = 56
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2A44.toInt() }
        c.drawCircle(size / 2f, size / 2f, size * 0.16f, fill)
        val p = ink(0xFF1B2A44.toInt(), 3f)
        c.drawArc(size * 0.12f, size * 0.1f, size * 0.9f, size * 0.88f, 20f, 320f, false, p)
        return bmp
    }
}

/** Moitié basse : les lieux les plus proches, avec un bout de leur légende et la distance à vol d'oiseau. */
@Composable
private fun ZoneList(
    fix: Fix?,
    zones: List<Zone>,
    selected: Zone?,
    onSelect: (Zone) -> Unit,
    onTrack: (Zone) -> Unit,
    modifier: Modifier,
) {
    val sorted = remember(zones, fix) {
        if (fix == null) zones else zones.sortedBy { distanceM(fix.lat, fix.lon, it.lat, it.lon) }
    }
    Column(modifier.background(Color(0xFF14110C)).padding(horizontal = 16.dp).padding(top = 12.dp)) {
        Text("LIEUX LES PLUS PROCHES", color = Color(0xFFB59A6A), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        when {
            fix == null -> Mono("Recherche du signal GPS…", Color.Gray)
            sorted.isEmpty() -> Mono("Aucun lieu dans les 100 km. Utilise le menu TEST.", Color.Gray)
            else -> Column(Modifier.verticalScroll(rememberScrollState()).systemBarsPadding()) {
                sorted.take(30).forEach { z ->
                    val open = selected?.id == z.id
                    val d = distanceM(fix.lat, fix.lon, z.lat, z.lon)
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (open) Color(0xFF2A2216) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelect(z) }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    z.name, color = Color(0xFFF1E6CF), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    z.legend ?: kindLabel(z.kind), color = Color(0xFF9C8B6E), fontSize = 13.sp,
                                    maxLines = if (open) 12 else 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(formatDistance(d), color = Color(0xFFE0B25A), fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        if (open) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "▶ SUIVRE AU RADAR", color = Color.White, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                modifier = Modifier.background(Color(0xFF7A1010), RoundedCornerShape(8.dp))
                                    .clickable { onTrack(z) }.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "cemetery" -> "Cimetière"
    "forest" -> "Bois"
    "ruin" -> "Ruine"
    "test" -> "Lieu de test"
    else -> "Lieu étrange"
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
        if (inside) Mono("Sors ton détecteur…", Red, size = 16)
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
