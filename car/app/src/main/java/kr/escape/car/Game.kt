package kr.escape.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.escape.car.engine.CarRestorer
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.HoldJudge
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Questions
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Safety
import kr.escape.car.engine.Setup
import kr.escape.car.engine.Snapshot
import kr.escape.car.engine.StopReason
import kr.escape.car.story.Lines
import kr.escape.car.story.Question
import kr.escape.car.story.QuestionBank
import kr.escape.car.story.StoryKeys
import kr.escape.car.story.Scene
import kr.escape.car.story.Story
import kr.escape.car.story.StoryRepo
import kr.escape.car.voice.Lumi
import kotlin.random.Random

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
    /**
     * Next to the parent's 정답 보기: where a temperature or fan answer counts from (and for a number, where it should
     * end), or whose reading light it is.
     */
    val answerNote: String? = null,
    /**
     * The app's own question for each quiz (answer key), drawn from its copy of the bank when the game began, or
     * when a puzzle was swapped as it started.
     */
    val own: Map<String, Pair<Question, String>> = emptyMap(),
    val snapshot: Snapshot? = null,
    /** Words from the story server for this episode; null means the app's own [Lines]. */
    val story: Story? = null,
    /** The friend's hearts after this episode, set when the treasure is found. */
    val heartsNow: Int? = null,
    /** A rare friend opened from the friend book just now, shown until the next touch. */
    val invited: Friend? = null,
    /** 처음 설정으로: the app is putting the car back right now. */
    val restoring: Boolean = false,
    /** The app has tried to put the car back at least once (what is still different is left to the parent). */
    val restoreTried: Boolean = false,
    /** Why the app did not put the car back (not parked); the parent can try again. */
    val restoreNote: String? = null,
) {
    val puzzle: Puzzle? get() = puzzles.getOrNull(index)
    val paused: Boolean get() = stopped != null || held

    fun scene(p: Puzzle): Scene =
        own[StoryKeys.key(p)].let { mine -> story?.scene(p, mine) ?: Lines.scene(p, mine) }

    /**
     * What Lumi says once the puzzle being played is solved: the story's line for that very puzzle (a quiz swapped as
     * it started has its own), or the app's own when the scene is played from [Lines] or the line was dropped.
     */
    val solvedLine: String
        get() = puzzle?.let { scene(it).result }?.takeIf { it.isNotBlank() } ?: Lines.SUCCESS
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
    private val restorer: CarRestorer? = null,
    private val bank: QuestionBank? = null,
    private val rnd: Random = Random.Default,
) {
    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui

    private var hold: HoldJudge? = null
    private var lastTickMs: Long? = null
    private var recirculationSince: Long? = null

    fun setup(change: (Setup) -> Setup) = _ui.update { it.copy(setup = change(it.setup)) }

    /** A friend picked in the book goes through the next episode; a rare one only once it is open. */
    fun pick(f: Friend) {
        if (book?.book?.value?.isOpen(f) == false) return
        _ui.update { it.copy(setup = it.setup.copy(friend = f), invited = null) }
    }

    /** "새로운 친구를 만나러 가자": [by] at full hearts opens the next rare friend. */
    fun invite(by: Friend) {
        val rare = book?.invite(by) ?: return
        _ui.update { it.copy(invited = rare) }
        lumi.say(Lines.invited(rare))
    }

    fun closeInvite() = _ui.update { it.copy(invited = null) }

    /** What the planner may ask: the app's copy of the question bank, or without one its own built-in questions. */
    private val questions: Questions = bank?.questions ?: Questions.ANY

    /** Whether this car can play an episode for the parent's choices right now (the setup screen's start button). */
    fun playable(s: CarState): Boolean = Planner.plan(_ui.value.setup, s, Random(0), questions).isNotEmpty()

    /**
     * The start button (이 이야기로 시작하기 / 기본 이야기로 시작하기): plays the episode the story server already wrote for this setup, or plans one and plays
     * the app's own lines. Remembers the climate as the parent left it, then starts writing the next episode.
     */
    fun begin() {
        val s = car.value
        val setup = _ui.value.setup
        val ready = stories?.take(setup, s)
        var ui = GameUi(
            phase = Phase.INTRO, setup = setup, puzzles = ready?.puzzles ?: Planner.plan(setup, s, rnd, questions),
            snapshot = Snapshot.of(s), story = ready?.story,
        )
        // an episode kept since an earlier drive may hold a quiz the car now already answers
        var i = 0
        while (i < ui.puzzles.size) {
            val size = ui.puzzles.size
            ui = fitted(ui, i, s)
            if (ui.puzzles.size == size) i++
        }
        if (ui.puzzles.isEmpty()) return
        // the youngest child on board sets how hard the app's own questions are
        val youngest = Planner.youngest(setup)
        val own = ui.puzzles.filterIsInstance<Puzzle.Quiz>().mapNotNull { q -> bank?.pick(q, youngest, rnd)?.let { StoryKeys.key(q) to it } }.toMap()
        _ui.value = ui.copy(own = own)
        lastTickMs = null
        val intro = "${ready?.story?.intro ?: Lines.intro(setup.friend)} ${Lines.AUTO_OFF}"
        lumi.say(intro)
        // every line this episode will say, fetched now so each plays without a wait
        lumi.prefetch(lines(_ui.value))
        stories?.prepare(setup, s)
    }

    fun startPuzzle(index: Int) {
        if (_ui.value.puzzles.getOrNull(index) == null) return
        // a temperature or fan answer counts from the car as it is now
        val start = car.value
        // the parent may have changed the car during an earlier puzzle: never start one whose answer already shows
        var ui = _ui.value
        while (true) {
            val size = ui.puzzles.size
            ui = fitted(ui, index, start)
            if (ui.puzzles.size == size) break
        }
        val p = ui.puzzles.getOrNull(index)
        if (p == null) {
            // the last puzzle had nothing the car allows now: the episode ends here
            _ui.value = ui
            treasure()
            return
        }
        val quiz = p as? Puzzle.Quiz
        hold = quiz?.let { q -> HoldJudge({ s -> q.solvedBy(s, start, ui.setup.childSeat) }) }
        _ui.value = ui.copy(phase = Phase.PLAYING, index = index, hintHeard = false, answerNote = quiz?.let { note(it, start, ui.setup) })
        lumi.say(_ui.value.scene(p).speech)
        // a quiz swapped just now has new words for after the answer
        lumi.prefetch(listOf(_ui.value.solvedLine))
    }

    /**
     * What Lumi will say in [ui], exactly as she will say it: each puzzle's story and question, the line after the
     * answer, the last words and the pause line. Hints are left to when they are asked for, so a line nobody hears is
     * never paid for.
     */
    private fun lines(ui: GameUi): List<String> = buildList {
        ui.puzzles.forEachIndexed { i, p ->
            add(ui.scene(p).speech)
            add(ui.copy(index = i).solvedLine)
        }
        val treasure = ui.story?.treasureBadge ?: Lines.TREASURE_BADGE
        add(treasure)
        add("$treasure ${Lines.CLOSER}")
        add(Lines.PAUSED)
    }

    /**
     * [ui] with the quiz at [index] fitting the car [s]: one whose answer the car already shows, or whose story no
     * longer fits the car, is swapped for one that does (its question from the app's own bank, its words the app's
     * own lines), or dropped when the car allows nothing.
     */
    private fun fitted(ui: GameUi, index: Int, s: CarState): GameUi {
        val puzzles = Planner.fitted(ui.puzzles, index, s, ui.setup, rnd, questions)
        if (puzzles == ui.puzzles) return ui
        val swap = if (puzzles.size == ui.puzzles.size) puzzles[index] as? Puzzle.Quiz else null
        val own = swap?.let { bank?.pick(it, Planner.youngest(ui.setup), rnd) }
        return ui.copy(puzzles = puzzles, own = if (swap == null || own == null) ui.own else ui.own + (StoryKeys.key(swap) to own))
    }

    /** Where a temperature or fan answer counts from, or whose reading light it is. */
    private fun note(q: Puzzle.Quiz, start: CarState, setup: Setup): String? = when (q.dial) {
        Dial.TEMP_UP, Dial.TEMP_DOWN -> start.tempLeft?.let { from ->
            if (q.kind == QuizKind.REASON) "지금 ${degrees(from)}도보다 ${if (q.dial == Dial.TEMP_UP) "높게" else "낮게"}"
            else "지금 ${degrees(from)}도에서 ${degrees(q.tempTarget(from))}도로"
        }
        Dial.FAN_UP, Dial.FAN_DOWN -> start.fanSpeed?.let { "지금 ${it}단에서" }
        Dial.LIGHT_CHILD -> "${setup.childSeat.label} 자리"
        else -> null
    }

    private fun degrees(c: Float) = if (c % 1f == 0f) c.toInt().toString() else c.toString()

    fun next() {
        val ui = _ui.value
        if (ui.index + 1 < ui.puzzles.size) startPuzzle(ui.index + 1) else treasure()
    }

    /** The end of the episode: a whole episode with this friend is one heart more. */
    private fun treasure() {
        val ui = _ui.value
        val friend = ui.setup.friend
        val hearts = book?.played(friend)
        _ui.update { it.copy(phase = Phase.TREASURE, heartsNow = hearts) }
        val treasure = ui.story?.treasureBadge ?: Lines.TREASURE_BADGE
        lumi.say(if (hearts == null) treasure else "$treasure ${Lines.CLOSER}")
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

    /** 끝내기 or the treasure's 처음 설정으로: the app puts back what the game changed. */
    fun finish() {
        _ui.update { it.copy(phase = Phase.RESTORE, held = false) }
        restore()
    }

    /** Puts the car back to the snapshot taken at the start; only while parked (the parent can try again after P). */
    fun restore() {
        val target = _ui.value.snapshot ?: return
        val r = restorer ?: return
        if (_ui.value.restoring) return
        if (StopReason.DRIVING in Safety.startBlockers(car.value)) {
            _ui.update { it.copy(restoreNote = "주차(P)한 뒤 다시 되돌려 주세요") }
            return
        }
        _ui.update { it.copy(restoring = true, restoreNote = null) }
        r.restore(target) { _ -> _ui.update { it.copy(restoring = false, restoreTried = true) } }
    }

    fun reset() {
        lumi.stop()
        _ui.update { GameUi(setup = it.setup) }
    }

    private fun solved() {
        hold = null
        val ui = _ui.value
        _ui.update { it.copy(phase = Phase.SOLVED, stars = it.stars + 1) }
        lumi.say(ui.solvedLine)
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
        if (hold?.update(s, nowMs) == true) solved()
    }
}
