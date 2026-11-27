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
    private val CLAUSE_END = Regex("""(,|는데|니까|니|지만|어서|아서|해서|하고|타고|면|려고|는지|다가)$""")
    // a place or a way ("밖에서", "안으로") ends a phrase too, if less clearly
    private val PLACE_END = Regex("""(에서|에게|으로|까지|부터)$""")
    // an object ("바람을") belongs with the verb after it, so a cut right after it reads badly
    private val OBJECT_END = Regex("""[을를]$""")

    // the end of a sentence ("탐험대장!") is the clearest place of all: a piece of two short sentences breaks between them
    private val SENTENCE_WORD_END = Regex("""[.!?…~]$""")

    /** How good a place to cut after [word] is, at [at] letters where [ideal] would be even: lower is better. */
    private fun cutScore(word: String, at: Int, ideal: Int): Int = kotlin.math.abs(at - ideal) -
        (if (SENTENCE_WORD_END.containsMatchIn(word)) 12 else 0) -
        (if (CLAUSE_END.containsMatchIn(word)) 6 else 0) - (if (PLACE_END.containsMatchIn(word)) 3 else 0) +
        (if (OBJECT_END.containsMatchIn(word)) 5 else 0)

    /**
     * A cut that leaves one side under a quarter of [whole] ("안녕, / 탐험대장! 나는 강아지 반테야.") is never taken while
     * another is possible, whatever word it comes after.
     */
    private fun lopsided(before: Int, after: Int, whole: Int): Int = if (minOf(before, after) * 4 < whole) 100 else 0

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
        val best = (0 until words.size - 1).minBy { i ->
            cutScore(words[i], ends[i], piece.length / 2) + lopsided(ends[i], piece.length - ends[i] - 1, piece.length)
        }
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
    fun starts(pieces: List<String>, totalMs: Long, timeline: Pauses.Timeline? = null): List<Long> {
        if (timeline != null && pieces.size > 1 && timeline.voicedMs > 0) return along(pieces, timeline)
        // no recording to read: a share by letters, the voice also resting a little at a comma and longer at a
        // sentence's end
        val weights = pieces.map { p ->
            p.count { !it.isWhitespace() }.coerceAtLeast(1).toLong() * 2 + p.count { it == ',' } * 2 + p.count { it in ".!?…~" } * 5
        }
        val sum = weights.sum().coerceAtLeast(1)
        var at = 0L
        return weights.map { w -> at.also { at += totalMs * w / sum } }
    }

    /**
     * Along the recording itself. The friend's pace is measured on the voiced stretches only (the pauses left out),
     * so a long line with many commas does not drift: where piece k ends is the moment the voice has said that share
     * of the line's syllables. A pause within [SNAP_MS] of that moment is where the voice really turns to the next
     * piece, and the piece comes up as the voice starts again after it; with no pause near, it comes up at the
     * measured moment.
     */
    private fun along(pieces: List<String>, timeline: Pauses.Timeline): List<Long> {
        val sizes = pieces.map { p -> p.count { it.isLetterOrDigit() }.coerceAtLeast(1).toLong() }
        val total = sizes.sum()
        anchored(pieces, sizes, timeline)?.let { return it }
        val out = mutableListOf(0L)
        var said = 0L
        var used = -1
        for (k in 1 until pieces.size) {
            said += sizes[k - 1]
            val end = timeline.at(timeline.voicedMs * said / total)
            val pause = timeline.gaps.withIndex()
                .filter { (i, g) -> i > used && g.endMs > out.last() && kotlin.math.abs(g.middleMs - end) <= SNAP_MS }
                .minByOrNull { (_, g) -> kotlin.math.abs(g.middleMs - end) }
            if (pause != null) used = pause.index
            out += maxOf(pause?.value?.endMs?.minus(TURN_LEAD_MS) ?: end, out.last())
        }
        return out
    }

    /** A word the voice stops after: a comma or a sentence's end. */
    private val STOP_WORD = Regex("""[,.!?…~]$""")

    /**
     * Along the pauses the line's own marks make. The voice rests longest at each comma and sentence end, longer than
     * between any two words, so the line's marks, in order, are the recording's longest pauses, in order. A piece that
     * ends at a mark comes up as the voice starts again after its pause; any other piece is placed between the marks
     * around it as [along] places it. A share by syllables alone drifts by a second or more over a long line and turns
     * at a short breath near the true pause ("함께 온 친구" a second and a half before the voice says it).
     * Null when the marks and the pauses do not match: fewer pauses than marks, or one too far from where it should be.
     */
    private fun anchored(pieces: List<String>, sizes: List<Long>, timeline: Pauses.Timeline): List<Long>? {
        val total = sizes.sum()
        // where the line stops, as the syllables said by then: every mark but the line's last
        val stops = mutableListOf<Long>()
        var count = 0L
        for (word in pieces.joinToString(" ").split(" ").filter { it.isNotEmpty() }) {
            count += word.count { it.isLetterOrDigit() }
            if (STOP_WORD.containsMatchIn(word) && count < total) stops += count
        }
        val matched = match(stops.map { timeline.at(timeline.voicedMs * it / total) }, timeline.gaps)
        if (matched.isEmpty()) return null
        // (syllables said, the voice stops at, the voice goes on at): the start, each mark with its pause, the end
        val marks = listOf(Triple(0L, timeline.startMs, timeline.startMs)) +
            matched.map { (i, g) -> Triple(stops[i], g.startMs, g.endMs) } +
            Triple(total, timeline.endMs, timeline.endMs)
        val out = mutableListOf(0L)
        var said = 0L
        for (k in 1 until pieces.size) {
            said += sizes[k - 1]
            val at = marks.firstOrNull { it.first == said }
            val turn = if (at != null) at.third - TURN_LEAD_MS else {
                // between two marks: its share of the voice between them, or a pause close to that
                val from = marks.last { it.first < said }
                val to = marks.first { it.first > said }
                val end = from.third + (to.second - from.third) * (said - from.first) / (to.first - from.first)
                timeline.gaps.filter { g -> g.startMs >= from.third && g.endMs <= to.second && kotlin.math.abs(g.middleMs - end) <= SNAP_MS }
                    .minByOrNull { g -> kotlin.math.abs(g.middleMs - end) }?.let { it.endMs - TURN_LEAD_MS } ?: end
            }
            out += maxOf(turn, out.last())
        }
        return out
    }

    /**
     * Each mark, by where the syllables put it ([marks], in ms), paired in order with a pause of [gaps]: a long pause
     * a little away is taken over a short breath right there, and a mark the voice runs through ("탐험대장! 나는")
     * gets none. Returns (the mark's index, its pause) for every mark that has one.
     */
    private fun match(marks: List<Long>, gaps: List<Pauses.Gap>): List<Pair<Int, Pauses.Gap>> {
        fun worth(i: Int, j: Int): Long? {
            val g = gaps[j]
            val off = kotlin.math.abs(g.middleMs - marks[i])
            return (g.endMs - g.startMs - off / 2).takeIf { off <= ANCHOR_MS && it > 0 }
        }
        val n = marks.size
        val m = gaps.size
        // best[i][j]: the most the first i marks can get from the first j pauses
        val best = Array(n + 1) { LongArray(m + 1) }
        for (i in 0..n) for (j in 0..m) {
            if (i == 0 || j == 0) continue
            val paired = worth(i - 1, j - 1)?.let { best[i - 1][j - 1] + it } ?: Long.MIN_VALUE
            best[i][j] = maxOf(best[i - 1][j], best[i][j - 1], paired)
        }
        val out = mutableListOf<Pair<Int, Pauses.Gap>>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val w = worth(i - 1, j - 1)
            when {
                w != null && best[i][j] == best[i - 1][j - 1] + w -> { out += (i - 1) to gaps[j - 1]; i--; j-- }
                best[i][j] == best[i - 1][j] -> i--
                else -> j--
            }
        }
        return out.reversed()
    }

    /** How far a mark's pause may be from where the syllables put it and still be taken as that mark's. */
    const val ANCHOR_MS = 2500L

    /** A piece comes up this much before the voice starts again, as the eye is a moment slower than the ear. */
    const val TURN_LEAD_MS = 120L

    /** How far from the measured end of a piece a pause may be and still be the one the voice turns at. */
    const val SNAP_MS = 700L

    /** How long a piece stays up when there is no sound to follow. */
    fun readMs(piece: String): Long = (piece.count { !it.isWhitespace() } * READ_MS_PER_CHAR).coerceAtLeast(MIN_PIECE_MS)

    /** Start times (ms) for each piece and total reading duration (ms) when shown silently at reading pace. */
    fun readingPlan(pieces: List<String>): Pair<List<Long>, Long> {
        if (pieces.isEmpty()) return emptyList<Long>() to 0L
        val times = pieces.runningFold(0L) { at, p -> at + readMs(p) }
        return times.dropLast(1) to times.last()
    }
}
