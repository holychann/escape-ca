package kr.escape.car.voice

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lumi's lines as WAV files on the device, one per line exactly as it is said, fetched from the story server once
 * ([fetch]: the line's words in, WAV bytes out) and kept in [dir]. A line already here plays even when the server is
 * gone; a line that is neither here nor fetched has no sound (the screen shows it as a caption).
 */
class VoiceCache(private val dir: File, private val fetch: (String) -> ByteArray) {
    /** One lock per line, so a line being prefetched is not fetched a second time by say(). */
    private val locks = ConcurrentHashMap<String, Any>()

    /** The recording kept for [text], or null when there is none yet. */
    fun cached(text: String): File? = file(text).takeIf { it.isFile && it.length() > 0 }

    /** The recording for [text]: the one kept, or fetched now and kept; null when it cannot be had. Blocking. */
    fun get(text: String): File? {
        val line = tidy(text)
        if (line.isEmpty()) return null
        cached(line)?.let { return it }
        val lock = locks.getOrPut(key(line)) { Any() }
        synchronized(lock) {
            cached(line)?.let { return it }
            val bytes = runCatching { fetch(line) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val to = file(line)
            return runCatching {
                dir.mkdirs()
                val part = File(to.path + ".part")
                part.writeBytes(bytes)
                if (!part.renameTo(to)) error("could not keep $to")
                to
            }.getOrNull()
        }
    }

    private fun file(text: String) = File(dir, "${key(tidy(text))}.wav")

    companion object {
        /** Spaces collapsed and ends trimmed, the same way the server keeps it (server app/service.py tidy_line). */
        fun tidy(text: String): String = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        fun key(line: String): String =
            MessageDigest.getInstance("SHA-256").digest(line.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
