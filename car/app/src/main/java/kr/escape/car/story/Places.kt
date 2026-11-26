package kr.escape.car.story

import kotlin.random.Random

/**
 * The places the dice next to the 장소 box picks from, so the family need not type on the car screen: each suits all
 * six kinds of story, nothing in it is scary, and a four-year-old pictures it at once.
 */
object Places {
    val ALL = listOf(
        "공룡 섬", "우주 정거장", "바닷속 마을", "북극 얼음 마을", "구름 위 놀이공원", "장난감 나라",
        "과자 마을", "정글 탐험 기지", "사막 오아시스", "달나라 기지", "꽃 정원 마을", "등대 마을",
    )

    /** A place other than [current], so every press shows something new. */
    fun next(current: String, rnd: Random = Random.Default): String = ALL.filter { it != current.trim() }.random(rnd)
}
