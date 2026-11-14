package kr.escape.car.engine

import kotlin.math.abs
import kotlin.random.Random

enum class Age(val label: String, val years: String) {
    LITTLE("꼬마 탐험대", "4~6세"), CAPTAIN("탐험대장", "7~9세"), DETECTIVE("수석 탐정", "10~12세")
}

/** The rhythm of the "하나 둘 셋, 동시에!" puzzle. */
enum class Rhythm(val label: String) {
    SAME_TIME("동시에 켜기"),
    CHILD_ONCE_PARENT_TWICE("아이 한 번, 부모 두 번"),
    FAN_WHILE_LIT("아이 불 켜진 동안 부모가 바람 올리기"),
}

/**
 * A puzzle with its answer already chosen by the rule engine. The story only dresses it up; it can never move
 * the answer. [solvedBy] reads the car state and nothing else.
 */
sealed class Puzzle(val title: String, val devices: Set<Device>) {
    open fun solvedBy(s: CarState): Boolean = false

    /** ① 바람길 열기: wind toward the face or the feet, at a strength inside [speed]. */
    data class WindPath(val direction: FanDir, val speed: IntRange) :
        Puzzle("바람길 열기", setOf(Device.FAN_DIRECTION, Device.FAN_SPEED)) {
        override fun solvedBy(s: CarState) = s.fanDirection == direction && s.fanSpeed != null && s.fanSpeed in speed
    }

    /** ② 먼지 구름 피하기: dust from outside wants recirculation, steam from inside wants fresh air. */
    data class DustCloud(val fromOutside: Boolean) : Puzzle("먼지 구름 피하기", setOf(Device.RECIRCULATION)) {
        override fun solvedBy(s: CarState) = s.recirculation == fromOutside
    }

    /** ③ 두 방 온도 맞추기: the penguin's side colder than the fox's side by at least [GAP] degrees. */
    data class TwoRooms(val penguinLeft: Boolean) : Puzzle("두 방 온도 맞추기", setOf(Device.DUAL_TEMPERATURE)) {
        override fun solvedBy(s: CarState): Boolean {
            val left = s.tempLeft ?: return false
            val right = s.tempRight ?: return false
            val (penguin, fox) = if (penguinLeft) left to right else right to left
            return fox - penguin >= GAP
        }
    }

    /** ④ 김 서린 창 닦기: the front defroster clears the fog and shows the hidden clue. */
    data object FoggyWindow : Puzzle("김 서린 창 닦기", setOf(Device.DEFROSTER)) {
        override fun solvedBy(s: CarState) = s.defrostFront == true
    }

    /** ⑤ 우리 자리 조사: the reading light of the seat Lumi hid the clue in. */
    data class SeatSearch(val seat: Seat) : Puzzle("우리 자리 조사", setOf(Device.READING_LIGHT)) {
        override fun solvedBy(s: CarState) = s.readingLights[seat] == true
    }

    /** ⑥ 하나 둘 셋, 동시에!: judged on light events over time by [TogetherJudge], not on one snapshot. */
    data class Together(val childSeat: Seat, val rhythm: Rhythm) :
        Puzzle("하나 둘 셋, 동시에!", setOf(Device.READING_LIGHT))

    /** 벨트 딸깍 미션: no car signal at all, the parent confirms with a button. */
    data object BeltMission : Puzzle("벨트 딸깍 미션", emptySet())

    companion object {
        const val GAP = 2f
    }
}

/** What the parent chose on E1. */
data class Setup(
    val ages: List<Age> = listOf(Age.CAPTAIN),
    val minutes: Int = 10,
    val giftReady: Boolean = false,
    val childSeat: Seat = Seat.REAR_RIGHT,
)

/** Picks this episode's puzzles and their answers at random, only from devices this car has. */
object Planner {
    fun puzzleCount(minutes: Int) = when {
        minutes <= 5 -> 2
        minutes <= 10 -> 3
        else -> 4
    }

    fun plan(setup: Setup, s: CarState, rnd: Random = Random.Default): List<Puzzle> {
        val candidates = listOf(
            windPath(s.fanSpeedRange, rnd),
            Puzzle.DustCloud(fromOutside = rnd.nextBoolean()),
            Puzzle.TwoRooms(penguinLeft = rnd.nextBoolean()),
            Puzzle.FoggyWindow,
            Puzzle.SeatSearch(Seat.entries.random(rnd)),
            Puzzle.Together(setup.childSeat, Rhythm.entries.random(rnd)),
        ).filter { s.devices.containsAll(it.devices) }.shuffled(rnd)
        val picks = candidates.take(puzzleCount(setup.minutes)).toMutableList()
        // the belt mission sits after the second puzzle, as a short break between car puzzles
        if (picks.size >= 3) picks.add(2, Puzzle.BeltMission)
        return picks
    }

