package kr.escape.car.story

import kr.escape.car.engine.Book
import kr.escape.car.engine.Friend

/**
 * What the friend shows and says while its story is being written (about a minute): itself, its hearts, and the rare
 * friends still to meet. Every line here is recorded once and shipped in assets/voice, so it plays at once even while
 * the server is busy writing, or with no server at all.
 */
object FriendIntro {
    /** One card on the waiting screen, with the line the friend says for it (null: nothing said). */
    sealed interface Card {
        val line: String?

        data class Me(val friend: Friend) : Card {
            override val line: String get() = about(friend)
        }

        /** [canMeet]: more hearts open a rare friend (a base friend that has not opened one yet, while one is left). */
        data class Hearts(val friend: Friend, val hearts: Int, val canMeet: Boolean) : Card {
            override val line: String? get() = if (canMeet) HEARTS else null
        }

        data class Mystery(val friends: List<Friend>) : Card {
            override val line: String get() = MYSTERY
        }
    }

    const val HEARTS = "하트를 다섯 개 모으면 새로운 친구를 만날 수 있어!"
    const val MYSTERY = "아직 만나지 못한 친구가 있어. 누구일까?"

    /** The friend telling the child about itself, in its own words. */
    fun about(f: Friend): String = when (f) {
        Friend.BANTE -> "나는 아반떼를 닮은 강아지 반테야. 처음 해 보는 일도 용기 내서 먼저 해 봐."
        Friend.KONYANG -> "나는 코나를 닮은 고양이 코냥이야. 작은 것도 놓치지 않고 꼼꼼히 살펴봐."
        Friend.RIA -> "나는 스타리아를 닮은 토끼 리아야. 새롭고 재미있는 생각을 잘 떠올려."
        Friend.TUBUGI -> "나는 투싼을 닮은 거북이 투부기야. 어려워도 포기하지 않고 끝까지 해."
        Friend.PAPPI -> "나는 파비스를 닮은 뱁새 파삐야. 친구들과 힘을 모으는 걸 제일 좋아해."
        Friend.POTTO -> "나는 포터를 닮은 판다 포또야. 맛있는 간식이 생기면 친구들과 꼭 나눠 먹어."
        Friend.IO -> "나는 아이오닉을 닮은 여우 이오야. 막힐 때면 재치 있는 생각으로 길을 찾아."
        Friend.SSOLRANG -> "나는 쏠라티를 닮은 호랑이 쏠랑이야. 앞장서서 친구들을 따뜻하게 챙겨."
        Friend.CASPIN -> "나는 캐스퍼를 닮은 상어 캐스핀이야. 나랑 다르게 생긴 친구와도 금방 친해져."
    }

    /** "아반떼를 닮은 강아지", shown under the friend's name. */
    fun looksLike(f: Friend): String = "${Lines.objectOf(f.car)} 닮은 ${f.animal}"

    /** The cards in the order they are shown: the friend, its hearts, then the rare friends still locked (if any). */
    fun cards(f: Friend, book: Book): List<Card> = buildList {
        add(Card.Me(f))
        add(Card.Hearts(f, book.hearts(f), canMeet = !f.rare && f !in book.opened.values && book.nextRare != null))
        val locked = Friend.RARE.filter { it !in book.opened }
        if (locked.isNotEmpty()) add(Card.Mystery(locked))
    }

    /**
     * The recordings shipped with the app (assets/voice/<name>.wav), each with the line it says. They are named by
     * friend, not by the voice cache's key, so the app works the key out when it copies them in; a line changed here
     * needs its recording made again.
     */
    val RECORDINGS: Map<String, String>
        get() = Friend.entries.associate { "intro_${it.name.lowercase()}" to about(it) } +
            mapOf("intro_hearts" to HEARTS, "intro_mystery" to MYSTERY)

    /** Every line any card can say. */
    val ALL: List<String> get() = RECORDINGS.values.toList()
}
