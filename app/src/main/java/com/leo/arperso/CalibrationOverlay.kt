package com.leo.arperso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

const val SHADOW_MODEL = "models/shadow.glb"
const val MARKER_MODEL = "models/marker.glb"

enum class CalibStep { NONE, AIM, ADJUST }

private val Panel = Color(0xCC000000)
private val Accent = Color(0xFFFF4040)

/**
 * Étalonnage du sol en deux temps :
 * 1. le joueur vise la base d'un objet posé au sol à 3 grands pas → on en déduit la hauteur du téléphone ;
 * 2. un cercle rouge apparaît au sol, le joueur l'ajuste avec deux curseurs.
 */
@Composable
fun CalibrationOverlay(
    step: CalibStep,
    calibration: Calibration,
    onAim: () -> Unit,
    onChange: (Calibration) -> Unit,
    onRestart: () -> Unit,
    onDone: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (step == CalibStep.AIM) {
            Crosshair(Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (step) {
                CalibStep.AIM -> {
                    Title("Réglage du sol · 1/2")
                    Body(
                        "Pose une chaussure par terre, puis recule de 3 grands pas.\n" +
                            "Tiens ton téléphone comme pendant le jeu et place la croix sur la base de la chaussure."
                    )
                    Button(
                        onClick = onAim,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Je vise la chaussure") }
                }
                CalibStep.ADJUST -> {
                    Title("Réglage du sol · 2/2")
                    Body(
                        "Le cercle rouge doit être posé sur la chaussure.\n" +
                            "• Trop loin ou trop près : règle la hauteur.\n" +
                            "• Il glisse quand tu tournes le téléphone à gauche et à droite : règle le zoom."
                    )
                    Labeled("Hauteur du téléphone", String.format(Locale.FRANCE, "%.2f m", calibration.phoneHeight))
                    Slider(
                        value = calibration.phoneHeight,
                        onValueChange = { onChange(calibration.copy(phoneHeight = it)) },
                        valueRange = Calibration.MIN_HEIGHT..Calibration.MAX_HEIGHT
                    )
                    Labeled("Zoom", String.format(Locale.FRANCE, "%.0f %%", calibration.fovScale * 100))
                    Slider(
                        value = calibration.fovScale,
                        onValueChange = { onChange(calibration.copy(fovScale = it)) },
                        valueRange = Calibration.MIN_FOV..Calibration.MAX_FOV
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                            Text("Recommencer", color = Color.White)
                        }
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.weight(1f)
                        ) { Text("C'est bon") }
                    }
                }
                CalibStep.NONE -> Unit
            }
        }
    }
}

@Composable
private fun Crosshair(modifier: Modifier) {
    Canvas(modifier.size(64.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        drawLine(Accent, Offset(c.x - r, c.y), Offset(c.x - r / 3, c.y), strokeWidth = 4f)
        drawLine(Accent, Offset(c.x + r / 3, c.y), Offset(c.x + r, c.y), strokeWidth = 4f)
        drawLine(Accent, Offset(c.x, c.y - r), Offset(c.x, c.y - r / 3), strokeWidth = 4f)
        drawLine(Accent, Offset(c.x, c.y + r / 3), Offset(c.x, c.y + r), strokeWidth = 4f)
        drawCircle(Accent, radius = 4f, center = c)
    }
}

@Composable
private fun Title(text: String) =
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String) =
    Text(text, color = Color(0xFFDDDDDD), fontSize = 14.sp, lineHeight = 20.sp)

@Composable
private fun Labeled(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(0.dp))
}
