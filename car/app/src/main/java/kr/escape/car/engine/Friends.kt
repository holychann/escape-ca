package kr.escape.car.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The six friends of the puzzles, the same ones the pictures draw and the story server writes about. */
enum class Friend(val label: String, val picture: String, val trait: String) {
    SPROUT_ROBOT("새싹 로봇", "crew_sprout_robot", "새싹 화분을 돌보고 낮잠을 좋아해요"),
    OTTER_COOK("수달 요리사", "crew_otter_cook", "따끈한 수프를 끓이는 솜씨꾼이에요"),
    PENGUIN_CAPTAIN("펭귄 선장", "crew_penguin_captain", "친구들을 이끄는 씩씩한 선장이에요"),
    GRUMPY_MACHINE("투덜이 기계", "crew_grumpy_machine", "바람이 불면 투덜대지만 마음은 착해요"),
    FENNEC_FOX("사막여우", "crew_fennec_fox", "반짝이는 물건을 숨기는 장난꾸러기예요"),
    RABBIT_ENGINEER("토끼 기관사", "crew_rabbit_engineer", "기계와 문을 고치는 솜씨꾼이에요");

    companion object {
        /** The friend a puzzle helps; solving it puts that friend in the book, and every time after, closer. */
        fun of(p: Puzzle): Friend? = when (p) {
            is Puzzle.WindPath -> SPROUT_ROBOT
            is Puzzle.DustCloud -> OTTER_COOK
            is Puzzle.TwoRooms -> PENGUIN_CAPTAIN
            Puzzle.FoggyWindow -> GRUMPY_MACHINE
            is Puzzle.SeatSearch -> FENNEC_FOX
            is Puzzle.Together -> RABBIT_ENGINEER
            Puzzle.BeltMission -> null
        }

        /** Hearts shown for a friend helped [times] times. */
        fun hearts(times: Int) = times.coerceIn(0, MAX_HEARTS)

        const val MAX_HEARTS = 5
    }
}

/** Today's new friend as the story server wrote it: someone who lives in that story's world. */
data class StoryFriend(val name: String, val look: String, val trait: String, val hello: String)

/** A friend a story brought, kept in the book for good with the story it came from. */
data class Guest(
    /** The story it came from; one friend per story. */
    val id: String,
    val name: String,
    val trait: String,
    val hello: String,
    /** That story's title, for "어디서 만났지?" */
    val story: String,
    val metAt: Long,
    /** Its portrait in the book's own folder, or null when none was drawn (the app shows a placeholder). */
    val picture: String?,
)

data class Book(
    /** Book friends met so far and how many puzzles each has helped with. */
    val crew: Map<Friend, Int> = emptyMap(),
    /** Friends the stories brought, newest first. */
    val guests: List<Guest> = emptyList(),
) {
    val met: Set<Friend> get() = crew.keys
    val size: Int get() = crew.size + guests.size
}

/**
 * Which friends this car has met, kept across episodes in [dir]/book.json with the story friends' portraits next to
 * it (the story's own picture folder is cleared once the next story is ready). [legacy] is the old list of book
 * friend names, read once.
 */
class FriendBook(
    private val dir: File,
    private val legacy: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = File(dir, "book.json")
    private val _book = MutableStateFlow(load())
    val book: StateFlow<Book> = _book

    /** One more puzzle with [f]; returns true when [f] is new to the book. */
    fun meet(f: Friend): Boolean {
        val b = _book.value
        val times = b.crew[f] ?: 0
        _book.value = b.copy(crew = b.crew + (f to times + 1))
        save()
        return times == 0
    }

    /** Puts the friend story [id] brought into the book; returns it, or null when it is already there. */
    fun welcome(id: String, friend: StoryFriend, story: String, picture: File?): Guest? {
        val b = _book.value
        if (b.guests.any { it.id == id }) return null
        val kept = picture?.takeIf { it.isFile }?.let { from ->
            runCatching { from.copyTo(File(dir, "$id.webp"), overwrite = true).absolutePath }.getOrNull()
        }
        val guest = Guest(id, friend.name, friend.trait, friend.hello, story, now(), kept)
        _book.value = b.copy(guests = listOf(guest) + b.guests)
        save()
        return guest
    }

    private fun save() {
        val b = _book.value
        runCatching {
            dir.mkdirs()
            file.writeText(
                JSONObject()
                    .put("crew", JSONObject().also { o -> b.crew.forEach { (f, n) -> o.put(f.name, n) } })
                    .put("guests", JSONArray(b.guests.map { g ->
                        JSONObject().put("id", g.id).put("name", g.name).put("trait", g.trait).put("hello", g.hello)
                            .put("story", g.story).put("metAt", g.metAt).put("picture", g.picture ?: JSONObject.NULL)
                    }))
                    .toString(),
            )
        }
    }

    private fun load(): Book = runCatching {
        val o = JSONObject(file.readText())
        val crew = o.optJSONObject("crew")
        val guests = o.optJSONArray("guests") ?: JSONArray()
        Book(
            crew = crew?.keys()?.asSequence()?.mapNotNull { k ->
                Friend.entries.firstOrNull { it.name == k }?.let { it to crew.getInt(k).coerceAtLeast(1) }
            }?.toMap().orEmpty(),
            guests = List(guests.length()) { i ->
                guests.getJSONObject(i).let { g ->
                    Guest(
                        g.getString("id"), g.getString("name"), g.optString("trait"), g.optString("hello"),
                        g.optString("story"), g.optLong("metAt"),
                        if (g.isNull("picture")) null else g.optString("picture").takeIf { File(it).isFile },
                    )
                }
            },
        )
    }.getOrElse { fromLegacy() }

    /** The old friends.txt only listed names: each counts as met once. */
    private fun fromLegacy(): Book = runCatching {
        val names = legacy?.readLines().orEmpty().map { it.trim() }
        Book(crew = Friend.entries.filter { it.name in names }.associateWith { 1 })
    }.getOrDefault(Book())
}
