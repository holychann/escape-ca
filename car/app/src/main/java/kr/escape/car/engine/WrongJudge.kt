package kr.escape.car.engine

import kotlin.math.abs

/**
 * Tells when the car shows a wrong answer to [quiz], so the friend can say so gently. Only [HoldJudge] decides a
 * solve; this reads the same car values for a change that does not lead to the answer:
 * - only a change from the car as the puzzle began counts, never what it already showed then;
 * - a value counts once it has stayed [settleMs], as long as a solve has to hold, so the steps passed on the way
 *   (the fan from 2 up to 6) do not;
 * - on the quiz's own device a value on the way from the start to the answer is not wrong (the lights switched on
 *   one at a time), only one past the answer or the other way;
 * - switching a reading light off is tidying up, never wrong;
 * - a value the car did not know yet (null, no lights reported) becoming known is not a change;
 * - a wrong state is told once while it stays, at most every [gapMs], and never over the friend's own words.
 */
class WrongJudge(
    private val quiz: Puzzle.Quiz,
    private val start: CarState,
    private val settleMs: Long = HoldJudge.HOLD_MS,
    private val gapMs: Long = GAP_MS,
) {
    enum class Kind {
        /** A device this quiz does not use: the wrong button. */
        BUTTON,
        /** The quiz's device at a wrong amount: past a number answer, or away from it. */
        AMOUNT,
        /** The quiz's device turned the other way from what the situation needs. */
        WAY,
    }

    /** The car values a quiz is answered with; [lightsOn] is null while the car has reported no reading lights. */
    private data class Values(
        val fanSpeed: Int?,
        val fanDirection: FanDir,
        val tempLeft: Float?,
        val lightsOn: Set<Seat>?,
        val seatHeat: Int?,
        val acOn: Boolean?,
        val recirculation: Boolean?,
        val defrostFront: Boolean?,
    ) {
        companion object {
            fun of(s: CarState) = Values(
                s.fanSpeed, s.fanDirection, s.tempLeft,
                s.readingLights.takeIf { it.isNotEmpty() }?.filterValues { it }?.keys,
                s.seatHeat, s.acOn, s.recirculation, s.defrostFront,
            )
        }
    }

    private val from = Values.of(start)
    private var base: Values? = from
    private var seen: Values? = null
    private var seenSinceMs = 0L
    private var told: Values? = null
    private var toldAtMs: Long? = null

    /** What changes while the game is paused is not the child's answer: the next update starts from the car then. */
    fun pause() {
        base = null
    }

    /**
     * Called with every tick of the puzzle; returns what to tell, or null. While [quiet] (the friend is still
     * speaking) nothing is told, and a wrong answer that stays is told once the friend has finished.
     */
    fun update(s: CarState, nowMs: Long, quiet: Boolean = false): Kind? {
        val v = Values.of(s)
        val b = base ?: v.also { base = it }
        if (v != seen) {
            seen = v
            seenSinceMs = nowMs
            told = null
        }
        if (quiz.solvedBy(s, start)) return null
        if (v == told || nowMs - seenSinceMs < settleMs) return null
        val kind = own(b, v) ?: if (other(b, v)) Kind.BUTTON else null
        if (kind == null || quiet || toldAtMs?.let { nowMs - it < gapMs } == true) return null
        told = v
        toldAtMs = nowMs
        return kind
    }

    /** A wrong value on the quiz's own device, changed since [b]. */
    private fun own(b: Values, v: Values): Kind? {
        val value = quiz.value
        val reason = quiz.kind == QuizKind.REASON
        return when (quiz.dial) {
            Dial.FAN -> Kind.AMOUNT.takeIf { changed(b.fanSpeed, v.fanSpeed) && !onTheWay(from.fanSpeed, value, v.fanSpeed) }
            Dial.SEAT_HEAT -> Kind.AMOUNT.takeIf { changed(b.seatHeat, v.seatHeat) && !onTheWay(from.seatHeat, value, v.seatHeat) }
            // more lights on than both the answer and the start: switching off is never wrong
            Dial.LIGHTS -> {
                val was = from.lightsOn?.size ?: return null
                val now = v.lightsOn?.size ?: return null
                Kind.AMOUNT.takeIf { b.lightsOn != v.lightsOn && now > maxOf(was, value) }
            }
            Dial.TEMP_UP, Dial.TEMP_DOWN -> {
                val was = from.tempLeft ?: return null
                val now = v.tempLeft ?: return null
                if (!changed(b.tempLeft, now)) return null
                val up = quiz.dial == Dial.TEMP_UP
                when {
                    // a situation only asks for the right way
                    reason -> Kind.WAY.takeIf { if (up) was - now > TEMP_EPS else now - was > TEMP_EPS }
                    else -> {
                        val target = quiz.tempTarget(was)
                        val ok = if (target in Puzzle.TEMP_MIN..Puzzle.TEMP_MAX) {
                            now in minOf(was, target) - TEMP_EPS..maxOf(was, target) + TEMP_EPS
                        } else {
                            // a target the car cannot reach counts either way round (Puzzle.Quiz.solvedBy)
                            abs(now - was) <= value + TEMP_EPS
                        }
                        Kind.AMOUNT.takeIf { !ok }
                    }
                }
            }
            Dial.FAN_UP -> {
                val was = from.fanSpeed ?: return null
                val now = v.fanSpeed ?: return null
                Kind.WAY.takeIf { changed(b.fanSpeed, now) && now < was }
            }
            Dial.FAN_DOWN -> {
                val was = from.fanSpeed ?: return null
                val now = v.fanSpeed ?: return null
                when {
                    !changed(b.fanSpeed, now) -> null
                    now > was -> Kind.WAY
                    // the fan switched off is its own button, and not a weaker wind
                    now < 1 -> Kind.BUTTON
                    else -> null
                }
            }
            Dial.SEAT_HEAT_OFF -> {
                val was = from.seatHeat ?: return null
                val now = v.seatHeat ?: return null
                Kind.WAY.takeIf { changed(b.seatHeat, now) && now > was }
            }
            // a front seat's light is another button; either back light is the answer
            Dial.LIGHT_CHILD -> Kind.BUTTON.takeIf { newlyOn(b, v).any { !it.rear } }
            Dial.LIGHTS_OFF -> Kind.WAY.takeIf { newlyOn(b, v).isNotEmpty() }
            // switched one way it is the answer, switched back it is the start again
            Dial.AC, Dial.RECIRC, Dial.DEFROST, Dial.SEAT_HEAT_ON -> null
        }
    }

    /** A device this quiz does not use, changed since [b]; the passenger's temperature is not read. */
    private fun other(b: Values, v: Values): Boolean {
        val d = quiz.dial.device
        return (d != Device.FAN_SPEED && changed(b.fanSpeed, v.fanSpeed)) ||
            (b.fanDirection !in UNSURE && v.fanDirection !in UNSURE && b.fanDirection != v.fanDirection) ||
            (d != Device.TEMPERATURE && changed(b.tempLeft, v.tempLeft)) ||
            (d != Device.READING_LIGHT && newlyOn(b, v).isNotEmpty()) ||
            (d != Device.SEAT_HEAT && changed(b.seatHeat, v.seatHeat)) ||
            (d != Device.AC && changed(b.acOn, v.acOn)) ||
            (d != Device.RECIRCULATION && changed(b.recirculation, v.recirculation)) ||
            (d != Device.DEFROST && changed(b.defrostFront, v.defrostFront))
    }

    private fun <T> changed(before: T?, now: T?) = before != null && now != null && before != now

    private fun newlyOn(b: Values, v: Values): Set<Seat> {
        val before = b.lightsOn ?: return emptySet()
        val now = v.lightsOn ?: return emptySet()
        return now - before
    }

    /** [now] lies between the start [was] and the [answer]: still on the way there. */
    private fun onTheWay(was: Int?, answer: Int, now: Int?): Boolean {
        if (was == null || now == null) return true
        return now in minOf(was, answer)..maxOf(was, answer)
    }

    companion object {
        /** Between two things the friend says about wrong answers, so the child is not told off again and again. */
        const val GAP_MS = 5000L
        private const val TEMP_EPS = 0.25f
        private val UNSURE = setOf(FanDir.UNKNOWN, FanDir.OTHER)
    }
}
