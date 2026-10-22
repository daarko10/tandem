package dev.daarkolabs.tandem.chat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One orb of the working indicator ([OrbIndicator]) in a unit box: its centre ([x] left to right, [y] top to bottom, both 0 to 1), its
 * [radius] in box widths and its [alpha] from 0 to 1.
 */
data class Orb(val x: Float, val y: Float, val radius: Float, val alpha: Float)

/**
 * The working row's indicator (spec 4.9.1) after the desktop app's: a cluster of coral orbs that turns in 3D. Each orb is a point in a
 * world centred on the box, x right, y down and z towards the viewer, seen through a perspective camera ([project]): a near orb is
 * bigger and further from the centre, a far one smaller and closer to the centre, at full colour, as the desktop app's are; only
 * an orb that flips through the depth behind the shapes all but vanishes and fades, as the desktop app's do. The orbs take four
 * shapes in turn and merge into one between them, as the desktop app's do. The shapes, their timings and the merges were measured
 * from the desktop app's recording; the cycle of [PERIOD_MS] that strings them together is composed:
 * - a ring of three, edge on, that spins about the vertical once in 3 s, tipping its near side low and then high ([Phase.RING]);
 * - a square of four whose diagonal pairs swap places in turn, each half turn bulging towards the other pair, every 0.6 s
 *   ([Phase.SQUARE]);
 * - three orbs on the corners of a hexagon, each flipping through the back to the opposite corner one after another, 1/3 s apart,
 *   every orb back home in 2 s ([Phase.TRIAD]);
 * - one orb between them, which backs away a little, never out of sight, and comes back ([Phase.SOLO_AFTER_RING] and the like)
 *   before it splits again.
 * Pure: the same time always gives the same frame. Under Reduce motion the row shows [STILL].
 */
object OrbIndicator {
    /** One whole cycle of the four shapes. */
    const val PERIOD_MS: Long = 12_000

    /** The camera's distance from the box's centre, in box widths: how much depth changes an orb's size and place. */
    private const val CAMERA = 1.25f

    /** An orb's radius at the depth of the box's centre. */
    private const val ORB_RADIUS = 0.11f

    /**
     * Behind the shapes, from [VANISH_FROM] back (the ring's far side, the deepest a shape goes), an orb shrinks beyond the camera's
     * term too, to [VANISH_SCALE] of its size at [FAR_DEPTH], and fades with it to [FAR_ALPHA]: a flipping orb at its deepest ends at
     * about a fifth of an orb at the centre, as the desktop app's do. Nearer than [VANISH_FROM] an orb is opaque: the desktop app
     * shows a far orb by its size alone.
     */
    private const val VANISH_FROM = 0.35f
    private const val VANISH_SCALE = 0.3f
    private const val FAR_DEPTH = 0.6f
    private const val FAR_ALPHA = 0.25f

    /**
     * How far the single orb backs away between two shapes: to the ring's far side, where [VANISH_FROM] begins, and no further, so
     * that it dips to about four fifths of its radius and stays opaque. The desktop app's dips to about two thirds and never vanishes.
     */
    private const val SOLO_BACK = VANISH_FROM

    private const val RING_RADIUS = 0.34f
    private const val RING_SPIN_MS = 3_000L

    /**
     * How far the ring tips at its most, in radians: edge on most of the time, its near side low about a quarter of the way through
     * [Phase.RING] and high about three quarters of the way, each tip about 0.5 s, as the desktop app's.
     */
    private const val RING_TILT = 0.75f

    /** Half the side of the square of four. */
    private const val SQUARE_HALF = 0.255f
    private const val SQUARE_RADIUS = SQUARE_HALF * 1.4142135f

    /** How far a swapping orb's half turn bulges sideways, as a share of [SQUARE_RADIUS], and how far it leaves the screen's plane. */
    private const val SWAP_BULGE = 0.55f
    private const val SWAP_DEPTH = 0.08f
    private const val SQUARE_CYCLE_MS = 1_200L
    private const val SWAP_MS = 500L
    private const val SQUARE_REST_MS = 100L

