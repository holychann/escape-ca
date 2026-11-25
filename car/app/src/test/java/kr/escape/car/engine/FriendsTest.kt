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

    private fun book() = FriendBook(tmp.root.resolve("book"))

    @Test
    fun sixBaseFriendsAreOpenFromTheStartAndThreeRareOnesAreNot() {
        val b = Book()
        assertEquals(6, Friend.BASE.size)
        assertEquals(listOf(Friend.IO, Friend.SSOLRANG, Friend.CASPIN), Friend.RARE)
        assertTrue(Friend.BASE.all(b::isOpen))
        assertTrue(Friend.RARE.none(b::isOpen))
        assertEquals(Friend.BASE, b.open)
    }

    @Test
    fun eachFinishedEpisodeIsOneHeartUpToFiveAndIsKept() {
        val b = book()
        repeat(7) { b.played(Friend.KONYANG) }
        assertEquals(Friend.MAX_HEARTS, b.book.value.hearts(Friend.KONYANG))
        b.played(Friend.RIA)
        val again = book().book.value
        assertEquals(5, again.hearts(Friend.KONYANG))
        assertEquals(1, again.hearts(Friend.RIA))
        assertEquals(0, again.hearts(Friend.BANTE))
    }

    @Test
    fun aBaseFriendAtFullHeartsOpensTheNextRareFriendOnce() {
        val b = book()
        repeat(4) { b.played(Friend.BANTE) }
        assertFalse(b.book.value.canInvite(Friend.BANTE))
        assertNull(b.invite(Friend.BANTE))
        b.played(Friend.BANTE)
        assertTrue(b.book.value.canInvite(Friend.BANTE))
        assertEquals(Friend.IO, b.invite(Friend.BANTE))
        // one invitation per friend
        assertFalse(b.book.value.canInvite(Friend.BANTE))
        assertNull(b.invite(Friend.BANTE))
        assertTrue(book().book.value.isOpen(Friend.IO))

        repeat(5) { b.played(Friend.POTTO) }
        assertEquals(Friend.SSOLRANG, b.invite(Friend.POTTO))
        repeat(5) { b.played(Friend.TUBUGI) }
        assertEquals(Friend.CASPIN, b.invite(Friend.TUBUGI))
        // all three are open: nobody is asked to go and meet another
        repeat(5) { b.played(Friend.PAPPI) }
        assertFalse(b.book.value.canInvite(Friend.PAPPI))
        assertEquals(Friend.entries, book().book.value.open)
    }

    @Test
    fun aRareFriendGetsHeartsButInvitesNobody() {
        val b = book()
        repeat(5) { b.played(Friend.BANTE) }
        b.invite(Friend.BANTE)
        repeat(5) { b.played(Friend.IO) }
        assertFalse(b.book.value.canInvite(Friend.IO))
    }

    @Test
    fun anOldOrBrokenBookStartsEmpty() {
        tmp.root.resolve("book").mkdirs()
        tmp.root.resolve("book/book.json").writeText("""{"crew":{"PENGUIN_CAPTAIN":3},"guests":[]}""")
        assertEquals(Book(), book().book.value)
        tmp.root.resolve("book/book.json").writeText("not json")
        assertEquals(Book(), book().book.value)
    }
}
