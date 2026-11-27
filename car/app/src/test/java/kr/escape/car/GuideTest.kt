package kr.escape.car

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kr.escape.car.engine.Book
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Setup
import kr.escape.car.story.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {
    private val screen = Size(1920f, 1080f)
    private val card = Size(640f, 400f)

    @Test
    fun theGuideIsForACarWithNothingPlayedYet() {
        assertTrue(Guide.fresh(book = Book(), saved = null))
        // a family that has played before already knows the way
        assertFalse(Guide.fresh(book = Book(hearts = mapOf(Friend.BANTE to 1)), saved = null))
        val saved = Progress(Setup(), listOf(Puzzle.BeltMission), next = 0, stars = 0, own = emptyMap(), story = null)
        assertFalse(Guide.fresh(book = Book(), saved = saved))
    }

    @Test
    fun theCardGoesOnThePreferredSideWhenItFits() {
        val hole = Rect(40f, 300f, 720f, 500f)
        val at = Guide.place(hole, card, screen, gap = 28f, margin = 24f, prefer = Side.RIGHT)
        assertEquals(748f, at.x)
        // vertically on the hole's middle
        assertEquals(200f, at.y)
    }

    @Test
    fun theCardMovesToAnotherSideWhenThePreferredOneHasNoRoom() {
        // a wide shelf at the bottom: no room on its right, left or below, so the card goes above it
        val shelf = Rect(300f, 820f, 1880f, 1050f)
        val at = Guide.place(shelf, card, screen, gap = 28f, margin = 24f, prefer = Side.BELOW)
        assertEquals(820f - 28f - 400f, at.y)
        assertEquals((300f + 1880f) / 2 - 320f, at.x)
    }

    @Test
    fun theCardStaysInsideTheScreenAndGoesInTheMiddleWithNoHole() {
        // a hole near the top: the card on its right is pushed down to the margin, never off the screen
        val at = Guide.place(Rect(400f, 20f, 800f, 90f), card, screen, gap = 28f, margin = 24f, prefer = Side.RIGHT)
        assertEquals(24f, at.y)
        assertEquals(Offset(640f, 340f), Guide.place(null, card, screen, gap = 28f, margin = 24f, prefer = Side.RIGHT))
    }

    @Test
    fun twoPartsAreLeftBrightTogether() {
        val both = Guide.around(listOf(Rect(40f, 300f, 700f, 380f), Rect(40f, 400f, 720f, 480f)))
        assertEquals(Rect(40f, 300f, 720f, 480f), both)
        assertEquals(null, Guide.around(emptyList()))
    }

    @Test
    fun theTwoScreensShareTheGuideWithoutSayingAnythingTwice() {
        val all = Guide.SETUP + Guide.INTRO
        assertTrue(Guide.SETUP.size <= 4 && Guide.INTRO.size <= 3)
        // each part of a screen is pointed at once, and no page repeats another
        assertEquals(all.flatMap { it.spots }.size, all.flatMap { it.spots }.toSet().size)
        assertEquals(all.size, all.map { it.title }.toSet().size)
        assertEquals(all.mapNotNull { it.line }.size, all.mapNotNull { it.line }.toSet().size)
        // 공조 AUTO is the episode screen's own notice; the guide does not say it again
        assertFalse(all.any { "AUTO" in it.title || "AUTO" in it.line.orEmpty() })
    }

    @Test
    fun everyPageIsShortAndPointsAtThePartItTalksAbout() {
        for (step in Guide.SETUP + Guide.INTRO) {
            // a short title and at most one short line: the screen itself says the rest
            assertTrue(step.title.isNotBlank() && step.title.length <= 18)
            assertTrue(step.line == null || step.line!!.length <= 30)
            assertTrue(step.spots.isNotEmpty())
            // no middle dot in the guide's writing
            assertFalse(listOfNotNull(step.title, step.line).any { '·' in it })
        }
    }
}