    private const val HEX_RADIUS = 0.32f

    /** How far a flipping orb's path bends off the straight line through the centre, as a share of [HEX_RADIUS]. */
    private const val FLIP_BULGE = 0.15f

    /** How far behind the centre a flipping orb passes: far enough to all but vanish ([VANISH_SCALE]), as the desktop app's do. */
    private const val FLIP_DEPTH = 0.6f
    private const val TRIAD_CYCLE_MS = 2_000L
    private const val FLIP_MS = 800L

    /** Where a flip begins, one orb after another. */
    private val FLIP_OFFSETS_MS: LongArray = longArrayOf(0, 333, 667)

    /** Each orb's home corner of the hexagon in degrees, clockwise from the right (y points down): upper right, upper left, top. */
    private val FLIP_HOMES: FloatArray = floatArrayOf(330f, 210f, 270f)

    /** The corners of the square in degrees, clockwise from the right: top left, top right, bottom right, bottom left. */
    private val CORNERS: FloatArray = floatArrayOf(225f, 315f, 45f, 135f)

    /** Where the hexagon's later orbs come from: as deep as a flip goes, all but vanished ([VANISH_SCALE]). */
    private const val JOIN_DEPTH = 0.6f

    /** When the orbs of [Phase.TRIAD_SPLIT] leave the centre, and how long each takes to reach the hexagon. */
    private val TRIAD_JOINS_MS: LongArray = longArrayOf(0, 150, 300)
    private val TRIAD_JOIN_MS: LongArray = longArrayOf(300, 300, 150)

    /** The phases of one cycle, in order, with how long each lasts and how many orbs it shows at most. */
    internal enum class Phase(val durationMs: Long, val orbs: Int) {
        RING_SPLIT(300, 3),
        RING(3_000, 3),
        RING_MERGE(200, 3),
        SOLO_AFTER_RING(350, 1),
        SQUARE_SPLIT(200, 4),
        SQUARE(2_400, 4),
        SQUARE_MERGE(200, 4),
        SOLO_AFTER_SQUARE(350, 1),
        TRIAD_SPLIT(450, 3),
        TRIAD(4_000, 3),
        TRIAD_MERGE(200, 3),
        SOLO_AFTER_TRIAD(350, 1);

        /** When the phase begins in the cycle. */
        val startMs: Long get() = entries.takeWhile { it != this }.sumOf { it.durationMs }
    }

    private val STARTS: LongArray = Phase.entries.map { it.startMs }.toLongArray()

    /**
     * What the row shows under Reduce motion, or while it waits or the CLI is silent: the ring of three tipped towards the viewer, one
     * orb in front and low, two behind it.
     */
    val STILL: List<Orb> = drawn((0 until 3).map { ring(it, spin = 0f, tilt = RING_TILT, radius = RING_RADIUS) })

    /** The orbs at [tMillis] (any time, the cycle repeats every [PERIOD_MS]), far to near: draw them in this order. */
    fun frame(tMillis: Long): List<Orb> {
        val t = tMillis.mod(PERIOD_MS)
        val index = STARTS.indexOfLast { it <= t }
        val phase = Phase.entries[index]
        return drawn(points(phase, t - STARTS[index]))
    }

    /** [frame] while [animated], else [STILL] whatever the time. */
    fun frame(tMillis: Long, animated: Boolean): List<Orb> = if (animated) frame(tMillis) else STILL

    /** The phase at [tMillis], for tests. */
    internal fun phaseAt(tMillis: Long): Phase {
        val t = tMillis.mod(PERIOD_MS)
        return Phase.entries[STARTS.indexOfLast { it <= t }]
    }

