package kr.escape.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTest {
    @Test
    fun theFourTermsAreAskedAndAllOfThemTickEveryBit() {
        assertEquals(listOf("서비스 이용약관", "개인정보 수집, 이용", "보호자 동의", "정차 중에만 쓰기 약속"), Terms.ITEMS.map { it.title })
        assertEquals(0b1111, Terms.ALL)
    }

    @Test
    fun eachSummaryIsShortPlainAndWithoutAMiddleDot() {
        for (item in Terms.ITEMS) {
            assertTrue(item.title, item.lines.size in 2..3)
            for (line in item.lines) {
                assertTrue(line, line.length <= 70)
                assertTrue(line, '·' !in line)
            }
        }
    }

    @Test
    fun thePrivacySummarySaysWhatIsSentAndWhatIsNot() {
        val privacy = Terms.ITEMS[1].lines.joinToString(" ")
        for (word in listOf("나이대", "소원", "이름", "사는 곳")) assertTrue(word, word in privacy)
    }
}
