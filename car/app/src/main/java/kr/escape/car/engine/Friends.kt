package kr.escape.car.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * 차근차근 탐험단: the friend the child picks goes through every puzzle of the episode. The six base friends can be
 * picked from the start; the three rare ones open one by one as base friends reach full hearts.
 */
enum class Friend(
    val label: String,
    val animal: String,
    /** The car it is modelled on. */
    val car: String,
    /** What it stands for, given to the story server as part of the seed. */
    val symbol: String,
    /** What it tells the child; blank for the rare friends. */
    val message: String,
    val rare: Boolean = false,
) {
    BANTE("반테", "강아지", "아반떼", "용기", "먼저 해볼래!"),
    KONYANG("코냥", "고양이", "코나", "관찰력", "자세히 보면 보여"),
    RIA("리아", "토끼", "스타리아", "상상력", "이렇게 생각해 보면 어때?"),
    TUBUGI("투부기", "거북이", "투싼", "끈기", "천천히 해도 괜찮아"),
    PAPPI("파삐", "뱁새", "파비스", "협동", "같이 하면 할 수 있어"),
    POTTO("포또", "판다", "포터", "배려", "나눠 먹자"),
    IO("이오", "여우", "아이오닉", "재치", "", rare = true),
    SSOLRANG("쏠랑이", "호랑이", "쏠라티", "따뜻한 리더십", "", rare = true),
    CASPIN("캐스핀", "상어", "캐스퍼", "편견 없는 우정", "", rare = true);

    /** Its picture in assets/img; until one is put there, the app shows the first letter of its name. */
    val picture: String get() = "char_${name.lowercase()}"

    companion object {
        const val MAX_HEARTS = 5
        val BASE: List<Friend> = entries.filter { !it.rare }
        /** The order the rare friends open in. */
        val RARE: List<Friend> = entries.filter { it.rare }
    }
}

data class Book(
    /** Hearts per friend, one for each episode finished with it, up to [Friend.MAX_HEARTS]. */
    val hearts: Map<Friend, Int> = emptyMap(),
    /** Each opened rare friend and the base friend whose full hearts opened it. */
    val opened: Map<Friend, Friend> = emptyMap(),
) {
    fun hearts(f: Friend): Int = hearts[f] ?: 0

    fun isOpen(f: Friend): Boolean = !f.rare || f in opened

    /** The rare friend that opens next, or null when all of them are open. */
    val nextRare: Friend? get() = Friend.RARE.firstOrNull { it !in opened }

    /** "새로운 친구를 만나러 가자": a base friend at full hearts that has not opened a rare friend yet, while one is left. */
    fun canInvite(f: Friend): Boolean =
        !f.rare && hearts(f) >= Friend.MAX_HEARTS && f !in opened.values && nextRare != null

    val open: List<Friend> get() = Friend.entries.filter(::isOpen)
}

/** The friend book, kept across episodes in [dir]/book.json. */
class FriendBook(private val dir: File) {
    private val file = File(dir, "book.json")
    private val _book = MutableStateFlow(load())
    val book: StateFlow<Book> = _book

    /** An episode finished with [f]: one heart more, up to the most. Returns its hearts now. */
    fun played(f: Friend): Int {
        val b = _book.value
        val hearts = (b.hearts(f) + 1).coerceAtMost(Friend.MAX_HEARTS)
        _book.value = b.copy(hearts = b.hearts + (f to hearts))
        save()
        return hearts
    }

    /** [by]'s bubble was tapped: opens the next rare friend and returns it, or null when [by] cannot invite. */
    fun invite(by: Friend): Friend? {
        val b = _book.value
        if (!b.canInvite(by)) return null
        val rare = b.nextRare ?: return null
        _book.value = b.copy(opened = b.opened + (rare to by))
        save()
        return rare
    }

    private fun save() {
        val b = _book.value
        runCatching {
            dir.mkdirs()
            file.writeText(
                JSONObject()
                    .put("hearts", JSONObject().also { o -> b.hearts.forEach { (f, n) -> o.put(f.name, n) } })
                    .put("opened", JSONObject().also { o -> b.opened.forEach { (rare, by) -> o.put(rare.name, by.name) } })
                    .toString(),
            )
        }
    }

    private fun load(): Book = runCatching {
        val o = JSONObject(file.readText())
        val hearts = o.optJSONObject("hearts") ?: JSONObject()
        val opened = o.optJSONObject("opened") ?: JSONObject()
        Book(
            hearts = hearts.keys().asSequence().mapNotNull { k ->
                friend(k)?.let { it to hearts.getInt(k).coerceIn(0, Friend.MAX_HEARTS) }
            }.toMap(),
            opened = opened.keys().asSequence().mapNotNull { k ->
                val rare = friend(k)?.takeIf { it.rare } ?: return@mapNotNull null
                val by = friend(opened.getString(k))?.takeIf { !it.rare } ?: return@mapNotNull null
                rare to by
            }.toMap(),
        )
    }.getOrDefault(Book())

    private fun friend(name: String) = Friend.entries.firstOrNull { it.name == name }
}
