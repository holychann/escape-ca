package kr.escape.car.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlacesTest {
    @Test
    fun theTwelvePlacesAreDifferentAndFitThePlaceBox() {
        assertEquals(12, Places.ALL.size)
        assertEquals(Places.ALL.size, Places.ALL.toSet().size)
        // the place box takes 20 characters (MainActivity PLACE_MAX)
        assertTrue(Places.ALL.all { it.isNotBlank() && it.length <= 20 && it == it.trim() })
    }

    @Test
    fun theDiceNeverShowsThePlaceAlreadyInTheBoxAndReachesEveryOther() {
        val rnd = Random(7)
        var place = ""
        val seen = mutableSetOf<String>()
        repeat(300) {
            val next = Places.next(place, rnd)
            assertNotEquals(place, next)
            assertTrue(next in Places.ALL)
            seen += next
            place = next
        }
        assertEquals(Places.ALL.toSet(), seen)
        // a place the family typed with spaces around it still counts as that place
        repeat(50) { assertNotEquals("공룡 섬", Places.next("  공룡 섬 ", rnd)) }
    }
}
