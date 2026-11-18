package com.leo.arperso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArModeScreen(onArFailed: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes = rememberNodes()

    var frame by remember { mutableStateOf<Frame?>(null) }
    var planeDetected by remember { mutableStateOf(false) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // Un seul personnage : on garde son ancre et son modèle pour le déplacer.
    var anchorNode by remember { mutableStateOf<AnchorNode?>(null) }
    var modelNode by remember { mutableStateOf<ModelNode?>(null) }
    var animationIndex by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            childNodes = childNodes,
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
                    else Config.DepthMode.DISABLED
            },
            onSessionFailed = { onArFailed() },
            onTrackingFailureChanged = { trackingFailure = it },
            onSessionUpdated = { session, updatedFrame ->
                frame = updatedFrame
                if (!planeDetected) {
                    planeDetected = session.getAllTrackables(Plane::class.java).any {
                        it.trackingState == com.google.ar.core.TrackingState.TRACKING
                    }
                }
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, node ->
                    val model = modelNode
                    if (node != null && model != null) {
                        // Toucher le personnage : animation suivante.
                        model.stopAnimation(animationIndex)
                        animationIndex = (animationIndex + 1) % model.animationCount.coerceAtLeast(1)
                        model.playAnimation(animationIndex)
                        return@rememberOnGestureListener
                    }

                    val currentFrame = frame ?: return@rememberOnGestureListener
                    val hit = currentFrame.hitTest(event.x, event.y).firstOrNull {
                        it.isValid(depthPoint = false, point = false)
                    } ?: return@rememberOnGestureListener

                    // Le personnage est tourné vers le téléphone.
                    val camPose = currentFrame.camera.pose
                    val hitPose = hit.hitPose
                    val yaw = atan2(camPose.tx() - hitPose.tx(), camPose.tz() - hitPose.tz())
                    val pose = Pose(
                        floatArrayOf(hitPose.tx(), hitPose.ty(), hitPose.tz()),
                        floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
                    )
                    val anchor = runCatching { hit.trackable.createAnchor(pose) }.getOrNull()
                        ?: hit.createAnchorOrNull()
                        ?: return@rememberOnGestureListener

                    val existing = anchorNode
                    if (existing != null) {
                        // Déjà posé : on le déplace.
                        existing.anchor.detach()
                        existing.anchor = anchor
                    } else {
                        val newModel = ModelNode(
                            modelInstance = modelLoader.createModelInstance(FOX_MODEL),
                            autoAnimate = true,
                            scaleToUnits = CHARACTER_SIZE_M
                        ).apply { isEditable = false }
                        val newAnchorNode = AnchorNode(engine, anchor).apply {
                            isEditable = false
                            addChildNode(newModel)
                        }
                        childNodes += newAnchorNode
                        anchorNode = newAnchorNode
                        modelNode = newModel
                        animationIndex = 0
                    }
                }
            )
        )

        val message = when {
            trackingFailure != null -> trackingMessage(trackingFailure!!)
            !planeDetected -> "Bouge doucement ton téléphone en visant le sol ou une table…"
            modelNode == null -> "Surface trouvée ! Touche-la pour poser le renard 🦊"
            else -> "Le renard ${ANIMATIONS.getOrNull(animationIndex)?.second ?: "bouge"}.\n" +
                "Touche-le pour changer d'animation, ou touche ailleurs pour le déplacer."
        }
        Hint(message, Modifier.align(Alignment.BottomCenter))
        SwitchButton("Ça ne marche pas ? Mode simple", Modifier.align(Alignment.TopEnd), onArFailed)
    }
}

private fun trackingMessage(reason: TrackingFailureReason) = when (reason) {
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Il fait trop sombre, allume la lumière."
    TrackingFailureReason.EXCESSIVE_MOTION -> "Doucement ! Bouge le téléphone plus lentement."
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Vise une surface avec un peu de texture (parquet, tapis, table…)."
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "La caméra n'est pas disponible."
    else -> "Bouge doucement ton téléphone…"
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .systemBarsPadding()
            .padding(16.dp)
            .background(Color(0xAA000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
