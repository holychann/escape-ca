package kr.escape.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Friend
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.HoldJudge
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Safety
import kr.escape.car.engine.Setup
import kr.escape.car.engine.Snapshot
import kr.escape.car.engine.StopReason
import kr.escape.car.engine.TogetherJudge
import kr.escape.car.story.Lines
import kr.escape.car.story.Scene
import kr.escape.car.story.Story
import kr.escape.car.story.StoryRepo
import kr.escape.car.voice.Lumi

enum class Phase { SETUP, INTRO, PLAYING, SOLVED, TREASURE, RESTORE }

data class GameUi(
    val phase: Phase = Phase.SETUP,
    val setup: Setup = Setup(),
    val puzzles: List<Puzzle> = emptyList(),
    val index: Int = 0,
    val stars: Int = 0,
    val playedMs: Long = 0,
    /** Stopped by the car (driving, a door, time). The game cannot go on until it clears. */
    val stopped: StopReason? = null,
    /** Stopped with the parent's 멈춤 button. */
    val held: Boolean = false,
    val hintHeard: Boolean = false,
    val recirculationWarning: Boolean = false,
    /** Something left on from before keeps this puzzle from counting; the parent screen says what to turn off. */
    val resetNeeded: String? = null,
    val snapshot: Snapshot? = null,
    /** Words from the story server for this episode; null means the app's own [Lines]. */
    val story: Story? = null,
    /** The friend the puzzle just solved put in the book for the first time. */
    val newFriend: Friend? = null,
    /** Friends first met in this episode, in order, for the treasure screen. */
    val metToday: List<Friend> = emptyList(),
) {
    val puzzle: Puzzle? get() = puzzles.getOrNull(index)
    val paused: Boolean get() = stopped != null || held

    fun scene(p: Puzzle): Scene = story?.scene(p) ?: Lines.scene(p)
}

/**
 * One episode from the parent's setup to "처음 설정으로". The car state comes in through [tick]; the rule engine
 * decides every answer and Lumi only speaks.
 */
class Game(
    private val car: StateFlow<CarState>,
    private val lumi: Lumi,
    private val stories: StoryRepo? = null,
    private val book: FriendBook? = null,
) {
    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui

    private var hold: HoldJudge? = null
    private var together: TogetherJudge? = null
    private var lastTickMs: Long? = null
    private var recirculationSince: Long? = null

    fun setup(change: (Setup) -> Setup) = _ui.update { it.copy(setup = change(it.setup)) }

    /**
     * The start button (이 이야기로 시작하기 / 기본 이야기로 시작하기): plays the episode the story server already wrote for this setup, or plans one and plays
     * the app's own lines. Remembers the climate as the parent left it, then starts writing the next episode.
     */
    fun begin() {
        val s = car.value
        val setup = _ui.value.setup
        val ready = stories?.take(setup, s)
        val puzzles = ready?.puzzles ?: Planner.plan(setup, s)
        if (puzzles.isEmpty()) return
        _ui.update { GameUi(phase = Phase.INTRO, setup = setup, puzzles = puzzles, snapshot = Snapshot.of(s), story = ready?.story) }
        lastTickMs = null
        lumi.say(ready?.story?.intro ?: Lines.INTRO)
        stories?.prepare(setup, s)
    }

    fun startPuzzle(index: Int) {
        val planned = _ui.value.puzzles.getOrNull(index) ?: return
        val p = Planner.refresh(planned, car.value)
        hold = if (p is Puzzle.Together || p is Puzzle.BeltMission) null else HoldJudge(p)
        together = (p as? Puzzle.Together)?.let { TogetherJudge(it) }
        _ui.update { ui ->
            val puzzles = ui.puzzles.toMutableList().also { it[index] = p }
            ui.copy(phase = Phase.PLAYING, puzzles = puzzles, index = index, hintHeard = false, newFriend = null, resetNeeded = null)
        }
        lumi.say(_ui.value.scene(p).speech)
    }

    fun next() {
        val ui = _ui.value
        if (ui.index + 1 < ui.puzzles.size) startPuzzle(ui.index + 1) else {
            _ui.update { it.copy(phase = Phase.TREASURE) }
            val story = ui.story
            lumi.say(
                when {
                    ui.setup.giftReady -> story?.treasureGift ?: Lines.TREASURE_GIFT
                    else -> story?.treasureBadge ?: Lines.TREASURE_BADGE
                }
            )
        }
    }

    fun hint() {
        val p = _ui.value.puzzle ?: return
        _ui.update { it.copy(hintHeard = true) }
        lumi.say(_ui.value.scene(p).hint)
    }

    /** 벨트 딸깍 미션: the parent saw the child buckle up (no car signal is read). */
    fun confirmBelt() {
        if (_ui.value.puzzle == Puzzle.BeltMission && !_ui.value.paused) solved()
    }

    fun hold() {
        _ui.update { it.copy(held = true) }
        lumi.stop()
    }

    /** 이어 하기: only once the car is safe again. */
    fun resume() {
        if (_ui.value.stopped != null) return
        _ui.update { it.copy(held = false) }
        lumi.repeat()
    }

    fun finish() = _ui.update { it.copy(phase = Phase.RESTORE, held = false) }

    fun reset() {
        lumi.stop()
        _ui.update { GameUi(setup = it.setup) }
    }

    private fun solved() {
        hold = null
        together = null
        val ui = _ui.value
        val friend = ui.puzzle?.let(Friend::of)?.takeIf { book?.meet(it) == true }
        _ui.update {
            it.copy(
                phase = Phase.SOLVED, stars = it.stars + 1, newFriend = friend, metToday = it.metToday + listOfNotNull(friend),
                resetNeeded = null,
            )
        }
        val praise = ui.story?.success(ui.stars) ?: Lines.SUCCESS
        lumi.say(if (friend == null) praise else "$praise ${Lines.newFriend(friend)}")
    }

    /** 이야기 신고 from the parent's screen. */
    fun report(reason: String) {
        val id = _ui.value.story?.storyId ?: return
        stories?.report(id, reason)
    }

    /** Called a few times a second with the clock: safety first, then the answer. */
    fun tick(nowMs: Long) {
        val ui = _ui.value
        val s = car.value
        if (ui.phase !in listOf(Phase.INTRO, Phase.PLAYING, Phase.SOLVED)) {
            lastTickMs = nowMs
            return
        }
        val dt = lastTickMs?.let { nowMs - it } ?: 0
        lastTickMs = nowMs

        val stop = Safety.pauseReason(s, ui.playedMs)
        if (stop != ui.stopped) {
            _ui.update { it.copy(stopped = stop) }
            if (stop != null) lumi.say(Lines.PAUSED)
        }
        if (stop != null || ui.held) return

        if (s.recirculation == true) {
            if (recirculationSince == null) recirculationSince = nowMs
        } else {
            recirculationSince = null
        }
        val warn = Safety.recirculationTooLong(s, recirculationSince, nowMs)
        _ui.update { it.copy(playedMs = it.playedMs + dt, recirculationWarning = warn) }

        if (ui.phase != Phase.PLAYING) return
        val done = hold?.update(s, nowMs) == true || together?.update(s, nowMs) == true
        val reset = hold?.resetNeeded(s) ?: together?.resetNeeded()
        if (reset != ui.resetNeeded) _ui.update { it.copy(resetNeeded = reset) }
        if (done) solved()
    }
}
