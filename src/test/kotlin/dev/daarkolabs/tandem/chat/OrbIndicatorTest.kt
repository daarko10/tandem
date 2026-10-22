package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.chat.OrbIndicator.Phase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The working row's orbs (spec 4.9): the desktop app's choreography as measured from its recording. */
class OrbIndicatorTest {
    /** Every 5 ms of one cycle. */
    private val times: LongProgression = 0L until OrbIndicator.PERIOD_MS step 5

    @Test
    fun `one cycle is the measured shapes in order and lasts 12 s`() {
        assertEquals(12_000L, OrbIndicator.PERIOD_MS)
        assertEquals(OrbIndicator.PERIOD_MS, Phase.entries.sumOf { it.durationMs })
        assertEquals(3_000L, Phase.RING.durationMs, "one turn of the ring")
        assertEquals(2_400L, Phase.SQUARE.durationMs, "two 1.2 s swap cycles of the square")
        assertEquals(4_000L, Phase.TRIAD.durationMs, "two 2 s flip cycles of the hexagon")
        assertEquals(Phase.RING, OrbIndicator.phaseAt(Phase.RING.startMs))
        assertEquals(Phase.SOLO_AFTER_TRIAD, OrbIndicator.phaseAt(OrbIndicator.PERIOD_MS - 1))
        assertEquals(Phase.RING_SPLIT, OrbIndicator.phaseAt(OrbIndicator.PERIOD_MS))
    }

    @Test
    fun `the frame repeats every period, before zero too`() {
        for (t in times step 35) {
            val frame = OrbIndicator.frame(t)
            assertEquals(frame, OrbIndicator.frame(t + OrbIndicator.PERIOD_MS), "t = $t")
            assertEquals(frame, OrbIndicator.frame(t + 7 * OrbIndicator.PERIOD_MS), "t = $t")
            assertEquals(frame, OrbIndicator.frame(t - OrbIndicator.PERIOD_MS), "t = $t")
        }
    }

    @Test
    fun `the frame is the same for the same time`() {
        assertEquals(OrbIndicator.frame(4_321), OrbIndicator.frame(4_321))
    }

    @Test
    fun `every orb stays inside the unit box`() {
        for (t in times) {
            for (orb in OrbIndicator.frame(t)) {
                assertTrue(orb.radius > 0f, "t = $t: $orb")
                assertTrue(orb.x - orb.radius >= 0f && orb.x + orb.radius <= 1f, "t = $t: $orb")
                assertTrue(orb.y - orb.radius >= 0f && orb.y + orb.radius <= 1f, "t = $t: $orb")
                assertTrue(orb.alpha in 0f..1f, "t = $t: $orb")
            }
        }
        for (orb in OrbIndicator.STILL) {
            assertTrue(orb.x - orb.radius >= 0f && orb.x + orb.radius <= 1f && orb.y - orb.radius >= 0f && orb.y + orb.radius <= 1f, "$orb")
        }
    }

    @Test
    fun `radius and alpha grow with nearness`() {
        val depths = (-40..40).map { it / 100f }
        val orbs = depths.map { OrbIndicator.project(0.1f, -0.1f, it) }
        orbs.zipWithNext().forEach { (far, near) ->
            assertTrue(near.radius > far.radius, "$far then $near")
            assertTrue(near.alpha >= far.alpha, "$far then $near")
        }
        assertTrue(OrbIndicator.project(0f, 0f, -0.3f).alpha < OrbIndicator.project(0f, 0f, -0.1f).alpha, "fades behind the centre")
        assertEquals(1f, OrbIndicator.project(0f, 0f, 0f).alpha, "opaque at the centre's depth")
        assertTrue(orbs.last().x > orbs.first().x, "a near orb sits further from the centre")
    }

    @Test
    fun `orbs come far to near, so that the near ones draw last`() {
        for (t in times) {
            val radii = OrbIndicator.frame(t).map { it.radius }
            assertEquals(radii.sorted(), radii, "t = $t")
        }
    }

