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
        override fun episode(request: JSONObject): Story {
            calls++
            return answer()
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
    fun aFailedFetchLeavesTheAppsOwnLines() = runTest {
        val r = repoWith(FakeClient { throw IOException("server down") }, keep = null)
        r.prepare(Setup(), parked)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.OFFLINE, r.status.value)
        assertNull(r.take(Setup(), parked))
    }
}
