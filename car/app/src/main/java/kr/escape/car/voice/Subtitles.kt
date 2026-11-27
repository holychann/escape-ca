package kr.escape.car.voice

/**
 * Lumi's line as subtitles for the child: tags taken out, cut into short pieces of one or two lines, and timed along
 * the recording. The recording gives no word timings, so each piece gets a share of its length in proportion to how
 * many letters it has.
 */
object Subtitles {
    /** About two lines of the child's subtitle at its size. */
    const val MAX_CHARS = 34

    /** Reading pace when there is no sound: slow enough for a child who reads along. */
    const val READ_MS_PER_CHAR = 230L
    const val MIN_PIECE_MS = 1800L

    // a speech tag ([excited], <short pause>, {laugh}) is for the voice, never for the screen
    // closing brackets escaped too: Android's regex engine (ICU) refuses a bare } or ]
    private val TAG = Regex("""\[[^\]]*\]|<[^>]*>|\{[^}]*\}""")
    private val SENTENCE_END = Regex("""(?<=[.!?…~])\s+""")

    // a letter count ("apple, 사과를 영어로 쓰면 몇 글자일까?"): the English word, then its Korean meaning
    private val SPELLED = Regex("""(?<![A-Za-z])([A-Za-z]+),\s*(?=([가-힣][가-힣 ]*?)[을를]\s*영어로\s*쓰면)""")

