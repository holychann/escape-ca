package kr.escape.car.story

import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Rhythm
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class StoryTest {
    private val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet())

    @Test
    fun keysUseTheServerFormat() {
        assertEquals("WIND:FACE:GENTLE", StoryKeys.key(Puzzle.WindPath(FanDir.FACE, 1..2)))
        assertEquals("WIND:FLOOR:STRONG", StoryKeys.key(Puzzle.WindPath(FanDir.FLOOR, 6..8)))
        assertEquals("DUST:OUTSIDE", StoryKeys.key(Puzzle.DustCloud(fromOutside = true)))
        assertEquals("ROOMS:RIGHT", StoryKeys.key(Puzzle.TwoRooms(penguinLeft = false)))
        assertEquals("FOG", StoryKeys.key(Puzzle.FoggyWindow))
        assertEquals("SEAT:REAR_LEFT", StoryKeys.key(Puzzle.SeatSearch(Seat.REAR_LEFT)))
        assertEquals(
            "TOGETHER:CHILD_ONCE_PARENT_TWICE",
            StoryKeys.key(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.CHILD_ONCE_PARENT_TWICE)),
        )
        assertEquals("BELT", StoryKeys.key(Puzzle.BeltMission))
    }

    @Test
    fun variantsCoverEveryAnswerARefreshCanPick() {
        val states = listOf(
            parked,
            parked.copy(recirculation = true, fanDirection = FanDir.FACE, fanSpeed = 1, dual = true, tempLeft = 18f, tempRight = 26f),
            parked.copy(recirculation = false, fanDirection = FanDir.FLOOR, fanSpeed = 7, dual = true, tempLeft = 26f, tempRight = 18f,
                readingLights = mapOf(Seat.DRIVER to true, Seat.REAR_LEFT to true)),
        )
        repeat(100) { seed ->
            for (p in Planner.plan(Setup(minutes = 15), parked, Random(seed))) {
                val keys = StoryKeys.variants(p).map(StoryKeys::key)
                assertEquals(p, StoryKeys.variants(p).first())
                for (s in states) assertTrue(keys.contains(StoryKeys.key(Planner.refresh(p, s, Random(seed)))))
            }
        }
    }

    @Test
    fun storedKeysComeBackAsTheSamePuzzles() {
        repeat(100) { seed ->
            for (p in Planner.plan(Setup(minutes = 15), parked, Random(seed))) {
                assertEquals(p, StoryKeys.puzzle(StoryKeys.key(p), parked.fanSpeedRange, Seat.REAR_RIGHT))
            }
        }
        assertNull(StoryKeys.puzzle("SEAT:TRUNK", 1..8, Seat.REAR_RIGHT))
    }

    private val json = """
        {"storyId":"0123456789abcdef0123456789abcdef","model":"m","theme":"SEA_SUB","title":"잠수함 대소동",
         "intro":"안녕!","success":["최고야!","해냈어!"],"treasureGift":"선물!","treasureBadge":"배지!",
         "scenes":{"ROOMS:LEFT":{"bubble":"펭귄 방은?","speech":"펭귄은 왼쪽 방이야. 안전 지킴이에게 알려 주자.","hint":"왼쪽!"}},
         "dropped":["ROOMS:RIGHT"]}
    """.trimIndent()

    @Test
    fun storyChangesOnlyTheWords() {
        val story = Story.parse(JSONObject(json))
        val left = story.scene(Puzzle.TwoRooms(penguinLeft = true))
        val base = Lines.scene(Puzzle.TwoRooms(penguinLeft = true))
        assertEquals("펭귄 방은?", left.bubble)
        assertEquals(base.pictures, left.pictures)
        assertEquals(base.answer, left.answer)
        // dropped by the server's checker: the app's own lines
        assertEquals(Lines.scene(Puzzle.TwoRooms(penguinLeft = false)), story.scene(Puzzle.TwoRooms(penguinLeft = false)))
        assertEquals("theme_sea_sub", story.themePicture)
        assertEquals(listOf("theme_sea_sub"), story.scene(Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME)).pictures)
        assertEquals("해냈어!", story.success(3))
        assertEquals(story, Story.parse(story.toJson()))
    }

    @Test
    fun requestCarriesOnlyEnumsNumbersAndKeys() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 18, 30) }
        val puzzles = listOf(Puzzle.TwoRooms(penguinLeft = true), Puzzle.BeltMission)
        val body = StoryRepo.request("00000000-0000-0000-0000-000000000000", Setup(), puzzles, now)
        assertEquals(setOf("installId", "ages", "minutes", "context", "puzzles"), body.keySet())
        assertEquals("AUTUMN", body.getJSONObject("context").getString("season"))
        assertEquals("EVENING", body.getJSONObject("context").getString("timeOfDay"))
        assertEquals("""[["ROOMS:LEFT","ROOMS:RIGHT"],["BELT"]]""", body.getJSONArray("puzzles").toString())
    }
}
