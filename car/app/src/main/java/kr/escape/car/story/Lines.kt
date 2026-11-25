package kr.escape.car.story

import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kotlin.random.Random

/**
 * What one puzzle looks and sounds like: Lumi's line, the hint, the stage picture (assets/img), the parent's buttons
 * and the answer, and for a quiz the question the child's screen shows.
 */
data class Scene(
    val bubble: String,
    val speech: String,
    val hint: String,
    val pictures: List<String>,
    val buttons: List<String>,
    val answer: String,
    val question: Question? = null,
)

/**
 * The reviewed lines the app ships with. The AI story server writes a new question around the same answer; when it
 * fails or a check rejects its question, the app asks its own simple one made here.
 */
object Lines {
    const val SUCCESS = "고쳤다! 기차 칸 하나가 반짝 살아났어. 우리 탐험대장 최고야!"
    const val TREASURE_BADGE = "기차가 다 고쳐졌어! 기차 친구들이 고맙다며 반짝이는 탐험 배지를 줬어!"
    const val PAUSED = "잠깐 쉬어 갈게. 다시 준비되면 이어서 하자!"

    /**
     * At the start of play: the climate's AUTO mode changes the fan, the air conditioner and the recirculation by
     * itself, which the app cannot read. Lumi says it in Korean letters so the speech engine does not spell it out.
     */
    const val AUTO_OFF = "안전 지킴이, 공조 오토를 꺼 주세요."
    const val AUTO_OFF_PARENT = "공조 AUTO를 꺼 주세요. AUTO가 바람, 에어컨, 내기 순환을 저절로 바꿔요"

    fun intro(f: Friend) = "안녕, 탐험대장! 나는 ${isName(f.label)}. 오늘 밤 별 축제에 가야 하는데 별빛 기차 칸 곳곳이 고장 났대. " +
        "나랑 같이 문제를 풀어서 기차를 고쳐 보자! 답을 찾으면, 그 답으로 버튼을 어떻게 바꿀지 안전 지킴이에게 알려 줘."

    fun closer(f: Friend) = "${withAnd(f.label)} 한층 더 친해졌어!"

    fun invited(rare: Friend) = "새로운 친구 ${subject(rare.label)} 탐험단에 들어왔어!"

    /** [word] with 과 or 와, whichever its last syllable takes ("코냥과", "반테와"). */
    fun withAnd(word: String): String = word + if (closed(word)) "과" else "와"

    /** [word] with 이 or 가 ("코냥이", "반테가"). */
    fun subject(word: String): String = word + if (closed(word)) "이" else "가"

    /** [word] with 의 ("반테의"). */
    fun of(word: String): String = word + "의"

    /** "반테야", "코냥이야". */
    fun isName(word: String): String = word + if (closed(word)) "이야" else "야"

    /** "토끼면", "곰이면": 이면 after a closed syllable. */
    fun ifIs(word: String): String = word.trimEnd() + if (closed(word)) "이면" else "면"

    private fun closed(word: String): Boolean {
        val last = word.trimEnd().lastOrNull() ?: return false
        return last in '가'..'힣' && (last - '가') % 28 != 0
    }

    /**
     * [own] is a question the app drew from its copy of the question bank, with its hint; without one, a built-in one.
     * [with] is the friend going through the episode, who is in trouble in every scene.
     */
    fun scene(p: Puzzle, own: Pair<Question, String>? = null, with: Friend = Friend.BANTE): Scene = when (p) {
        is Puzzle.Quiz -> (own?.first ?: question(p)).let { q -> quizScene(p, bubble(p), lead(p, with), own?.second ?: hint(p, q), q) }
        Puzzle.BeltMission -> Scene(
            bubble = "탐험대장, 벨트를 딸깍!",
            speech = "다음 칸으로 가기 전에 준비! 탐험대장, 벨트를 딸깍! 이미 매고 있다면 버클을 짚고 딸깍 확인!",
            hint = "벨트를 버클에 끝까지 넣으면 딸깍 소리가 나!",
            pictures = listOf("scene_belt"),
            buttons = emptyList(),
            answer = "아이가 스스로 매면 확인 버튼",
        )
    }

