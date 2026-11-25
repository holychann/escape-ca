package kr.escape.car.engine

import kotlin.math.abs

/**
 * Tells when the child has picked a wrong button, so Lumi can say so. Only [HoldJudge] and [TogetherJudge] decide a
 * solve; this reads the same car values for a change that does not lead to the answer:
 * - what the car showed when the puzzle began is never wrong, only a change from it;
 * - a change counts once it has stayed [settleMs], so the steps passed on the way (the fan from 1 to 8) do not;
 * - a wrong state is told once while it stays, and at most every [gapMs];
 * - switching a reading light off is tidying up, never wrong;
 * - ⑥ 하나 둘 셋 is about timing too: a press the solve has not followed within [missMs] is a missed beat.
 */
class WrongJudge(
    private val puzzle: Puzzle,
    private val settleMs: Long = 2000,
    private val gapMs: Long = 5000,
    private val missMs: Long = 3500,
) {
    enum class Kind { BUTTON, BEAT }

    /** The car values the puzzles are answered with. */
    private data class Values(
        val fanDirection: FanDir,
        val fanSpeed: Int?,
        val recirculation: Boolean?,
        val dual: Boolean?,
        val tempLeft: Float?,
        val tempRight: Float?,
        val defrostFront: Boolean?,
        val lightsOn: Set<Seat>,
    ) {
        companion object {
            fun of(s: CarState) = Values(
                s.fanDirection, s.fanSpeed, s.recirculation, s.dual, s.tempLeft, s.tempRight, s.defrostFront,
                s.readingLights.filterValues { it }.keys,
            )
        }
    }

    private var base: Values? = null
    private var seen: Values? = null
    private var seenSinceMs = 0L
    private var told: Values? = null
    private var toldAtMs: Long? = null
    private var lastPressMs: Long? = null

    /** What changes while the game is paused is not the child's answer: the next update starts afresh. */
    fun pause() {
        base = null
    }

    /** Called with every tick of the puzzle; returns what Lumi should say, or null. */
    fun update(s: CarState, nowMs: Long): Kind? {
        val v = Values.of(s)
        val b = base
        val prev = seen
        if (b == null || prev == null) {
            base = v
            seen = v
            seenSinceMs = nowMs
            told = null
            lastPressMs = null
            return null
        }
        if (v != prev) {
            if (puzzle is Puzzle.Together && pressed(puzzle, prev, v)) lastPressMs = nowMs
            seen = v
            seenSinceMs = nowMs
            told = null
        }
        if (puzzle.solvedBy(s)) return null
        val missed = lastPressMs?.let { nowMs - it >= missMs } == true
        val kind = when {
            missed -> Kind.BEAT
            v != told && nowMs - seenSinceMs >= settleMs && wrong(b, v) -> Kind.BUTTON
            else -> return null
        }
        if (toldAtMs?.let { nowMs - it < gapMs } == true) return null
        if (missed) lastPressMs = null
        told = v
        toldAtMs = nowMs
        return kind
    }

    /** A light of the two seats switched on, or in [Rhythm.FAN_WHILE_LIT] the fan turned up. */
    private fun pressed(p: Puzzle.Together, prev: Values, v: Values): Boolean {
        if (p.rhythm == Rhythm.FAN_WHILE_LIT) return (v.fanSpeed ?: 0) > (prev.fanSpeed ?: Int.MAX_VALUE)
        val on = v.lightsOn - prev.lightsOn
        return Seat.DRIVER in on || p.childSeat in on
    }

    private fun wrong(b: Values, v: Values): Boolean {
        val p = puzzle
        if (p is Puzzle.BeltMission) return false
        val turnedOn = v.lightsOn - b.lightsOn
        val lights = when (p) {
            is Puzzle.SeatSearch -> (turnedOn - p.seat).isNotEmpty()
            is Puzzle.Together -> (turnedOn - setOf(Seat.DRIVER, p.childSeat)).isNotEmpty()
            else -> turnedOn.isNotEmpty()
        }
        val wind = when {
            p is Puzzle.WindPath ->
                (v.fanDirection != b.fanDirection && v.fanDirection != p.direction) || farther(b.fanSpeed, v.fanSpeed, p.speed)
            p is Puzzle.Together && p.rhythm == Rhythm.FAN_WHILE_LIT -> v.fanDirection != b.fanDirection
            else -> v.fanDirection != b.fanDirection || v.fanSpeed != b.fanSpeed
        }
        val recirculation = p !is Puzzle.DustCloud && v.recirculation != b.recirculation
        val defroster = p !is Puzzle.FoggyWindow && v.defrostFront != b.defrostFront
        val rooms = if (p is Puzzle.TwoRooms) {
            // the penguin's side warmer than it was against the fox's; half way to the answer is fine
            val before = gap(p, b)
            val now = gap(p, v)
            before != null && now != null && now < before
        } else {
            v.tempLeft != b.tempLeft || v.tempRight != b.tempRight || v.dual != b.dual
        }
        return lights || wind || recirculation || defroster || rooms
    }

    /** The fan moved further from the answer's strength than it was. */
    private fun farther(before: Int?, now: Int?, answer: IntRange): Boolean {
        if (before == null || now == null || before == now) return false
        fun away(x: Int) = if (x in answer) 0 else minOf(abs(x - answer.first), abs(x - answer.last))
        return away(now) > away(before)
    }

    /** How much colder the penguin's room is than the fox's. */
    private fun gap(p: Puzzle.TwoRooms, v: Values): Float? {
        val left = v.tempLeft ?: return null
        val right = v.tempRight ?: return null
        return if (p.penguinLeft) right - left else left - right
    }
}
