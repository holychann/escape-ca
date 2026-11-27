package kr.escape.car.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Questions
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val libraryRequests = mutableListOf<JSONObject>()
        var shelf: (JSONObject) -> List<Shelf> = { throw IOException("no shelf") }
        override fun library(request: JSONObject): List<Shelf> {
            libraryRequests += request
            return shelf(request)
        }

        override fun episode(request: JSONObject): Story {
            calls++
            lastRequest = request
            return answer()
        }

        /** How many times a picture whose path says "drawing" is still being drawn when asked. */
        var drawingRounds = 0
        private val asked = mutableMapOf<String, Int>()

        override fun fetch(path: String, to: java.io.File): Fetched {
            if ("broken" in path) return Fetched.MISSING
            if ("drawing" in path && asked.merge(path, 1, Int::plus)!! <= drawingRounds) return Fetched.DRAWING
            to.parentFile?.mkdirs()
            to.writeText(path)
            return Fetched.SAVED
        }
    }

    private fun TestScope.repoWith(
        client: StoryClient, keep: String? = stored, bank: QuestionBank? = null, heard: HeardStories? = null,
        log: (String) -> Unit = {},
    ): StoryRepo {
        val d = StandardTestDispatcher(testScheduler)
        val file = tmp.root.resolve("next_episode.json")
        if (keep == null) file.delete() else file.writeText(keep)
        return StoryRepo(
            file, "00000000-0000-0000-0000-000000000000", client, CoroutineScope(d), io = d,
            questions = bank?.questions ?: Questions.ANY, bank = bank, heard = heard, log = log,
        )
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

    private fun drawingStory() = Story.parse(JSONObject(stored).getJSONObject("story")).copy(
        storyId = "d".repeat(32),
        images = mapOf("cover" to "/v1/images/d/cover.webp", "p1" to "/v1/images/d/p1-drawing.webp"),
    )

    @Test
    fun aPictureStillBeingDrawnComesAfterTheStory() = runTest {
        val client = FakeClient { drawingStory() }.also { it.drawingRounds = 2 }
        val r = repoWith(client, keep = null)
        r.prepare(Setup(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기"), parked)
        runCurrent()
        // the story is ready with its cover on the device; the picture still being drawn keeps its server path
        assertEquals(StoryRepo.Status.READY, r.status.value)
        val ready = r.preview.value!!
        assertFalse(Story.drawing(ready.images.getValue("cover")))
        assertTrue(ready.drawing("p1"))
        assertTrue(r.settled.value.isEmpty())
        advanceTimeBy(StoryRepo.FOLLOW_EVERY_MS * 2 + 1)
        runCurrent()
        val got = r.settled.value.getValue("${"d".repeat(32)}/p1")
        assertTrue(java.io.File(got!!).isFile)
        // taken into the story, it is a picture on the device
        assertFalse(ready.settledWith(r.settled.value).drawing("p1"))
    }

    @Test
    fun aPictureThatNeverComesIsGivenUpSoNobodyWaitsForever() = runTest {
        val client = FakeClient { drawingStory() }.also { it.drawingRounds = Int.MAX_VALUE }
        val r = repoWith(client, keep = null)
        r.prepare(Setup(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기"), parked)
        advanceUntilIdle()
        val key = "${"d".repeat(32)}/p1"
        assertTrue(key in r.settled.value)
        assertNull(r.settled.value[key])
        // left out of the story, so its scene shows the app's own picture
        assertFalse("p1" in r.preview.value!!.settledWith(r.settled.value).images)
    }

    @Test
    fun followingAKeptStoryFetchesWhatIsStillComing() = runTest {
        val client = FakeClient { drawingStory() }
        val r = repoWith(client, keep = null)
        val kept = drawingStory()
        r.follow(kept)
        r.follow(kept)  // asked twice (the card's 이어 하기 and the story arriving): fetched once
        advanceUntilIdle()
        assertTrue(java.io.File(r.settled.value.getValue("${"d".repeat(32)}/p1")!!).isFile)
        assertTrue(java.io.File(r.settled.value.getValue("${"d".repeat(32)}/cover")!!).isFile)
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

    // ------------------------------------------------------------ 이어 하기: the pictures of the episode in progress

    private val playedId = "b".repeat(32)

    /** The cover of the episode the family was playing when the car went off, still on the device. */
    private fun playedCover() = tmp.root.resolve("story_images/$playedId/cover.webp").apply {
        parentFile.mkdirs()
        writeText("cover")
    }

    /** A new story with a picture of its own arrives for the setup screen. */
    private fun TestScope.nextStoryArrives(r: StoryRepo) {
        r.prepare(Setup(wish = "공룡 섬에서 보물찾기"), parked)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.READY, r.status.value)
    }

    private fun drawnStory() = Story.parse(JSONObject(stored).getJSONObject("story"))
        .copy(storyId = "a".repeat(32), images = mapOf("cover" to "/v1/images/a/cover.webp"))

    @Test
    fun theStoryBeingPlayedKeepsItsPicturesAfterARestart() = runTest {
        val cover = playedCover()
        val r = repoWith(FakeClient { drawnStory() }, keep = null)
        r.keepPlaying(playedId)
        nextStoryArrives(r)
        assertTrue(cover.isFile)
        assertTrue(tmp.root.resolve("story_images/${"a".repeat(32)}/cover.webp").isFile)
    }

    @Test
    fun anOldStorysPicturesGoWhenNothingKeepsThem() = runTest {
        val cover = playedCover()
        val r = repoWith(FakeClient { drawnStory() }, keep = null)
        nextStoryArrives(r)
        assertFalse(cover.parentFile.exists())
    }

    @Test
    fun lettingGoOfAStoryRemovesItsPictures() = runTest {
        val cover = playedCover()
        val r = repoWith(FakeClient { drawnStory() }, keep = null)
        r.keepPlaying(playedId)
        r.letGo(playedId)
        advanceUntilIdle()
        assertFalse(cover.parentFile.exists())
    }

    // ------------------------------------------------------------ 이야기 창고: the shelf is asked first

    /** Wind at 2, two reading lights off, the air conditioner off, 24 degrees: the car the stored keys suit. */
    private val shelfCar = parked.copy(
        fanSpeed = 2, readingLights = mapOf(Seat.DRIVER to false, Seat.REAR_RIGHT to false), acOn = false, tempLeft = 24f,
    )

    /** The questions the app ships here: the stored story's own, and one each for the lights and the air conditioner. */
    private val shelfBankEntries = listOf(
        QuestionBank.Entry(QuizKind.MATH, Dial.FAN, Age.CAPTAIN, "조개 2개에 3개를 더하면?", "세어 봐!", 5, expr = "2+3"),
        QuestionBank.Entry(QuizKind.ENGLISH, Dial.LIGHTS, Age.CAPTAIN, "TWO는 우리말로 몇일까?", "세어 봐", 2, word = "TWO"),
        QuestionBank.Entry(QuizKind.REASON, Dial.AC, Age.CAPTAIN, "더워서 헥헥! 차 버튼으로 도와줘!", "버튼을 찾아봐", 1),
    )
    private val shelfBank = QuestionBank(shelfBankEntries)

    private val goodSlots = listOf(
        listOf("Q:MATH:FAN:5"), listOf("Q:ENGLISH:LIGHTS:2"), listOf("BELT"), listOf("Q:REASON:AC:1"),
    )
    private val goodPuzzles = listOf(
        Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5), Puzzle.Quiz(QuizKind.ENGLISH, Dial.LIGHTS, 2),
        Puzzle.BeltMission, Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1),
    )

    private fun storyOf(id: String): Story = Story.parse(JSONObject(stored).getJSONObject("story")).copy(storyId = id)

    private fun shelved(id: String, slots: List<List<String>> = goodSlots, story: Story = storyOf(id)) = Shelf(slots, story)

    private fun heardFile() = HeardStories(tmp.root.resolve("heard_stories.json"))

    @Test
    fun withoutAWishTheShelfIsAskedFirst() = runTest {
        val client = FakeClient { throw IOException("no new story") }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        assertEquals(StoryRepo.Status.WRITING, r.status.value)
        advanceUntilIdle()
        assertEquals(0, client.calls)
        assertEquals(StoryRepo.Status.READY, r.status.value)
        assertEquals("a".repeat(32), r.preview.value?.storyId)
        val asked = client.libraryRequests.single()
        assertEquals(setOf("ages", "minutes", "friend", "playable", "allowFresh", "season"), asked.keys().asSequence().toSet())
        assertEquals(true, asked.getBoolean("allowFresh"))
        assertEquals("BANTE", asked.getString("friend"))
        assertEquals(10, asked.getInt("minutes"))
        val keys = asked.getJSONArray("playable").let { a -> List(a.length()) { a.getString(it) } }
        assertTrue("Q:MATH:FAN:5" in keys)
        assertFalse("BELT" in keys)
        assertEquals(goodPuzzles, r.take(Setup(), shelfCar)!!.puzzles)
    }

    @Test
    fun aShelfStoryThisCarHeardIsPassedOver() = runTest {
        val heard = heardFile().also { it.add("a".repeat(32)) }
        val fresh = storyOf("f".repeat(32))
        val client = FakeClient { fresh }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heard)
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals("f".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun aShelfStoryWhoseQuestionTheAppDoesNotShipIsPassedOver() = runTest {
        val base = storyOf("a".repeat(32))
        val other = base.copy(scenes = base.scenes + ("Q:MATH:FAN:5" to base.scenes.getValue("Q:MATH:FAN:5")
            .copy(question = Question(QuizKind.MATH, "다른 질문은?", expr = "9+9"))))
        val client = FakeClient { throw IOException("no new story") }
        client.shelf = { listOf(shelved("a".repeat(32), story = other), shelved("b".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals(0, client.calls)
        assertEquals("b".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun aShelfStoryWhoseHintIsNotTheAppsIsPassedOver() = runTest {
        val base = storyOf("a".repeat(32))
        val other = base.copy(scenes = base.scenes + ("Q:MATH:FAN:5" to base.scenes.getValue("Q:MATH:FAN:5")
            .copy(hint = "정답은 5야!")))
        val client = FakeClient { throw IOException("no new story") }
        client.shelf = { listOf(shelved("a".repeat(32), story = other), shelved("b".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals("b".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun aShelfStoryThatDoesNotFitIsPassedOver() = runTest {
        // the car already shows wind 2, so a story asking for it cannot be played
        val showing = listOf(listOf("Q:MATH:FAN:2")) + goodSlots.drop(1)
        val client = FakeClient { throw IOException("no new story") }
        client.shelf = { listOf(shelved("a".repeat(32), showing), shelved("b".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals("b".repeat(32), r.preview.value?.storyId)
        assertEquals(goodPuzzles, r.take(Setup(), shelfCar)!!.puzzles)
    }

    @Test
    fun aQuestionOfAnAlternateKeyIsCheckedToo() = runTest {
        val slots = listOf(goodSlots[0], goodSlots[1], goodSlots[2], listOf("Q:REASON:AC:1", "Q:REASON:AC:0"))
        val base = storyOf("a".repeat(32))
        val off = base.scenes.getValue("BELT").copy(question = Question(QuizKind.REASON, "끄는 이야기, 차 버튼으로 도와줘!"))
        val story = base.copy(scenes = base.scenes + ("Q:REASON:AC:0" to off))
        val withOff = QuestionBank(shelfBankEntries + QuestionBank.Entry(
            QuizKind.REASON, Dial.AC, Age.CAPTAIN, "끄는 이야기, 차 버튼으로 도와줘!", "딸깍 소리!", 0))
        for ((bank, picked) in listOf(shelfBank to false, withOff to true)) {
            val client = FakeClient { throw IOException("no new story") }
            client.shelf = { listOf(shelved("a".repeat(32), slots, story)) }
            val r = repoWith(client, keep = null, bank = bank, heard = HeardStories(tmp.newFile().also { it.delete() }))
            r.prepare(Setup(), shelfCar)
            advanceUntilIdle()
            assertEquals(if (picked) StoryRepo.Status.READY else StoryRepo.Status.OFFLINE, r.status.value)
        }
    }

    @Test
    fun aShelfStoryWithAKeyThisAppDoesNotKnowIsPassedOver() = runTest {
        val unknown = listOf(listOf("Q:MATH:WIPER:1")) + goodSlots.drop(1)
        val client = FakeClient { throw IOException("no new story") }
        client.shelf = { listOf(shelved("a".repeat(32), unknown), shelved("b".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals("b".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun aShelfStoryWithNoSlotsIsPassedOver() = runTest {
        val client = FakeClient { throw IOException("no new story") }
        client.shelf = { listOf(shelved("a".repeat(32), emptyList()), shelved("b".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals("b".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun anEmptyShelfLeadsToANewStoryAndBackToTheShelfIfThatFails() = runTest {
        val client = FakeClient { throw IOException("server down") }
        client.shelf = { req -> if (req.getBoolean("allowFresh")) emptyList() else listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals(listOf(true, false), client.libraryRequests.map { it.getBoolean("allowFresh") })
        assertEquals(StoryRepo.Status.READY, r.status.value)
        assertEquals("a".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun aShelfThatCannotBeReachedLeadsToANewStory() = runTest {
        val client = FakeClient { throw IOException("server down") }
        client.shelf = { throw IOException("429") }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals(1, client.libraryRequests.size)
        assertEquals(StoryRepo.Status.OFFLINE, r.status.value)
        assertNull(r.preview.value)
    }

    @Test
    fun aShelfWithNothingForThisCarLeavesTheAppOffline() = runTest {
        val client = FakeClient { throw IOException("server down") }
        client.shelf = { listOf(shelved("a".repeat(32), listOf(listOf("Q:MATH:FAN:2")))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals(StoryRepo.Status.OFFLINE, r.status.value)
        // not empty, so the shelf is not asked again
        assertEquals(1, client.libraryRequests.size)
    }

    @Test
    fun aWishAsksTheShelfForThatSameWishFirst() = runTest {
        val client = FakeClient { storyOf("f".repeat(32)) }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(wish = "  공룡 섬에서 펼쳐지는 보물찾기 이야기 "), shelfCar)
        advanceUntilIdle()
        val asked = client.libraryRequests.single()
        assertEquals("공룡 섬에서 펼쳐지는 보물찾기 이야기", asked.getString("wish"))
        assertTrue(asked.getString("season") in setOf("SPRING", "SUMMER", "AUTUMN", "WINTER"))
        assertEquals(0, client.calls)
        assertEquals("a".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun theLogSaysWhereTheStoryCameFrom() = runTest {
        val lines = mutableListOf<String>()
        val client = FakeClient { storyOf("f".repeat(32)) }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val heard = heardFile()
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heard, log = { lines += it })
        val wish = Setup(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기")
        r.prepare(wish, shelfCar)
        advanceUntilIdle()
        r.takeWritten()
        r.prepare(wish, shelfCar)
        advanceUntilIdle()
        assertEquals(listOf("이야기 창고에서 꺼냄: ${"a".repeat(32)}", "이야기 새로 만듦: ${"f".repeat(32)}"), lines)
    }

    @Test
    fun aWishWithEveryShelfStoryHeardIsWrittenNew() = runTest {
        val heard = heardFile().also { it.add("a".repeat(32)) }
        val client = FakeClient { storyOf("f".repeat(32)) }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heard)
        r.prepare(Setup(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기"), shelfCar)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals("f".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun aShelfStoryFoundForPlannedPuzzlesBringsItsOwnAndIsHeard() = runTest {
        val heard = heardFile()
        val client = FakeClient { storyOf("f".repeat(32)) }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heard)
        val planned = listOf(Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1), Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5), Puzzle.BeltMission)
        val wish = Setup(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기")
        r.prepare(wish, shelfCar, planned)
        advanceUntilIdle()
        assertEquals(0, client.calls)
        val ready = r.takeWritten()!!
        assertEquals("a".repeat(32), ready.story.storyId)
        assertEquals(goodPuzzles, ready.puzzles)
        assertTrue("a".repeat(32) in heard)
        assertNull(r.takeWritten())
    }

    @Test
    fun aStoryWrittenForPlannedPuzzlesKeepsThemAndIsHeard() = runTest {
        val heard = heardFile()
        val client = FakeClient { storyOf("f".repeat(32)) }
        client.shelf = { emptyList() }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heard)
        val planned = listOf(Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5), Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1), Puzzle.BeltMission)
        r.prepare(Setup(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기"), shelfCar, planned)
        advanceUntilIdle()
        assertEquals(1, client.calls)
        val ready = r.takeWritten()!!
        assertEquals(planned, ready.puzzles)
        assertTrue("f".repeat(32) in heard)
    }

    @Test
    fun withoutABankTheShelfIsNotAsked() = runTest {
        for (bank in listOf(null, QuestionBank(emptyList()))) {
            val client = FakeClient { storyOf("f".repeat(32)) }
            client.shelf = { listOf(shelved("a".repeat(32))) }
            val r = repoWith(client, keep = null, bank = bank, heard = heardFile())
            r.prepare(Setup(), shelfCar)
            advanceUntilIdle()
            assertEquals(0, client.libraryRequests.size)
            assertEquals(1, client.calls)
        }
    }

    @Test
    fun aTakenStoryIsRememberedAsHeard() = runTest {
        val heard = heardFile()
        val r = repoWith(FakeClient { throw IOException("x") }, heard = heard)
        assertFalse("7b7c564a6a284c9881821fe55b9ae1a5" in heard)
        assertNotNull(r.take(Setup(), parked))
        assertTrue("7b7c564a6a284c9881821fe55b9ae1a5" in heard)
    }

    @Test
    fun aStoryWrittenHereIsNotOfferedBackFromTheShelf() = runTest {
        var n = 0
        val client = FakeClient { storyOf((if (n++ == 0) "e" else "f").repeat(32)) }
        client.shelf = { listOf(shelved("e".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        // the first story is written (the shelf's candidate is the one this car then hears: take marks it)
        client.shelf = { emptyList() }
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals("e".repeat(32), r.take(Setup(), shelfCar)!!.story.storyId)
        client.shelf = { listOf(shelved("e".repeat(32))) }
        r.prepare(Setup(), shelfCar)
        advanceUntilIdle()
        assertEquals(2, client.calls)
        assertEquals("f".repeat(32), r.preview.value?.storyId)
    }

    @Test
    fun nothingIsAskedBeforeTheCarReportsItsDevices() = runTest {
        val client = FakeClient { storyOf("f".repeat(32)) }
        client.shelf = { listOf(shelved("a".repeat(32))) }
        val r = repoWith(client, keep = null, bank = shelfBank, heard = heardFile())
        r.prepare(Setup(), parked.copy(devices = emptySet()))
        advanceUntilIdle()
        assertEquals(0, client.libraryRequests.size)
        assertEquals(0, client.calls)
        assertEquals(StoryRepo.Status.NONE, r.status.value)
    }

    @Test
    fun theShelfAnswerIsReadIntoStories() {
        val one = JSONObject(stored).getJSONObject("story")
        val answer = JSONObject().put("stories", org.json.JSONArray()
            .put(JSONObject().put("puzzles", org.json.JSONArray(goodSlots.map { org.json.JSONArray(it) })).put("episode", one))
            .put(JSONObject().put("puzzles", org.json.JSONArray()).put("episode", JSONObject().put("broken", 1))))
        val got = StoryClient.parseShelves(answer.toString())
        assertEquals(1, got.size)
        assertEquals(goodSlots, got[0].slots)
        assertEquals("7b7c564a6a284c9881821fe55b9ae1a5", got[0].story.storyId)
        assertTrue(runCatching { StoryClient.parseShelves("not json") }.exceptionOrNull() is IOException)
    }
}
