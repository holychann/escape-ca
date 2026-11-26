package kr.escape.car.story

import android.content.Context
import kr.escape.car.engine.Age
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Questions
import kr.escape.car.engine.QuizKind
import org.json.JSONObject
import kotlin.random.Random

/**
 * The question bank the story server draws from (server app/question_bank.json, made once with Claude and checked),
 * shipped in the app's assets so the car still asks a fresh, age-fitting question about the right device when the
 * server cannot be reached. The planner only plans a quiz this bank has a question for.
 */
class QuestionBank(private val entries: List<Entry>) : Questions {
    data class Entry(
        val kind: QuizKind,
        /** The device the question is about; it is asked only for a puzzle on that device. */
        val dial: Dial,
        val age: Age,
        val text: String,
        val hint: String,
        /**
         * What the question works out to: the amount for a number question, and for a situation (REASON) the way
         * the function goes (1 on or up, 0 off).
         */
        val answer: Int?,
        val expr: String = "",
        val word: String = "",
        val terms: List<Int> = emptyList(),
    )

    val size: Int get() = entries.size

    /** What the planner may ask: this bank's questions, or with an empty (broken) bank the app's own built-in ones. */
    val questions: Questions get() = if (entries.isEmpty()) Questions.ANY else this

    private fun matches(e: Entry, p: Puzzle.Quiz) = e.kind == p.kind && e.dial == p.dial && e.answer == p.value

    /** Whether [pick] finds a question for this quiz at this age. */
    override fun has(q: Puzzle.Quiz, age: Age): Boolean = entries.any { matches(it, q) && it.age.ordinal <= age.ordinal }

    /**
     * Whether [question] with its [hint] is this bank's own for [p] at [age] or a younger age (same text, parts and
     * hint: Lumi speaks the hint, so a story's own hint is not played).
     */
    fun asks(p: Puzzle.Quiz, age: Age, question: Question, hint: String): Boolean = entries.any {
        matches(it, p) && it.age.ordinal <= age.ordinal && it.kind == question.kind && it.text == question.text &&
            it.hint == hint &&
            it.expr == question.expr && it.word == question.word && it.terms == question.terms
    }

    /** A random question for this quiz at this age (an easier age's when this one has none) and its hint. */
    fun pick(p: Puzzle.Quiz, age: Age, rnd: Random): Pair<Question, String>? {
        for (a in Age.entries.take(age.ordinal + 1).reversed()) {
            val found = entries.filter { matches(it, p) && it.age == a }
            if (found.isEmpty()) continue
            val e = found.random(rnd)
            return Question(e.kind, e.text, e.expr, e.word, e.terms) to e.hint
        }
        return null
    }

    companion object {
        const val FILE = "question_bank.json"

        /** Entries this app cannot use (an unknown kind or age) are left out; a broken file is an empty bank. */
        fun parse(json: String): QuestionBank {
            val list = runCatching { JSONObject(json).getJSONArray("questions") }.getOrNull() ?: return QuestionBank(emptyList())
            val entries = (0 until list.length()).mapNotNull { i ->
                runCatching {
                    val o = list.getJSONObject(i)
                    val terms = o.optJSONArray("terms")
                    Entry(
                        kind = QuizKind.valueOf(o.getString("kind")),
                        dial = Dial.valueOf(o.getString("dial")),
                        age = Age.valueOf(o.getString("age")),
                        text = o.getString("text"),
                        hint = o.getString("hint"),
                        answer = if (o.has("answer") && !o.isNull("answer")) o.getInt("answer") else null,
                        expr = o.optString("expr", ""),
                        word = o.optString("word", ""),
                        terms = terms?.let { t -> List(t.length()) { t.getInt(it) } }.orEmpty(),
                    )
                }.getOrNull()
            }
            return QuestionBank(entries)
        }

        fun load(context: Context): QuestionBank =
            runCatching { parse(context.assets.open(FILE).bufferedReader().use { it.readText() }) }.getOrDefault(QuestionBank(emptyList()))
    }
}
