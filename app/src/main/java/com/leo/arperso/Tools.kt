package com.leo.arperso

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Les outils du chasseur. Un seul en main à la fois ; NONE = caméra du téléphone, sans outil. */
enum class Tool(val label: String, val icon: String) {
    NONE("", ""),
    CAMCORDER("CAMÉSCOPE", "●"),
    EMF("EMF", "≋"),
}

private val Mono = FontFamily.Monospace

/** Barre d'outils en bas de l'écran. Toucher l'outil en main le range. */
@Composable
fun ToolBar(current: Tool, onSelect: (Tool) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (t in listOf(Tool.CAMCORDER, Tool.EMF)) {
            val active = t == current
            val accent = if (t == Tool.CAMCORDER) Color(0xFFFF4040) else Color(0xFF7CFF8A)
            Column(
                Modifier
                    .weight(1f)
                    .background(if (active) accent.copy(alpha = 0.22f) else Color(0xB3000000), RoundedCornerShape(12.dp))
                    .border(2.dp, if (active) accent else Color(0x44FFFFFF), RoundedCornerShape(12.dp))
                    .clickable { onSelect(t) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(t.icon, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(t.label, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (active) "ranger" else "sortir", color = Color(0xFF999999), fontSize = 11.sp)
            }
        }
    }
}

/** Le détecteur EMF en plein écran : on ne voit plus la caméra, seulement l'appareil. */
@Composable
fun EmfScreen(level: Int, searching: Boolean) {
    val colors = listOf(0xFF3DDC57, 0xFF9BE22D, 0xFFF5D31B, 0xFFFF8A1F, 0xFFFF2D2D)
    val flicker by rememberInfiniteTransition(label = "emf").animateFloat(
        0.75f, 1f, infiniteRepeatable(tween(90), RepeatMode.Reverse), label = "f"
    )
    // Lecture en milligauss, un peu bruitée pour faire vivant.
    val reading = when (level) {
        0 -> 0.3f; 1 -> 1.4f; 2 -> 2.6f; 3 -> 5.2f; 4 -> 9.1f; else -> 21f
    } * (0.9f + 0.2f * flicker)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(28.dp))
                .border(3.dp, Color(0xFF333333), RoundedCornerShape(28.dp))
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("EMF-5", color = Color(0xFFBBBBBB), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.forEachIndexed { i, c ->
                    val lit = level > i
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(width = 40.dp, height = 64.dp)
                                .background(
                                    if (lit) Color(c).copy(alpha = flicker) else Color(c).copy(alpha = 0.12f),
                                    RoundedCornerShape(8.dp)
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("${i + 1}", color = Color(0xFF777777), fontFamily = Mono, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                String.format("%.1f mG", reading), color = if (level >= 5) Color(0xFFFF2D2D) else Color(0xFF7CFF8A),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 32.sp
            )
        }
        Text(
            if (searching) "Tourne-toi lentement : les bips accélèrent\nquand tu pointes vers la présence."
            else "Elle est tout près…\nSeul le caméscope peut la montrer.",
            color = Color(0xFF999999), fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 96.dp, start = 24.dp, end = 24.dp)
        )
    }
}

@Composable
fun TopChip(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}
