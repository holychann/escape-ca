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
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Puzzle
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

    // the shape the app writes, as saved on the emulator
    private val stored = """
        {"setup":{"ages":["CAPTAIN"],"minutes":10,"childSeat":"REAR_RIGHT"},
         "keys":["SEAT:REAR_RIGHT","WIND:FLOOR:STRONG","BELT","DUST:OUTSIDE"],
         "story":{"storyId":"7b7c564a6a284c9881821fe55b9ae1a5","theme":"SEA_SUB","title":"잠수함","intro":"안녕!",
          "success":["최고!"],"treasureGift":"선물!","treasureBadge":"배지!",
          "scenes":{"BELT":{"bubble":"딸깍!","speech":"벨트를 딸깍!","hint":"딸깍 소리!"}}}}
    """.trimIndent()

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
        assertEquals(4, ready!!.puzzles.size)
        assertEquals(Puzzle.BeltMission, ready.puzzles[2])
        assertEquals("벨트를 딸깍!", ready.story.scene(Puzzle.BeltMission).speech)
    }

    @Test
    fun storedEpisodeIsKeptForItsOwnSetupOnly() {
        val r = repo()
        assertNull(r.take(Setup(minutes = 5), parked))
        assertNull(r.take(Setup(), parked.copy(devices = Device.entries.toSet() - Device.READING_LIGHT)))
        // the gift does not change the puzzles
        assertNotNull(r.take(Setup(giftReady = true), parked))
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
    fun recordedLinesAreKeptOnTheDeviceAndSurviveARestart() = runTest {
        val base = Story.parse(JSONObject(stored).getJSONObject("story"))
        val read = base.copy(storyId = "b".repeat(32), voices = mapOf(
            "intro" to "/v1/audio/b/intro.wav",
            "BELT.speech" to "/v1/audio/b/BELT.speech.wav",
            "DUST:OUTSIDE.hint" to "/v1/audio/b/DUST_OUTSIDE.hint.wav",
            "success0" to "/v1/audio/broken/success0.wav",
        ))
        val r = repoWith(FakeClient { read }, keep = null)
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        val story = r.preview.value!!
        // the line that failed to download is left out and read by the car's voice
        assertEquals(setOf("intro", "BELT.speech", "DUST:OUTSIDE.hint"), story.voices.keys)
        // read the stored path itself: voice() only takes "/..." paths, which a Windows test machine does not make
        val hint = java.io.File(story.voices.getValue("DUST:OUTSIDE.hint"))
        assertEquals("DUST_OUTSIDE.hint.wav", hint.name)
        assertEquals("voice", hint.parentFile!!.name)
        assertEquals("/v1/audio/b/DUST_OUTSIDE.hint.wav", hint.readText())
        assertNull(story.successVoice(0))
        val afterRestart = StoryRepo(
            tmp.root.resolve("next_episode.json"), "00000000-0000-0000-0000-000000000000", FakeClient { read },
            CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(story.voices, afterRestart.take(Setup(), parked)?.story?.voices)
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
    fun aFailedFetchLeavesTheAppsOwnLines() = runTest {
        val r = repoWith(FakeClient { throw IOException("server down") }, keep = null)
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.OFFLINE, r.status.value)
        assertNull(r.take(Setup(), parked))
    }
}
