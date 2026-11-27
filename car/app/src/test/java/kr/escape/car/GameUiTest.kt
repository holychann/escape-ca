package kr.escape.car

import kr.escape.car.engine.Friend
import kr.escape.car.engine.Setup
import kr.escape.car.story.Progress
import org.junit.Assert.assertEquals
import org.junit.Test

class GameUiTest {
    private val pappiEpisode = Progress(Setup(friend = Friend.PAPPI), emptyList(), next = 3, stars = 3, own = emptyMap(), story = null)

    @Test
    fun anEpisodeWaitingToGoOnShowsTheFriendItWasPlayedWith() {
        val ui = GameUi(phase = Phase.SETUP, setup = Setup(friend = Friend.BANTE))
        assertEquals(Friend.PAPPI, ui.friendShown(pappiEpisode))
    }

    @Test
    fun withNothingToGoOnTheSetupScreenShowsTheFriendPicked() {
        val ui = GameUi(phase = Phase.SETUP, setup = Setup(friend = Friend.BANTE))
        assertEquals(Friend.BANTE, ui.friendShown(null))
    }

    @Test
    fun duringAnEpisodeItsOwnFriendIsShown() {
        val ui = GameUi(phase = Phase.PLAYING, setup = Setup(friend = Friend.RIA))
        assertEquals(Friend.RIA, ui.friendShown(pappiEpisode))
    }
}
