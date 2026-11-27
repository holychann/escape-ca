package kr.escape.car.voice

import java.io.File

/**
 * Where a recording goes quiet between words: the friend's voice pauses at a comma and between sentences, which is
 * where the subtitles turn to their next piece. The recording has no word timings, so the quiet is read from the sound
 * itself (a 16-bit PCM WAV, as the story server makes it).
 */
object Pauses {
    /** A quiet stretch, from [startMs] to [endMs] after the start of the recording. */
    data class Gap(val startMs: Long, val endMs: Long) {
        val middleMs: Long get() = (startMs + endMs) / 2
    }

    const val FRAME_MS = 20
    /** Shorter than this is the space between two syllables, not a pause. */
    const val MIN_GAP_MS = 150

    fun of(file: File): List<Gap> = runCatching { of(file.readBytes()) }.getOrDefault(emptyList())

    /** The pauses inside [wav], not counting the quiet before the first sound and after the last. */
    fun of(wav: ByteArray): List<Gap> {
        val (rate, channels, data) = pcm(wav) ?: return emptyList()
        val perFrame = rate * FRAME_MS / 1000
        val levels = IntArray(data.size / channels / perFrame) { f ->
            var sum = 0L
            for (i in 0 until perFrame) sum += kotlin.math.abs(data[(f * perFrame + i) * channels].toInt())
            (sum / perFrame).toInt()
        }
        return gaps(levels)
    }

    /**
     * The quiet runs in [levels] (the loudness of each [FRAME_MS] frame) at least [MIN_GAP_MS] long and with sound on
     * both sides. Quiet is measured against how loud the voice is, so a soft line finds its pauses too.
     */
    fun gaps(levels: IntArray): List<Gap> {
        if (levels.isEmpty()) return emptyList()
        val loud = levels.sorted()[(levels.size * 9) / 10]
        val quiet = maxOf(120, loud / 8)
        val sound = levels.indices.filter { levels[it] > quiet }
        if (sound.isEmpty()) return emptyList()
        val out = mutableListOf<Gap>()
        var from = -1
        for (f in sound.first()..sound.last()) {
            if (levels[f] <= quiet) {
                if (from < 0) from = f
            } else if (from >= 0) {
                if ((f - from) * FRAME_MS >= MIN_GAP_MS) out += Gap(from.toLong() * FRAME_MS, f.toLong() * FRAME_MS)
                from = -1
            }
        }
        return out
    }

    /** The sample rate, the channel count and the 16-bit samples of a PCM WAV; null for anything else. */
    private fun pcm(wav: ByteArray): Triple<Int, Int, ShortArray>? {
        fun int(at: Int) = (wav[at].toInt() and 0xff) or ((wav[at + 1].toInt() and 0xff) shl 8) or
            ((wav[at + 2].toInt() and 0xff) shl 16) or ((wav[at + 3].toInt() and 0xff) shl 24)
        fun short(at: Int) = (wav[at].toInt() and 0xff) or ((wav[at + 1].toInt() and 0xff) shl 8)
        fun id(at: Int) = String(wav, at, 4, Charsets.US_ASCII)
        if (wav.size < 12 || id(0) != "RIFF" || id(8) != "WAVE") return null
        var at = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (at + 8 <= wav.size) {
            val size = int(at + 4)
            val body = at + 8
            when (id(at)) {
                "fmt " -> {
                    if (short(body) != 1) return null
                    channels = short(body + 2)
                    rate = int(body + 4)
                    bits = short(body + 14)
                }
                "data" -> {
                    if (bits != 16 || channels < 1 || rate <= 0) return null
                    val end = minOf(wav.size, body + size)
                    val samples = ShortArray((end - body) / 2) { i -> short(body + i * 2).toShort() }
                    return Triple(rate, channels, samples)
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }
}
