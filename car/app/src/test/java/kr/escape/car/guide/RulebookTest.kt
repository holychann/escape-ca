package kr.escape.car.guide

import kr.escape.car.guide.Rulebook.Audience
import kr.escape.car.guide.Rulebook.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The real docs/차근차근탐험단_룰북.md, read the way the app reads it. */
class RulebookTest {
    private val source = File(System.getProperty("rulebook.file") ?: "../../docs/차근차근탐험단_룰북.md").readText()
    private val book = Rulebook.parse(source)

    /** Every piece of text the app shows, in one string. */
    private fun shown(b: Rulebook): String = buildList {
        add(b.title)
        b.chapters.forEach { c ->
            add(c.title)
            c.parts.forEach { p ->
                p.title?.let(::add)
                p.blocks.forEach { bl ->
                    when (bl) {
                        is Block.Para -> add(bl.text)
                        is Block.Bullets -> addAll(bl.items)
                        is Block.Steps -> bl.items.forEach { add(it.text); addAll(it.more) }
                        is Block.Checks -> addAll(bl.items)
                        is Block.Table -> { addAll(bl.header); bl.rows.forEach(::addAll) }
                        is Block.Picture -> add(bl.caption)
                    }
                }
            }
        }
    }.joinToString("\n")

    @Test
    fun everySentenceOfTheDocumentIsInTheApp() {
        val all = shown(book)
        val lines = source.replace("\r", "").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(">") && !it.matches(Regex("""^\|[\s:|-]+\|$""")) }
        for (line in lines) {
            // the marks around a line (#, -, 1., - [ ], |, [그림: ]) are the app's to draw; the words must all be there
            var words = line.removePrefix("- [ ]")
            for (mark in listOf("""^#+\s*""", """^-\s+""", """^\d+\.\s+""", """^\[그림:\s*""", """]$""")) words = words.replace(Regex(mark), "").trim()
            for (cell in words.split("|").map { it.trim() }.filter { it.isNotEmpty() }) {
                assertTrue("not in the app: $cell", cell in all)
            }
        }
    }

    @Test
    fun aFeatureThisBuildDoesNotHaveIsLeftOut() {
        assertTrue("틀려도" in source)
        assertFalse("틀려도" in shown(book))
        assertFalse("그 버튼은 아닌 것 같아" in shown(book))
    }

    @Test
    fun chaptersKnowWhoTheyAreFor() {
        assertEquals("차근차근 탐험단 룰북", book.title)
        val who = book.chapters.associate { it.name to it.audience }
        assertEquals(Audience.TOGETHER, who["놀이 전 약속"])
        assertEquals(Audience.CHILD, who["친구와 함께하는 탐험 순서"])
        assertEquals(Audience.CHILD, who["문제 푸는 법"])
        assertEquals(Audience.PARENT, who["문제 해결"])
        assertTrue(book.parentChapters.none { it.audience == Audience.CHILD })
        assertEquals(2, book.childChapters.size)
    }

    @Test
    fun thePromiseHasItsFourParts() {
        val p = book.promise
        assertNotNull(p)
        p!!
        assertEquals("놀이 전 약속", p.title)
        assertEquals(7, p.notUsed.rows.size)
        assertEquals("브레이크 페달, 액셀 페달", p.notUsed.rows.first().first())
        assertTrue(p.notUsed.rows.any { "경적" in it.first() })
        assertTrue(p.notUsed.rows.any { "안전벨트 풀기" in it.first() })
        assertTrue("시트 열선" in p.used)
        assertEquals(6, p.pledges.size)
        assertEquals("나는 내 자리에 앉아 벨트를 매고 있어요.", p.pledges.first())
        assertEquals(5, p.checks.size)
        assertTrue(p.checks.first().startsWith("기어 P"))
    }

    @Test
    fun stepsKeepTheirNumbersAcrossAPicture() {
        val order = book.childChapters.first().parts.first().blocks
        val numbers = order.filterIsInstance<Block.Steps>().flatMap { s -> s.items.map { it.number } }
        assertEquals((1..8).toList(), numbers)
        // the picture under step 1 comes right after it
        assertTrue(order[order.indexOfFirst { it is Block.Steps } + 1] is Block.Picture)
    }

    @Test
    fun indentedBulletsStayWithTheirStep() {
        val setup = book.chapters.first { it.name == "시작 전 준비" }
        val steps = setup.parts.flatMap { it.blocks }.filterIsInstance<Block.Steps>().flatMap { it.items }
        val wish = steps.first { "소원" in it.text }
        assertEquals(3, wish.more.size)
    }

    @Test
    fun readAloudLinesAreShortAndHaveNoSpeechTags() {
        for (c in book.childChapters) for (p in c.parts) {
            val lines = Rulebook.spoken(p)
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.all { it.length < 600 })
            // a button name in brackets would be taken for a tag ([excited]) by the voice and the subtitles
            assertTrue(lines.none { '[' in it || ']' in it || "**" in it })
        }
        val tip = Rulebook.spoken(Rulebook.Part(null, listOf(Block.Bullets(listOf("안전 지킴이가 [힌트 듣기]를 눌러 줘요.")))))
        assertEquals(listOf("안전 지킴이가 ‘힌트 듣기’를 눌러 줘요."), tip)
    }

    @Test
    fun aDocumentWithoutThePromiseHasNone() {
        assertEquals(null, Rulebook.parse("# 룰북\n\n## [보호자] 준비\n\n글.").promise)
    }
}
