package com.leo.arperso

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * La créature, avec une vraie position dans la zone (repère local en mètres).
 * Elle n'est simulée que pendant une mission, et ne sort jamais de sa zone.
 */
class CreatureSim(
    start: Vec,
    private val behavior: Behavior,
    private val inZone: (Vec) -> Boolean,
    private val rnd: Random = Random.Default,
) {
    var position = start
        private set
    /** Vitesse actuelle (m/s), pour l'animation. */
    var speed = 0.0
        private set
    /** Ce que fait la créature, en clair (écran de réglage). */
    var activity = "tapie"
        private set

    private var wanderTarget: Vec? = null
    private var wanderTime = 0.0
    private var jumpClock = 2.5

    /**
     * Avance la simulation de [dt] secondes.
     * @param player position du joueur (null = inconnue) ; [playerHeading] = où il regarde (degrés)
     * @param watched la créature est filmée au caméscope en ce moment
     * @return true si elle vient de faire un « saut » vers le joueur (glitch à jouer)
     */
    fun update(dt: Double, player: Vec?, playerHeading: Float, watched: Boolean): Boolean {
        val toPlayer = player?.let { it - position }
        val d = toPlayer?.length ?: Double.MAX_VALUE
        val playerHere = player != null && inZone(player)

        if (watched) {
            // Filmée : elle se fige… mais certaines profitent d'un glitch pour se rapprocher d'un coup.
            speed = 0.0
            activity = "figée (filmée)"
            if (behavior.jump && toPlayer != null && d > 1.8) {
                jumpClock -= dt
                if (jumpClock <= 0) {
                    jumpClock = 1.6 + rnd.nextDouble() * 2.0
                    moveToward(toPlayer.normalized(), minOf(0.8, d - 1.2))
                    return true
                }
            }
            return false
        }

        var dir: Vec? = null
        var v = 0.0
        if (playerHere && toPlayer != null) {
            when (behavior.mode) {
                "hunt", "stalk" -> {
                    dir = toPlayer.normalized(); v = behavior.speed.toDouble()
                    activity = if (behavior.mode == "hunt") "chasse" else "traque"
                }
                "keep" -> {
                    // Le joueur lui tourne-t-il le dos ? (la créature est à plus de 110° de son regard)
                    val rel = angleDiff(playerHeading.toDouble(), (position - player).bearing)
                    when {
                        abs(rel) > 110 -> { dir = toPlayer.normalized(); v = behavior.speed.toDouble(); activity = "approche dans ton dos" }
                        d < behavior.keepMin -> { dir = toPlayer.normalized() * -1.0; v = behavior.speed * 0.8; activity = "recule" }
                        d > behavior.keepMax -> { dir = toPlayer.normalized(); v = behavior.speed * 0.6; activity = "se rapproche" }
                        else -> { v = 0.0; activity = "observe" }
                    }
                }
            }
            if (d < 0.8) v = 0.0 // pas plus près : c'est le cri qui prend le relais
        }
        if (dir == null && v == 0.0 && activity != "observe") {
            // Personne à poursuivre : elle erre au hasard dans sa zone.
            wanderTime -= dt
            val t = wanderTarget
            if (t == null || (t - position).length < 1.0 || wanderTime <= 0) {
                wanderTarget = randomPointNear(position, 15.0)
                wanderTime = 10.0 + rnd.nextDouble() * 10.0
            }
            wanderTarget?.let { dir = (it - position).normalized() }
            v = behavior.wander.toDouble()
            activity = "erre"
        }
        speed = v
        dir?.let { if (v > 0) { if (!moveToward(it, v * dt)) wanderTarget = null } }
        return false
    }

    /** Déplace la créature en restant dans la zone (glisse le long du bord si besoin). */
    private fun moveToward(dir: Vec, dist: Double): Boolean {
        if (dist <= 0) return true
        val step = dir * dist
        for (cand in listOf(position + step, position + Vec(step.x, 0.0), position + Vec(0.0, step.y))) {
            if (inZone(cand)) { position = cand; return true }
        }
        speed = 0.0
        return false
    }

    private fun randomPointNear(from: Vec, r: Double): Vec {
        repeat(30) {
            val a = rnd.nextDouble() * 2 * Math.PI
            val k = r * kotlin.math.sqrt(rnd.nextDouble())
            val p = from + Vec(sin(a) * k, cos(a) * k)
            if (inZone(p)) return p
        }
        return from
    }
}

/** Écart signé entre deux caps, entre -180 et 180 degrés. */
fun angleDiff(a: Double, b: Double): Double = ((b - a) % 360 + 540) % 360 - 180
