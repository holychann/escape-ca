package kr.escape.car.story

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How a picture stands on the story server: saved on the device now, still being drawn, or not there at all. */
enum class Fetched { SAVED, DRAWING, MISSING }

/** The server refused the family's story wish before writing anything; [reasons] say why, for the parent. */
class WishRejected(val reasons: List<String>) : IOException("wish rejected: $reasons")

/** A story another car got that the server offers again: the slots (answer keys) it was written for, and its words. */
data class Shelf(val slots: List<List<String>>, val story: Story)

/** The app talks only to its own story server; the AI keys live there, never in the APK. */
open class StoryClient(private val baseUrl: String) {
    /** Blocking. Throws [WishRejected] for a refused wish and IOException on any other failure (502: every draft failed). */
    open fun episode(request: JSONObject): Story = Story.parse(JSONObject(post("/v1/episode", request, readTimeoutMs = 300_000)))

    /**
     * Blocking, short waits (connect 3 s, read 5 s). Shelf stories offered for [request]; IOException on failure,
     * a 429 or a broken answer included.
     */
    open fun library(request: JSONObject): List<Shelf> =
        parseShelves(post("/v1/library", request, readTimeoutMs = 5_000, connectTimeoutMs = 3_000))

    open fun report(storyId: String, reason: String) {
        post("/v1/report", JSONObject().put("storyId", storyId).put("reason", reason), readTimeoutMs = 10_000)
    }

    /**
     * Blocking. One of Lumi's lines read aloud by the story server, exactly as given, as WAV bytes. Throws IOException
     * when the server has no sound for it (503) or cannot be reached; the app then shows the line as a caption.
     */
    open fun speak(installId: String, text: String): ByteArray {
        val conn = URL(baseUrl.trimEnd('/') + "/v1/speak").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            // one line takes a few seconds to make the first time, and is kept on the server after that
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("installId", installId).put("text", text)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IOException("story server /v1/speak: HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking. Saves one picture the server drew ([path] as the episode gave it) to [to]. */
    /**
     * One picture of a story: saved to [to] when the server has it, [Fetched.DRAWING] while it is still being drawn
     * (202), [Fetched.MISSING] when it is not there and will not be (404). IOException when the server cannot be reached.
     */
    open fun fetch(path: String, to: File): Fetched {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 60_000
            when (conn.responseCode) {
                202 -> return Fetched.DRAWING
                404 -> return Fetched.MISSING
                !in 200..299 -> throw IOException("story server $path: HTTP ${conn.responseCode}")
            }
            to.parentFile?.mkdirs()
            val part = File(to.path + ".part")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(to)) throw IOException("could not save $to")
            return Fetched.SAVED
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject, readTimeoutMs: Int, connectTimeoutMs: Int = 5_000): String {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 422) {
                val error = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val detail = runCatching { JSONObject(error).optJSONObject("detail") }.getOrNull()
                if (detail?.optString("error") == "wish_rejected") {
                    val r = detail.optJSONArray("reasons")
                    throw WishRejected(if (r == null) emptyList() else List(r.length()) { r.getString(it) })
                }
            }
            if (code !in 200..299) throw IOException("story server $path: HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The shelf's answer; a story that does not parse is left out, a broken answer is an IOException. */
        fun parseShelves(text: String): List<Shelf> {
            val items = try {
                JSONObject(text).getJSONArray("stories")
            } catch (e: JSONException) {
                throw IOException("story server /v1/library: broken answer", e)
            }
            return (0 until items.length()).mapNotNull { i ->
                runCatching {
                    val o = items.getJSONObject(i)
                    val slots = o.getJSONArray("puzzles").let { all ->
                        List(all.length()) { s -> all.getJSONArray(s).let { keys -> List(keys.length()) { keys.getString(it) } } }
                    }
                    Shelf(slots, Story.parse(o.getJSONObject("episode")))
                }.getOrNull()
            }
        }
    }
}