    /**
     * The world point ([x], [y], [z]) as drawn: moved away from the centre and grown in proportion to the camera's distance over its
     * own; opaque down to [VANISH_FROM] behind the centre, and from there shrunk further, to [VANISH_SCALE] at [FAR_DEPTH], and faded
     * with it, to [FAR_ALPHA].
     */
    internal fun project(x: Float, y: Float, z: Float): Orb {
        val scale = CAMERA / (CAMERA - z)
        val deep = easeInOut(((-z - VANISH_FROM) / (FAR_DEPTH - VANISH_FROM)).coerceIn(0f, 1f))
        val vanish = 1f - (1f - VANISH_SCALE) * deep
        val alpha = 1f - (1f - FAR_ALPHA) * deep
        return Orb(0.5f + x * scale, 0.5f + y * scale, ORB_RADIUS * scale * vanish, alpha)
    }

    /** A point of the world, drawn at [fade] of its alpha: below 1 only while it joins a shape ([triadSplit]). */
    private class Point(val x: Float, val y: Float, val z: Float, val fade: Float = 1f) {
        fun lerp(to: Point, f: Float): Point = Point(x + (to.x - x) * f, y + (to.y - y) * f, z + (to.z - z) * f, fade + (to.fade - fade) * f)
    }

    private val CENTRE = Point(0f, 0f, 0f)

    /** [points] projected, far to near. */
    private fun drawn(points: List<Point>): List<Orb> = points.sortedBy { it.z }.map { point ->
        project(point.x, point.y, point.z).let { if (point.fade < 1f) it.copy(alpha = it.alpha * point.fade) else it }
    }

    /** The orbs of [phase], [elapsed] into it. */
    private fun points(phase: Phase, elapsed: Long): List<Point> {
        val u = elapsed.toFloat() / phase.durationMs
        return when (phase) {
            Phase.RING_SPLIT -> ringAt(elapsed - phase.durationMs, radius = RING_RADIUS * easeOut(u))
            Phase.RING -> ringAt(elapsed)
            Phase.RING_MERGE -> ringAt(Phase.RING.durationMs + elapsed).map { it.lerp(CENTRE, easeIn(u)) }
            Phase.SQUARE_SPLIT -> (0 until 4).map { CENTRE.lerp(square(it, 0), easeOut(u)) }
            Phase.SQUARE -> (0 until 4).map { square(it, elapsed) }
            Phase.SQUARE_MERGE -> (0 until 4).map { square(it, Phase.SQUARE.durationMs + elapsed).lerp(CENTRE, easeIn(u)) }
            Phase.TRIAD_SPLIT -> triadSplit(elapsed - phase.durationMs, elapsed)
            Phase.TRIAD -> (0 until 3).map { triad(it, elapsed) }
            Phase.TRIAD_MERGE -> (0 until 3).map { triad(it, Phase.TRIAD.durationMs + elapsed).lerp(CENTRE, easeIn(u)) }
            Phase.SOLO_AFTER_RING, Phase.SOLO_AFTER_SQUARE, Phase.SOLO_AFTER_TRIAD -> listOf(Point(0f, 0f, -SOLO_BACK * sin(PI.toFloat() * u)))
        }
    }

    /**
     * The ring of three [tau] into [Phase.RING] (negative before it): one turn per [RING_SPIN_MS], its near side tipped low a quarter of
     * the way through and high three quarters of the way, each tip a fourth power of a sine, so that it lasts about 0.5 s.
     */
    private fun ringAt(tau: Long, radius: Float = RING_RADIUS): List<Point> {
        val spin = 2f * PI.toFloat() * tau / RING_SPIN_MS
        val along = (tau.toFloat() / Phase.RING.durationMs).coerceIn(0f, 1f)
        val wave = sin(2f * PI.toFloat() * along)
        val tilt = RING_TILT * wave * wave * wave * abs(wave)
        return (0 until 3).map { ring(it, spin, tilt, radius) }
    }

    /**
     * Orb [i] of a ring of three of [radius], turned [spin] radians about the vertical and tipped [tilt] radians: the near side low
     * for a positive [tilt], high for a negative one.
     */
    private fun ring(i: Int, spin: Float, tilt: Float, radius: Float): Point {
        val angle = spin + i * 2f * PI.toFloat() / 3f
        val depth = radius * cos(angle)
        return Point(radius * sin(angle), depth * sin(tilt), depth * cos(tilt))
    }

