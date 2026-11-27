package kr.escape.car.story

import kr.escape.car.engine.Book
import kr.escape.car.engine.Friend
import kr.escape.car.voice.VoiceCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FriendIntroTest {
    @Test
    fun aNewFriendShowsItselfItsHeartsAndTheRareFriendsToMeet() {
        val cards = FriendIntro.cards(Friend.PAPPI, Book())
        assertEquals(FriendIntro.Card.Me(Friend.PAPPI), cards[0])
        assertEquals(FriendIntro.Card.Hearts(Friend.PAPPI, 0, canMeet = true), cards[1])
        assertEquals(FriendIntro.Card.Mystery(Friend.RARE), cards[2])
        assertEquals(FriendIntro.HEARTS, cards[1].line)
    }

    @Test
    fun aFriendThatOpenedItsRareFriendNoLongerPromisesOne() {
        val book = Book(hearts = mapOf(Friend.BANTE to 5), opened = mapOf(Friend.IO to Friend.BANTE))
        val hearts = FriendIntro.cards(Friend.BANTE, book)[1] as FriendIntro.Card.Hearts
        assertFalse(hearts.canMeet)
        assertEquals(null, hearts.line)
        assertEquals(FriendIntro.Card.Mystery(listOf(Friend.SSOLRANG, Friend.CASPIN)), FriendIntro.cards(Friend.BANTE, book)[2])
    }

    @Test
    fun withEveryRareFriendOpenThereIsNoMysteryCard() {
        val book = Book(opened = mapOf(Friend.IO to Friend.BANTE, Friend.SSOLRANG to Friend.RIA, Friend.CASPIN to Friend.POTTO))
        val cards = FriendIntro.cards(Friend.CASPIN, book)
        assertEquals(2, cards.size)
        assertFalse((cards[1] as FriendIntro.Card.Hearts).canMeet)
    }

    @Test
    fun theCarItLooksLikeTakesTheRightParticle() {
        assertEquals("투싼을 닮은 거북이", FriendIntro.looksLike(Friend.TUBUGI))
        assertEquals("아반떼를 닮은 강아지", FriendIntro.looksLike(Friend.BANTE))
        assertEquals("아이오닉을 닮은 여우", FriendIntro.looksLike(Friend.IO))
    }

    @Test
    fun everyFriendSaysItsOwnNameAndCar() {
        for (f in Friend.entries) {
            val line = FriendIntro.about(f)
            assertTrue(line, line.contains(f.label) && line.contains(f.car) && line.contains(f.animal))
        }
    }

    @Test
    fun everyCardLineShipsRecordedAndNothingElseDoes() {
        val names = FriendIntro.RECORDINGS.keys.map { "$it.wav" }.toSet()
        val shipped = File("src/main/assets/voice").listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(names, shipped)
        assertEquals(FriendIntro.ALL.toSet(), FriendIntro.RECORDINGS.values.toSet())
    }
}
