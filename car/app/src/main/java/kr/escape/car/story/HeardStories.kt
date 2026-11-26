package kr.escape.car.story

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The stories this car has played, kept on the car only (never sent anywhere), so the story shelf does not offer the
 * same one back. A story is forgotten after [KEEP_MS], and only the latest [KEEP] are kept. A file that cannot be read
 * is forgotten without a word.
 */
class HeardStories(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val id: String, val at: Long)

    private var entries: List<Entry> = load()

    fun add(storyId: String) {
        val t = now()
        entries = (live(t).filter { it.id != storyId } + Entry(storyId, t)).takeLast(KEEP)
        save()
    }

    operator fun contains(storyId: String): Boolean = live(now()).any { it.id == storyId }

    /** What is still remembered at [t]: not older than [KEEP_MS], and not from the future (the clock went backwards). */
    private fun live(t: Long) = entries.filter { t - it.at in 0 until KEEP_MS }

    private fun load(): List<Entry> {
        val t = now()
        return runCatching {
            val list = JSONObject(file.readText()).getJSONArray("heard")
            List(list.length()) { i -> list.getJSONObject(i).let { Entry(it.getString("id"), it.getLong("at")) } }
        }.getOrDefault(emptyList()).filter { t - it.at in 0 until KEEP_MS }.takeLast(KEEP)
    }

    private fun save() {
        val o = JSONObject().put("heard", JSONArray(entries.map { JSONObject().put("id", it.id).put("at", it.at) }))
        runCatching {
            file.parentFile?.mkdirs()
            // the new file is on the disk before it takes the old one's place in one step, as in [ProgressStore]
            val part = File(file.path + ".part")
            FileOutputStream(part).use { out ->
                out.write(o.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { File(file.path + ".part").delete() }
    }

    companion object {
        const val KEEP_MS = 90L * 24 * 60 * 60 * 1000
        const val KEEP = 500
    }
}
