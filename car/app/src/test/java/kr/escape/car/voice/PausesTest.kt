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

    @Test
    fun aPauseBetweenWordsIsFoundButNotTheQuietAroundTheLine() {
        val gaps = Pauses.of(wav(300 to false, 800 to true, 400 to false, 900 to true, 500 to false))
        assertEquals(1, gaps.size)
        // the pause from 1100 ms to 1500 ms, to a frame
        assertTrue(gaps[0].toString(), kotlin.math.abs(gaps[0].startMs - 1100) <= Pauses.FRAME_MS)
        assertTrue(gaps[0].toString(), kotlin.math.abs(gaps[0].endMs - 1500) <= Pauses.FRAME_MS)
    }

    @Test
    fun aBreathBetweenSyllablesIsNotAPause() {
        assertEquals(emptyList<Pauses.Gap>(), Pauses.of(wav(600 to true, 60 to false, 600 to true)))
        // not a WAV at all: no pauses, never a crash
        assertEquals(emptyList<Pauses.Gap>(), Pauses.of(ByteArray(10)))
    }

    @Test
    fun aPieceTurnsWhenTheVoiceStartsAgainAfterTheNearestPause() {
        val pieces = listOf("냄새가 밖에서 바람을 타고 들어오니", "자동차 그림 안에서 화살표가 빙글 도는 버튼을 찾아봐.")
        // a pause well after the letters' share: the second piece waits for it
        val starts = Subtitles.starts(pieces, 6000, listOf(Pauses.Gap(2900, 3300)))
        assertEquals(listOf(0L, 3300L - Subtitles.TURN_LEAD_MS), starts)
        // with no pause in the recording the guess stands
        assertEquals(Subtitles.starts(pieces, 6000), Subtitles.starts(pieces, 6000, emptyList()))
    }
}