    /** One quiz scene from its words ([lead] is the story before the question) and its checked question. */
    fun quizScene(p: Puzzle.Quiz, bubble: String, lead: String, hint: String, q: Question) = Scene(
        bubble = bubble,
        speech = spoken(lead, q, p),
        hint = hint,
        pictures = listOf(picture(p)),
        buttons = listOf(button(p.dial)),
        answer = answer(p, q),
        question = q,
    )

    /** Lumi's words in order: the story, the question, then what to do with the answer (always the app's own words). */
    fun spoken(lead: String, q: Question, p: Puzzle.Quiz): String =
        listOf(lead.trim(), sayable(q.text.trim(), q.word), ask(p, q)).filter { it.isNotEmpty() }.joinToString(" ")

    /** The speech engine may read a word in capitals letter by letter, so Lumi says the English word in small letters. */
    private fun sayable(text: String, word: String) =
        if (word.isBlank()) text else text.replace(word, word.lowercase(), ignoreCase = true)

    /**
     * What to do with the answer. The app says this itself, so a written question can never send the child wrong. A
     * situation already ends by asking for help with a car button ("차 버튼으로 수달 요리사를 도와줘!"), so nothing
     * follows it.
     */
    fun ask(p: Puzzle.Quiz, q: Question): String {
        if (p.kind == QuizKind.REASON) return ""
        // the question itself is the instruction ("ONE만큼 온도를 내려 줘!"): the child works the amount out and says it
        return when (p.dial) {
            Dial.LIGHTS -> "손이 닿는 불은 직접 켜고, 나머지는 몇 개 켤지 안전 지킴이에게 부탁해!"
            else -> "어떻게 하면 되는지 안전 지킴이에게 말해 줘!"
        }
    }

