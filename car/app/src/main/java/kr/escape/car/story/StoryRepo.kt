package kr.escape.car.story

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Questions
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Keeps the next episode ready: its puzzles from the rule engine, its words from the story server and, when the
 * family asked for a story of their own, the pictures the server drew for it, all kept on disk until the next drive.
 * The family never has to wait for the server in the car; without one they play [Lines].
 */
class StoryRepo(
    private val file: File,
    private val installId: String,
    private val client: StoryClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The quizzes the planner may ask (the app's copy of the question bank, the same one the server draws from). */
    private val questions: Questions = Questions.ANY,
    /** The questions this app ships; the shelf is asked only with one, to hold a shelved story to them. */
    private val bank: QuestionBank? = null,
    /** The stories this car already played; the shelf is not asked without it. */
    private val heard: HeardStories? = null,
) {
    enum class Status { NONE, WRITING, READY, OFFLINE, REJECTED }

    data class Ready(val puzzles: List<Puzzle>, val story: Story)

    private class Saved(val setup: Setup, val keys: List<String>, val story: Story)

    private val _status = MutableStateFlow(Status.NONE)
    val status: StateFlow<Status> = _status

    /** Why the server refused the family's wish (shown to the parent), while [status] is REJECTED. */
    private val _rejection = MutableStateFlow<List<String>>(emptyList())
    val rejection: StateFlow<List<String>> = _rejection

    /** The ready story, for the setup screen to show its title and cover before the game starts. */
    private val _preview = MutableStateFlow<Story?>(null)
    val preview: StateFlow<Story?> = _preview

    /** Pictures live next to the saved episode, one folder per story. */
    private val pictures = File(file.parentFile, "story_images")

    private var saved: Saved? = runCatching { load(JSONObject(file.readText())) }.getOrNull()
    private var writingFor: Setup? = null
    private var job: Job? = null
    /** The story being played; its pictures must stay while the next one is prepared. */
    private var playing: String? = null

    /** Called while the parent is choosing, and when an episode starts (for the next one). */
    fun prepare(setup: Setup, car: CarState) {
        val ready = fits(setup, car)
        if (ready != null) {
            // a fetch started a moment ago, before the car had reported every device, must not overwrite this
            job?.cancel()
            writingFor = null
            _status.value = Status.READY
            _preview.value = ready.story
            return
        }
        val wanted = setup.forStory()
        if (job?.isActive == true && writingFor == wanted) return
        job?.cancel()
        _preview.value = null
        val puzzles = Planner.plan(setup, car, questions = questions)
        if (puzzles.isEmpty()) return
        writingFor = wanted
        _status.value = Status.WRITING
        _rejection.value = emptyList()
        val body = request(installId, setup, puzzles, Calendar.getInstance())
        // a family's own wish is always written new; the shelf only serves a story chosen by the app
        val askShelf = wanted.wish.isEmpty() && bank != null && bank.size > 0 && heard != null
        job = scope.launch {
            val result = withContext(io) {
                runCatching { if (askShelf) viaShelf(setup, car, puzzles, body) else Found(withLocalPictures(client.episode(body)), puzzles) }
            }
            if (writingFor != wanted) return@launch
            writingFor = null
            val found = result.getOrElse { e ->
                if (e is WishRejected) {
                    _rejection.value = e.reasons
                    _status.value = Status.REJECTED
                } else {
                    _status.value = Status.OFFLINE
                }
                return@launch
            }
            val story = found.story
            val next = Saved(wanted, found.puzzles.map(StoryKeys::key), story)
            saved = next
            withContext(io) {
                runCatching { file.writeText(save(next).toString()) }
                forgetPicturesExcept(setOfNotNull(story.storyId, playing))
            }
            _status.value = Status.READY
            _preview.value = story
        }
    }

    private class Found(val story: Story, val puzzles: List<Puzzle>)

    /**
     * The shelf first: a story other cars got that this car and the app's own question bank accept. Otherwise a new
     * one is written; if that fails and the shelf had nothing at all, the shelf is asked once more for any story it
     * would not have kept back for a fresh one. Blocking; throws when nothing could be had.
     */
    private fun viaShelf(setup: Setup, car: CarState, puzzles: List<Puzzle>, body: JSONObject): Found {
        val playable = Planner.playable(setup, car, questions)
        val offered = runCatching { client.library(libraryRequest(setup, playable, allowFresh = true)) }.getOrNull()
        offered?.let { pick(it, setup, car) }?.let { return it }
        try {
            return Found(withLocalPictures(client.episode(body)), puzzles)
        } catch (e: Exception) {
            if (offered?.isEmpty() != true) throw e
            val again = runCatching { client.library(libraryRequest(setup, playable, allowFresh = false)) }.getOrNull()
            return again?.let { pick(it, setup, car) } ?: throw e
        }
    }

    /** The first shelf story this car has not heard, plays on this car now, and asks only questions this app ships. */
    private fun pick(offered: List<Shelf>, setup: Setup, car: CarState): Found? {
        val bank = bank ?: return null
        val heard = heard ?: return null
        val youngest = Planner.youngest(setup)
        for (candidate in offered) {
            if (candidate.story.storyId in heard) continue
            // a key this app does not know leaves the story out
            val slots = candidate.slots.map { keys -> keys.map { StoryKeys.puzzle(it) } }
            if (slots.any { null in it }) continue
            val chosen = Planner.accepts(slots.map { it.filterNotNull() }, setup, car, questions)
                ?.takeIf { it.isNotEmpty() } ?: continue
            val asked = chosen.filterIsInstance<Puzzle.Quiz>().all { p ->
                candidate.story.scenes[StoryKeys.key(p)]?.question?.let { bank.asks(p, youngest, it) } ?: true
            }
            if (asked) return Found(withLocalPictures(candidate.story), chosen)
        }
        return null
    }

    /** The ready episode if it was written for this setup and this car can play it; it is used up. */
    fun take(setup: Setup, car: CarState): Ready? {
        val ready = fits(setup, car) ?: return null
        heard?.add(ready.story.storyId)
        saved = null
        file.delete()
        playing = ready.story.storyId
        _status.value = Status.NONE
        _preview.value = null
        return ready
    }

    /** The choices the episode kept on disk was written for, so the setup screen can start from them after a restart. */
    fun savedSetup(): Setup? = saved?.setup

    /** 이야기 신고: fire and forget; a parent's report must never stop the game. */
    fun report(storyId: String, reason: String) {
        scope.launch(io) { runCatching { client.report(storyId, reason) } }
    }

    /** Downloads the pictures the server drew; one that fails is left out and its scene keeps the app's picture. */
    private fun withLocalPictures(story: Story): Story {
        if (story.images.isEmpty()) return story
        val dir = File(pictures, story.storyId)
        val local = story.images.mapNotNull { (name, path) ->
            val to = File(dir, "$name.webp")
            runCatching { if (!to.isFile) client.download(path, to) }.map { name to to.absolutePath }.getOrNull()
        }.toMap()
        return story.copy(images = local)
    }

    /** The story a saved episode plays, read back after a restart: its pictures stay while the next one is prepared. */
    fun keepPlaying(storyId: String?) {
        playing = storyId
    }

    /** The saved episode was dropped (새로 시작): its pictures go, unless they are the ready next story's. */
    fun letGo(storyId: String) {
        if (playing == storyId) playing = null
        if (saved?.story?.storyId == storyId) return
        scope.launch(io) { File(pictures, storyId).deleteRecursively() }
    }

    private fun forgetPicturesExcept(keep: Set<String>) {
        pictures.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun fits(setup: Setup, car: CarState): Ready? {
        val s = saved ?: return null
        if (s.setup != setup.forStory()) return null
        val puzzles = s.keys.map { StoryKeys.puzzle(it) ?: return null }
        if (puzzles.any { !car.devices.containsAll(it.devices) }) return null
        // kept since an earlier drive: a temperature quiz whose way (up or down) no longer suits the cabin is not played
        val temp = Planner.tempDial(car)
        if (puzzles.any { it is Puzzle.Quiz && (it.dial == Dial.TEMP_UP || it.dial == Dial.TEMP_DOWN) && it.dial != temp }) return null
        return Ready(puzzles, s.story)
    }

    companion object {
        const val WISH_MAX = 60

        /**
         * The lessons a story can carry, one picked at random for each episode the server writes, apart from the kind
         * of story the family chose (server models.LESSON_LABEL has their words).
         */
        val LESSONS = listOf("COURAGE", "CARE", "CREATIVITY", "GRIT", "TEAMWORK", "SHARING", "HONESTY", "THANKS",
            "PROMISE", "FRIENDSHIP")

        /**
         * What the story shelf is asked: only enums, numbers and answer keys ([playable] holds quiz keys only), never an
         * install id, a context, a wish or a lesson. With [allowFresh] false the shelf may offer any story it has.
         */
        fun libraryRequest(setup: Setup, playable: List<Puzzle.Quiz>, allowFresh: Boolean): JSONObject = JSONObject()
            .put("ages", JSONArray(setup.ages.map { it.name }))
            .put("minutes", setup.minutes)
            .put("friend", setup.friend.name)
            .put("playable", JSONArray(playable.map(StoryKeys::key)))
            .put("allowFresh", allowFresh)

        private fun Setup.forStory() = copy(wish = wish.trim())

        /**
         * Enums, numbers and answer keys; the only free text is the family's own wish, and with it the server is asked
         * to draw that world too. No name, voice or place ever leaves the car otherwise.
         */
        fun request(
            installId: String, setup: Setup, puzzles: List<Puzzle>, now: Calendar, lesson: String = LESSONS.random(),
        ): JSONObject {
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when (hour) {
                in 5..10 -> "MORNING"
                in 11..16 -> "AFTERNOON"
                in 17..20 -> "EVENING"
                else -> "NIGHT"
            }
            val season = when (now.get(Calendar.MONTH) + 1) {
                3, 4, 5 -> "SPRING"
                6, 7, 8 -> "SUMMER"
                9, 10, 11 -> "AUTUMN"
                else -> "WINTER"
            }
            val youngest = Planner.youngest(setup)
            // the server refuses a request that names a key twice
            val named = puzzles.map(StoryKeys::key).toMutableSet()
            val body = JSONObject()
                .put("installId", installId)
                .put("ages", JSONArray(setup.ages.map { it.name }))
                .put("minutes", setup.minutes)
                .put("context", JSONObject().put("timeOfDay", timeOfDay).put("season", season))
                // the friend the child picked tells the story
                .put("friend", setup.friend.name)
                // one slot per puzzle: the planned answer first, then the quiz the app switches to when the car no
                // longer allows the planned one as the puzzle starts (Planner.replacement tries the opposite first)
                .put("puzzles", JSONArray(puzzles.map { p ->
                    val other = (p as? Puzzle.Quiz)?.let(Planner::opposite)
                        ?.takeIf { Planner.allowed(it, youngest) }?.let(StoryKeys::key)
                        ?.takeIf { named.add(it) }
                    JSONArray(listOfNotNull(StoryKeys.key(p), other))
                }))
                .put("lesson", lesson)
            val wish = setup.wish.trim().take(WISH_MAX)
            if (wish.isNotEmpty()) body.put("wish", wish).put("pictures", true)
            return body
        }

        /** The parent's choices as kept on disk, for the next episode here and for the one in progress ([ProgressStore]). */
        fun setupJson(s: Setup): JSONObject = JSONObject()
            .put("ages", JSONArray(s.ages.map { it.name }))
            .put("minutes", s.minutes)
            .put("childSeat", s.childSeat.name)
            .put("friend", s.friend.name)
            .put("wish", s.wish)

        /** Back from [setupJson]; throws on a value this app does not know. A friend it does not know is 반테. */
        fun setupOf(o: JSONObject): Setup {
            val ages = o.getJSONArray("ages")
            return Setup(
                ages = List(ages.length()) { Age.valueOf(ages.getString(it)) },
                minutes = o.getInt("minutes"),
                childSeat = Seat.valueOf(o.getString("childSeat")),
                friend = Friend.entries.firstOrNull { it.name == o.optString("friend") } ?: Friend.BANTE,
                wish = o.optString("wish", ""),
            )
        }

        private fun save(s: Saved): JSONObject = JSONObject()
            .put("setup", setupJson(s.setup))
            .put("keys", JSONArray(s.keys))
            .put("story", s.story.toJson())

        private fun load(o: JSONObject): Saved {
            val keys = o.getJSONArray("keys")
            return Saved(
                setupOf(o.getJSONObject("setup")),
                List(keys.length()) { keys.getString(it) },
                Story.parse(o.getJSONObject("story")),
            )
        }
    }
}
