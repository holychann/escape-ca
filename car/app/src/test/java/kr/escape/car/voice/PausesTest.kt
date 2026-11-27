package kr.escape.car.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

class PausesTest {
    private val rate = 24_000

    /** A mono 16-bit WAV of [parts]: (milliseconds, loud or quiet) one after another. */
    private fun wav(vararg parts: Pair<Int, Boolean>): ByteArray {
        val pcm = ByteArrayOutputStream()
        var n = 0
        for ((ms, loud) in parts) repeat(rate * ms / 1000) {
            val v = if (loud) (8000 * sin(2 * PI * 220 * n / rate)).toInt() else 0
            pcm.write(v and 0xff)
            pcm.write((v shr 8) and 0xff)
            n++
        }
        val data = pcm.toByteArray()
        val out = ByteArrayOutputStream()
        fun int(v: Int) = (0..3).forEach { out.write((v shr (8 * it)) and 0xff) }
        fun short(v: Int) = (0..1).forEach { out.write((v shr (8 * it)) and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(data.size); out.write(data)
        return out.toByteArray()
    }

    private fun near(expected: Long, actual: Long) =
        assertTrue("$actual, not $expected", kotlin.math.abs(actual - expected) <= Pauses.FRAME_MS)

    @Test
    fun theVoiceAndAPauseBetweenWordsAreFoundButNotTheQuietAroundTheLine() {
        val t = Pauses.of(wav(300 to false, 800 to true, 400 to false, 900 to true, 500 to false))!!
        near(300, t.startMs)
        near(2400, t.endMs)
        assertEquals(1, t.gaps.size)
        near(1100, t.gaps[0].startMs)
        near(1500, t.gaps[0].endMs)
        // the voice is heard for 800 + 900 ms; 1000 ms of it is reached 200 ms after the pause
        near(1700, t.voicedMs)
        near(1700, t.at(1000))
    }

    @Test
    fun aBreathBetweenSyllablesIsNotAPause() {
        assertEquals(emptyList<Pauses.Gap>(), Pauses.of(wav(600 to true, 60 to false, 600 to true))!!.gaps)
        // not a WAV at all, or no sound in it: nothing to time by, never a crash
        assertEquals(null, Pauses.of(ByteArray(10)))
        assertEquals(null, Pauses.of(wav(500 to false)))
    }

    @Test
    fun eachPieceTurnsAsTheVoiceStartsAgainAfterItsOwnPause() {
        // three pieces of the same length said at the same pace; the voice pauses after each of the first two
        val pieces = listOf("가나다라마바사아", "자차카타파하가나", "다라마바사아자차")
        val t = Pauses.Timeline(200, 6200, listOf(Pauses.Gap(1800, 2100), Pauses.Gap(3700, 4600)))
        assertEquals(listOf(0L, 2100L - Subtitles.TURN_LEAD_MS, 4600L - Subtitles.TURN_LEAD_MS), Subtitles.starts(pieces, 6400, t))
    }

    @Test
    fun aLongLineWithManyPausesDoesNotDrift() {
        // the first piece is said in three bits with long comma pauses, the second in one go
        val pieces = listOf("가나다라, 마바사아, 자차카타", "파하가나다라마바사아자차")
        val t = Pauses.Timeline(
            0, 5000, listOf(Pauses.Gap(500, 1300), Pauses.Gap(1800, 2600), Pauses.Gap(3100, 3500)),
        )
        // measured on the voice alone, the second piece comes up as the voice starts it again after the last pause
        assertEquals(3500L - Subtitles.TURN_LEAD_MS, Subtitles.starts(pieces, 5000, t)[1])
        // the share-of-the-whole guess counts the pauses as talking and turns while "자차카타" is still to come
        assertTrue(Subtitles.starts(pieces, 5000, null)[1] < 3100)
    }
}
