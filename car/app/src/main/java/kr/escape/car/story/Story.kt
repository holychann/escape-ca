package kr.escape.car.story

import kr.escape.car.engine.Puzzle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Words the story server wrote and checked for one planned episode. Only the words change: pictures, buttons and
 * the answer shown to the parent always come from [Lines]. A scene the server dropped is played from [Lines].
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
) {
    data class Words(val bubble: String, val speech: String, val hint: String)

    val themePicture: String
        get() = when (theme) {
            "SEA_SUB" -> "theme_sea_sub"
            "FOREST_SHIP" -> "theme_forest_ship"
            else -> "theme_star_train"
        }

    fun scene(p: Puzzle): Scene {
        val base = Lines.scene(p)
        val words = scenes[StoryKeys.key(p)] ?: return base
        return base.copy(bubble = words.bubble, speech = words.speech, hint = words.hint)
    }

    fun success(n: Int): String = if (success.isEmpty()) Lines.SUCCESS else success[n % success.size]

    fun toJson(): JSONObject = JSONObject()
        .put("storyId", storyId).put("theme", theme).put("title", title).put("intro", intro)
        .put("success", JSONArray(success)).put("treasureGift", treasureGift).put("treasureBadge", treasureBadge)
        .put("scenes", JSONObject().also { o ->
            scenes.forEach { (k, w) -> o.put(k, JSONObject().put("bubble", w.bubble).put("speech", w.speech).put("hint", w.hint)) }
        })

    companion object {
        /** Throws JSONException when a field is missing: a half story is no story. */
        fun parse(o: JSONObject): Story {
            val s = o.getJSONArray("success")
            val sc = o.getJSONObject("scenes")
            return Story(
                storyId = o.getString("storyId"),
                theme = o.getString("theme"),
                title = o.getString("title"),
                intro = o.getString("intro"),
                success = List(s.length()) { s.getString(it) },
                treasureGift = o.getString("treasureGift"),
                treasureBadge = o.getString("treasureBadge"),
                scenes = sc.keys().asSequence().associateWith { k ->
                    sc.getJSONObject(k).let { Words(it.getString("bubble"), it.getString("speech"), it.getString("hint")) }
                },
            )
        }
    }
}
