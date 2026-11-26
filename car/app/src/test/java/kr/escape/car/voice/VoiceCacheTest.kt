package kr.escape.car.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class VoiceCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val line = "안녕, 탐험대장! 3 더하기 2만큼 바람을 세게 해 줘! 어떻게 하면 되는지 안전 지킴이에게 말해 줘!"

    private class Server(var up: Boolean = true) {
        val asked = mutableListOf<String>()
        fun speak(text: String): ByteArray {
            asked += text
            if (!up) throw IOException("HTTP 503")
            return "RIFF$text".toByteArray()
        }
    }

    @Test
    fun aLineIsFetchedOnceAndKept() {
        val server = Server()
        val cache = VoiceCache(tmp.root) { server.speak(it) }
        val first = cache.get(line)
        assertNotNull(first)
        assertEquals("RIFF$line", first!!.readText())
        // spaced differently it is the same line, as on the server
        assertEquals(first, cache.get("  ${line.replace(" ", "   ")} "))
        assertEquals(listOf(line), server.asked)
    }

    @Test
    fun aKeptLinePlaysWhenTheServerIsGone() {
        val server = Server()
        val cache = VoiceCache(tmp.root) { server.speak(it) }
        cache.get(line)
        server.up = false
        assertNotNull(VoiceCache(tmp.root) { server.speak(it) }.get(line))
        assertEquals(1, server.asked.size)
    }

    @Test
    fun aLineWithNoSoundIsNotKeptAndIsAskedAgainLater() {
        val server = Server(up = false)
        val cache = VoiceCache(tmp.root) { server.speak(it) }
        assertNull(cache.get(line))
        assertNull(cache.cached(line))
        assertEquals(0, tmp.root.listFiles()!!.count { it.name.endsWith(".wav") })
        server.up = true
        assertNotNull(cache.get(line))
        assertEquals(2, server.asked.size)
    }

    @Test
    fun anEmptyLineIsNeverAskedFor() {
        val server = Server()
        assertNull(VoiceCache(tmp.root) { server.speak(it) }.get("   "))
        assertEquals(emptyList<String>(), server.asked)
    }

    @Test
    fun theKeyIsTheTidiedWords() {
        assertEquals("가 나 다", VoiceCache.tidy("  가\n나\t\t다 "))
        assertEquals(VoiceCache.key("가 나"), VoiceCache.key(VoiceCache.tidy("가   나")))
        assertEquals(64, VoiceCache.key("가").length)
    }
}