    /** [text] as it is shown: no tags, no English word the child must spell, no doubled spaces. */
    fun clean(text: String): String =
        unspelled(TAG.replace(text, " ")).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Each letter count's English word in [text] with its Korean meaning: ("apple", "사과"). */
    fun spelled(text: String): List<Pair<String, String>> =
        SPELLED.findAll(text).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    /**
     * [text] without the English word of a letter count: the child spells it in their head, so the screen keeps only
     * the Korean meaning ("사과를 영어로 쓰면 몇 글자일까?"), and the word said anywhere else in the line is shown as
     * its meaning too. Lumi still says the word.
     */
    fun unspelled(text: String): String {
        val found = spelled(text)
        if (found.isEmpty()) return text
        var out = SPELLED.replace(text, "")
        for ((word, meaning) in found) {
            out = Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])", RegexOption.IGNORE_CASE).replace(out) { meaning }
        }
        return out
    }

    /** [text] in pieces of at most [max] letters, cut between sentences, then at a comma, then between words. */
    fun pieces(text: String, max: Int = MAX_CHARS): List<String> {
        val sentences = clean(text).split(SENTENCE_END).filter { it.isNotBlank() }.flatMap { split(it, max) }
        // short sentences that fit together share a piece ("고쳤다! 최고야!")
        val out = mutableListOf<String>()
        for (s in sentences) {
            val last = out.lastOrNull()
            if (last != null && last.length + 1 + s.length <= max) out[out.size - 1] = "$last $s" else out += s
        }
        return out
    }

    // where a Korean sentence pauses by itself: after a comma or a word that ends a clause ("가야 하는데", "들어오니")
    private val CLAUSE_END = Regex("""(,|는데|니까|니|지만|어서|아서|해서|하고|타고|으면|려고|는지|다가)$""")
    // a place or a way ("밖에서", "안으로") ends a phrase too, if less clearly
    private val PLACE_END = Regex("""(에서|에게|으로|까지|부터)$""")
    // an object ("바람을") belongs with the verb after it, so a cut right after it reads badly
    private val OBJECT_END = Regex("""[을를]$""")

    /** How good a place to cut after [word] is, at [at] letters where [ideal] would be even: lower is better. */
    private fun cutScore(word: String, at: Int, ideal: Int): Int = kotlin.math.abs(at - ideal) -
        (if (CLAUSE_END.containsMatchIn(word)) 6 else 0) - (if (PLACE_END.containsMatchIn(word)) 3 else 0) +
        (if (OBJECT_END.containsMatchIn(word)) 5 else 0)

    /** About one line of the child's subtitle at its size: a longer piece is shown on two. */
    const val LINE_CHARS = 16

    /**
     * [piece] with its line break put where the phrase allows, near the middle ("냄새가 밖에서 / 바람을 타고 들어오니"),
     * instead of wherever the screen runs out ("바람을 / 타고"). A piece that fits one line is left as it is.
     */
    fun lines(piece: String): String {
        if (piece.length <= LINE_CHARS) return piece
        val words = piece.split(" ")
        if (words.size < 2) return piece
        val ends = words.runningReduce { a, b -> "$a $b" }.map { it.length }
        val best = (0 until words.size - 1).minBy { i -> cutScore(words[i], ends[i], piece.length / 2) }
        return words.subList(0, best + 1).joinToString(" ") + "\n" + words.subList(best + 1, words.size).joinToString(" ")
    }

    /**
     * A sentence too long for one piece, in as few pieces as fit, of about the same length, each cut at the word
     * boundary nearest its share and, when one is close, where the sentence pauses ("가야 하는데 / 별빛 기차 칸
     * 곳곳이 고장 났대", never "기차 / 칸").
     */
    private fun split(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val words = sentence.split(" ")
        for (count in (sentence.length + max - 1) / max..words.size) {
            val cut = balanced(words, count)
            if (cut.all { it.length <= max }) return cut
        }
        return words
    }

    private fun balanced(words: List<String>, count: Int): List<String> {
        // ends[i]: the length of the words up to and with words[i], spaces included
        val ends = words.runningReduce { a, b -> "$a $b" }.map { it.length }
        val total = ends.last()
        val out = mutableListOf<String>()
        var start = 0
        for (k in 1 until count) {
            val ideal = total * k / count
            // leave at least one word for each piece still to come
            val best = (start..words.size - 1 - (count - k)).minByOrNull { i -> cutScore(words[i], ends[i], ideal) } ?: break
            out += words.subList(start, best + 1).joinToString(" ")
            start = best + 1
        }
        out += words.subList(start, words.size).joinToString(" ")
        return out
    }

    /** When each piece starts, in ms from the start of a recording [totalMs] long: by letters, spaces not counted. */
    fun starts(pieces: List<String>, totalMs: Long, gaps: List<Pauses.Gap> = emptyList()): List<Long> {
        // the guess: a share by letters, the voice also resting a little at a comma and longer at a sentence's end
        val weights = pieces.map { p ->
            p.count { !it.isWhitespace() }.coerceAtLeast(1).toLong() * 2 + p.count { it == ',' } * 2 + p.count { it in ".!?…~" } * 5
        }
        val sum = weights.sum().coerceAtLeast(1)
        var at = 0L
        val guess = weights.map { w -> at.also { at += totalMs * w / sum } }
        if (gaps.isEmpty() || pieces.size < 2) return guess
        // each turn goes to the real pause nearest its guess, at the moment the voice starts again after it, so a
        // piece never comes up before its words are said; a turn with no pause near it keeps its guess
        val reach = totalMs / pieces.size
        val out = mutableListOf(0L)
        var used = -1
        for (k in 1 until pieces.size) {
            val pick = gaps.withIndex().filter { (i, g) -> i > used && g.endMs > out.last() && kotlin.math.abs(g.middleMs - guess[k]) <= reach }
                .minByOrNull { (_, g) -> kotlin.math.abs(g.middleMs - guess[k]) }
            if (pick != null) used = pick.index
            out += maxOf(pick?.value?.endMs?.minus(TURN_LEAD_MS) ?: guess[k], out.last())
        }
        return out
    }

    /** A piece comes up this much before the voice starts again, as the eye is a moment slower than the ear. */
    const val TURN_LEAD_MS = 120L

    /** How long a piece stays up when there is no sound to follow. */
    fun readMs(piece: String): Long = (piece.count { !it.isWhitespace() } * READ_MS_PER_CHAR).coerceAtLeast(MIN_PIECE_MS)

    /** Start times (ms) for each piece and total reading duration (ms) when shown silently at reading pace. */
    fun readingPlan(pieces: List<String>): Pair<List<Long>, Long> {
        if (pieces.isEmpty()) return emptyList<Long>() to 0L
        val times = pieces.runningFold(0L) { at, p -> at + readMs(p) }
        return times.dropLast(1) to times.last()
    }
}
