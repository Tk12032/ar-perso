package com.leo.arperso

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Scénarios de poursuite simulés, sans téléphone : zone ronde de 40 m autour de (0, 0). */
class CreatureSimTest {
    private val inZone: (Vec) -> Boolean = { it.length <= 40.0 }

    private fun run(
        behavior: Behavior, start: Vec, player0: Vec, playerVel: Vec, seconds: Double,
        heading: (Vec, Vec) -> Float = { p, c -> (c - p).bearing.toFloat() }, // le joueur regarde la créature
    ): Pair<CreatureSim, List<Double>> {
        val sim = CreatureSim(start, behavior, inZone, Random(1))
        var p = player0
        val dists = mutableListOf<Double>()
        var t = 0.0
        while (t < seconds) {
            p += playerVel * 0.1
            sim.update(0.1, p, heading(p, sim.position), watched = false)
            dists += (sim.position - p).length
            assertTrue("la créature est sortie de sa zone", inZone(sim.position))
            t += 0.1
        }
        return sim to dists
    }

    @Test fun rougeRattrapeUnMarcheurImmobile() {
        val (_, d) = run(Behavior.forCreature("red"), Vec(0.0, 20.0), Vec(0.0, 0.0), Vec(0.0, 0.0), 15.0)
        assertTrue("la rouge doit arriver au contact, reste ${d.last()} m", d.last() < 1.0)
    }

    @Test fun onSemeLaRougeEnCourant() {
        // Le joueur court (3 m/s) vers le bord et sort de la zone : la rouge (1,6 m/s) reste bloquée au bord.
        val (_, d) = run(Behavior.forCreature("red"), Vec(0.0, -10.0), Vec(0.0, 0.0), Vec(0.0, 3.0), 25.0)
        assertTrue("on doit la distancer, distance finale ${d.last()} m", d.last() > 35.0)
    }

    @Test fun onRattrapeLaNoireEnMarchant() {
        // La noire avance vers le joueur à 0,5 m/s ; le joueur marche vers elle à 1,2 m/s : contact rapide.
        val (_, d) = run(Behavior.forCreature("black"), Vec(0.0, 30.0), Vec(0.0, 0.0), Vec(0.0, 1.2), 30.0)
        assertTrue(d.any { it < 2.0 })
    }

    @Test fun laPaleGardeSesDistancesQuandOnLaRegarde() {
        val (_, d) = run(Behavior.forCreature("pale"), Vec(0.0, 25.0), Vec(0.0, 0.0), Vec(0.0, 0.0), 40.0)
        val last = d.takeLast(50)
        assertTrue("la pâle doit rester entre ~8 et ~12 m, a fini à ${d.last()} m", last.all { it in 7.0..13.0 })
    }

    @Test fun laPaleApprocheDansLeDos() {
        // Le joueur tourne le dos (regarde à l'opposé) : elle approche.
        val (_, d) = run(Behavior.forCreature("pale"), Vec(0.0, 10.0), Vec(0.0, 0.0), Vec(0.0, 0.0), 20.0,
            heading = { p, c -> (((c - p).bearing + 180) % 360).toFloat() })
        assertTrue("dans le dos, elle doit venir au contact, reste ${d.last()} m", d.last() < 1.5)
    }

    @Test fun personneDansLaZoneElleErre() {
        val sim = CreatureSim(Vec(0.0, 0.0), Behavior.forCreature("red"), inZone, Random(3))
        repeat(600) { sim.update(0.1, Vec(500.0, 500.0), 0f, false) }
        assertTrue(inZone(sim.position))
        assertFalse(sim.activity == "chasse")
    }

    @Test fun filmeeElleSeFige() {
        val sim = CreatureSim(Vec(0.0, 10.0), Behavior.forCreature("pale"), inZone, Random(1))
        val before = sim.position
        repeat(50) { sim.update(0.1, Vec(0.0, 0.0), 0f, watched = true) }
        assertTrue((sim.position - before).length < 1e-9)
    }
}
