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

    private fun split(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val words = sentence.split(" ")
        val out = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            // a comma close to the limit is a better place to cut than the limit itself
            if (next.length > max && cur.isNotEmpty()) {
                out += cur
                cur = w
            } else {
                cur = next
                if (cur.endsWith(",") && cur.length >= max * 2 / 3) {
                    out += cur
                    cur = ""
                }
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** When each piece starts, in ms from the start of a recording [totalMs] long: by letters, spaces not counted. */
    fun starts(pieces: List<String>, totalMs: Long): List<Long> {
        val weights = pieces.map { p -> p.count { !it.isWhitespace() }.coerceAtLeast(1).toLong() }
        val sum = weights.sum().coerceAtLeast(1)
        var at = 0L
        return weights.map { w -> at.also { at += totalMs * w / sum } }
    }

    /** How long a piece stays up when there is no sound to follow. */
    fun readMs(piece: String): Long = (piece.count { !it.isWhitespace() } * READ_MS_PER_CHAR).coerceAtLeast(MIN_PIECE_MS)

    /** Start times (ms) for each piece and total reading duration (ms) when shown silently at reading pace. */
    fun readingPlan(pieces: List<String>): Pair<List<Long>, Long> {
        if (pieces.isEmpty()) return emptyList<Long>() to 0L
        val times = pieces.runningFold(0L) { at, p -> at + readMs(p) }
        return times.dropLast(1) to times.last()
    }
}
