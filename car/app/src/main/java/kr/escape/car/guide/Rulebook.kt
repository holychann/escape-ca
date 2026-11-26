package kr.escape.car.guide

/**
 * The user rulebook (docs/차근차근탐험단_룰북.md, copied into the app's assets at build time as [GuideText.ASSET]) read
 * into chapters, so the app shows the very sentences the document has: change the document, and the app follows.
 *
 * Only what the document uses is understood: `#`/`##`/`###` headings, paragraphs, `-` bullets, `1.` steps (with
 * indented bullets under them), `- [ ]` checks, `|` tables and `[그림: …]` picture places. A `>` quote is a note for a
 * feature this build does not have yet, so it is left out.
 */
data class Rulebook(val title: String, val chapters: List<Chapter>) {
    enum class Audience { ALL, PARENT, CHILD, TOGETHER }

    data class Chapter(val title: String, val audience: Audience, val parts: List<Part>) {
        /** The heading without its "[보호자]" tag, as the screen shows it. */
        val name: String get() = title.replace(Regex("""^\[[^\]]+]\s*"""), "")
    }

    /** A `###` part of a chapter; [title] is null for what comes before the first one. */
    data class Part(val title: String?, val blocks: List<Block>)

    sealed interface Block {
        data class Para(val text: String) : Block
        data class Bullets(val items: List<String>) : Block
        data class Step(val number: Int, val text: String, val more: List<String>)
        data class Steps(val items: List<Step>) : Block
        data class Checks(val items: List<String>) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data class Picture(val caption: String) : Block
    }

    /** 「놀이 전 약속」: what the child and the parent go through before each episode. */
    data class Promise(
        /** The chapter's name ("놀이 전 약속"). */
        val title: String,
        val intro: String,
        val notUsedTitle: String,
        val notUsed: Block.Table,
        val usedTitle: String,
        val used: String,
        val pledgeTitle: String,
        val pledges: List<String>,
        val checkTitle: String,
        val checks: List<String>,
    )

    val parentChapters: List<Chapter> get() = chapters.filter { it.audience != Audience.CHILD }
    val childChapters: List<Chapter> get() = chapters.filter { it.audience == Audience.CHILD }

    /** The 놀이 전 약속 chapter as its four parts; null when the document no longer has them. */
    val promise: Promise? get() {
        val c = chapters.firstOrNull { "약속" in it.name && it.audience == Audience.TOGETHER } ?: return null
        fun part(word: String) = c.parts.firstOrNull { it.title?.contains(word) == true }
        val notUsed = part("쓰지 않는") ?: return null
        val used = c.parts.firstOrNull { it.title?.contains("쓰는 것") == true && it != notUsed } ?: return null
        val pledges = part("탐험대장의 약속") ?: return null
        val checks = part("보호자 확인") ?: return null
        return Promise(
            title = c.name,
            intro = c.parts.firstOrNull { it.title == null }?.blocks?.filterIsInstance<Block.Para>()?.joinToString("\n") { it.text }.orEmpty(),
            notUsedTitle = notUsed.title!!,
            notUsed = notUsed.blocks.filterIsInstance<Block.Table>().firstOrNull() ?: return null,
            usedTitle = used.title!!,
            used = used.blocks.filterIsInstance<Block.Para>().joinToString("\n") { it.text },
            pledgeTitle = pledges.title!!,
            pledges = pledges.blocks.filterIsInstance<Block.Steps>().firstOrNull()?.items?.map { it.text } ?: return null,
            checkTitle = checks.title!!,
            checks = checks.blocks.filterIsInstance<Block.Checks>().firstOrNull()?.items ?: return null,
        )
    }

