package kr.escape.car.story

import kr.escape.car.engine.Puzzle
import org.json.JSONArray
import org.json.JSONObject

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
) {
    /** [picture] names this scene's picture in [images]. */
    data class Words(val bubble: String, val speech: String, val hint: String, val picture: String? = null)

    val themePicture: String
        get() = when (theme) {
            "SEA_SUB" -> "theme_sea_sub"
            "FOREST_SHIP" -> "theme_forest_ship"
            else -> "theme_star_train"
        }

    /** A drawn picture as a stage picture name ("file:/…/cover.webp"), or null when there is none on the device. */
    fun picture(name: String): String? = images[name]?.takeIf { it.startsWith("/") && !it.startsWith("/v1/") }?.let { FILE + it }

    fun scene(p: Puzzle): Scene {
        val base = Lines.scene(p)
        val words = scenes[StoryKeys.key(p)] ?: return base
        val drawn = words.picture?.let(::picture)
        return base.copy(
            bubble = words.bubble, speech = words.speech, hint = words.hint,
            pictures = if (drawn != null) listOf(drawn) else base.pictures,
        )
    }

    fun success(n: Int): String = if (success.isEmpty()) Lines.SUCCESS else success[n % success.size]

    fun toJson(): JSONObject = JSONObject()
        .put("storyId", storyId).put("theme", theme).put("title", title).put("intro", intro)
        .put("success", JSONArray(success)).put("treasureGift", treasureGift).put("treasureBadge", treasureBadge)
        .put("world", world).put("images", JSONObject(images))
        .put("scenes", JSONObject().also { o ->
            scenes.forEach { (k, w) ->
                o.put(k, JSONObject().put("bubble", w.bubble).put("speech", w.speech).put("hint", w.hint).put("picture", w.picture ?: JSONObject.NULL))
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
                        )
                    }
                },
                world = o.optString("world", ""),
                images = im?.keys()?.asSequence()?.associateWith { im.getString(it) } ?: emptyMap(),
            )
        }
    }
}
