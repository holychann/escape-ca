package kr.escape.car.story

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The app talks only to its own story server; the AI key lives there, never in the APK. */
open class StoryClient(private val baseUrl: String) {
    /** Blocking. Throws IOException on any failure, including the server rejecting every draft (502). */
    open fun episode(request: JSONObject): Story = Story.parse(JSONObject(post("/v1/episode", request, readTimeoutMs = 300_000)))

    open fun report(storyId: String, reason: String) {
        post("/v1/report", JSONObject().put("storyId", storyId).put("reason", reason), readTimeoutMs = 10_000)
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
            if (code !in 200..299) throw IOException("story server $path: HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
