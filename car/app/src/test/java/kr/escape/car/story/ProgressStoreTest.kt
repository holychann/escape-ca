package kr.escape.car.story

import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Setup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000_000L
    private val file get() = tmp.root.resolve("playing_episode.json")
    private val store get() = ProgressStore(file) { now }

    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L

    private val math = Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5)

    private fun story(images: Map<String, String> = emptyMap()) = Story.parse(JSONObject("""
        {"storyId":"7b7c564a6a284c9881821fe55b9ae1a5","theme":"SEA_SUB","title":"잠수함","intro":"안녕!",
         "treasureBadge":"배지!",
         "scenes":{"BELT":{"bubble":"딸깍!","speech":"벨트를 딸깍!","hint":"딸깍 소리!","result":"잘했어!"},
          "Q:MATH:FAN:5":{"bubble":"바람!","speech":"새싹이 목말라.","hint":"세어 봐!","picture":"p1",
           "question":{"kind":"MATH","text":"조개 2개에 3개를 더하면?","expr":"2+3","word":"","terms":[]},"result":"맞았어!"}}}
    """.trimIndent())).copy(images = images)

    private fun progress(story: Story? = story(), next: Int = 1, restUntil: Long? = null) = Progress(
        setup = Setup(minutes = 15, friend = Friend.entries.last { !it.rare }, wish = "공룡 섬"),
        puzzles = listOf(math, Puzzle.BeltMission),
        next = next,
        stars = 1,
        own = mapOf(StoryKeys.key(math) to (Question(QuizKind.MATH, "2+3은?", "2+3") to "세어 봐")),
        story = story,
        restUntil = restUntil,
    )

    private fun picture(name: String): String = tmp.newFile("$name.webp").absolutePath

    @Test
    fun aGameInProgressComesBackAsItWasSaved() {
        val saved = progress(story(mapOf("cover" to picture("cover"))))
        store.write(saved)
        assertEquals(saved, store.read())
    }

    @Test
    fun theAppsOwnStoryNeedsNoStory() {
        val saved = progress(story = null)
        store.write(saved)
        assertEquals(saved, store.read())
    }

    @Test
    fun aSaveIsKeptForADay() {
        store.write(progress())
        now += 23 * hour
        assertNotNull(store.read())
        now += 2 * hour
        assertNull(store.read())
        assertFalse(file.exists())
    }

    @Test
    fun aClockThatWentBackDropsTheSave() {
        store.write(progress())
        now -= 1
        assertNull(store.read())
        assertFalse(file.exists())
    }

    @Test
    fun aBrokenOrUnknownSaveIsDroppedQuietly() {
        file.writeText("{")
        assertNull(store.read())
        assertFalse(file.exists())

        store.write(progress())
        file.writeText(JSONObject(file.readText()).put("v", 2).toString())
        assertNull(store.read())

        store.write(progress())
        file.writeText(JSONObject(file.readText()).put("puzzles", JSONArray(listOf("WIND:FLOOR:STRONG"))).toString())
        assertNull(store.read())

        store.write(progress(next = 9))
        assertNull(store.read())
        assertFalse(file.exists())
    }

    @Test
    fun aPictureNoLongerOnTheDeviceIsLeftOut() {
        val gone = File(tmp.root, "gone/p1.webp").absolutePath
        store.write(progress(story(mapOf("cover" to picture("cover"), "p1" to gone))))
        assertEquals(setOf("cover"), store.read()!!.story!!.images.keys)
    }

    @Test
    fun restLeftCountsDownAndNeverExceedsTheRest() {
        assertEquals(0L, store.restLeftMs(progress(restUntil = null)))
        assertEquals(4 * minute, store.restLeftMs(progress(restUntil = now + 4 * minute)))
        // a clock that went back must not make the rest longer than the rest itself
        assertEquals(ProgressStore.REST_MS, store.restLeftMs(progress(restUntil = now + 30 * minute)))
        assertEquals(0L, store.restLeftMs(progress(restUntil = now - 1)))
    }

    @Test
    fun writingLeavesNoHalfFile() {
        store.write(progress())
        assertFalse(File(file.path + ".part").exists())
        assertNotNull(store.read())
    }
}
