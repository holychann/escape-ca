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

    @Test
    fun aFriendIsNewOnlyOnceAndStaysInTheBook() {
        val file = tmp.root.resolve("friends.txt")
        val book = FriendBook(file)
        assertTrue(book.meet(Friend.PENGUIN_CAPTAIN))
        assertFalse(book.meet(Friend.PENGUIN_CAPTAIN))
        assertTrue(book.meet(Friend.FENNEC_FOX))
        assertEquals(setOf(Friend.PENGUIN_CAPTAIN, Friend.FENNEC_FOX), FriendBook(file).met.value)
    }

    @Test
    fun unknownNamesInTheFileAreSkipped() {
        val file = tmp.root.resolve("friends.txt").apply { writeText("NOBODY\nOTTER_COOK\n") }
        assertEquals(setOf(Friend.OTTER_COOK), FriendBook(file).met.value)
    }
}
