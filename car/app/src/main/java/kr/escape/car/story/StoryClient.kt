package kr.escape.car.story

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server refused the family's story wish before writing anything; [reasons] say why, for the parent. */
class WishRejected(val reasons: List<String>) : IOException("wish rejected: $reasons")

/** The app talks only to its own story server; the AI keys live there, never in the APK. */
open class StoryClient(private val baseUrl: String) {
    /** Blocking. Throws [WishRejected] for a refused wish and IOException on any other failure (502: every draft failed). */
    open fun episode(request: JSONObject): Story = Story.parse(JSONObject(post("/v1/episode", request, readTimeoutMs = 300_000)))

    open fun report(storyId: String, reason: String) {
        post("/v1/report", JSONObject().put("storyId", storyId).put("reason", reason), readTimeoutMs = 10_000)
    }

    /** Blocking. Saves one picture the server drew ([path] as the episode gave it) to [to]. */
    open fun download(path: String, to: File) {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw IOException("story server $path: HTTP ${conn.responseCode}")
            to.parentFile?.mkdirs()
            val part = File(to.path + ".part")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(to)) throw IOException("could not save $to")
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject, readTimeoutMs: Int): String {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
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
}
