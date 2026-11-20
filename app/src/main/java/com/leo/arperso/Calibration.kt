package com.leo.arperso

import android.content.Context

/**
 * Réglage du sol, propre à chaque joueur et à sa façon de tenir le téléphone.
 * - [phoneHeight] : hauteur du téléphone au-dessus du sol, en mètres ;
 * - [fovScale] : correction du champ de vision de la caméra (1 = valeur annoncée par le téléphone).
 */
data class Calibration(val phoneHeight: Float, val fovScale: Float, val done: Boolean) {
    companion object {
        private const val PREFS = "calibration"
        const val MIN_HEIGHT = 0.6f
        const val MAX_HEIGHT = 2.0f
        const val MIN_FOV = 0.8f
        const val MAX_FOV = 1.25f

        /** Distance supposée entre le joueur et l'objet visé : « 3 grands pas ». */
        const val AIM_DISTANCE_M = 2.2f

        fun load(context: Context): Calibration {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Calibration(
                phoneHeight = p.getFloat("height", PHONE_HEIGHT_M),
                fovScale = p.getFloat("fov", 1f),
                done = p.getBoolean("done", false),
            )
        }

        fun save(context: Context, c: Calibration) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("height", c.phoneHeight)
                .putFloat("fov", c.fovScale)
                .putBoolean("done", c.done)
                .apply()
        }
    }
}
