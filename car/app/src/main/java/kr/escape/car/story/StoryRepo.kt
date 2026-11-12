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
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Keeps the next episode ready: its puzzles from the rule engine and its words from the story server, kept on disk
 * until the next drive. The family never waits for the server in the car; without one they play [Lines].
 */
class StoryRepo(
    private val file: File,
    private val installId: String,
    private val client: StoryClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Status { NONE, WRITING, READY, OFFLINE }

    data class Ready(val puzzles: List<Puzzle>, val story: Story)

    private class Saved(val setup: Setup, val keys: List<String>, val story: Story)

    private val _status = MutableStateFlow(Status.NONE)
    val status: StateFlow<Status> = _status

    private var saved: Saved? = runCatching { load(JSONObject(file.readText())) }.getOrNull()
    private var writingFor: Setup? = null
    private var job: Job? = null

    /** Called while the parent is choosing, and when an episode starts (for the next one). */
    fun prepare(setup: Setup, car: CarState) {
        if (fits(setup, car) != null) {
            // a fetch started a moment ago, before the car had reported every device, must not overwrite this
            job?.cancel()
            writingFor = null
            _status.value = Status.READY
            return
        }
        val wanted = setup.forStory()
        if (job?.isActive == true && writingFor == wanted) return
        job?.cancel()
        val puzzles = Planner.plan(setup, car)
        if (puzzles.isEmpty()) return
        writingFor = wanted
        _status.value = Status.WRITING
        val body = request(installId, setup, puzzles, Calendar.getInstance())
        job = scope.launch {
            val story = withContext(io) { runCatching { client.episode(body) }.getOrNull() }
            if (writingFor != wanted) return@launch
            writingFor = null
            if (story == null) {
                _status.value = Status.OFFLINE
                return@launch
            }
            val next = Saved(wanted, puzzles.map(StoryKeys::key), story)
            saved = next
            withContext(io) { runCatching { file.writeText(save(next).toString()) } }
            _status.value = Status.READY
        }
    }

    /** The ready episode if it was written for this setup and this car can play it; it is used up. */
    fun take(setup: Setup, car: CarState): Ready? {
        val ready = fits(setup, car) ?: return null
        saved = null
        file.delete()
        _status.value = Status.NONE
        return ready
    }

    /** 이야기 신고: fire and forget; a parent's report must never stop the game. */
    fun report(storyId: String, reason: String) {
        scope.launch(io) { runCatching { client.report(storyId, reason) } }
    }

    private fun fits(setup: Setup, car: CarState): Ready? {
        val s = saved ?: return null
        if (s.setup != setup.forStory()) return null
        val puzzles = s.keys.map { StoryKeys.puzzle(it, car.fanSpeedRange, setup.childSeat) ?: return null }
        if (puzzles.any { !car.devices.containsAll(it.devices) }) return null
        return Ready(puzzles, s.story)
    }

    companion object {
        /** The gift choice does not change the puzzles, so a story fits either way. */
        private fun Setup.forStory() = copy(giftReady = false)

        /** Only enums, numbers and answer keys: no name, voice or place ever leaves the car. */
        fun request(installId: String, setup: Setup, puzzles: List<Puzzle>, now: Calendar): JSONObject {
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
            return JSONObject()
                .put("installId", installId)
                .put("ages", JSONArray(setup.ages.map { it.name }))
                .put("minutes", setup.minutes)
                .put("context", JSONObject().put("timeOfDay", timeOfDay).put("season", season))
                .put("puzzles", JSONArray(puzzles.map { p -> JSONArray(StoryKeys.variants(p).map(StoryKeys::key)) }))
        }

        private fun save(s: Saved): JSONObject = JSONObject()
            .put("setup", JSONObject()
                .put("ages", JSONArray(s.setup.ages.map { it.name }))
                .put("minutes", s.setup.minutes)
                .put("childSeat", s.setup.childSeat.name))
            .put("keys", JSONArray(s.keys))
            .put("story", s.story.toJson())

        private fun load(o: JSONObject): Saved {
            val st = o.getJSONObject("setup")
            val ages = st.getJSONArray("ages")
            val keys = o.getJSONArray("keys")
            return Saved(
                Setup(
                    ages = List(ages.length()) { Age.valueOf(ages.getString(it)) },
                    minutes = st.getInt("minutes"),
                    childSeat = Seat.valueOf(st.getString("childSeat")),
                ),
                List(keys.length()) { keys.getString(it) },
                Story.parse(o.getJSONObject("story")),
            )
        }
    }
}