    @Test
    fun `the orb count follows the measured phases`() {
        fun count(phase: Phase, at: Float): Int = OrbIndicator.frame(phase.startMs + (phase.durationMs * at).toLong()).size
        for (at in listOf(0.01f, 0.5f, 0.99f)) {
            assertEquals(3, count(Phase.RING, at), "ring at $at")
            assertEquals(4, count(Phase.SQUARE, at), "square at $at")
            assertEquals(3, count(Phase.TRIAD, at), "hexagon at $at")
            assertEquals(1, count(Phase.SOLO_AFTER_RING, at))
            assertEquals(1, count(Phase.SOLO_AFTER_SQUARE, at))
            assertEquals(1, count(Phase.SOLO_AFTER_TRIAD, at))
        }
        // Into the hexagon the orbs come out one after another: 1, then 2, then 3.
        val split = Phase.TRIAD_SPLIT
        assertEquals(listOf(1, 2, 3), listOf(100L, 250L, 400L).map { OrbIndicator.frame(split.startMs + it).size })
        for (phase in Phase.entries) {
            for (t in phase.startMs until phase.startMs + phase.durationMs step 5) {
                assertTrue(OrbIndicator.frame(t).size in 1..phase.orbs, "$phase at $t")
            }
        }
    }

    @Test
    fun `the shapes meet at the centre, so that a phase change never jumps`() {
        for (phase in Phase.entries) {
            val before = OrbIndicator.frame(phase.startMs - 1)
            val after = OrbIndicator.frame(phase.startMs)
            // Compare the cluster's extent and the nearest orb, which merges and splits leave in place.
            assertEquals(before.maxOf { it.radius }, after.maxOf { it.radius }, 0.01f, "into $phase")
            assertEquals(extent(before), extent(after), 0.02f, "into $phase")
        }
    }

    @Test
    fun `a merge ends with every orb at the centre`() {
        for (merge in listOf(Phase.RING_MERGE, Phase.SQUARE_MERGE, Phase.TRIAD_MERGE)) {
            val end = OrbIndicator.frame(merge.startMs + merge.durationMs - 1)
            assertTrue(end.all { kotlin.math.abs(it.x - 0.5f) < 0.01f && kotlin.math.abs(it.y - 0.5f) < 0.01f }, "$merge: $end")
        }
    }

    @Test
    fun `the square holds its corners and the hexagon its stations between moves`() {
        val square = OrbIndicator.frame(Phase.SQUARE.startMs + 50)
        assertEquals(4, square.size)
        assertTrue(square.all { kotlin.math.abs(kotlin.math.abs(it.x - 0.5f) - 0.255f) < 0.001f }, "$square")
        // 0.2 s into the hexagon the first orb flips and the second rests at its home corner, upper left.
        val hexagon = OrbIndicator.frame(Phase.TRIAD.startMs + 200)
        assertTrue(hexagon.any { kotlin.math.abs(it.x - (0.5f - 0.32f * 0.866f)) < 0.01f && kotlin.math.abs(it.y - (0.5f - 0.16f)) < 0.01f }, "$hexagon")
    }

    @Test
    fun `the reduced-motion frame is constant`() {
        val still = OrbIndicator.STILL
        assertEquals(3, still.size)
        for (t in times step 97) assertSame(still, OrbIndicator.frame(t, animated = false), "t = $t")
        assertEquals(OrbIndicator.frame(1_234), OrbIndicator.frame(1_234, animated = true))
        val radii = still.map { it.radius }
        assertEquals(radii.sorted(), radii, "far to near")
        assertTrue(radii.last() > radii.first(), "one orb in front of the others")
    }

    private fun extent(frame: List<Orb>): Float = frame.maxOf { maxOf(kotlin.math.abs(it.x - 0.5f), kotlin.math.abs(it.y - 0.5f)) + it.radius }
}
