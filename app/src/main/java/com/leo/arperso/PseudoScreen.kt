package com.leo.arperso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Premier lancement : le joueur choisit son pseudo (pour le classement). */
@Composable
fun PseudoScreen(onDone: (Player) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pseudo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val valid = pseudo.trim().length in 2..24 && Regex("^[\\p{L}\\p{N}_ .\\-]+$").matches(pseudo.trim())

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("● REC", color = Color(0xFFFF4040), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text("FOUND FOOTAGE", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Choisis ton nom de chasseur. Il apparaîtra dans le classement.",
            color = Color(0xFFBBBBBB), fontSize = 15.sp
        )
        OutlinedTextField(
            value = pseudo,
            onValueChange = { pseudo = it.take(24); error = null },
            singleLine = true,
            label = { Text("Pseudo") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFFF4040), focusedLabelColor = Color(0xFFFF4040)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp) }
        Button(
            enabled = valid && !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { Api.register(pseudo.trim()) }
                        .onSuccess { Player.save(context, it); onDone(it) }
                        .onFailure { error = it.message ?: "Erreur réseau" }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4040)),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "…" else "Commencer la chasse") }
    }
}
