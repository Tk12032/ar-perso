package com.leo.arperso

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay

private enum class Mode { CHECKING, AR, SIMPLE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var mode by remember { mutableStateOf(Mode.CHECKING) }

            // Vraie RA si ARCore est pris en charge, sinon mode simple (caméra + gyroscope).
            LaunchedEffect(Unit) {
                var availability = ArCoreApk.getInstance().checkAvailability(context)
                repeat(30) {
                    if (!availability.isTransient) return@repeat
                    delay(100)
                    availability = ArCoreApk.getInstance().checkAvailability(context)
                }
                mode = if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) Mode.AR else Mode.SIMPLE
            }

            when (mode) {
                Mode.CHECKING -> Unit
                Mode.AR -> ArModeScreen(onArFailed = { mode = Mode.SIMPLE })
                Mode.SIMPLE -> SimpleModeScreen()
            }
        }
    }
}