    companion object {
        private val HEADING = Regex("""^(#{1,3})\s+(.*)$""")
        private val STEP = Regex("""^(\d+)\.\s+(.*)$""")
        private val CHECK = Regex("""^-\s+\[[ xX]]\s+(.*)$""")
        private val PICTURE = Regex("""^\[그림:\s*(.*?)]\s*$""")

        fun parse(markdown: String): Rulebook {
            var title = ""
            val chapters = mutableListOf<Chapter>()
            var chapter: Pair<String, Audience>? = null
            val parts = mutableListOf<Part>()
            var partTitle: String? = null
            val blocks = mutableListOf<Block>()
            val para = mutableListOf<String>()
            val bullets = mutableListOf<String>()
            val steps = mutableListOf<Block.Step>()
            val checks = mutableListOf<String>()
            val table = mutableListOf<List<String>>()

            fun flushInline() {
                if (para.isNotEmpty()) blocks += Block.Para(para.joinToString(" ")).also { para.clear() }
                if (bullets.isNotEmpty()) blocks += Block.Bullets(bullets.toList()).also { bullets.clear() }
                if (steps.isNotEmpty()) blocks += Block.Steps(steps.toList()).also { steps.clear() }
                if (checks.isNotEmpty()) blocks += Block.Checks(checks.toList()).also { checks.clear() }
                if (table.isNotEmpty()) {
                    blocks += Block.Table(table.first(), table.drop(1))
                    table.clear()
                }
            }
            fun flushPart() {
                flushInline()
                if (blocks.isNotEmpty() || partTitle != null) parts += Part(partTitle, blocks.toList())
                blocks.clear()
                partTitle = null
            }
            fun flushChapter() {
                flushPart()
                val c = chapter
                if (c != null && parts.isNotEmpty()) chapters += Chapter(c.first, c.second, parts.toList())
                parts.clear()
            }

            for (raw in markdown.replace("\r", "").lines()) {
                val indented = raw.startsWith("  ") || raw.startsWith("\t")
                val line = raw.trim()
                val heading = HEADING.matchEntire(line)
                when {
                    line.isEmpty() -> flushInline()
                    // a note for a feature this build does not have
                    line.startsWith(">") -> flushInline()
                    heading != null -> {
                        val level = heading.groupValues[1].length
                        val text = heading.groupValues[2].trim()
                        when (level) {
                            1 -> { flushChapter(); title = text; chapter = text to Audience.ALL }
                            2 -> { flushChapter(); chapter = text to audienceOf(text) }
                            else -> { flushPart(); partTitle = text }
                        }
                    }
                    PICTURE.matches(line) -> {
                        flushInline()
                        blocks += Block.Picture(PICTURE.matchEntire(line)!!.groupValues[1])
                    }
                    line.startsWith("|") -> {
                        if (table.isEmpty()) flushInline()
                        val cells = line.trim('|').split("|").map { it.trim() }
                        // the |---|---| line under the header
                        if (cells.all { it.matches(Regex("""^:?-{2,}:?$""")) }) continue
                        table += cells
                    }
                    CHECK.matches(line) -> {
                        if (checks.isEmpty()) flushInline()
                        checks += CHECK.matchEntire(line)!!.groupValues[1]
                    }
                    STEP.matches(line) && !indented -> {
                        if (steps.isEmpty()) flushInline()
                        STEP.matchEntire(line)!!.let { m -> steps += Block.Step(m.groupValues[1].toInt(), m.groupValues[2], emptyList()) }
                    }
                    line.startsWith("- ") && indented && steps.isNotEmpty() -> {
                        val item = line.removePrefix("- ").trim()
                        val pic = PICTURE.matchEntire(item)
                        if (pic != null) {
                            // a picture under a step comes after the steps it belongs to
                            val keep = steps.toList()
                            steps.clear()
                            blocks += Block.Steps(keep)
                            blocks += Block.Picture(pic.groupValues[1])
                        } else {
                            val last = steps.removeAt(steps.lastIndex)
                            steps += last.copy(more = last.more + item)
                        }
                    }
                    line.startsWith("- ") -> {
                        if (bullets.isEmpty()) flushInline()
                        bullets += line.removePrefix("- ").trim()
                    }
                    else -> {
                        if (bullets.isNotEmpty() || steps.isNotEmpty() || checks.isNotEmpty() || table.isNotEmpty()) flushInline()
                        para += line
                    }
                }
            }
            flushChapter()
            return Rulebook(title, chapters)
        }

        private fun audienceOf(heading: String) = when {
            heading.startsWith("[보호자]") -> Audience.PARENT
            heading.startsWith("[아이]") -> Audience.CHILD
            heading.startsWith("[함께]") -> Audience.TOGETHER
            else -> Audience.ALL
        }

        /** The words without the document's marks: **bold** and table cells, as a child hears them. */
        fun plain(text: String): String = text.replace("**", "")

        /**
         * A part as the friend reads it aloud, one line per step, row or paragraph (each well under the speech
         * server's 600 letters). A "[힌트 듣기]" button name is said in quotes, so neither the voice nor the subtitles
         * take it for a speech tag; pictures are not read.
         */
        fun spoken(part: Part): List<String> = buildList {
            part.title?.let { add(it) }
            for (b in part.blocks) when (b) {
                is Block.Para -> add(b.text)
                is Block.Bullets -> addAll(b.items)
                is Block.Steps -> b.items.forEach { add((listOf(it.text) + it.more).joinToString(" ")) }
                is Block.Checks -> addAll(b.items)
                is Block.Table -> b.rows.forEach { add(it.joinToString(", ")) }
                is Block.Picture -> Unit
            }
        }.map { line -> plain(line).replace(Regex("""\[([^\]]+)]"""), "‘$1’").trim() }.filter { it.isNotEmpty() }
    }
}
