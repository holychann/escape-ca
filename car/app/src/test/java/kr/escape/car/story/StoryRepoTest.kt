package kr.escape.car.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.json.JSONObject
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StoryRepoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet())

    // the shape the app writes
    private val stored = """
        {"setup":{"ages":["CAPTAIN"],"minutes":10,"childSeat":"REAR_RIGHT"},
         "keys":["Q:MATH:FAN:5","Q:ENGLISH:LIGHTS:2","BELT","Q:REASON:AC:1"],
         "story":{"storyId":"7b7c564a6a284c9881821fe55b9ae1a5","theme":"SEA_SUB","title":"잠수함","intro":"안녕!",
          "success":["최고!"],"treasureGift":"선물!","treasureBadge":"배지!",
          "scenes":{"BELT":{"bubble":"딸깍!","speech":"벨트를 딸깍!","hint":"딸깍 소리!"},
           "Q:MATH:FAN:5":{"bubble":"바람!","speech":"새싹이 목말라.","hint":"세어 봐!","picture":null,
            "question":{"kind":"MATH","text":"조개 2개에 3개를 더하면?","expr":"2+3","word":"","terms":[],"choices":[]}}}}}
    """.trimIndent()

    private val storedPuzzles = listOf(
        Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5), Puzzle.Quiz(QuizKind.ENGLISH, Dial.LIGHTS, 2),
        Puzzle.BeltMission, Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1),
    )

    private fun repo() = StoryRepo(
        tmp.root.resolve("next_episode.json").apply { writeText(stored) },
        "00000000-0000-0000-0000-000000000000",
        StoryClient("http://127.0.0.1:9"),
        CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun storedEpisodeIsPlayedAfterARestart() {
        val ready = repo().take(Setup(), parked)
        assertNotNull(ready)
        assertEquals(storedPuzzles, ready!!.puzzles)
        assertEquals("벨트를 딸깍!", ready.story.scene(Puzzle.BeltMission).speech)
        assertEquals("2+3", ready.story.scene(storedPuzzles[0]).question?.expr)
    }

    @Test
    fun storedEpisodeIsKeptForItsOwnSetupOnly() {
        val r = repo()
        assertNull(r.take(Setup(minutes = 5), parked))
        assertNull(r.take(Setup(), parked.copy(devices = Device.entries.toSet() - Device.READING_LIGHT)))
        assertNotNull(r.take(Setup(), parked))
        // used up
        assertNull(r.take(Setup(), parked))
    }

    private class FakeClient(private val answer: () -> Story) : StoryClient("http://127.0.0.1:9") {
        var calls = 0
        var lastRequest: JSONObject? = null
        override fun episode(request: JSONObject): Story {
            calls++
            lastRequest = request
            return answer()
        }

        override fun download(path: String, to: java.io.File) {
            if ("broken" in path) throw IOException("gone")
            to.parentFile?.mkdirs()
            to.writeText(path)
        }
    }

    private fun TestScope.repoWith(client: StoryClient, keep: String? = stored): StoryRepo {
        val d = StandardTestDispatcher(testScheduler)
        val file = tmp.root.resolve("next_episode.json")
        if (keep == null) file.delete() else file.writeText(keep)
        return StoryRepo(file, "00000000-0000-0000-0000-000000000000", client, CoroutineScope(d), io = d)
    }

    @Test
    fun aLateFailureDoesNotHideTheStoredEpisode() = runTest {
        val client = FakeClient { throw IOException("server down") }
        val r = repoWith(client)
        // right after start the car has not reported the reading lights yet, so the stored episode does not fit
        r.prepare(Setup(), parked.copy(devices = parked.devices - Device.READING_LIGHT))
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.READY, r.status.value)
        assertNotNull(r.take(Setup(), parked))
    }

    @Test
    fun aWrittenEpisodeSurvivesARestart() = runTest {
        val story = Story.parse(JSONObject(stored).getJSONObject("story"))
        val client = FakeClient { story }
        val r = repoWith(client, keep = null)
        r.prepare(Setup(), parked)
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals(StoryRepo.Status.READY, r.status.value)
        val afterRestart = StoryRepo(
            tmp.root.resolve("next_episode.json"), "00000000-0000-0000-0000-000000000000", client,
            CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(story, afterRestart.take(Setup(), parked)?.story)
    }

    @Test
    fun aWishedStoryComesWithItsPicturesOnTheDevice() = runTest {
        val base = Story.parse(JSONObject(stored).getJSONObject("story"))
        val drawn = base.copy(storyId = "a".repeat(32), images = mapOf("cover" to "/v1/images/a/cover.webp", "p1" to "/v1/images/broken/p1.webp"))
        val client = FakeClient { drawn }
        val r = repoWith(client, keep = null)
        val wish = Setup(wish = "공룡 섬에서 보물찾기")
        r.prepare(wish, parked)
        advanceUntilIdle()
        assertEquals("공룡 섬에서 보물찾기", client.lastRequest?.getString("wish"))
        assertEquals(StoryRepo.Status.READY, r.status.value)
        val story = r.preview.value!!
        // the cover is on the device; the picture that failed to download is left out
        assertEquals(setOf("cover"), story.images.keys)
        assertEquals("/v1/images/a/cover.webp", java.io.File(story.images.getValue("cover")).readText())
        // a story written for a wish is only played for that wish
        assertNull(r.take(Setup(), parked))
        assertNotNull(r.take(wish, parked))
    }

    @Test
    fun aRefusedWishSaysWhy() = runTest {
        val r = repoWith(FakeClient { throw WishRejected(listOf("무서운 말: 귀신")) }, keep = null)
        r.prepare(Setup(wish = "귀신의 집"), parked)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.REJECTED, r.status.value)
        assertEquals(listOf("무서운 말: 귀신"), r.rejection.value)
    }

    @Test
    fun anEpisodeWithKeysThisAppCannotPlayIsWrittenAgain() = runTest {
        // kept by an older app, whose puzzles are gone
        val old = stored.replace("\"Q:MATH:FAN:5\",\"Q:ENGLISH", "\"WIND:FLOOR:STRONG\",\"Q:ENGLISH")
        val story = Story.parse(JSONObject(stored).getJSONObject("story"))
        val client = FakeClient { story }
        val r = repoWith(client, keep = old)
        assertNull(r.take(Setup(), parked))
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals(StoryRepo.Status.READY, r.status.value)
        // the planned answer key first in each slot, and the episode plays exactly the puzzles it was written for
        val asked = client.lastRequest!!.getJSONArray("puzzles")
        val ready = r.take(Setup(), parked)!!
        val keys = List(asked.length()) { i -> asked.getJSONArray(i).getString(0) }
        assertEquals(keys, ready.puzzles.map(StoryKeys::key))
        assertEquals("BANTE", client.lastRequest!!.getString("friend"))
    }

    @Test
    fun aFailedFetchLeavesTheAppsOwnLines() = runTest {
        val r = repoWith(FakeClient { throw IOException("server down") }, keep = null)
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.OFFLINE, r.status.value)
        assertNull(r.take(Setup(), parked))
    }
}
