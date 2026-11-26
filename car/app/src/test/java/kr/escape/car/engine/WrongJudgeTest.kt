package kr.escape.car.engine

import kr.escape.car.engine.WrongJudge.Kind
import kr.escape.car.story.Lines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrongJudgeTest {
    private val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet())

    /** Every climate value known: the fan at 3, 21 degrees, everything else off. */
    private val known = parked.copy(
        fanSpeed = 3, fanDirection = FanDir.FACE, tempLeft = 21f, acOn = false, recirculation = false,
        defrostFront = false, seatHeat = 0, readingLights = Seat.entries.associateWith { false },
    )

    private val settle = HoldJudge.HOLD_MS

    private fun number(dial: Dial, value: Int) = Puzzle.Quiz(QuizKind.MATH, dial, value)
    private fun reason(dial: Dial, value: Int = 1) = Puzzle.Quiz(QuizKind.REASON, dial, value)
    private fun lit(vararg seats: Seat) = known.copy(readingLights = Seat.entries.associateWith { it in seats })

    /** What a fresh judge of [q] says once the car has shown [s] long enough. */
    private fun wrongAt(q: Puzzle.Quiz, s: CarState, start: CarState = known): Kind? {
        val j = WrongJudge(q, start)
        assertNull(j.update(s, 0))
        return j.update(s, settle)
    }

    @Test
    fun aButtonTheQuizDoesNotUseIsWrongOnceItHasStayed() {
        val j = WrongJudge(number(Dial.FAN, 5), known)
        val ac = known.copy(acOn = true)
        assertNull(j.update(ac, 0))
        // a moment is not an answer yet
        assertNull(j.update(ac, settle - 1))
        assertEquals(Kind.BUTTON, j.update(ac, settle))
        // told once while it stays
        assertNull(j.update(ac, 20_000))
        for (s in listOf(
            known.copy(recirculation = true), known.copy(defrostFront = true), known.copy(seatHeat = 1),
            known.copy(tempLeft = 22f), known.copy(fanDirection = FanDir.FLOOR), lit(Seat.DRIVER),
        )) assertEquals(s.toString(), Kind.BUTTON, wrongAt(number(Dial.FAN, 5), s))
        // the passenger's temperature is not read
        assertNull(wrongAt(number(Dial.FAN, 5), known.copy(tempRight = 25f)))
    }

    @Test
    fun whatTheCarShowedAtTheStartIsNeverWrong() {
        val start = known.copy(acOn = true, recirculation = true, seatHeat = 2, readingLights = lit(Seat.DRIVER).readingLights)
        assertNull(wrongAt(number(Dial.FAN, 5), start, start))
        // values the car had not told yet coming in are not a change
        assertNull(wrongAt(number(Dial.FAN, 5), known.copy(fanSpeed = 4), parked))
        assertNull(wrongAt(reason(Dial.AC), known.copy(fanDirection = FanDir.FLOOR), known.copy(fanDirection = FanDir.UNKNOWN)))
    }

    @Test
    fun aNumberOnTheWayIsNotWrongButOnePastItIs() {
        val fan = number(Dial.FAN, 5)
        assertNull(wrongAt(fan, known.copy(fanSpeed = 4)))
        assertNull(wrongAt(fan, known.copy(fanSpeed = 5)))
        assertEquals(Kind.AMOUNT, wrongAt(fan, known.copy(fanSpeed = 6)))
        assertEquals(Kind.AMOUNT, wrongAt(fan, known.copy(fanSpeed = 2)))
        val heat = number(Dial.SEAT_HEAT, 2)
        assertNull(wrongAt(heat, known.copy(seatHeat = 1)))
        assertEquals(Kind.AMOUNT, wrongAt(heat, known.copy(seatHeat = 3)))
        // the steps passed on the way to a wrong amount do not count, the amount it stays at does
        val j = WrongJudge(fan, known)
        for ((t, speed) in listOf(0L to 4, 500L to 5, 1000L to 6, 1500L to 7)) assertNull(j.update(known.copy(fanSpeed = speed), t))
        assertNull(j.update(known.copy(fanSpeed = 7), 1500 + settle - 1))
        assertEquals(Kind.AMOUNT, j.update(known.copy(fanSpeed = 7), 1500 + settle))
    }

    @Test
    fun lightsSwitchedOnOneAtATimeAreOnTheWayAndOffIsNeverWrong() {
        val three = number(Dial.LIGHTS, 3)
        assertNull(wrongAt(three, lit(Seat.REAR_RIGHT)))
        assertNull(wrongAt(three, lit(Seat.REAR_RIGHT, Seat.DRIVER)))
        assertEquals(Kind.AMOUNT, wrongAt(three, lit(*Seat.entries.toTypedArray())))
        // one light to go from two: off is fine, another on is too many
        val start = lit(Seat.DRIVER, Seat.PASSENGER)
        val one = number(Dial.LIGHTS, 1)
        assertNull(wrongAt(one, lit(), start))
        assertEquals(Kind.AMOUNT, wrongAt(one, lit(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT), start))
        // in any other quiz a light switched off is tidying up, one switched on another button
        assertNull(wrongAt(reason(Dial.AC), lit(), lit(Seat.DRIVER)))
        assertEquals(Kind.BUTTON, wrongAt(reason(Dial.AC), lit(Seat.DRIVER, Seat.REAR_LEFT), lit(Seat.DRIVER)))
    }

    @Test
    fun aTemperatureNumberCountsTheWayFromTheStart() {
        // 21 down by 2 is 19
        val down = number(Dial.TEMP_DOWN, 2)
        assertNull(wrongAt(down, known.copy(tempLeft = 20f)))
        assertNull(wrongAt(down, known.copy(tempLeft = 19f)))
        assertEquals(Kind.AMOUNT, wrongAt(down, known.copy(tempLeft = 18f)))
        assertEquals(Kind.AMOUNT, wrongAt(down, known.copy(tempLeft = 21.5f)))
        // 17 down by 2 is below what the car can set, so the answer counts either way round
        val cold = known.copy(tempLeft = 17f)
        assertNull(wrongAt(down, cold.copy(tempLeft = 18f), cold))
        assertNull(wrongAt(down, cold.copy(tempLeft = 19f), cold))
        assertEquals(Kind.AMOUNT, wrongAt(down, cold.copy(tempLeft = 19.5f), cold))
    }

    @Test
    fun aSituationTurnedTheOtherWayIsWrong() {
        assertEquals(Kind.WAY, wrongAt(reason(Dial.TEMP_UP), known.copy(tempLeft = 20f)))
        assertNull(wrongAt(reason(Dial.TEMP_UP), known.copy(tempLeft = 23f)))
        assertEquals(Kind.WAY, wrongAt(reason(Dial.TEMP_DOWN), known.copy(tempLeft = 22f)))
        assertEquals(Kind.WAY, wrongAt(reason(Dial.FAN_UP), known.copy(fanSpeed = 2)))
        assertEquals(Kind.WAY, wrongAt(reason(Dial.FAN_DOWN), known.copy(fanSpeed = 4)))
        assertNull(wrongAt(reason(Dial.FAN_DOWN), known.copy(fanSpeed = 1)))
        // the fan switched off is the off button, not a weaker wind
        assertEquals(Kind.BUTTON, wrongAt(reason(Dial.FAN_DOWN), known.copy(fanSpeed = 0)))
        val warm = known.copy(seatHeat = 2)
        assertEquals(Kind.WAY, wrongAt(reason(Dial.SEAT_HEAT_OFF, 0), warm.copy(seatHeat = 3), warm))
        assertNull(wrongAt(reason(Dial.SEAT_HEAT_OFF, 0), warm.copy(seatHeat = 1), warm))
        assertEquals(Kind.WAY, wrongAt(reason(Dial.LIGHTS_OFF, 0), lit(Seat.DRIVER, Seat.REAR_LEFT), lit(Seat.DRIVER)))
        // a front seat's light is another button; either back-seat light is the answer
        assertEquals(Kind.BUTTON, wrongAt(reason(Dial.LIGHT_CHILD), lit(Seat.DRIVER)))
        assertEquals(Kind.BUTTON, wrongAt(reason(Dial.LIGHT_CHILD), lit(Seat.PASSENGER)))
        assertNull(wrongAt(reason(Dial.LIGHT_CHILD), lit(Seat.REAR_LEFT)))
        assertNull(wrongAt(reason(Dial.LIGHT_CHILD), lit(Seat.REAR_RIGHT)))
        // a function that only goes on and off is either the answer or back at the start
        assertNull(wrongAt(reason(Dial.AC), known.copy(acOn = true)))
        assertEquals(Kind.BUTTON, wrongAt(reason(Dial.AC), known.copy(recirculation = true)))
        assertNull(wrongAt(reason(Dial.SEAT_HEAT_ON), known.copy(seatHeat = 3)))
    }

    @Test
    fun theSolvedAnswerIsNeverWrongWhateverElseTheCarDidOnTheWay() {
        // a defroster that also turns the air conditioner on is still the answer
        assertNull(wrongAt(reason(Dial.DEFROST), known.copy(defrostFront = true, acOn = true, fanDirection = FanDir.DEFROST)))
    }

    @Test
    fun anotherWrongAnswerIsToldButNotTooOften() {
        val j = WrongJudge(number(Dial.FAN, 5), known)
        val ac = known.copy(acOn = true)
        val both = ac.copy(recirculation = true)
        j.update(ac, 0)
        assertEquals(Kind.BUTTON, j.update(ac, settle))
        j.update(both, settle + 500)
        // settled again, but too soon after the last one
        assertNull(j.update(both, settle + 500 + settle))
        assertEquals(Kind.BUTTON, j.update(both, settle + WrongJudge.GAP_MS))
        // back as it began: nothing wrong
        j.update(known, 20_000)
        assertNull(j.update(known, 30_000))
    }

    @Test
    fun nothingIsToldOverTheFriendsWords() {
        val j = WrongJudge(number(Dial.FAN, 5), known)
        val ac = known.copy(acOn = true)
        assertNull(j.update(ac, 0, quiet = true))
        assertNull(j.update(ac, settle, quiet = true))
        // once the friend has finished, the wrong answer still there is told
        assertEquals(Kind.BUTTON, j.update(ac, settle + 3000))
    }

    @Test
    fun whatChangesDuringAPauseIsNotTheChildsAnswer() {
        val j = WrongJudge(number(Dial.FAN, 5), known)
        j.update(known, 0)
        j.pause()
        val ac = known.copy(acOn = true)
        assertNull(j.update(ac, 1000))
        assertNull(j.update(ac, 1000 + settle))
        // a change after the pause counts again
        val both = ac.copy(recirculation = true)
        j.update(both, 5000)
        assertEquals(Kind.BUTTON, j.update(both, 5000 + settle))
    }

    @Test
    fun theWrongLinesAreGentleTakeTurnsAndGiveNothingAway() {
        val never = listOf("출발", "시동", "운전", "기어", "핸들", "브레이크", "차 문", "창문")
        for (kind in Kind.entries) {
            val lines = (0 until 6).map { Lines.wrong(kind, it) }
            assertTrue(kind.name, lines.toSet().size >= 2)
            assertEquals(lines[0], Lines.wrong(kind, lines.toSet().size))
            for (line in lines) {
                assertFalse(line, line.any { it.isDigit() })
                assertTrue(line, never.none { it in line })
            }
        }
    }
}
