package kr.escape.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WishLineTest {
    @Test
    fun eachKindReadsAsSomethingToDoAtThePlace() {
        assertEquals("우주 정거장으로 구조대 출동!", wishLine("우주 정거장", "구조대"))
        assertEquals("과자 마을로 구조대 출동!", wishLine("과자 마을", "구조대"))
        assertEquals("공룡 섬에서 보물찾기 하자!", wishLine("공룡 섬", "보물찾기"))
        assertEquals("공룡 섬에서 탐정 놀이 하자!", wishLine(" 공룡 섬 ", "탐정 놀이"))
        assertEquals("달나라 기지로 수리하러 가자!", wishLine("달나라 기지", "수리하러 가기"))
        assertEquals("우주 정거장으로 수리하러 가자!", wishLine("우주 정거장", "수리하러 가기"))
        assertEquals("달나라 기지에서 축제 준비하자!", wishLine("달나라 기지", "축제 준비"))
        assertEquals("장난감 나라에서 발명 대회 열자!", wishLine("장난감 나라", "발명 대회"))
    }

    @Test
    fun halfAWishStillSaysSomethingAndNoneSaysNothing() {
        assertEquals("구조대 출동!", wishLine("", "구조대"))
        assertEquals("공룡 섬 좋아!", wishLine("공룡 섬", null))
        assertNull(wishLine("  ", null))
    }

    @Test
    fun towardFollowsTheLastLetter() {
        assertEquals("으로", toward("정거장"))
        assertEquals("로", toward("마을"))
        assertEquals("로", toward("하늘 위 별"))
        assertEquals("로", toward("바다"))
        assertEquals("로", toward("Lego"))
    }
}
