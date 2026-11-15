package kr.escape.car.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyCarPuzzleHasItsOwnFriend() {
        val puzzles = listOf(
            Puzzle.WindPath(FanDir.FLOOR, 1..2), Puzzle.DustCloud(true), Puzzle.TwoRooms(true),
            Puzzle.FoggyWindow, Puzzle.SeatSearch(Seat.DRIVER), Puzzle.Together(Seat.REAR_RIGHT, Rhythm.SAME_TIME),
        )
        assertEquals(Friend.entries.toSet(), puzzles.mapNotNull(Friend::of).toSet())
        assertNull(Friend.of(Puzzle.BeltMission))
    }

    private fun book(now: Long = 1_000L) = FriendBook(tmp.root.resolve("book"), legacy = tmp.root.resolve("friends.txt")) { now }

    private val bubbles = StoryFriend("방울 해마", "분홍 해마, 진주 목걸이", "노래를 좋아해", "반가워!")

    @Test
    fun aFriendIsNewOnlyOnceThenGetsCloserAndStaysInTheBook() {
        val b = book()
        assertTrue(b.meet(Friend.PENGUIN_CAPTAIN))
        assertFalse(b.meet(Friend.PENGUIN_CAPTAIN))
        assertFalse(b.meet(Friend.PENGUIN_CAPTAIN))
        assertTrue(b.meet(Friend.FENNEC_FOX))
        val again = book().book.value
        assertEquals(mapOf(Friend.PENGUIN_CAPTAIN to 3, Friend.FENNEC_FOX to 1), again.crew)
        assertEquals(setOf(Friend.PENGUIN_CAPTAIN, Friend.FENNEC_FOX), again.met)
        assertEquals(Friend.MAX_HEARTS, Friend.hearts(9))
    }

    @Test
    fun theOldListOfNamesIsReadOnceAndUnknownNamesAreSkipped() {
        tmp.root.resolve("friends.txt").writeText("NOBODY\nOTTER_COOK\n")
        assertEquals(mapOf(Friend.OTTER_COOK to 1), book().book.value.crew)
    }

    @Test
    fun eachStoryBringsItsOwnFriendWithItsPortraitKeptInTheBook() {
        val drawn = tmp.newFolder("story_images", "s1").resolve("friend.webp").apply { writeText("face") }
        val b = book(now = 42L)
        val g = b.welcome("s1", bubbles, "바닷속 보물찾기", drawn)!!
        assertEquals("방울 해마", g.name)
        assertEquals(42L, g.metAt)
        // the story's own folder is cleared once the next story is ready; the book keeps its own copy
        drawn.parentFile!!.deleteRecursively()
        assertEquals("face", java.io.File(g.picture!!).readText())
        assertNull(b.welcome("s1", bubbles, "바닷속 보물찾기", null))

        val other = b.welcome("s2", bubbles.copy(name = "별똥 차장"), "별빛 기차", null)!!
        assertNull(other.picture)
        val again = book().book.value
        assertEquals(listOf("별똥 차장", "방울 해마"), again.guests.map { it.name })
        assertEquals("바닷속 보물찾기", again.guests[1].story)
        assertEquals(2, again.size)
    }
}
