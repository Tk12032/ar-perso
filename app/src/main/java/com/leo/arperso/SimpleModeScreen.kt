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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

/** Hauteur supposée du téléphone au-dessus du sol (tenu à hauteur de poitrine), en mètres. */
private const val PHONE_HEIGHT_M = 1.3f

/** Distance à laquelle le renard apparaît au début, en mètres. */
private const val START_DISTANCE_M = 1.2f

/** Plus loin que ça, on refuse de poser le renard (le sol virtuel devient imprécis). */
private const val MAX_DISTANCE_M = 4f

/**
 * Mode « RA légère » pour les téléphones sans ARCore : la caméra filme en fond, le gyroscope
 * oriente une caméra 3D virtuelle, et le renard est posé sur un sol imaginaire situé
 * [PHONE_HEIGHT_M] sous le téléphone. Il reste en place quand on tourne le téléphone,
 * mais pas quand on se déplace.
 */
@Composable
fun SimpleModeScreen() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCamera = it }
    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize()) {
        if (hasCamera) {
            CameraPreview(Modifier.fillMaxSize())
            FoxScene()
        } else {
            Hint(
                "L'application a besoin de la caméra pour afficher le renard dans ta pièce.",
                Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                // SurfaceView : la scène 3D se place juste au-dessus (media overlay).
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }, ContextCompat.getMainExecutor(context))
            }
        }
    )
}

@Composable
private fun FoxScene() {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val childNodes = rememberNodes()

    // Champ de vision vertical de la caméra du téléphone, pour que le renard « colle » à l'image.
    val verticalFovDeg = remember { backCameraVerticalFovDegrees(context) }
    val screenSize = remember { mutableStateOf(1 to 1) }

    // Orientation du téléphone : matrice 3x3 (repère SceneView : X droite, Y haut, -Z devant).
    val orientation = remember { FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f } }
    var hasOrientation by remember { mutableStateOf(false) }

    var foxRoot by remember { mutableStateOf<Node?>(null) }
    var foxModel by remember { mutableStateOf<ModelNode?>(null) }
    var animationIndex by remember { mutableStateOf(0) }
    var tooFar by remember { mutableStateOf(false) }

    fun placeFox(x: Float, z: Float) {
        val root = foxRoot ?: Node(engine).also { node ->
            val model = ModelNode(
                modelInstance = modelLoader.createModelInstance(FOX_MODEL),
                autoAnimate = true,
                scaleToUnits = CHARACTER_SIZE_M
            )
            node.addChildNode(model)
            childNodes += node
            foxRoot = node
            foxModel = model
            animationIndex = 0
        }
        root.position = Position(x, -PHONE_HEIGHT_M, z)
        // Le renard se tourne vers le téléphone (situé en 0,0,0).
        val yawDeg = Math.toDegrees(atan2(-x, -z).toDouble()).toFloat()
        root.rotation = Rotation(0f, yawDeg, 0f)
    }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val r = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                // Repère capteur (X est, Y nord, Z haut) -> repère 3D (X, Y haut, Z vers le sud).
                for (col in 0..2) {
                    orientation[col] = r[col]           // ligne X
                    orientation[3 + col] = r[6 + col]   // ligne Y = Z capteur
                    orientation[6 + col] = -r[3 + col]  // ligne Z = -Y capteur
                }
                cameraNode.worldTransform = Mat4(
                    Float4(orientation[0], orientation[3], orientation[6], 0f),
                    Float4(orientation[1], orientation[4], orientation[7], 0f),
                    Float4(orientation[2], orientation[5], orientation[8], 0f),
                    Float4(0f, 0f, 0f, 1f)
                )
                if (!hasOrientation) {
                    hasOrientation = true
                    // Premier placement : devant le téléphone, dans la direction où il regarde.
                    val fx = -orientation[2]
                    val fz = -orientation[8]
                    val len = sqrt(fx * fx + fz * fz).takeIf { it > 0.01f } ?: 1f
                    if (foxRoot == null) placeFox(fx / len * START_DISTANCE_M, fz / len * START_DISTANCE_M)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = null,
            childNodes = childNodes,
            isOpaque = false,
            onViewCreated = {
                // Au-dessus de l'aperçu caméra, mais sous les textes de l'interface.
                setZOrderOnTop(false)
                setZOrderMediaOverlay(true)
                // Objectif 35 mm équivalent (pellicule de 24 mm de haut) qui donne le même champ vertical.
                cameraNode.focalLength = 12.0 / tan(Math.toRadians(verticalFovDeg / 2.0))
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    screenSize.value = v.width to v.height
                }
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, node ->
                    val model = foxModel
                    if (node != null && model != null) {
                        model.stopAnimation(animationIndex)
                        animationIndex = (animationIndex + 1) % model.animationCount.coerceAtLeast(1)
                        model.playAnimation(animationIndex)
                        return@rememberOnGestureListener
                    }
                    // Rayon depuis le doigt jusqu'au sol imaginaire.
                    val (w, h) = screenSize.value
                    val tanV = tan(Math.toRadians(verticalFovDeg / 2.0)).toFloat()
                    val tanH = tanV * w / h
                    val dx = (2f * event.x / w - 1f) * tanH
                    val dy = (1f - 2f * event.y / h) * tanV
                    val dz = -1f
                    val wy = orientation[3] * dx + orientation[4] * dy + orientation[5] * dz
                    if (wy >= -0.05f) { tooFar = true; return@rememberOnGestureListener }
                    val wx = orientation[0] * dx + orientation[1] * dy + orientation[2] * dz
                    val wz = orientation[6] * dx + orientation[7] * dy + orientation[8] * dz
                    val t = -PHONE_HEIGHT_M / wy
                    val x = wx * t
                    val z = wz * t
                    if (sqrt(x * x + z * z) > MAX_DISTANCE_M) { tooFar = true; return@rememberOnGestureListener }
                    tooFar = false
                    placeFox(x, z)
                }
            )
        )

        val message = when {
            !hasOrientation -> "Démarrage du gyroscope…"
            tooFar -> "Trop loin ! Touche le sol un peu plus près de toi."
            else -> "Mode simple (sans ARCore) : le renard ${ANIMATIONS.getOrNull(animationIndex)?.second ?: "bouge"}.\n" +
                "Touche-le pour changer d'animation, ou touche le sol pour le déplacer. " +
                "Tourne-toi sans marcher pour qu'il reste bien en place."
        }
        Hint(message, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Champ de vision vertical (en mode portrait) de la caméra arrière, en degrés.
 * En portrait, la hauteur de l'écran correspond à la grande largeur du capteur.
 */
private fun backCameraVerticalFovDegrees(context: Context): Double = runCatching {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = manager.cameraIdList.first {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }
    val chars = manager.getCameraCharacteristics(id)
    val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
    val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
    val longSide = maxOf(sensor.width, sensor.height)
    Math.toDegrees(2.0 * atan(longSide / (2.0 * focal)))
}.getOrDefault(65.0).coerceIn(40.0, 100.0)
