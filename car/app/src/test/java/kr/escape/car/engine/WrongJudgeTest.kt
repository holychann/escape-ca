package kr.escape.car.engine

import kr.escape.car.engine.WrongJudge.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrongJudgeTest {
    private val parked = CarState(
        gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet(),
        fanDirection = FanDir.FLOOR, fanSpeed = 4, recirculation = false, dual = false,
        tempLeft = 22f, tempRight = 22f, defrostFront = false,
        readingLights = Seat.entries.associateWith { false },
    )

    private fun CarState.light(seat: Seat, on: Boolean) = copy(readingLights = readingLights + (seat to on))

    /** Feeds [s] from [fromMs] to [toMs] every 100 ms, the way the game ticks; returns what was told, with when. */
    private fun WrongJudge.run(s: CarState, fromMs: Long, toMs: Long): List<Pair<Long, Kind>> =
        (fromMs..toMs step 100).mapNotNull { t -> update(s, t)?.let { t to it } }

    @Test
    fun anotherSeatsLightIsWrongOnceItHasStayedTwoSeconds() {
        val j = WrongJudge(Puzzle.SeatSearch(Seat.REAR_LEFT))
        assertEquals(emptyList<Pair<Long, Kind>>(), j.run(parked, 0, 1000))
        val wrongSeat = parked.light(Seat.DRIVER, true)
        // told once, two seconds after the change, and not again while it stays
        assertEquals(listOf(3100L to Kind.BUTTON), j.run(wrongSeat, 1100, 9000))
        // the right seat is the answer, never wrong
        assertEquals(emptyList<Pair<Long, Kind>>(), j.run(wrongSeat.light(Seat.REAR_LEFT, true), 9100, 15000))
    }

    @Test
    fun theStateThePuzzleStartsWithIsNeverWrong() {
        // recirculation, a warm room and a light all left on from before: nothing has changed since the puzzle began
        val before = parked.copy(recirculation = true, tempLeft = 26f, fanSpeed = 8).light(Seat.PASSENGER, true)
        val j = WrongJudge(Puzzle.FoggyWindow)
        assertEquals(emptyList<Pair<Long, Kind>>(), j.run(before, 0, 10000))
    }

    @Test
    fun valuesPassedOnTheWayAreNotWrong() {
        // strong wind toward the face: the fan climbs 4 to 8 a step at a time and the direction moves once
        val j = WrongJudge(Puzzle.WindPath(FanDir.FACE, 6..8))
        j.update(parked, 0)
        var t = 100L
        val told = mutableListOf<Kind>()
        for (speed in 5..8) {
            repeat(5) { j.update(parked.copy(fanSpeed = speed), t)?.let(told::add); t += 100 }
        }
        repeat(30) { j.update(parked.copy(fanSpeed = 8, fanDirection = FanDir.FACE), t)?.let(told::add); t += 100 }
        assertEquals(emptyList<Kind>(), told)
    }

    @Test
    fun windTheWrongWayIsWrong() {
        val gentleFace = Puzzle.WindPath(FanDir.FACE, 1..2)
        // the fan turned up, away from gentle
        WrongJudge(gentleFace).let { j ->
            j.update(parked, 0)
            assertEquals(listOf(Kind.BUTTON), j.run(parked.copy(fanSpeed = 7), 100, 5000).map { it.second })
        }
        // the right direction but the fan turned up: still wrong; turned down toward gentle: not
        WrongJudge(gentleFace).let { j ->
            j.update(parked, 0)
            assertEquals(emptyList<Pair<Long, Kind>>(), j.run(parked.copy(fanDirection = FanDir.FACE, fanSpeed = 3), 100, 5000))
        }
        // another direction than the answer
        WrongJudge(gentleFace).let { j ->
            j.update(parked, 0)
            assertEquals(listOf(Kind.BUTTON), j.run(parked.copy(fanDirection = FanDir.DEFROST), 100, 5000).map { it.second })
        }
    }

    @Test
    fun thePenguinsRoomGettingWarmerIsWrongButHalfWayIsNot() {
        val penguinLeft = Puzzle.TwoRooms(penguinLeft = true)
        WrongJudge(penguinLeft).let { j ->
            j.update(parked, 0)
            assertEquals(listOf(Kind.BUTTON), j.run(parked.copy(tempLeft = 24f), 100, 5000).map { it.second })
        }
        WrongJudge(penguinLeft).let { j ->
            j.update(parked, 0)
            // one degree colder is on the way to the answer
            assertEquals(emptyList<Pair<Long, Kind>>(), j.run(parked.copy(tempLeft = 21f), 100, 5000))
        }
    }

    @Test
    fun aButtonThisPuzzleDoesNotUseIsWrongButSwitchingALightOffIsNot() {
        WrongJudge(Puzzle.FoggyWindow).let { j ->
            j.update(parked, 0)
            assertEquals(listOf(Kind.BUTTON), j.run(parked.copy(recirculation = true), 100, 5000).map { it.second })
        }
        WrongJudge(Puzzle.DustCloud(fromOutside = true)).let { j ->
            val lit = parked.light(Seat.REAR_RIGHT, true)
            j.update(lit, 0)
            // tidying up a light left on from 우리 자리 조사
            assertEquals(emptyList<Pair<Long, Kind>>(), j.run(parked, 100, 5000))
        }
    }

    @Test
    fun aSecondWrongWaitsFiveSecondsAfterTheFirst() {
        val j = WrongJudge(Puzzle.FoggyWindow)
        j.update(parked, 0)
        val first = j.run(parked.copy(recirculation = true), 100, 2500)
        val second = j.run(parked.copy(recirculation = true, fanSpeed = 6), 2600, 12000)
        assertEquals(listOf(2100L to Kind.BUTTON), first)
        assertEquals(listOf(7100L to Kind.BUTTON), second)
    }

    @Test
    fun theSameWrongTwiceIsToldTwice() {
        val j = WrongJudge(Puzzle.SeatSearch(Seat.REAR_LEFT))
        j.update(parked, 0)
        val wrong = parked.light(Seat.DRIVER, true)
        assertEquals(1, j.run(wrong, 100, 3000).size)
        j.run(parked, 3100, 6000)
        assertEquals(1, j.run(wrong, 6100, 12000).size)
    }

    @Test
    fun whatChangedDuringAPauseIsNotTheChildsAnswer() {
        val j = WrongJudge(Puzzle.FoggyWindow)
        j.update(parked, 0)
        j.pause()
        assertEquals(emptyList<Pair<Long, Kind>>(), j.run(parked.copy(recirculation = true), 100, 8000))
    }

    @Test
    fun aPressTheSolveDoesNotFollowIsAMissedBeat() {
        val j = WrongJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME))
        j.update(parked, 0)
        val child = parked.light(Seat.REAR_RIGHT, true)
        // the child's light at 0.1 s, the driver's only at 3 s: a press resets the wait
        assertEquals(emptyList<Pair<Long, Kind>>(), j.run(child, 100, 2900))
        assertEquals(listOf(6500L to Kind.BEAT), j.run(child.light(Seat.DRIVER, true), 3000, 12000))
    }

    @Test
    fun anotherSeatsLightInTheTogetherPuzzleIsAWrongButton() {
        val j = WrongJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.CHILD_ONCE_PARENT_TWICE))
        j.update(parked, 0)
        assertEquals(listOf(Kind.BUTTON), j.run(parked.light(Seat.REAR_LEFT, true), 100, 5000).map { it.second })
    }

    @Test
    fun theFanAloneInFanWhileLitIsAMissedBeatNotAWrongButton() {
        val j = WrongJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.FAN_WHILE_LIT))
        j.update(parked, 0)
        assertEquals(listOf(3600L to Kind.BEAT), j.run(parked.copy(fanSpeed = 6), 100, 8000))
    }

    @Test
    fun beltMissionHasNoWrongAnswer() {
        val j = WrongJudge(Puzzle.BeltMission)
        j.update(parked, 0)
        assertNull(j.run(parked.copy(recirculation = true, fanSpeed = 8), 100, 8000).firstOrNull())
    }
}
