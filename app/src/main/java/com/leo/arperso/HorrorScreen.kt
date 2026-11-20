package com.leo.arperso

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.filament.ColorGrading
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun HorrorScreen(hunt: Hunt, onExit: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            HorrorContent(hunt, onExit)
        } else {
            Hint("L'application a besoin de la caméra.", Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun HorrorContent(hunt: Hunt, onExit: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val childNodes = rememberNodes()

    // Un seul outil en main à la fois : rien (caméra normale), le caméscope, ou le détecteur EMF.
    var tool by remember { mutableStateOf(Tool.NONE) }
    val footage = tool == Tool.CAMCORDER
    var cameraView by remember { mutableStateOf<FootageCameraView?>(null) }
    val verticalFovDeg = remember { backCameraVerticalFovDegrees(context) }

    // Étalonnage du sol : demandé au premier lancement, refaisable avec le bouton ⚙.
    var calibration by remember { mutableStateOf(Calibration.load(context)) }
    var calibStep by remember { mutableStateOf(if (calibration.done) CalibStep.NONE else CalibStep.AIM) }
    var markerPos by remember { mutableStateOf(0f to -Calibration.AIM_DISTANCE_M) }

    val audio = remember { HorrorAudio() }
    val director = remember { GhoulDirector(audio, vibrate = { vibrate(context) }, verticalFovDeg) }
    val ghoul = remember {
        ModelNode(modelInstance = modelLoader.createModelInstance(GHOUL_MODEL), autoAnimate = false).also {
            it.isVisible = false
            tintSkin(it, hunt.color)
            childNodes += it
        }
    }
    val shadow = remember {
        ModelNode(modelInstance = modelLoader.createModelInstance(SHADOW_MODEL), autoAnimate = false).also {
            it.isVisible = false
            childNodes += it
        }
    }
    val marker = remember {
        ModelNode(modelInstance = modelLoader.createModelInstance(MARKER_MODEL), autoAnimate = false).also {
            it.isVisible = false
            childNodes += it
        }
    }
    val calibrating = calibStep != CalibStep.NONE

    // La créature a une vraie position dans sa zone (repère local en mètres autour de la zone).
    // - Poursuite : le joueur marche, on suit ses pas ; la créature n'est pas affichée, l'EMF la situe.
    // - Rencontre : à 10 m ou moins et immobile, elle se manifeste autour de lui, dans sa vraie direction.
    //   Repartir en marchant relance la poursuite. Fins : attrapé (le cri) ou échappé (trop loin / hors zone).
    val fix by rememberLocation(true)
    val heading by rememberHeading(fix)
    val zone = hunt.zone
    val frame = remember { LocalFrame(zone?.lat ?: hunt.lairLat ?: 0.0, zone?.lon ?: hunt.lairLon ?: 0.0) }
    val tracker = remember { PlayerTracker(context, frame, calibration.phoneHeight) }
    val stepPermission = remember {
        android.os.Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }
    DisposableEffect(Unit) {
        tracker.start(stepPermission)
        onDispose { tracker.stop() }
    }
    LaunchedEffect(fix) { fix?.let { tracker.onFix(it) } }
    LaunchedEffect(heading) { tracker.heading = heading }
    val inZone: (Vec) -> Boolean = remember {
        { v -> zone?.let { z -> frame.toLatLon(v).let { (la, lo) -> z.contains(la, lo) } } ?: true }
    }
    var sim by remember { mutableStateOf<CreatureSim?>(null) }
    var encounter by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) } // null = en cours, "escaped" = sorti de la zone
    var catches by remember { mutableStateOf(0) } // nombre de fois où elle t'a attrapé
    var debug by remember { mutableStateOf(false) }
    var debugText by remember { mutableStateOf("") }
    var emfLevel by remember { mutableStateOf(0) }
    val searching = !encounter
    LaunchedEffect(Unit) {
        director.armed = false
        director.externalMode = true
        var encounterAnchor: Vec? = null
        var yawOffset = 0.0
        var stepsAtEncounter = 0
        while (outcome == null) {
            delay(100)
            val p = tracker.position ?: continue
            // Création de la créature au premier point connu du joueur.
            val c = sim ?: run {
                val start = if (hunt.immediate || hunt.lairLat == null || hunt.lairLon == null) {
                    val h = Math.toRadians(heading.toDouble())
                    p + Vec(kotlin.math.sin(h), kotlin.math.cos(h)) * 5.0
                } else frame.toXY(hunt.lairLat, hunt.lairLon)
                CreatureSim(start, hunt.behavior, inZone).also {
                    // Jamais sur le joueur : au moins 20 m (ou le plus loin possible dans une petite zone)…
                    if (!hunt.immediate && (it.position - p).length < 20.0) {
                        it.relocateFar(p, 20.0, zone?.radiusM ?: 40.0)
                    }
                    // … et elle ne l'a pas encore senti pendant les 20 premières secondes.
                    if (!hunt.immediate) it.calm(20.0)
                    sim = it
                }
            }
            val here = encounterAnchor ?: p // pendant la rencontre, on fige la position du joueur (il est immobile)
            if (c.update(0.1, here, heading, director.watchedNow)) {
                director.glitchNow()
            }
            val rel = c.position - here
            val d = rel.length

            // Passage poursuite <-> rencontre
            if (!encounter && d <= 10.0 && (hunt.immediate || !tracker.isMoving())) {
                encounter = true
                encounterAnchor = p
                stepsAtEncounter = tracker.steps
                yawOffset = director.localYaw() - Math.toRadians(heading.toDouble())
                director.armed = true
                audio.glitch()
            } else if (encounter && tracker.steps - stepsAtEncounter >= 4) {
                encounter = false
                encounterAnchor = null
                director.armed = false
            }
            if (encounter) {
                val a = yawOffset + Math.toRadians(rel.bearing)
                director.setTarget(
                    (kotlin.math.sin(a) * d).toFloat(), (-kotlin.math.cos(a) * d).toFloat(), c.speed > 0.05
                )
            }

            // Attrapé : le cri, puis elle disparaît et réapparaît loin, avec un répit. Pas de game over.
            if (director.caught) {
                catches++
                c.relocateFar(p, 25.0, zone?.radiusM ?: 40.0)
                c.calm(15.0)
                encounter = false
                encounterAnchor = null
                director.armed = false
                director.resetAfterCatch()
            }
            // Fin de mission : on quitte la zone (ou on la distance beaucoup).
            if (d > 35.0 || (!inZone(p) && d > 15.0)) outcome = "escaped"

            // Détecteur EMF
            var v: Float
            var five = false
            if (encounter) {
                val t = director.target()
                if (t != null) {
                    val relDeg = Math.toDegrees(t.second.toDouble())
                    v = emfValue(t.first.toDouble(), relDeg, 8.0)
                    five = t.first < 2.5f && kotlin.math.abs(relDeg) < 35 && "emf5" in hunt.evidences
                } else v = 0f
            } else {
                val reach = maxOf((zone?.radiusM ?: 30.0) * 2, 40.0)
                v = emfValue(d, angleDiff(heading.toDouble(), rel.bearing), reach)
            }
            emfLevel = if (five) 5 else kotlin.math.ceil(v * 4f).toInt().coerceIn(0, 4)
            audio.beepInterval = when {
                tool != Tool.EMF || calibrating -> 0f
                five -> 0.06f
                v < 0.05f -> 2.5f // tic lent : le détecteur est allumé mais ne capte rien
                else -> 1.4f - 1.3f * Math.pow(v.toDouble(), 0.8).toFloat()
            }
            audio.beepPitch = if (five) 3000f else 1300f + 1300f * v

            if (debug) {
                debugText = buildString {
                    append(if (encounter) "RENCONTRE" else "POURSUITE")
                    append(" · attrapé ×$catches")
                    append(String.format(" · créature à %.1f m · %s · %.1f m/s\n", d, c.activity, c.speed))
                    append(String.format("joueur : %d pas (%s, %.2f m/pas) · %.1f m/s · %s\n",
                        tracker.steps, if (tracker.usingStepSensor) "podomètre" else "accéléromètre",
                        tracker.stepLength, tracker.speed(), if (tracker.isMoving()) "marche" else "immobile"))
                    append(String.format("GPS ± %.0f m · dans la zone : %s · cap %.0f°",
                        tracker.gpsAccuracy, if (inZone(p)) "oui" else "non", heading))
                }
            }
        }
        audio.beepInterval = 0f
        audio.heartbeatInterval = 0f
    }

    // Son : démarré une fois, coupé quand l'appli passe en arrière-plan.
    DisposableEffect(Unit) {
        audio.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> audio.muted = true
                Lifecycle.Event.ON_RESUME -> audio.muted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            audio.release()
        }
    }

    // Gyroscope : oriente la caméra 3D comme le téléphone.
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val r = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                director.setSensorRotation(r)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(listener) }
    }

    LaunchedEffect(footage, calibrating, cameraView) {
        director.footage = footage && !calibrating
        director.paused = calibrating
        audio.staticOn = footage && !calibrating
        cameraView?.footageMode = footage || calibrating
        cameraView?.clean = calibrating
    }
    LaunchedEffect(calibration) {
        director.phoneHeight = calibration.phoneHeight
        director.setFov(verticalFovDeg * calibration.fovScale)
    }

    Box(Modifier.fillMaxSize()) {
        // 1) Scène 3D (surface transparente, au-dessus de l'image caméra « found footage »)
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = null,
            childNodes = childNodes,
            isOpaque = false,
            onViewCreated = {
                setZOrderOnTop(false)
                setZOrderMediaOverlay(true)
                cameraNode.focalLength = focalLengthFor(verticalFovDeg * calibration.fovScale)
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    if (v.height > 0) director.aspect = v.width.toFloat() / v.height
                }
                // Rendu basse définition et quasi noir et blanc, pour coller à l'image VHS.
                view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
                    enabled = true
                    minScale = 0.4f
                    maxScale = 0.4f
                    quality = com.google.android.filament.View.QualityLevel.LOW
                }
                view.colorGrading = ColorGrading.Builder()
                    .saturation(0.15f)
                    .contrast(1.3f)
                    .exposure(-0.4f)
                    .build(engine)
            },
            onFrame = { nanos ->
                val focal = focalLengthFor(verticalFovDeg * calibration.fovScale)
                if (abs(cameraNode.focalLength - focal) > 1e-3) cameraNode.focalLength = focal
                director.update(nanos, ghoul, shadow, cameraNode)
                cameraView?.glitch = director.glitch
                marker.isVisible = calibStep == CalibStep.ADJUST
                marker.position = Position(markerPos.first, -calibration.phoneHeight, markerPos.second)
            }
        )

        // 2) Caméra : aperçu normal, ou image dégradée en mode found footage
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FootageCameraView(ctx, lifecycleOwner).also {
                    it.footageMode = footage
                    cameraView = it
                }
            },
            onRelease = { it.release() }
        )

        // 3) Interface
        if (calibrating) {
            CalibrationOverlay(
                step = calibStep,
                calibration = calibration,
                onAim = {
                    val aim = director.aim()
                    if (aim != null) {
                        val (fx, fz, down) = aim
                        val d = Calibration.AIM_DISTANCE_M
                        val h = (d * tan(down)).coerceIn(Calibration.MIN_HEIGHT, Calibration.MAX_HEIGHT)
                        markerPos = fx * d to fz * d
                        calibration = calibration.copy(phoneHeight = h)
                        calibStep = CalibStep.ADJUST
                    }
                },
                onChange = { calibration = it },
                onRestart = { calibStep = CalibStep.AIM },
                onDone = {
                    calibration = calibration.copy(done = true)
                    Calibration.save(context, calibration)
                    calibStep = CalibStep.NONE
                },
            )
        } else {
        when (tool) {
            Tool.CAMCORDER -> FootageOverlay(glitch = { director.glitch })
            Tool.EMF -> EmfScreen(level = emfLevel, searching = searching)
            Tool.NONE -> Hint(
                if (searching) "Une présence se cache dans la zone.\nSors le détecteur EMF pour la localiser."
                else "Elle est là, tout près…\nTu ne peux la voir qu'à travers le caméscope.",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
            )
        }
        ToolBar(
            current = tool,
            onSelect = { tool = if (tool == it) Tool.NONE else it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopChip("DBG") { debug = !debug }
            TopChip("✕ QUITTER", onExit)
            TopChip("⚙") { calibStep = CalibStep.AIM }
        }
        if (debug) {
            Text(
                debugText, color = Color(0xFF7CFF8A), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 96.dp, start = 12.dp, end = 12.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }
        if (catches > 0 && outcome == null) {
            Text(
                "BANDE ENDOMMAGÉE ×$catches", color = Color(0xFFFF6B6B), fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        outcome?.let { OutcomeScreen(catches, onExit) }
        }
    }
}

/**
 * Le comportement de la ghoule :
 * - elle avance vers toi quand tu ne la regardes pas (mode normal, ou hors du cadre) ;
 * - filmée, elle se fige et a des tics… mais de temps en temps elle « saute » plus près dans un glitch ;
 * - trop près et filmée : hurlement, vibration, secousse, puis elle disparaît et revient plus tard, derrière toi.
 */
private class GhoulDirector(
    private val audio: HorrorAudio,
    private val vibrate: () -> Unit,
    verticalFovDeg: Double,
) {
    private enum class State { LURK, STALK, WATCHED, SCREAM, GONE }

    var footage = false
    /** false pendant la phase de recherche : la créature n'est pas encore là. */
    @Volatile var armed = true
    @Volatile private var pendingSpawn: Pair<Float, Float>? = null

    // Mode « externe » : la position vient de la simulation de la créature (CreatureSim), pas d'ici.
    @Volatile var externalMode = false
    @Volatile private var targetX = 0f
    @Volatile private var targetZ = -5f
    @Volatile private var targetMoving = false
    @Volatile private var snapToTarget = true
    @Volatile private var glitchRequested = false
    /** Filmée en ce moment (visible dans le cadre du caméscope). */
    @Volatile var watchedNow = false
        private set
    /** Le cri a eu lieu : le joueur s'est fait attraper. */
    @Volatile var caught = false
        private set

    /** Après le cri : prête pour la prochaine rencontre. */
    fun resetAfterCatch() {
        caught = false
        snapToTarget = true
        go(State.LURK)
    }

    fun setTarget(x: Float, z: Float, moving: Boolean) {
        targetX = x; targetZ = z; targetMoving = moving
    }

    /** Saut dans un glitch (la simulation l'a rapprochée d'un coup). */
    fun glitchNow() { glitchRequested = true }

    /** Angle de la visée dans le repère 3D (0 = -Z, positif vers la droite), en radians. */
    fun localYaw(): Double {
        val (fx, fz) = forward()
        return atan2(fx.toDouble(), -fz.toDouble())
    }

    /** Fait apparaître la créature dans la direction [relRad] (par rapport à la visée), à [dist] mètres. */
    fun spawnAt(relRad: Float, dist: Float) {
        pendingSpawn = relRad to dist
        armed = true
    }

    /** Distance et angle (radians, positif à droite) de la créature par rapport à la visée, ou null. */
    fun target(): Pair<Float, Float>? {
        if (!armed || !spawned || state == State.GONE || state == State.SCREAM) return null
        val (fx, fz) = forward()
        val d = hypot(x, z)
        if (d < 0.01f) return null
        val a = atan2(x * (-fz) - z * fx, x * fx + z * fz) // angle signé de la visée vers la créature
        return d to a
    }
    var paused = false
    var phoneHeight = PHONE_HEIGHT_M
    var aspect = 9f / 19.5f
    @Volatile var glitch = 0f
        private set

    private var tanV = tan(Math.toRadians(verticalFovDeg / 2.0)).toFloat()

    fun setFov(verticalDeg: Double) {
        tanV = tan(Math.toRadians(verticalDeg / 2.0)).toFloat()
    }

    /**
     * Où vise le téléphone : direction horizontale (x, z) et angle sous l'horizon (radians),
     * ou null si le téléphone ne vise pas le sol.
     */
    fun aim(): Triple<Float, Float, Float>? {
        if (!hasOrientation) return null
        val down = kotlin.math.asin(m[5].coerceIn(-1f, 1f))
        if (down < 0.12f) return null
        val (fx, fz) = forward()
        return Triple(fx, fz, down)
    }
    private val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // orientation caméra, ligne par ligne
    private var hasOrientation = false
    private var spawned = false

    private var x = 0f
    private var z = -5f
    private var state = State.STALK
    private var stateTime = 0f
    private var nextStep = 3f
    private var shake = 0f
    private var lastNanos = 0L
    private var currentAnim: String? = null

    fun setSensorRotation(r: FloatArray) {
        // Repère capteur (X est, Y nord, Z haut) -> repère 3D (X, Y haut, Z vers le sud).
        for (c in 0..2) {
            m[c] = r[c]
            m[3 + c] = r[6 + c]
            m[6 + c] = -r[3 + c]
        }
        hasOrientation = true
    }

    fun update(nanos: Long, ghoul: ModelNode, shadow: ModelNode, camera: CameraNode) {
        val dt = if (lastNanos == 0L) 0f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        if (!hasOrientation) return
        if (paused || !armed) {
            ghoul.isVisible = false
            shadow.isVisible = false
            watchedNow = false
            snapToTarget = true
            audio.heartbeatInterval = 0f
            placeCamera(camera)
            return
        }
        if (externalMode) {
            updateExternal(dt, ghoul, shadow, camera)
            return
        }
        pendingSpawn?.let { (rel, d) ->
            val (fx, fz) = forward()
            val a = atan2(fx, -fz) + rel
            x = sin(a) * d
            z = -cos(a) * d
            go(State.LURK)
            spawned = true
            pendingSpawn = null
        }
        if (!spawned) {
            spawnBehind()
            spawned = true
        }
        stateTime += dt
        glitch = (glitch - dt * 1.4f).coerceAtLeast(0f)

        val dist = hypot(x, z)
        val visible = footage && state != State.GONE && inView(x, -phoneHeight + 1.25f, z)

        when (state) {
            State.LURK -> {
                // Tapie, immobile : elle attend qu'on la trouve (ou s'impatiente au bout de 30 s).
                play(ghoul, "Idle")
                if (visible) go(State.WATCHED) else if (stateTime > 30f) go(State.STALK)
            }
            State.STALK -> {
                if (visible) {
                    go(State.WATCHED)
                } else if (dist > 0.8f) {
                    val speed = 0.22f
                    x -= x / dist * speed * dt
                    z -= z / dist * speed * dt
                    play(ghoul, "Walk")
                } else {
                    play(ghoul, "Idle")
                }
            }
            State.WATCHED -> {
                play(ghoul, "Idle")
                if (!visible) {
                    go(State.STALK)
                } else if (dist < 1.3f && stateTime > 0.5f) {
                    jumpscare(ghoul)
                } else if (stateTime > nextStep) {
                    // Saut dans un glitch : elle se rapproche d'un coup.
                    val step = minOf(0.8f, dist - 0.7f).coerceAtLeast(0f)
                    x -= x / dist * step
                    z -= z / dist * step
                    glitch = 0.8f
                    audio.glitch()
                    stateTime = 0f
                    nextStep = 1.6f + Random.nextFloat() * 2f
                }
            }
            State.SCREAM -> if (stateTime > 1.7f) {
                glitch = 1f
                go(State.GONE)
            }
            State.GONE -> if (stateTime > 3f) {
                spawnBehind()
                go(State.STALK)
            }
        }

        ghoul.isVisible = footage && state != State.GONE
        // Pendant le hurlement, on l'abaisse pour que son visage soit pile dans l'objectif.
        ghoul.position = Position(x, if (state == State.SCREAM) -1.72f else -phoneHeight, z)
        ghoul.rotation = Rotation(0f, Math.toDegrees(atan2(-x, -z).toDouble()).toFloat(), 0f)
        // Ombre et brume au sol : c'est ce qui donne l'impression qu'elle est posée.
        shadow.isVisible = ghoul.isVisible && state != State.SCREAM
        shadow.position = Position(x, -phoneHeight, z)

        shake = (shake - dt).coerceAtLeast(0f)
        placeCamera(camera)


        // Le cœur s'emballe quand elle approche (même sans la voir).
        if (state == State.GONE || dist > 3.5f) {
            audio.heartbeatInterval = 0f
        } else {
            val k = ((dist - 0.6f) / 2.9f).coerceIn(0f, 1f)
            audio.heartbeatInterval = 0.38f + k * 0.9f
            audio.heartbeatVolume = 0.25f + 0.5f * (1f - k)
        }
    }

    private fun updateExternal(dt: Float, ghoul: ModelNode, shadow: ModelNode, camera: CameraNode) {
        stateTime += dt
        glitch = (glitch - dt * 1.4f).coerceAtLeast(0f)
        if (state == State.SCREAM) {
            if (stateTime > 1.7f) { glitch = 1f; caught = true; go(State.GONE) }
        } else if (state != State.GONE) {
            // On rejoint en douceur la position donnée par la simulation (mise à jour 10 fois par seconde).
            if (snapToTarget || glitchRequested) {
                x = targetX; z = targetZ; snapToTarget = false
                if (glitchRequested) { glitch = 0.8f; audio.glitch(); glitchRequested = false }
            } else {
                val k = (dt * 6f).coerceAtMost(1f)
                x += (targetX - x) * k
                z += (targetZ - z) * k
            }
            play(ghoul, if (targetMoving) "Walk" else "Idle")
            if (hypot(x, z) < 1.3f) jumpscare(ghoul)
        }
        val dist = hypot(x, z)
        watchedNow = footage && state != State.GONE && state != State.SCREAM && inView(x, -phoneHeight + 1.25f, z)

        ghoul.isVisible = footage && state != State.GONE
        ghoul.position = Position(x, if (state == State.SCREAM) -1.72f else -phoneHeight, z)
        ghoul.rotation = Rotation(0f, Math.toDegrees(atan2(-x, -z).toDouble()).toFloat(), 0f)
        shadow.isVisible = ghoul.isVisible && state != State.SCREAM
        shadow.position = Position(x, -phoneHeight, z)
        shake = (shake - dt).coerceAtLeast(0f)
        placeCamera(camera)
        if (state == State.GONE || dist > 4.5f) {
            audio.heartbeatInterval = 0f
        } else {
            val k = ((dist - 0.6f) / 3.9f).coerceIn(0f, 1f)
            audio.heartbeatInterval = 0.38f + k * 0.9f
            audio.heartbeatVolume = 0.25f + 0.5f * (1f - k)
        }
    }

    private fun placeCamera(camera: CameraNode) {
        val j = 0.05f * shake
        fun jitter() = if (j > 0f) (Random.nextFloat() * 2f - 1f) * j else 0f
        camera.worldTransform = Mat4(
            Float4(m[0], m[3], m[6], 0f),
            Float4(m[1], m[4], m[7], 0f),
            Float4(m[2], m[5], m[8], 0f),
            Float4(jitter(), jitter(), jitter(), 1f)
        )
    }

    private fun go(s: State) {
        state = s
        stateTime = 0f
        if (s == State.WATCHED) nextStep = 1.5f + Random.nextFloat() * 2f
    }

    private fun play(ghoul: ModelNode, name: String, loop: Boolean = true) {
        if (currentAnim == name) return
        currentAnim?.let { ghoul.stopAnimation(it) }
        ghoul.playAnimation(name, 1f, loop)
        currentAnim = name
    }

    private fun jumpscare(ghoul: ModelNode) {
        // Elle surgit juste devant l'objectif.
        val (fx, fz) = forward()
        x = fx * 0.75f
        z = fz * 0.75f
        play(ghoul, "Scream", loop = false)
        audio.scream()
        vibrate()
        shake = 1.5f
        glitch = 1f
        go(State.SCREAM)
    }

    /** Réapparaît loin, dans ton dos (un peu de côté). */
    private fun spawnBehind() {
        val (fx, fz) = forward()
        val a = atan2(-fz, -fx) + (Random.nextFloat() - 0.5f) * 1.6f
        val d = 4.5f + Random.nextFloat() * 1.5f
        x = cos(a) * d
        z = sin(a) * d
    }

    private fun forward(): Pair<Float, Float> {
        val fx = -m[2]
        val fz = -m[8]
        val len = hypot(fx, fz)
        return if (len < 0.01f) 0f to -1f else (fx / len) to (fz / len)
    }

    private fun inView(px: Float, py: Float, pz: Float): Boolean {
        val cx = m[0] * px + m[3] * py + m[6] * pz
        val cy = m[1] * px + m[4] * py + m[7] * pz
        val cz = m[2] * px + m[5] * py + m[8] * pz
        if (cz > -0.2f) return false
        val tanH = tanV * aspect
        return abs(cx) < -cz * tanH * 0.95f && abs(cy) < -cz * tanV * 0.95f
    }
}

/**
 * Force du signal EMF entre 0 et 1 : plus on est près et plus on pointe vers la créature, plus c'est fort.
 * [relDeg] = angle entre la direction visée et la créature ; [reach] = distance où le signal s'éteint.
 */
private fun emfValue(dist: Double, relDeg: Double, reach: Double): Float {
    val proximity = (1.0 - dist / reach).coerceIn(0.0, 1.0)
    val align = cos(Math.toRadians(relDeg)).coerceAtLeast(0.0)
    return (proximity * (0.3 + 0.7 * align * align)).toFloat()
}

/** Fin de mission : le joueur a quitté la zone. */
@Composable
private fun OutcomeScreen(catches: Int, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000))) {
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "TU AS QUITTÉ LA ZONE", color = Color(0xFF7CFF8A),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp
            )
            Spacer(Modifier.size(12.dp))
            Text(
                when (catches) {
                    0 -> "Elle ne t'a pas touché. Elle reste dans sa zone… pour l'instant."
                    1 -> "Elle t'a attrapé une fois. La bande en garde la trace."
                    else -> "Elle t'a attrapé $catches fois. La bande en garde la trace."
                },
                color = Color(0xFFBBBBBB), fontSize = 15.sp
            )
            Spacer(Modifier.size(28.dp))
            TopChip("◀ RETOUR À LA CARTE", onExit)
        }
    }
}

