package kr.escape.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.Dial
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import kr.escape.car.engine.Snapshot
import kr.escape.car.story.Lines
import kr.escape.car.story.Progress
import kr.escape.car.story.ProgressStore
import kr.escape.car.voice.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class GameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Lumi without the car's speakers: what she said, in order. */
    private class FakeVoice : Voice {
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        val said = mutableListOf<String>()
        private var last: String? = null
        override fun say(text: String) {
            said += text
            last = text
        }
        override fun aside(text: String) {
            said += text
        }
        override fun repeat() {
            last?.let { said += it }
        }
        override fun stop() {}
    }

    /** Parked with a parent in the driver seat, every climate value known: the fan at 3, 21 degrees, everything else off. */
    private val known = CarState(
        gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet(),
        fanSpeed = 3, tempLeft = 21f, acOn = false, recirculation = false, defrostFront = false, seatHeat = 0,
        readingLights = Seat.entries.associateWith { false },
    )
    private val car = MutableStateFlow(known)
    private val voice = FakeVoice()
    private var now = 1_000_000_000L
    private val store by lazy { ProgressStore(tmp.root.resolve("playing_episode.json")) { now } }

    private fun game() = Game(car, voice, book = FriendBook(tmp.root.resolve("book")), progress = store, rnd = Random(0))

    private val quiz = Planner.plan(Setup(), known, Random(0)).first { it is Puzzle.Quiz }
    /** The front defroster switched on; the car above has it off, so it can be asked. */
    private val defrost = Puzzle.Quiz(QuizKind.REASON, Dial.DEFROST, 1)

    private fun saved(puzzles: List<Puzzle>, next: Int, stars: Int = next) =
        store.write(Progress(Setup(), puzzles, next, stars, own = emptyMap(), story = null))

    @Test
    fun beginSavesTheEpisodeFromItsFirstPuzzle() {
        val g = game()
        g.begin()
        val s = store.read()!!
        assertEquals(0, s.next)
        assertEquals(0, s.stars)
        assertEquals(g.ui.value.puzzles, s.puzzles)
        assertEquals(g.ui.value.setup, s.setup)
    }

    @Test
    fun startingAPuzzleSavesWhereToComeBack() {
        val g = game()
        g.begin()
        g.startPuzzle(1)
        assertEquals(1, store.read()!!.next)
    }

    @Test
    fun aSavedEpisodeContinuesAtItsNextPuzzleWithItsStars() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 1, stars = 1)
        car.value = known.copy(fanSpeed = 6)
        val g = game()
        assertNotNull(g.saved.value)
        assertTrue(g.canContinue(car.value))
        g.continueSaved()
        val ui = g.ui.value
        assertEquals(Phase.PLAYING, ui.phase)
        assertEquals(Puzzle.BeltMission, ui.puzzle)
        assertEquals(1, ui.stars)
        assertEquals(0L, ui.playedMs)
        // the climate is put back as it was when they went on, not as it was before the car went off
        assertEquals(Snapshot.of(car.value), ui.snapshot)
        assertTrue(voice.said.last().startsWith(Lines.WELCOME_BACK))
    }

    @Test
    fun solvingAContinuedPuzzleSavesTheNextOneAndTheStar() {
        saved(listOf(Puzzle.BeltMission, quiz), next = 0)
        val g = game()
        g.continueSaved()
        g.confirmBelt()
        val s = store.read()!!
        assertEquals(1, s.next)
        assertEquals(1, s.stars)
    }

    @Test
    fun aSavedEpisodeWaitsForASafeCarAndItsDevices() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        assertFalse(g.canContinue(known.copy(gear = Gear.DRIVE)))
        // right after the car is switched on it has not reported anything yet
        assertFalse(g.canContinue(known.copy(gear = Gear.UNKNOWN)))
        assertFalse(g.canContinue(known.copy(devices = emptySet())))

        saved(listOf(quiz, Puzzle.BeltMission), next = 1)
        // only the belt is left, which reads no device
        assertTrue(game().canContinue(known.copy(devices = emptySet())))
    }

    @Test
    fun aContinuedPuzzleTheCarAlreadyAnswersIsSwappedAndSaved() {
        saved(listOf(defrost, Puzzle.BeltMission), next = 0)
        // the parent switched the defroster on before the car went off
        car.value = known.copy(defrostFront = true)
        val g = game()
        g.continueSaved()
        assertFalse(defrost in g.ui.value.puzzles)
        assertEquals(g.ui.value.puzzles, store.read()!!.puzzles)
    }

    @Test
    fun aSaveWithOnlyTheTreasureLeftOpensItAndIsCleared() {
        saved(listOf(Puzzle.BeltMission), next = 1)
        val g = game()
        g.continueSaved()
        assertEquals(Phase.TREASURE, g.ui.value.phase)
        assertNull(store.read())
        assertNull(g.saved.value)
    }

    @Test
    fun endingTheGameForgetsTheSave() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        g.continueSaved()
        g.hold()
        g.finish()
        assertNull(store.read())
        assertNull(g.saved.value)
    }

    @Test
    fun startingOverForgetsTheSave() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        g.startOver()
        assertNull(store.read())
        assertNull(g.saved.value)
    }
}