    /**
     * Called when a puzzle starts: an answer the car already shows (a light left on, recirculation already on)
     * would be solved before the child says a word, so it is drawn again away from the current state.
     */
    fun refresh(p: Puzzle, s: CarState, rnd: Random = Random.Default): Puzzle {
        if (!p.solvedBy(s)) return p
        return when (p) {
            is Puzzle.WindPath -> {
                val other = if (p.direction == FanDir.FACE) FanDir.FLOOR else FanDir.FACE
                p.copy(direction = other)
            }
            is Puzzle.DustCloud -> p.copy(fromOutside = !p.fromOutside)
            is Puzzle.TwoRooms -> p.copy(penguinLeft = !p.penguinLeft)
            is Puzzle.SeatSearch -> {
                val dark = Seat.entries.filter { s.readingLights[it] != true }
                if (dark.isEmpty()) p else p.copy(seat = dark.random(rnd))
            }
            else -> p
        }
    }

    /** Gentle is the lowest two steps, strong the highest three, so the two answers never touch. */
    fun windSpeed(range: IntRange, gentle: Boolean): IntRange =
        if (gentle) range.first..(range.first + 1) else (range.last - 2)..range.last

    private fun windPath(range: IntRange, rnd: Random): Puzzle.WindPath {
        val direction = if (rnd.nextBoolean()) FanDir.FACE else FanDir.FLOOR
        return Puzzle.WindPath(direction, windSpeed(range, gentle = rnd.nextBoolean()))
    }
}

/**
 * Solved only when the parent has changed the car into the answer and it has held for [holdMs]: a state that was
 * already the answer when the puzzle began, or a value passed through on the way to another one, does not count.
 */
class HoldJudge(private val puzzle: Puzzle, private val holdMs: Long = 1000) {
    private var since: Long? = null
    private var armed = false

    fun update(s: CarState, nowMs: Long): Boolean {
        if (!puzzle.solvedBy(s)) {
            armed = true
            since = null
            return false
        }
        if (!armed) return false
        val start = since ?: nowMs.also { since = it }
        return nowMs - start >= holdMs
    }

    /**
     * The answer was already showing when the puzzle began (a defroster left on, every reading light lit), so it
     * cannot count until it is undone once. The parent is told, or the puzzle looks stuck.
     */
    fun resetNeeded(s: CarState): String? =
        if (!armed && puzzle.solvedBy(s)) "이미 정답 상태예요. 한 번 끈 다음, 아이가 가리키면 다시 켜 주세요" else null
}

/**
 * ⑥ 하나 둘 셋, 동시에!. Watches when the driver's and the child's reading lights come on.
 */
class TogetherJudge(private val puzzle: Puzzle.Together, private val windowMs: Long = 1500) {
    private var lastLights: Map<Seat, Boolean>? = null
    private var lastFan: Int? = null
    private val ons = ArrayDeque<Pair<Seat, Long>>()

    /**
     * The two seats whose light was already on when the puzzle began (often left on by 우리 자리 조사). Only a light
     * switched on counts, so each must go off once; until then the parent is asked to turn it off.
     */
    private val stale = mutableSetOf<Seat>()

    fun update(s: CarState, nowMs: Long): Boolean {
        // the first snapshot only sets the baseline: a light already on is not a press
        if (lastLights == null) {
            lastLights = s.readingLights
            lastFan = s.fanSpeed
            stale += listOf(Seat.DRIVER, puzzle.childSeat).filter { s.readingLights[it] == true }
            return false
        }
        stale.removeAll { s.readingLights[it] != true }
        val solved = when {
            puzzle.rhythm == Rhythm.SAME_TIME -> sameTime(s, nowMs)
            puzzle.rhythm == Rhythm.CHILD_ONCE_PARENT_TWICE -> sequence(s, nowMs)
            else -> fanWhileLit(s)
        }
        lastLights = s.readingLights
        lastFan = s.fanSpeed
        return solved
    }

    private fun turnedOn(s: CarState, seat: Seat) = s.readingLights[seat] == true && lastLights?.get(seat) != true

    private fun record(s: CarState, nowMs: Long) {
        for (seat in listOf(Seat.DRIVER, puzzle.childSeat)) if (turnedOn(s, seat)) ons.addLast(seat to nowMs)
        while (ons.size > 6) ons.removeFirst()
    }

    private fun sameTime(s: CarState, nowMs: Long): Boolean {
        record(s, nowMs)
        val driver = ons.lastOrNull { it.first == Seat.DRIVER }?.second ?: return false
        val child = ons.lastOrNull { it.first == puzzle.childSeat }?.second ?: return false
        return abs(driver - child) <= windowMs
    }

    /** Child on, then the driver on twice, each step within twice the window of the one before. */
    private fun sequence(s: CarState, nowMs: Long): Boolean {
        record(s, nowMs)
        val last3 = ons.takeLast(3)
        if (last3.size < 3) return false
        val pattern = last3.map { it.first } == listOf(puzzle.childSeat, Seat.DRIVER, Seat.DRIVER)
        val paced = last3.zipWithNext().all { (a, b) -> b.second - a.second <= windowMs * 2 }
        return pattern && paced
    }

    private fun fanWhileLit(s: CarState): Boolean {
        val before = lastFan ?: return false
        val now = s.fanSpeed ?: return false
        // a light left on from before is not the child's turn: without this the parent solves it alone
        return puzzle.childSeat !in stale && s.readingLights[puzzle.childSeat] == true && now > before
    }

    fun resetNeeded(): String? =
        if (stale.isEmpty()) null else "먼저 ${stale.joinToString(", ") { it.label }} 독서등을 꺼 주세요"
}
