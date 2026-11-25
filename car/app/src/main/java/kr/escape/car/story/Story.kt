package kr.escape.car.story

import kr.escape.car.engine.Friend
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * A quiz question as Lumi asks it. The story server wrote it and worked its answer out again (server app/quiz.py),
 * or the app made it itself ([Lines.question]). The parts besides [text] are what the child's screen shows; a
 * situation (REASON) has none, the child hears it.
 */
data class Question(
    val kind: QuizKind,
    val text: String,
    val expr: String = "",
    val word: String = "",
    val terms: List<Int> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("text", text).put("expr", expr).put("word", word).put("terms", JSONArray(terms))

    companion object {
        /** Null for a question this app cannot ask (an unknown kind, no text): the scene then asks the app's own. */
        fun parse(o: JSONObject): Question? = runCatching {
            val terms = o.optJSONArray("terms") ?: JSONArray()
            Question(
                kind = QuizKind.valueOf(o.getString("kind")),
                text = o.getString("text").also { require(it.isNotBlank()) },
                expr = o.optString("expr", ""),
                word = o.optString("word", ""),
                terms = List(terms.length()) { terms.getInt(it) },
            )
        }.getOrNull()
    }
}

/**
 * Words the story server wrote and checked for one planned episode, and the pictures it drew for it when the family
 * asked for a story of their own. The answer shown to the parent and the car buttons always come from [Lines];
 * a scene the server dropped, or a picture it could not draw, is played from [Lines] too.
 */
data class Story(
    val storyId: String,
    val theme: String,
    val title: String,
    val intro: String,
    val success: List<String>,
    val treasureGift: String,
    val treasureBadge: String,
    val scenes: Map<String, Words>,
    /** The day's world in one line ("공룡이 사는 화산섬"); empty for older stories. */
    val world: String = "",
    /** Picture name ("cover", "p1".., "ending") -> the server path, and after [StoryRepo] downloads it, a local file. */
    val images: Map<String, String> = emptyMap(),
    /** Answer key -> its puzzle's picture name, also for a scene the server dropped (its words are the app's own). */
    val scenePictures: Map<String, String> = emptyMap(),
) {
    /** [picture] names this scene's picture in [images]; [speech] is only the story before the question. */
    data class Words(
        val bubble: String,
        val speech: String,
        val hint: String,
        val picture: String? = null,
        val question: Question? = null,
    )

    val themePicture: String
        get() = when (theme) {
            "SEA_SUB" -> "theme_sea_sub"
            "FOREST_SHIP" -> "theme_forest_ship"
            else -> "theme_star_train"
        }

    /** A drawn picture as a stage picture name ("file:/…/cover.webp"), or null when there is none on the device. */
    fun picture(name: String): String? = images[name]?.takeIf { it.startsWith("/") && !it.startsWith("/v1/") }?.let { FILE + it }

    /**
     * [own] is the app's question for this puzzle, used where the server's words or question are missing; [with] is
     * the friend going through the episode, named in the app's own lines.
     */
    fun scene(p: Puzzle, own: Pair<Question, String>? = null, with: Friend = Friend.BANTE): Scene {
        val key = StoryKeys.key(p)
        val words = scenes[key]
        // the drawn picture shows today's world, never the answer, so it goes with the app's own lines too
        val drawn = (words?.picture ?: scenePictures[key])?.let(::picture)
        val base = Lines.scene(p, own, with).let { if (drawn != null) it.copy(pictures = listOf(drawn)) else it }
        if (words == null) return base
        if (p is Puzzle.Quiz) {
            // the server's story goes with the server's question; without a matching one the app asks its own
            val q = words.question?.takeIf { it.kind == p.kind } ?: return base
            return Lines.quizScene(p, words.bubble, words.speech, words.hint, q).copy(pictures = base.pictures)
        }
        return base.copy(bubble = words.bubble, speech = words.speech, hint = words.hint)
    }

    fun success(n: Int): String = if (success.isEmpty()) Lines.SUCCESS else success[n % success.size]

    fun toJson(): JSONObject = JSONObject()
        .put("storyId", storyId).put("theme", theme).put("title", title).put("intro", intro)
        .put("success", JSONArray(success)).put("treasureGift", treasureGift).put("treasureBadge", treasureBadge)
        .put("world", world).put("images", JSONObject(images)).put("pictures", JSONObject(scenePictures))
        .put("scenes", JSONObject().also { o ->
            scenes.forEach { (k, w) ->
                o.put(k, JSONObject().put("bubble", w.bubble).put("speech", w.speech).put("hint", w.hint)
                    .put("picture", w.picture ?: JSONObject.NULL).put("question", w.question?.toJson() ?: JSONObject.NULL))
            }
        })

    companion object {
        /** Stage pictures that start with this are files on the device, not assets. */
        const val FILE = "file:"

        /** Throws JSONException when a field is missing: a half story is no story. */
        fun parse(o: JSONObject): Story {
            val s = o.getJSONArray("success")
            val sc = o.getJSONObject("scenes")
            val im = o.optJSONObject("images")
            return Story(
                storyId = o.getString("storyId"),
                theme = o.getString("theme"),
                title = o.getString("title"),
                intro = o.getString("intro"),
                success = List(s.length()) { s.getString(it) },
                treasureGift = o.getString("treasureGift"),
                treasureBadge = o.getString("treasureBadge"),
                scenes = sc.keys().asSequence().associateWith { k ->
                    sc.getJSONObject(k).let {
                        Words(
                            it.getString("bubble"), it.getString("speech"), it.getString("hint"),
                            if (it.has("picture") && !it.isNull("picture")) it.getString("picture") else null,
                            it.optJSONObject("question")?.let(Question::parse),
                        )
                    }
                },
                world = o.optString("world", ""),
                images = im?.keys()?.asSequence()?.associateWith { im.getString(it) } ?: emptyMap(),
                scenePictures = o.optJSONObject("pictures")?.let { sp -> sp.keys().asSequence().associateWith { sp.getString(it) } } ?: emptyMap(),
            )
        }
    }
}
