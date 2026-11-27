package kr.escape.car.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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

    @Test
    fun aLineWithARespelledNameGetsANewFileAndNoOtherLineDoes() {
        fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(sha("반테야, 같이 찾자!"), VoiceCache.key("반테야, 같이 찾자!"))
        assertNotEquals(sha("파삐야, 같이 찾자!"), VoiceCache.key("파삐야, 같이 찾자!"))
        // a recording kept under the old name is not played; the line is fetched again
        val server = Server()
        File(tmp.root, "${sha("파삐야")}.wav").writeText("RIFF old")
        assertEquals("RIFF파삐야", VoiceCache(tmp.root) { server.speak(it) }.get("파삐야")!!.readText())
        assertEquals(listOf("파삐야"), server.asked)
    }
}
