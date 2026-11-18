package com.leo.arperso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private val hudStyle = TextStyle(
    color = Color(0xFFEFEFEF),
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    shadow = Shadow(Color.Black, Offset(2f, 2f), 2f)
)

/** Grain, lignes, vignettage, texte de caméscope et glitchs, par-dessus tout le reste. */
@Composable
fun FootageOverlay(glitch: () -> Float, modifier: Modifier = Modifier) {
    val noise = remember { List(4) { noiseBitmap(96, 170) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val start = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = System.currentTimeMillis() }
    }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val g = glitch()
            val tick = now / 50
            // Grain animé
            drawImage(
                noise[(tick % noise.size).toInt()],
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(96, 170),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = 0.16f + g * 0.4f,
                filterQuality = FilterQuality.None
            )
            // Lignes de balayage
            var y = 0f
            while (y < size.height) {
                drawRect(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, 2f))
                y += 6f
            }
            // Bande claire qui défile lentement (défaut de tracking)
            val bandY = (now / 12 % (size.height + 200).toLong()).toFloat() - 100f
            drawRect(Color.White.copy(alpha = 0.05f), Offset(0f, bandY), Size(size.width, 40f))
            // Glitchs : barres horizontales aléatoires
            val bars = (g * 14).toInt() + if (Random.nextInt(90) == 0) 2 else 0
            repeat(bars) {
                val by = Random.nextFloat() * size.height
                val c = if (Random.nextBoolean()) Color.White else Color(0xFF7FFFD4)
                drawRect(c.copy(alpha = 0.15f + Random.nextFloat() * 0.35f), Offset(0f, by), Size(size.width, 3f + Random.nextFloat() * 30f))
            }
            if (g > 0.85f && Random.nextInt(3) == 0) {
                drawRect(if (Random.nextBoolean()) Color.White.copy(alpha = 0.5f) else Color(0x99FF0000))
            }
            // Vignettage
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                    center = center,
                    radius = size.maxDimension * 0.62f
                )
            )
        }

        // Texte de caméscope
        val elapsed = (now - start) / 1000
        val blink = (now / 600) % 2 == 0L
        Column(Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 24.dp)) {
            Text(if (blink) "● REC" else "  REC", style = hudStyle.copy(color = Color(0xFFFF3030)))
            Text(
                "%02d:%02d:%02d".format(elapsed / 3600, elapsed / 60 % 60, elapsed % 60),
                style = hudStyle
            )
        }
        Text("SP ▮▮▯", style = hudStyle, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 36.dp, end = 24.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(bottom = 36.dp, start = 24.dp)) {
            Text(SimpleDateFormat("a hh:mm:ss", Locale.US).format(Date(now)), style = hudStyle)
            Text(SimpleDateFormat("MMM dd yyyy", Locale.US).format(Date(now)).uppercase(), style = hudStyle)
        }
    }
}

private fun noiseBitmap(w: Int, h: Int): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val px = IntArray(w * h) {
        val v = Random.nextInt(256)
        val a = Random.nextInt(60, 200)
        (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}