/** Teinte la peau de la ghoule (chaque créature a sa couleur). */
private fun tintSkin(node: ModelNode, color: Int) {
    fun lin(c: Int) = Math.pow(c / 255.0, 2.2).toFloat()
    val r = lin(android.graphics.Color.red(color))
    val g = lin(android.graphics.Color.green(color))
    val b = lin(android.graphics.Color.blue(color))
    runCatching {
        node.modelInstance.materialInstances.forEach { mi ->
            when (mi.name) {
                "peau" -> mi.setParameter("baseColorFactor", r, g, b, 1f)
                "peau_sombre" -> mi.setParameter("baseColorFactor", r * 0.45f, g * 0.45f, b * 0.45f, 1f)
            }
        }
    }
}

/** Focale « 35 mm » (capteur de 24 mm de haut) correspondant à un champ de vision vertical. */
private fun focalLengthFor(verticalFovDeg: Double): Double = 12.0 / tan(Math.toRadians(verticalFovDeg / 2.0))

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 80, 700), -1))
}

/** Champ de vision vertical (portrait) de la caméra arrière, en degrés. */
private fun backCameraVerticalFovDegrees(context: Context): Double = runCatching {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = manager.cameraIdList.first {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }
    val chars = manager.getCameraCharacteristics(id)
    val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
    val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
    Math.toDegrees(2.0 * atan(maxOf(sensor.width, sensor.height) / (2.0 * focal)))
}.getOrDefault(65.0).coerceIn(40.0, 100.0)
