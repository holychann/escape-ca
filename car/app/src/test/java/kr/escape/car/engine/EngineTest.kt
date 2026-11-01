package kr.escape.car.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EngineTest {
    private val allDevices = Device.entries.toSet()
    private val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = allDevices)

    @Test
    fun startNeedsParkClosedDoorsAndDriver() {
        assertEquals(emptyList<StopReason>(), Safety.startBlockers(parked))
        assertEquals(listOf(StopReason.DRIVING), Safety.startBlockers(parked.copy(gear = Gear.DRIVE)))
        assertEquals(listOf(StopReason.DRIVING), Safety.startBlockers(parked.copy(uxRestricted = true)))
        assertEquals(listOf(StopReason.NO_DRIVER), Safety.startBlockers(parked.copy(driverSeated = null)))
        assertEquals(listOf(StopReason.DOOR_OPEN), Safety.startBlockers(parked.copy(doorOpen = true)))
    }

    @Test
    fun pausePrefersDrivingThenDoorThenTime() {
        assertNull(Safety.pauseReason(parked, 0))
        assertEquals(StopReason.DRIVING, Safety.pauseReason(parked.copy(gear = Gear.DRIVE, doorOpen = true), 0))
        assertEquals(StopReason.DOOR_OPEN, Safety.pauseReason(parked.copy(doorOpen = true), 0))
        assertEquals(StopReason.TIME_UP, Safety.pauseReason(parked, Safety.MAX_PLAY_MS))
        // a driver getting out is a door first; the seat alone does not stop a running game
        assertNull(Safety.pauseReason(parked.copy(driverSeated = false), 0))
    }

    @Test
    fun recirculationWarningAfterThreeMinutes() {
        val on = parked.copy(recirculation = true)
        assertFalse(Safety.recirculationTooLong(on, 0, Safety.RECIRCULATION_LIMIT_MS - 1))
        assertTrue(Safety.recirculationTooLong(on, 0, Safety.RECIRCULATION_LIMIT_MS))
        assertFalse(Safety.recirculationTooLong(on.copy(recirculation = false), 0, Safety.RECIRCULATION_LIMIT_MS))
    }

    @Test
    fun windPathNeedsDirectionAndStrength() {
        val p = Puzzle.WindPath(FanDir.FLOOR, 1..2)
        assertTrue(p.solvedBy(parked.copy(fanDirection = FanDir.FLOOR, fanSpeed = 2)))
        assertFalse(p.solvedBy(parked.copy(fanDirection = FanDir.FLOOR, fanSpeed = 3)))
        assertFalse(p.solvedBy(parked.copy(fanDirection = FanDir.FACE, fanSpeed = 1)))
        assertFalse(p.solvedBy(parked.copy(fanDirection = FanDir.FLOOR, fanSpeed = null)))
    }

    @Test
    fun dustAndRoomsAndWindowAndSeat() {
        assertTrue(Puzzle.DustCloud(fromOutside = true).solvedBy(parked.copy(recirculation = true)))
        assertTrue(Puzzle.DustCloud(fromOutside = false).solvedBy(parked.copy(recirculation = false)))
        assertFalse(Puzzle.DustCloud(fromOutside = false).solvedBy(parked))

        val rooms = Puzzle.TwoRooms(penguinLeft = true)
        assertTrue(rooms.solvedBy(parked.copy(dual = true, tempLeft = 18f, tempRight = 26f)))
        assertFalse(rooms.solvedBy(parked.copy(dual = true, tempLeft = 26f, tempRight = 18f)))
        assertFalse(rooms.solvedBy(parked.copy(dual = false, tempLeft = 18f, tempRight = 26f)))
        assertFalse(rooms.solvedBy(parked.copy(dual = true, tempLeft = 21f, tempRight = 22f)))

        assertTrue(Puzzle.FoggyWindow.solvedBy(parked.copy(defrostFront = true)))
        assertTrue(Puzzle.SeatSearch(Seat.REAR_LEFT).solvedBy(parked.copy(readingLights = mapOf(Seat.REAR_LEFT to true))))
        assertFalse(Puzzle.SeatSearch(Seat.REAR_LEFT).solvedBy(parked.copy(readingLights = mapOf(Seat.REAR_RIGHT to true))))
    }

    @Test
    fun holdJudgeWaitsOneSecond() {
        val judge = HoldJudge(Puzzle.FoggyWindow)
        val on = parked.copy(defrostFront = true)
        assertFalse(judge.update(parked, 0))
        assertFalse(judge.update(on, 100))
        assertFalse(judge.update(on, 1000))
        assertTrue(judge.update(on, 1100))
        assertFalse(judge.update(parked, 1200))
        assertFalse(judge.update(on, 1300))
    }

    @Test
    fun holdJudgeIgnoresAnAnswerAlreadyShowing() {
        val judge = HoldJudge(Puzzle.FoggyWindow)
        val on = parked.copy(defrostFront = true)
        assertFalse(judge.update(on, 0))
        assertFalse(judge.update(on, 5000))
    }

    @Test
    fun refreshMovesAnswersAwayFromTheCurrentState() {
        val lit = parked.copy(readingLights = mapOf(Seat.REAR_LEFT to true), recirculation = true)
        val seat = Planner.refresh(Puzzle.SeatSearch(Seat.REAR_LEFT), lit, Random(3)) as Puzzle.SeatSearch
        assertTrue(seat.seat != Seat.REAR_LEFT)
        assertEquals(Puzzle.DustCloud(fromOutside = false), Planner.refresh(Puzzle.DustCloud(fromOutside = true), lit))
        assertEquals(Puzzle.DustCloud(fromOutside = false), Planner.refresh(Puzzle.DustCloud(fromOutside = false), lit))
    }

    private fun lights(driver: Boolean, child: Boolean) =
        parked.copy(readingLights = mapOf(Seat.DRIVER to driver, Seat.REAR_RIGHT to child))

    @Test
    fun togetherSameTimeWithinWindow() {
        val judge = TogetherJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME, childReachesLight = true))
        assertFalse(judge.update(lights(false, false), 0))
        assertFalse(judge.update(lights(false, true), 100))
        assertTrue(judge.update(lights(true, true), 1200))
    }

    @Test
    fun togetherTooFarApartFails() {
        val judge = TogetherJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME, childReachesLight = true))
        judge.update(lights(false, false), 0)
        judge.update(lights(false, true), 100)
        assertFalse(judge.update(lights(true, true), 3000))
    }

    @Test
    fun togetherIgnoresLightsAlreadyOn() {
        val judge = TogetherJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME, childReachesLight = true))
        assertFalse(judge.update(lights(true, true), 0))
        assertFalse(judge.update(lights(true, true), 500))
    }

    @Test
    fun togetherChildOnceParentTwice() {
        val judge = TogetherJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.CHILD_ONCE_PARENT_TWICE, childReachesLight = true))
        judge.update(lights(false, false), 0)
        assertFalse(judge.update(lights(false, true), 100))
        assertFalse(judge.update(lights(true, true), 900))
        assertFalse(judge.update(lights(false, true), 1500))
        assertTrue(judge.update(lights(true, true), 2200))
    }

    @Test
    fun togetherFanWhileChildLit() {
        val judge = TogetherJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.FAN_WHILE_LIT, childReachesLight = true))
        judge.update(lights(false, false).copy(fanSpeed = 2), 0)
        assertFalse(judge.update(lights(false, false).copy(fanSpeed = 3), 100))
        assertFalse(judge.update(lights(false, true).copy(fanSpeed = 3), 200))
        assertTrue(judge.update(lights(false, true).copy(fanSpeed = 4), 300))
    }

    @Test
    fun timingGameWhenChildCannotReach() {
        val judge = TogetherJudge(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME, childReachesLight = false))
        judge.update(lights(false, false), 0)
        assertTrue(judge.update(lights(true, false), 400))
    }

    @Test
    fun planUsesOnlyDevicesTheCarHasAndPutsBeltMissionThird() {
        val noDual = parked.copy(devices = allDevices - Device.DUAL_TEMPERATURE)
        repeat(50) { seed ->
            val plan = Planner.plan(Setup(minutes = 15), noDual, Random(seed))
            assertEquals(5, plan.size)
            assertEquals(Puzzle.BeltMission, plan[2])
            assertTrue(plan.none { it is Puzzle.TwoRooms })
        }
        assertEquals(2, Planner.plan(Setup(minutes = 5), parked, Random(1)).size)
        assertTrue(Planner.plan(Setup(), parked.copy(devices = emptySet()), Random(1)).isEmpty())
    }

    @Test
    fun windAnswersNeverOverlap() {
        repeat(50) { seed ->
            val p = Planner.plan(Setup(minutes = 15), parked.copy(devices = setOf(Device.FAN_DIRECTION, Device.FAN_SPEED)), Random(seed))
            val wind = p.single() as Puzzle.WindPath
            assertTrue(wind.speed == 1..2 || wind.speed == 6..8)
        }
    }

    @Test
    fun snapshotListsWhatChanged() {
        val before = parked.copy(recirculation = false, fanSpeed = 3, readingLights = mapOf(Seat.DRIVER to false))
        val snap = Snapshot.of(before)
        assertEquals(emptyList<String>(), snap.changes(before))
        val after = before.copy(recirculation = true, fanSpeed = 1, readingLights = mapOf(Seat.DRIVER to true))
        assertEquals(listOf("외기 유입", "풍량 3단", "운전석 독서등 끄기"), snap.changes(after))
    }
}
