package kr.escape.car.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test
    fun tagsNeverReachTheScreen() {
        assertEquals("와, 해냈어! 정말 최고야.", Subtitles.clean("[excited] 와, 해냈어! <short pause> 정말 {laugh} 최고야."))
        val pieces = Subtitles.pieces("[whispering] 비밀인데, <laugh> 보물은 바로 여기 있어! [excited] 찾았다!")
        assertTrue(pieces.none { '[' in it || '<' in it || '{' in it })
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
