package com.leo.arperso

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Animations contenues dans fox.glb, dans l'ordre du fichier. */
val ANIMATIONS = listOf("Survey" to "regarde autour", "Walk" to "marche", "Run" to "court")

/** Taille du personnage dans le monde réel, en mètres. */
const val CHARACTER_SIZE_M = 0.25f

const val FOX_MODEL = "models/fox.glb"

@Composable
fun SwitchButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier
            .systemBarsPadding()
            .padding(12.dp)
            .background(Color(0x88000000), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
