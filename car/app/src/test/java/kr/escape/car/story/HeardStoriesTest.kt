package kr.escape.car.story

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HeardStoriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val file get() = tmp.root.resolve("heard_stories.json")

    @Test
    fun remembersAcrossInstances() {
        HeardStories(file) { 1_000_000L }.add("a")
        val again = HeardStories(file) { 1_000_000L }
        assertTrue("a" in again)
        assertFalse("b" in again)
    }

    @Test
    fun forgetsAfterNinetyDays() {
        var now = 10 * day
        val heard = HeardStories(file) { now }
        heard.add("a")
        now = 10 * day + 89 * day
        assertTrue("a" in heard)
        assertTrue("a" in HeardStories(file) { now })
        now = 10 * day + 91 * day
        assertFalse("a" in heard)
        assertFalse("a" in HeardStories(file) { now })
        // a record from the future (the clock went backwards) is dropped too
        now = 5 * day
        assertFalse("a" in HeardStories(file) { now })
    }

    @Test
    fun keepsOnlyTheLatestFiveHundred() {
        val heard = HeardStories(file) { 1_000_000L }
        repeat(HeardStories.KEEP + 5) { heard.add("s$it") }
        assertFalse("s0" in heard)
        assertFalse("s4" in heard)
        assertTrue("s5" in heard)
        assertTrue("s${HeardStories.KEEP + 4}" in heard)
        val again = HeardStories(file) { 1_000_000L }
        assertFalse("s4" in again)
        assertTrue("s5" in again)
    }

    @Test
    fun aBrokenFileIsForgottenQuietly() {
        file.writeText("{not json")
        val heard = HeardStories(file) { 1_000_000L }
        assertFalse("a" in heard)
        heard.add("a")
        assertTrue("a" in HeardStories(file) { 1_000_000L })
    }
}
