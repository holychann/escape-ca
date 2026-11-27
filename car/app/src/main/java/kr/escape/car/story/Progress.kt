package kr.escape.car.story

import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Setup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One episode in progress, kept on disk so the family can go on after the car was switched off (이어 하기).
 * What the parent left the climate at and how long they played are not kept: both start again when they go on.
 */
data class Progress(
    val setup: Setup,
    val puzzles: List<Puzzle>,
    /** The puzzle to start when the family comes back; puzzles.size means only the treasure is left. */
    val next: Int,
    val stars: Int,
    val own: Map<String, Pair<Question, String>>,
    val story: Story?,
    /** Wall-clock ms before which the episode cannot go on (the rest after 20 minutes); null when there is none. */
    val restUntil: Long? = null,
)

/**
 * The episode in progress in [file], for a day after it was last written. Written at every step of the game, so a
 * car that loses power keeps the last one; a file that cannot be read back is dropped without a word.
 */
class ProgressStore(private val file: File, val now: () -> Long = System::currentTimeMillis) {

    /** The episode to go on with, or null (and the file deleted) when there is none, it is broken, or a day has passed. */
    fun read(): Progress? {
        if (!file.isFile) return null
        val p = runCatching { parse(JSONObject(file.readText())) }.getOrNull()
        if (p == null) clear()
        return p
    }

    fun write(p: Progress) {
        val o = JSONObject()
            .put("v", VERSION)
            .put("savedAt", now())
            .put("setup", StoryRepo.setupJson(p.setup))
            .put("puzzles", JSONArray(p.puzzles.map(StoryKeys::key)))
            .put("next", p.next)
            .put("stars", p.stars)
            .put("own", JSONObject().also { own ->
                p.own.forEach { (key, qh) -> own.put(key, JSONObject().put("question", qh.first.toJson()).put("hint", qh.second)) }
            })
            .put("story", p.story?.toJson() ?: JSONObject.NULL)
            .put("restUntil", p.restUntil ?: JSONObject.NULL)
        runCatching {
            file.parentFile?.mkdirs()
            // a car that loses power half way through leaves the old file, never half a new one: the new one is on
            // the disk (synced) before it takes the old one's place in one step
            val part = File(file.path + ".part")
            FileOutputStream(part).use { out ->
                out.write(o.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { File(file.path + ".part").delete() }
    }

    fun clear() {
        file.delete()
    }

    /** How long the family still rests before going on: never more than the rest itself, whatever the clock did. */
    fun restLeftMs(p: Progress): Long = p.restUntil?.let { (it - now()).coerceIn(0, REST_MS) } ?: 0

    private fun parse(o: JSONObject): Progress? {
        if (o.getInt("v") != VERSION) return null
        val age = now() - o.getLong("savedAt")
        if (age !in 0 until KEEP_MS) return null
        val keys = o.getJSONArray("puzzles")
        val puzzles = List(keys.length()) { StoryKeys.puzzle(keys.getString(it)) ?: return null }
        val next = o.getInt("next")
        val stars = o.getInt("stars")
        if (next !in 0..puzzles.size || stars < 0) return null
        val own = o.getJSONObject("own").let { all ->
            all.keys().asSequence().mapNotNull { key ->
                val e = all.getJSONObject(key)
                Question.parse(e.getJSONObject("question"))?.let { key to (it to e.getString("hint")) }
            }.toMap()
        }
        val story = if (o.isNull("story")) null else Story.parse(o.getJSONObject("story")).let { s ->
            // a picture gone from the device is left out: its scene shows the app's own. One the server was still
            // drawing keeps its server path: 이어 하기 fetches it again (StoryRepo.follow)
            s.copy(images = s.images.filterValues { Story.drawing(it) || ((it.startsWith("/") || File(it).isAbsolute) && File(it).isFile) })
        }
        return Progress(
            setup = StoryRepo.setupOf(o.getJSONObject("setup")),
            puzzles = puzzles,
            next = next,
            stars = stars,
            own = own,
            story = story,
            restUntil = if (o.isNull("restUntil")) null else o.getLong("restUntil"),
        )
    }

    companion object {
        const val VERSION = 1
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        const val REST_MS = 10 * 60 * 1000L
    }
}
