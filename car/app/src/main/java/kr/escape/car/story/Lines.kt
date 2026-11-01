package kr.escape.car.story

import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Rhythm

/** What one puzzle looks and sounds like: Lumi's line, the hint, the pictures and the parent's buttons. */
data class Scene(
    val bubble: String,
    val speech: String,
    val hint: String,
    val pictures: List<String>,
    val buttons: List<String>,
    val answer: String,
)

/**
 * The reviewed lines the app ships with. The AI story server (stage 2) rewrites the words around the same answers;
 * when it fails or a check rejects its text, these lines are used instead.
 */
object Lines {
    const val INTRO = "안녕, 탐험대장! 나는 루미야. 오늘은 별빛 기차가 고장 났대. 단서를 듣고 안전 지킴이에게 부탁해서 친구들을 도와주자!"
    const val SUCCESS = "정답! 별 하나 획득! 우리 탐험대장 최고야!"
    const val TREASURE_GIFT = "보물 상자가 열렸어! 안전 지킴이가 글로브박스에서 선물을 꺼내 줄 거야."
    const val TREASURE_BADGE = "모든 친구를 도왔어! 탐험 배지를 받았어!"
    const val PAUSED = "잠깐 쉬어 갈게. 다시 준비되면 이어서 하자!"

    fun scene(p: Puzzle): Scene = when (p) {
        is Puzzle.WindPath -> wind(p)
        is Puzzle.DustCloud -> if (p.fromOutside) Scene(
            bubble = "먼지 구름이 와!",
            speech = "큰일이야! 밖에서 먼지 구름이 몰려오고 있어. 바깥 공기가 들어오지 않게 막아야 해!",
            hint = "먼지는 바깥에서 와. 바깥 공기 문을 닫아 볼까?",
            pictures = listOf("dust_outside"),
            buttons = listOf("내기 순환", "외기 유입"),
            answer = "내기 순환 켜기",
        ) else Scene(
            bubble = "김이 모락모락!",
            speech = "차 안에 김이 모락모락 피어올라! 바깥의 맑은 공기를 들여보내 줄까?",
            hint = "김은 안에서 생겼어. 바깥 공기를 들어오게 해 볼까?",
            pictures = listOf("smoke_inside"),
            buttons = listOf("내기 순환", "외기 유입"),
            answer = "외기 유입(내기 순환 끄기)",
        )
        is Puzzle.TwoRooms -> {
            val side = if (p.penguinLeft) "왼쪽" else "오른쪽"
            Scene(
                bubble = "펭귄은 어느 방?",
                speech = "추운 걸 좋아하는 펭귄과 따뜻한 걸 좋아하는 사막여우가 이사 왔어. 펭귄 발자국이 $side 방으로 갔대! 두 방 온도를 따로 맞춰 줘.",
                hint = "펭귄 방은 시원하게, 여우 방은 따뜻하게! 펭귄은 $side 방이야.",
                pictures = if (p.penguinLeft) listOf("penguin_room", "fox_room") else listOf("fox_room", "penguin_room"),
                buttons = listOf("좌우 온도 따로", "운전석 온도", "조수석 온도"),
                answer = "좌우 따로 켜고, $side 쪽을 반대쪽보다 2도 이상 낮게",
            )
        }
        Puzzle.FoggyWindow -> Scene(
            bubble = "앞이 안 보여!",
            speech = "앞 유리가 뿌옇게 흐려서 길이 안 보여! 어떤 바람이 창문을 닦아 줄까?",
            hint = "창문을 향해 부는 바람이 있어. 창문 그림 버튼을 찾아봐!",
            pictures = listOf("windshield_foggy"),
            buttons = listOf("앞 유리 성에 제거", "얼굴 쪽", "발 쪽"),
            answer = "앞 유리 성에 제거 켜기",
        )
        is Puzzle.SeatSearch -> Scene(
            bubble = "단서는 어디에?",
            speech = "반짝이는 단서가 우리 차 ${p.seat.label} 자리에 숨었대! 그 자리 불을 켜서 찾아볼까?",
            hint = "${p.seat.label} 자리를 비춰 봐!",
            pictures = listOf("seats_topview"),
            buttons = listOf("좌석 독서등"),
            answer = "${p.seat.label} 독서등 켜기",
        )
        is Puzzle.Together -> together(p)
        Puzzle.BeltMission -> Scene(
            bubble = "탐험대장, 벨트를 딸깍!",
            speech = "탐험대장, 벨트를 딸깍! 이미 매고 있다면 버클을 짚고 딸깍 확인!",
            hint = "벨트를 버클에 끝까지 넣으면 딸깍 소리가 나!",
            pictures = listOf("belt_unbuckled"),
            buttons = emptyList(),
            answer = "아이가 스스로 매면 확인 버튼",
        )
    }

    private fun wind(p: Puzzle.WindPath): Scene {
        val sproutUp = p.direction == FanDir.FACE
        val gentle = p.speed.first <= 2
        val where = if (sproutUp) "위층" else "아래층"
        val other = if (sproutUp) "아래층" else "위층"
        val strength = if (gentle) "새싹이 아직 작아서 살살 불어야 해." else "새싹이 너무 목말라서 세게 불어야 해!"
        return Scene(
            bubble = "새싹에게 바람을!",
            speech = "탐사선 $where 새싹이 시들었어. 그런데 $other 기계는 바람이 싫대! $strength",
            hint = "새싹은 ${where}에 있어. 바람을 그쪽으로!",
            pictures = listOf("sprout_wilted", "machine_grumpy"),
            buttons = listOf("얼굴 쪽", "발 쪽", "풍량"),
            answer = "${if (sproutUp) "얼굴 쪽" else "발 쪽"}, 풍량 ${p.speed.first}~${p.speed.last}단",
        )
    }

    private fun together(p: Puzzle.Together): Scene {
        val speech = when {
            !p.childReachesLight -> "기차 문을 열려면 딱 맞는 순간에 눌러야 해. 탐험대장이 지금! 하고 외치면 안전 지킴이가 눌러 줘. 하나, 둘, 셋!"
            p.rhythm == Rhythm.SAME_TIME -> "기차 문은 앞자리와 뒷자리 스위치를 함께 눌러야 열려. 하나, 둘, 셋 하면 같이 켜자!"
            p.rhythm == Rhythm.CHILD_ONCE_PARENT_TWICE -> "이번 문은 박자 문이야. 탐험대장이 한 번, 안전 지킴이가 두 번! 번갈아 켜 보자."
            else -> "탐험대장 불이 켜져 있는 동안 안전 지킴이가 바람을 세게! 그러면 문이 열린대."
        }
        return Scene(
            bubble = "하나, 둘, 셋! 같이 켜자!",
            speech = speech,
            hint = "서로 눈을 맞추고 다시 해 보자. 하나, 둘, 셋!",
            pictures = listOf("theme_star_train"),
            buttons = listOf("운전석 독서등") + if (p.rhythm == Rhythm.FAN_WHILE_LIT) listOf("풍량") else emptyList(),
            answer = if (p.childReachesLight) "${p.rhythm.label} (아이 자리: ${p.childSeat.label})" else "아이가 \"지금!\" 할 때 운전석 독서등",
        )
    }
}