    /** The parent's 정답 보기: what to set, and how the question works out to it. */
    fun answer(p: Puzzle.Quiz, q: Question): String {
        val reason = p.kind == QuizKind.REASON
        val how = when (p.dial) {
            Dial.FAN -> "풍량 ${p.value}단"
            Dial.TEMP_UP -> if (reason) "운전석 온도를 퍼즐 시작 때보다 올리기" else "운전석 온도를 퍼즐 시작 때보다 ${p.value}도 올리기"
            Dial.TEMP_DOWN -> if (reason) "운전석 온도를 퍼즐 시작 때보다 내리기" else "운전석 온도를 퍼즐 시작 때보다 ${p.value}도 내리기"
            Dial.LIGHTS -> "독서등 모두 ${p.value}개 켜기"
            Dial.SEAT_HEAT -> "운전석 열선 ${p.value}단"
            Dial.AC -> if (p.value == 1) "에어컨 켜기" else "에어컨 끄기"
            Dial.RECIRC -> if (p.value == 1) "내기 순환 켜기" else "내기 순환 끄기 (외기 유입)"
            Dial.DEFROST -> "앞 유리 성에 제거 켜기"
            Dial.FAN_UP -> "풍량을 퍼즐 시작 때보다 세게"
            Dial.FAN_DOWN -> "풍량을 퍼즐 시작 때보다 약하게 (끄지 않기)"
            Dial.SEAT_HEAT_ON -> "운전석 열선 켜기 (몇 단이든)"
            Dial.SEAT_HEAT_OFF -> "운전석 열선 끄기"
            Dial.LIGHT_CHILD -> "아이 자리 독서등 켜기"
            Dial.LIGHTS_OFF -> "독서등 모두 끄기"
        }
        val why = when (p.kind) {
            QuizKind.MATH -> q.expr.takeIf { it.isNotBlank() }?.let { "${pretty(it)} = ${p.value}" }
            QuizKind.ENGLISH -> q.word.takeIf { it.isNotBlank() }?.uppercase()?.let {
                if (it in NUMBER_WORDS) "$it = ${p.value}" else "$it, ${it.length}글자"
            }
            QuizKind.PATTERN -> q.terms.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} 다음은 ${p.value}" }
            // nothing to work out: the child finds the function from the trouble
            QuizKind.REASON -> null
        }
        return if (why == null) how else "$how ($why)"
    }

    /** The big line on the child's screen ("3 + 2 = ?"), or null when the question has none (a situation is heard). */
    fun card(q: Question): String? = when (q.kind) {
        QuizKind.MATH -> q.expr.takeIf { it.isNotBlank() }?.let { "${pretty(it)} = ?" }
        QuizKind.ENGLISH -> q.word.takeIf { it.isNotBlank() }?.uppercase()?.let { if (it in NUMBER_WORDS) "$it = ?" else "$it 몇 글자?" }
        QuizKind.PATTERN -> q.terms.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")}, ?" }
        QuizKind.REASON -> null
    }

    /** "(4*3)-7" as a child reads it: "(4 × 3) - 7". */
    fun pretty(expr: String): String = expr
        .replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace("-", " - ")
        .replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    /** The same scene with its friend helped, after the puzzle is solved. */
    fun successPicture(p: Puzzle?): String = when (p) {
        is Puzzle.Quiz -> when (picture(p)) {
            "scene_fog" -> "scene_fog_ok"
            "scene_wind_face" -> "scene_wind_ok"
            "scene_rooms_left" -> "scene_rooms_ok"
            "scene_together" -> "scene_together_ok"
            else -> "scene_dust_ok"
        }
        Puzzle.BeltMission -> "scene_belt_ok"
        null -> "badge_explorer"
    }

    // ------------------------------------------------------------ the app's own questions

    val NUMBER_WORDS = listOf(
        "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN",
        "ELEVEN", "TWELVE", "THIRTEEN", "FOURTEEN", "FIFTEEN", "SIXTEEN", "SEVENTEEN", "EIGHTEEN", "NINETEEN", "TWENTY",
    )

    /**
     * A simple question whose answer is the puzzle's value, the same every time for the same puzzle (the parent's
     * answer and Lumi's words must agree), for when even the app's copy of the question bank has none.
     */
    fun question(p: Puzzle.Quiz): Question {
        val rnd = Random(StoryKeys.key(p).hashCode())
        val v = p.value
        return when (p.kind) {
            QuizKind.MATH -> if (v >= 2) {
                val a = rnd.nextInt(1, v)
                Question(QuizKind.MATH, instruction(p.dial, "$a 더하기 ${v - a}"), expr = "$a+${v - a}")
            } else {
                Question(QuizKind.MATH, instruction(p.dial, "3 빼기 2"), expr = "3-2")
            }
            QuizKind.ENGLISH -> NUMBER_WORDS[v - 1].let { Question(QuizKind.ENGLISH, instruction(p.dial, it), word = it) }
            QuizKind.PATTERN -> {
                val terms = if (v >= 3) listOf(v - 3, v - 2, v - 1) else listOf(v + 3, v + 2, v + 1)
                Question(QuizKind.PATTERN, instruction(p.dial, "${terms.joinToString(", ")} 다음 수"), terms = terms)
            }
            QuizKind.REASON -> Question(QuizKind.REASON, situation(p))
        }
    }

    /**
     * A situation that only one function solves, in the bank's form: the trouble, then a request for help with a car
     * button. It never names the button itself.
     */
    private fun situation(p: Puzzle.Quiz): String = when (p.dial) {
        Dial.AC -> if (p.value == 1) "뛰어놀고 온 친구들이 땀을 뻘뻘 흘려. 바람은 나오는데 하나도 안 시원하대. 차 버튼으로 친구들을 도와줘!"
            else "감기 걸린 친구들이 더울 때 켜는 시원한 바람이 켜져 있어서 떨어. 온도 숫자는 그대로 두고 차 버튼으로 도와줘!"
        Dial.RECIRC -> if (p.value == 1) "옆 버스의 연기 냄새가 바람 구멍으로 솔솔 들어와. 창문은 닫아 둔 채로 차 버튼으로 친구들을 도와줘!"
            else "같은 공기만 빙글빙글 돌아서 친구들이 숨이 답답하대. 창문은 닫아 둔 채로 차 버튼으로 친구들을 도와줘!"
        Dial.DEFROST -> "친구들 입김에 앞 유리 안쪽이 뿌예지려고 해. 와이퍼는 바깥만 닦아서 안 돼. 차 버튼으로 친구들을 도와줘!"
        Dial.TEMP_UP -> "감기 걸린 친구들이 으슬으슬 떨어. 의자 말고 차 안을 따뜻하게, 차 버튼으로 친구들을 도와줘!"
        Dial.TEMP_DOWN -> "뛰어놀고 온 친구들이 에어컨이 켜져 있는데도 아직 조금 덥대. 바람이 세지면 그림책이 날아가니까 차 버튼으로 친구들을 도와줘!"
        Dial.FAN_UP -> "바람 나오는 구멍 앞의 작은 깃발이 살짝만 흔들려. 깃발이 펄럭펄럭 날리게 차 버튼으로 친구들을 도와줘!"
        Dial.FAN_DOWN -> "바람이 너무 세게 불어서 친구들의 그림 종이가 훨훨 날아가. 바람은 조금 나와야 하니까 차 버튼으로 친구들을 도와줘!"
        Dial.SEAT_HEAT_ON -> "누가 얼음 주머니를 올려 둬서 안전 지킴이 의자가 차가워. 차 안 공기 말고 의자를 데우게 차 버튼으로 안전 지킴이를 도와줘!"
        Dial.SEAT_HEAT_OFF -> "안전 지킴이 의자가 계속 뜨끈뜨끈해서 땀이 나. 의자가 그만 데워지게 차 버튼으로 안전 지킴이를 도와줘!"
        Dial.LIGHT_CHILD -> "보물 지도 그림이 개미만큼 작아서 네 자리가 더 밝아야 해. 차 버튼으로 보물 찾기를 도와줘!"
        Dial.LIGHTS_OFF -> "친구들이 낮잠을 자려는데 작은 불이 하나도 안 켜져 있어야 잠이 온대. 차 버튼으로 친구들을 도와줘!"
        Dial.FAN, Dial.LIGHTS, Dial.SEAT_HEAT -> "차 버튼으로 친구들을 도와줘!"
    }

    /** The car instruction with the amount as a little puzzle: "TWO만큼 온도를 내려 줘!". */
    fun instruction(d: Dial, amount: String): String = when (d) {
        Dial.FAN -> "바람 세기를 ${amount}단으로 맞춰 줘!"
        Dial.TEMP_UP -> "${amount}만큼 온도를 올려 줘!"
        Dial.TEMP_DOWN -> "${amount}만큼 온도를 내려 줘!"
        Dial.LIGHTS -> "켜진 독서등이 모두 ${amount}개가 되게 해 줘!"
        Dial.SEAT_HEAT -> "의자 열선을 ${amount}단으로 맞춰 줘!"
        // a situation has no amount
        else -> amount
    }

    private fun lead(p: Puzzle.Quiz, f: Friend): String {
        val name = f.label
        // the situation itself tells the trouble; the story before it never gives the function away
        if (p.kind == QuizKind.REASON) return "${subject(name)} 기차 칸에서 곤란한 친구를 만났어. 잘 듣고 어떤 차 버튼이 도와줄 수 있을지 생각해 봐!"
        return when (p.dial) {
            Dial.FAN -> "${subject(name)} 키우는 새싹이 목말라서 축 늘어졌어. 문제를 풀면 바람을 보내 줄 수 있어!"
            Dial.TEMP_DOWN -> "기차 칸이 너무 더워서 ${subject(name)} 땀을 뻘뻘 흘려! 문제를 풀면 시원하게 할 수 있어."
            Dial.TEMP_UP -> "기차 칸이 너무 추워서 ${subject(name)} 덜덜 떨고 있어! 문제를 풀면 조금 따뜻하게 할 수 있어."
            Dial.LIGHTS -> "기차 칸이 깜깜해서 ${subject(name)} 길을 못 찾고 있어! 문제를 풀면 작은 불을 밝힐 수 있어."
            else -> "${of(name)} 의자가 얼음처럼 차가워! 문제를 풀면 의자를 따끈하게 데울 수 있어."
        }
    }

    private fun bubble(p: Puzzle.Quiz): String = if (p.kind == QuizKind.REASON) {
        when (p.dial) {
            Dial.AC -> if (p.value == 1) "너무 더워!" else "너무 추워!"
            Dial.RECIRC -> if (p.value == 1) "콜록콜록, 에취!" else "숨이 답답해!"
            Dial.DEFROST -> "앞이 뿌예져!"
            Dial.TEMP_UP -> "너무 추워!"
            Dial.TEMP_DOWN -> "조금 더워!"
            Dial.FAN_UP -> "바람이 약해!"
            Dial.FAN_DOWN -> "바람이 너무 세!"
            Dial.SEAT_HEAT_ON -> "의자가 차가워!"
            Dial.SEAT_HEAT_OFF -> "의자가 뜨끈뜨끈!"
            Dial.LIGHT_CHILD -> "글씨가 안 보여!"
            Dial.LIGHTS_OFF -> "잠이 안 와!"
            Dial.FAN, Dial.LIGHTS, Dial.SEAT_HEAT -> "친구를 도와줘!"
        }
    } else {
        when (p.dial) {
            Dial.FAN -> "새싹이 목말라!"
            Dial.TEMP_DOWN -> "얼음이 녹아!"
            Dial.TEMP_UP -> "방이 너무 추워!"
            Dial.LIGHTS -> "깜깜해서 안 보여!"
            else -> "의자가 차가워!"
        }
    }

    private fun hint(p: Puzzle.Quiz, q: Question): String = when (p.kind) {
        QuizKind.MATH -> "손가락을 하나씩 펴면서 세어 봐!"
        QuizKind.ENGLISH -> "원, 투, 쓰리, 손가락으로 하나씩 세면서 영어로 말해 봐!"
        QuizKind.PATTERN -> if (q.terms.size >= 2 && q.terms[1] < q.terms[0]) "수가 얼마씩 작아지는지 손가락으로 세어 봐!"
            else "수가 얼마씩 커지는지 손가락으로 세어 봐!"
        QuizKind.REASON -> "어떤 차 버튼이 친구를 도울 수 있을지 하나씩 떠올려 봐!"
    }

    /** The stage picture with the scene's friend in it, by the device the puzzle is about. */
    private fun picture(p: Puzzle.Quiz): String = when (p.dial) {
        Dial.FAN, Dial.FAN_UP, Dial.FAN_DOWN -> "scene_wind_face"
        Dial.TEMP_UP, Dial.TEMP_DOWN -> "scene_rooms_left"
        Dial.LIGHTS, Dial.LIGHT_CHILD, Dial.LIGHTS_OFF -> "scene_together"
        Dial.AC, Dial.DEFROST -> "scene_fog"
        Dial.SEAT_HEAT, Dial.SEAT_HEAT_ON, Dial.SEAT_HEAT_OFF, Dial.RECIRC -> "scene_dust_in"
    }

    private fun button(d: Dial): String = when (d) {
        Dial.FAN, Dial.FAN_UP, Dial.FAN_DOWN -> "풍량"
        Dial.TEMP_UP, Dial.TEMP_DOWN -> "운전석 온도"
        Dial.LIGHTS, Dial.LIGHT_CHILD, Dial.LIGHTS_OFF -> "독서등"
        Dial.SEAT_HEAT, Dial.SEAT_HEAT_ON, Dial.SEAT_HEAT_OFF -> "시트 열선"
        Dial.AC -> "에어컨"
        Dial.RECIRC -> "내기 순환"
        Dial.DEFROST -> "앞 유리 성에 제거"
    }
}