    /**
     * Orb [i] of the square [tau] into [Phase.SQUARE]. Each [SQUARE_CYCLE_MS] the top-left and bottom-right pair, then the top-right
     * and bottom-left pair, swap places: a half turn about the centre, anticlockwise for the first pair and clockwise for the second,
     * that bulges towards the other pair, the upper orb passing in front and the lower one behind.
     */
    private fun square(i: Int, tau: Long): Point {
        val local = tau.mod(SQUARE_CYCLE_MS)
        val swapped = tau.floorDiv(SQUARE_CYCLE_MS).mod(2L) != 0L
        val second = i % 2 == 1
        val begins = if (second) SQUARE_REST_MS * 2 + SWAP_MS else SQUARE_REST_MS
        val turn = PI.toFloat() * easeInOut(((local - begins).toFloat() / SWAP_MS).coerceIn(0f, 1f))
        val home = CORNERS[i] + if (swapped) 180f else 0f
        val side = home + if (second) 90f else -90f
        val radial = SQUARE_RADIUS * cos(turn)
        val bulge = SQUARE_RADIUS * SWAP_BULGE * sin(turn)
        val upper = sin(radians(home)) < 0f
        return Point(
            radial * cos(radians(home)) + bulge * cos(radians(side)),
            radial * sin(radians(home)) + bulge * sin(radians(side)),
            (if (upper) SWAP_DEPTH else -SWAP_DEPTH) * sin(turn),
        )
    }

    /**
     * Orb [i] of the hexagon [tau] into [Phase.TRIAD]. Every [TRIAD_CYCLE_MS], from its own offset, the orb flips from its home corner to
     * the opposite one through the back in [FLIP_MS], rests, flips home the same way and rests again.
     */
    private fun triad(i: Int, tau: Long): Point {
        val local = (tau - FLIP_OFFSETS_MS[i]).mod(TRIAD_CYCLE_MS)
        val half = TRIAD_CYCLE_MS / 2
        val from = FLIP_HOMES[i] + if (local >= half) 180f else 0f
        val turn = PI.toFloat() * easeInOut(((local % half).toFloat() / FLIP_MS).coerceIn(0f, 1f))
        val radial = HEX_RADIUS * cos(turn)
        val bulge = HEX_RADIUS * FLIP_BULGE * sin(turn)
        return Point(
            radial * cos(radians(from)) + bulge * cos(radians(from + 90f)),
            radial * sin(radians(from)) + bulge * sin(radians(from + 90f)),
            -FLIP_DEPTH * sin(turn),
        )
    }

    /**
     * The hexagon forming [tau] before [Phase.TRIAD] begins ([elapsed] into [Phase.TRIAD_SPLIT]): the single orb moves out to its
     * place, then the others come out of the depth behind the centre one after another, from all but vanished ([JOIN_DEPTH]) and
     * unseen ([Point.fade] 0), growing and fading in as they come forward to their places; an orb not yet out is not drawn.
     */
    private fun triadSplit(tau: Long, elapsed: Long): List<Point> = (0 until 3).mapNotNull { i ->
        val since = elapsed - TRIAD_JOINS_MS[i]
        if (i > 0 && since <= 0) return@mapNotNull null
        val start = if (i == 0) CENTRE else Point(0f, 0f, -JOIN_DEPTH, fade = 0f)
        start.lerp(triad(i, tau), easeOut((since.toFloat() / TRIAD_JOIN_MS[i]).coerceIn(0f, 1f)))
    }

    private fun radians(degrees: Float): Float = degrees * PI.toFloat() / 180f

    private fun easeIn(u: Float): Float = u * u

    private fun easeOut(u: Float): Float = 1f - (1f - u) * (1f - u)

    private fun easeInOut(u: Float): Float = u * u * (3f - 2f * u)
}
