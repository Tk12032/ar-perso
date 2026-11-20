package com.leo.arperso

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { App() }
    }
}

@androidx.compose.runtime.Composable
private fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var player by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Player.load(context)) }
    var hunt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Hunt?>(null) }
    val p = player
    val h = hunt
    when {
        p == null -> PseudoScreen(onDone = { player = it })
        h != null -> HorrorScreen(creatureColor = h.color, onExit = { hunt = null })
        else -> HuntScreen(p, onEncounter = { hunt = it })
    }
    androidx.activity.compose.BackHandler(enabled = h != null) { hunt = null }
}
