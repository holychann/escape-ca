package kr.escape.car.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    private fun gaps(vararg ms: Long) = ms.toList().chunked(2).map { (a, b) -> Pauses.Gap(a, b) }

    @Test
    fun aPieceTurnsAtThePauseOfItsMarkNotAtABreathNearIt() {
        // the pauses read from the friend's real recording of this puzzle (27 s): each sentence ends in a long pause a
        // second or so after a short breath, which is where the piece used to turn
        val line = "산호 광장에서 보물 지도 첫 조각을 찾는데, 안전 지킴이 의자에만 차가운 바닷물 방울이 톡톡 떨어졌어. " +
            "함께 온 친구 자리가 차가우면 내 마음이 쓰이니까, 내가 먼저 데워 주고 싶어! 9 빼기 6은 얼마일까? " +
            "시트 열선을 그 수와 같은 단으로 맞춰 줘! 어떻게 하면 되는지 안전 지킴이에게 말해 줘!"
        val timeline = Pauses.Timeline(
            40, 26520,
            gaps(
                1240, 1400, 3120, 3680, 5400, 5720, 7180, 7520, 7560, 7920, 7980, 8160, 8740, 9600, 12000, 12220,
                13260, 13420, 13600, 14280, 15600, 15760, 15860, 16980, 17160, 17360, 18040, 18360, 18720, 18880,
                19000, 19820, 20800, 21180, 23080, 23700, 24820, 25160,
            ),
        )
        assertEquals(listOf(0L, 3560, 9480, 12100, 16860, 19700, 23580), Subtitles.starts(Subtitles.pieces(line), 26979, timeline))
    }

    @Test
    fun aMarkTheVoiceRunsThroughIsLeftOut() {
        // the real hello (15 s): no pause after "탐험대장!", yet the last piece still turns at the pause after "내려와."
        val line = "안녕, 탐험대장! 나는 강아지 반테야. 오늘은 바닷속 마을에 왔어. 가을 햇살이 물결 사이로 살랑살랑 내려와. " +
            "마을 어딘가에 숨은 진주 보물상자를 함께 찾아보자!"
        val timeline = Pauses.Timeline(0, 14800, gaps(1100, 1540, 3160, 3900, 5860, 6600, 8560, 8800, 10120, 11120, 13600, 13920))
        assertEquals(listOf(0L, 3780, 6480, 11000), Subtitles.starts(Subtitles.pieces(line), 15209, timeline))
    }

    @Test
    fun tagsNeverReachTheScreen() {
        assertEquals("와, 해냈어! 정말 최고야.", Subtitles.clean("[excited] 와, 해냈어! <short pause> 정말 {laugh} 최고야."))
        val pieces = Subtitles.pieces("[whispering] 비밀인데, <laugh> 보물은 바로 여기 있어! [excited] 찾았다!")
        assertTrue(pieces.none { '[' in it || '<' in it || '{' in it })
    }

    @Test
    fun aLetterCountShowsOnlyTheKoreanMeaning() {
        val line = "새싹이 목말라. apple, 사과를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!"
        assertEquals("새싹이 목말라. 사과를 영어로 쓰면 몇 글자일까? 그 수만큼 바람 세기를 맞춰 줘!", Subtitles.clean(line))
        assertTrue(Subtitles.pieces(line).none { it.contains("apple", ignoreCase = true) })
        // in capitals, a closed meaning (닭을), and the word said again elsewhere in the line
        assertEquals("닭을 영어로 쓰면 몇 글자일까? 닭 친구도 기다려!",
            Subtitles.clean("CHICKEN, 닭을 영어로 쓰면 몇 글자일까? chicken 친구도 기다려!"))
        assertEquals(listOf("cat" to "고양이"), Subtitles.spelled("cat, 고양이를 영어로 쓰면 몇 글자일까?"))
        // a number word or a sum is meant to be seen
        assertEquals("two는 숫자로 몇일까? 그 수만큼 바람 세기를 맞춰 줘!", Subtitles.clean("two는 숫자로 몇일까? 그 수만큼 바람 세기를 맞춰 줘!"))
        assertEquals("seven 빼기 five는 얼마일까?", Subtitles.clean("seven 빼기 five는 얼마일까?"))
        assertEquals("와, 해냈어!", Subtitles.clean("와, 해냈어!"))
    }

    @Test
    fun aLongLineIsCutIntoShortPiecesInOrder() {
        val line = "휴, 기관실이 고쳐졌어! 그런데 이번엔 식당 칸에서 수달 요리사가 큰일이래. " +
            "밖에서 먼지 구름이 몰려와서 수프에 먼지가 들어가겠어. 어떻게 하면 되는지 안전 지킴이에게 말해 줘!"
        val pieces = Subtitles.pieces(line)
        assertTrue(pieces.size >= 4)
        assertTrue(pieces.all { it.length <= Subtitles.MAX_CHARS })
        // nothing lost, nothing moved
        assertEquals(Subtitles.clean(line), pieces.joinToString(" "))
    }

    @Test
    fun aLongSentenceIsCutWhereItPausesIntoEvenPieces() {
        val pieces = Subtitles.pieces("오늘 밤 별 축제에 가야 하는데 별빛 기차 칸 곳곳이 고장 났대.")
        assertEquals(listOf("오늘 밤 별 축제에 가야 하는데", "별빛 기차 칸 곳곳이 고장 났대."), pieces)
    }

    @Test
    fun piecesAndTheirLinesBreakBetweenPhrases() {
        val pieces = Subtitles.pieces("냄새가 밖에서 바람을 타고 들어오니 자동차 그림 안에서 화살표가 빙글 도는 버튼을 찾아봐.")
        assertEquals(listOf("냄새가 밖에서 바람을 타고 들어오니", "자동차 그림 안에서 화살표가 빙글 도는 버튼을 찾아봐."), pieces)
        assertEquals("냄새가 밖에서\n바람을 타고 들어오니", Subtitles.lines(pieces[0]))
        assertEquals("자동차 그림 안에서 화살표가\n빙글 도는 버튼을 찾아봐.", Subtitles.lines(pieces[1]))
        // a piece that fits one line stays on one
        assertEquals("고쳤다! 최고야!", Subtitles.lines("고쳤다! 최고야!"))
        // two sentences in one piece break between them, never after the comma of the first
        assertEquals("안녕, 탐험대장!\n나는 강아지 반테야.", Subtitles.lines("안녕, 탐험대장! 나는 강아지 반테야."))
        // "마음이 / 쓰이니까" is never cut apart: "차가우면" ends a clause too, and a one-word line is too short
        val warm = Subtitles.pieces("함께 온 친구 자리가 차가우면 내 마음이 쓰이니까, 내가 먼저 데워 주고 싶어!")
        assertEquals(listOf("함께 온 친구 자리가 차가우면", "내 마음이 쓰이니까, 내가 먼저 데워 주고 싶어!"), warm)
        assertEquals("내 마음이 쓰이니까,\n내가 먼저 데워 주고 싶어!", Subtitles.lines(warm[1]))
    }

    @Test
    fun shortSentencesShareAPiece() {
        assertEquals(listOf("고쳤다! 우리 탐험대장 최고야!"), Subtitles.pieces("고쳤다!  우리 탐험대장 최고야!"))
    }

    @Test
    fun aSentenceLongerThanAPieceIsCutBetweenWords() {
        val pieces = Subtitles.pieces("가나다라마바사 ".repeat(10).trim())
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= Subtitles.MAX_CHARS })
        assertFalse(pieces.any { it.startsWith(" ") || it.endsWith(" ") })
    }

    @Test
    fun piecesAreTimedByTheirLetters() {
        val starts = Subtitles.starts(listOf("가나", "다라마바"), 6000)
        assertEquals(listOf(0L, 2000L), starts)
        assertEquals(listOf(0L), Subtitles.starts(listOf("한 줄"), 3000))
        assertEquals(Subtitles.MIN_PIECE_MS, Subtitles.readMs("짧다"))
        assertEquals(20 * Subtitles.READ_MS_PER_CHAR, Subtitles.readMs("가".repeat(20)))
        val (planStarts, totalMs) = Subtitles.readingPlan(listOf("짧다", "가".repeat(20)))
        assertEquals(listOf(0L, Subtitles.MIN_PIECE_MS), planStarts)
        assertEquals(Subtitles.MIN_PIECE_MS + 20 * Subtitles.READ_MS_PER_CHAR, totalMs)
        assertEquals(emptyList<Long>() to 0L, Subtitles.readingPlan(emptyList()))
    }
}
