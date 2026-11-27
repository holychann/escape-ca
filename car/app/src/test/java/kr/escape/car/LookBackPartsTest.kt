package kr.escape.car

import kr.escape.car.LookBackParts.Row
import kr.escape.car.voice.Said
import org.junit.Assert.assertEquals
import org.junit.Test

class LookBackPartsTest {
    @Test
    fun each_part_of_the_episode_has_its_title() {
        assertEquals("시작", LookBackParts.of(GameUi(phase = Phase.INTRO)))
        assertEquals("퍼즐 1", LookBackParts.of(GameUi(phase = Phase.PLAYING, index = 0)))
        // the line after the answer stays under the puzzle it answers
        assertEquals("퍼즐 3", LookBackParts.of(GameUi(phase = Phase.SOLVED, index = 2)))
        assertEquals("마무리", LookBackParts.of(GameUi(phase = Phase.TREASURE)))
        assertEquals(null, LookBackParts.of(GameUi(phase = Phase.SETUP)))
    }

    @Test
    fun a_title_comes_once_before_the_first_line_of_its_part() {
        val said = listOf(
            Said("공조 오토를 꺼 주세요.", "시작"),
            Said("안녕, 탐험대장!", "시작"),
            Said("독서등을 켜 줘!", "퍼즐 1"),
            Said("환해졌어!", "퍼즐 1"),
            Said("다시 생각해 볼까?", null),
            Said("바람을 줄여 줘!", "퍼즐 2"),
        )
        assertEquals(
            listOf(
                Row.Title("시작"), Row.Line("공조 오토를 꺼 주세요."), Row.Line("안녕, 탐험대장!"),
                Row.Title("퍼즐 1"), Row.Line("독서등을 켜 줘!"), Row.Line("환해졌어!"), Row.Line("다시 생각해 볼까?"),
                Row.Title("퍼즐 2"), Row.Line("바람을 줄여 줘!"),
            ),
            LookBackParts.rows(said),
        )
    }
}
