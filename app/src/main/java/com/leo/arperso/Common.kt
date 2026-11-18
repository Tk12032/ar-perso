package com.leo.arperso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val GHOUL_MODEL = "models/ghoul.glb"

/** Hauteur supposée du téléphone au-dessus du sol (tenu à hauteur de poitrine), en mètres. */
const val PHONE_HEIGHT_M = 1.3f

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .systemBarsPadding()
            .padding(16.dp)
            .background(Color(0xAA000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
