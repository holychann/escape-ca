package kr.escape.car.story

import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Rhythm

/** What one puzzle looks and sounds like: Lumi's line, the hint, the stage picture (assets/img) and the parent's buttons. */
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
    const val INTRO = "안녕, 탐험대장! 나는 루미야. 오늘 밤 별 축제에 가야 하는데 별빛 기차 칸 곳곳이 고장 났대. 펭귄 선장과 친구들을 도와 기차를 고쳐 보자! 단서를 듣고 버튼을 찾으면, 손가락으로 가리키며 안전 지킴이에게 알려 줘."
    const val SUCCESS = "고쳤다! 기차 칸 하나가 반짝 살아났어. 우리 탐험대장 최고야!"
    const val TREASURE_GIFT = "기차가 다 고쳐졌어! 친구들이 고맙다며 보물 상자를 열었대. 안전 지킴이가 글로브박스에서 선물을 꺼내 줄 거야."
    const val TREASURE_BADGE = "기차가 다 고쳐졌어! 펭귄 선장이 고맙다며 반짝이는 탐험 배지를 줬어!"
    const val PAUSED = "잠깐 쉬어 갈게. 다시 준비되면 이어서 하자!"

    fun newFriend(f: Friend) = "새 친구 ${f.label}, 친구 도감에 쏙 들어왔어!"

    fun scene(p: Puzzle): Scene = when (p) {
        is Puzzle.WindPath -> wind(p)
        is Puzzle.DustCloud -> if (p.fromOutside) Scene(
            bubble = "먼지 구름이 와!",
            speech = "수달 요리사가 큰일이래! 밖에서 먼지 구름이 몰려와서 수프에 먼지가 들어가겠어. 바깥 공기가 들어오지 않게 막는 버튼을 찾아서 안전 지킴이에게 가리켜 줘.",
            hint = "먼지는 바깥에서 와. 바깥 공기 문을 닫아 볼까?",
            pictures = listOf("scene_dust_out"),
            buttons = listOf("내기 순환", "외기 유입"),
            answer = "내기 순환 켜기",
        ) else Scene(
            bubble = "김이 모락모락!",
            speech = "수달 요리사가 수프를 끓였더니 안에서 김이 모락모락! 바깥의 맑은 공기를 들여보내는 버튼을 찾아서 안전 지킴이에게 알려 줄래?",
            hint = "김은 안에서 생겼어. 바깥 공기를 들어오게 해 볼까?",
            pictures = listOf("scene_dust_in"),
            buttons = listOf("내기 순환", "외기 유입"),
            answer = "외기 유입(내기 순환 끄기)",
        )
        is Puzzle.TwoRooms -> {
            val side = if (p.penguinLeft) "왼쪽" else "오른쪽"
            Scene(
                bubble = "펭귄 선장은 어느 방?",
                speech = "추운 걸 좋아하는 펭귄 선장과 따뜻한 걸 좋아하는 사막여우가 옆방에 살아. 펭귄 선장 발자국이 $side 방으로 갔대! 두 방 온도를 따로 맞추는 버튼을 찾아서 안전 지킴이에게 말해 줘.",
                hint = "펭귄 선장 방은 시원하게, 여우 방은 따뜻하게! 펭귄 선장은 $side 방이야.",
                pictures = listOf(if (p.penguinLeft) "scene_rooms_left" else "scene_rooms_right"),
                buttons = listOf("좌우 온도 따로", "운전석 온도", "조수석 온도"),
                answer = "좌우 따로 켜고, $side 쪽을 반대쪽보다 2도 이상 낮게",
            )
        }
        Puzzle.FoggyWindow -> Scene(
            bubble = "앞이 안 보여!",
            speech = "투덜이 기계가 투덜투덜! 앞 유리가 뿌옇게 흐려서 하나도 안 보인대. 유리를 닦아 주는 바람 버튼을 찾아서 안전 지킴이에게 가리켜 줘.",
            hint = "창문을 향해 부는 바람이 있어. 창문 그림 버튼을 찾아봐!",
            pictures = listOf("scene_fog"),
            buttons = listOf("성에 제거", "얼굴 쪽", "발 쪽"),
            answer = "앞 유리 성에 제거 켜기",
        )
        is Puzzle.SeatSearch -> Scene(
            bubble = "단서는 어디에?",
            speech = "장난꾸러기 사막여우가 반짝이는 단서를 우리 차 ${p.seat.label} 자리에 숨겼대! 그 자리 불을 찾아서 안전 지킴이에게 알려 주자.",
            hint = "${p.seat.label} 자리를 비춰 봐!",
            pictures = listOf("scene_seats"),
            buttons = listOf("좌석 독서등"),
            answer = "${p.seat.label} 독서등 켜기",
        )
        is Puzzle.Together -> together(p)
        Puzzle.BeltMission -> Scene(
            bubble = "탐험대장, 벨트를 딸깍!",
            speech = "다음 칸으로 가기 전에 준비! 탐험대장, 벨트를 딸깍! 이미 매고 있다면 버클을 짚고 딸깍 확인!",
            hint = "벨트를 버클에 끝까지 넣으면 딸깍 소리가 나!",
            pictures = listOf("scene_belt"),
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
            speech = "새싹 로봇이 아끼는 $where 새싹이 시들었어. 그런데 $other 투덜이 기계는 바람이 싫대! $strength 바람 버튼을 찾아서 안전 지킴이에게 가리켜 줘.",
            hint = "새싹은 ${where}에 있어. 바람을 그쪽으로!",
            pictures = listOf(if (sproutUp) "scene_wind_face" else "scene_wind_floor"),
            buttons = listOf("얼굴 쪽", "발 쪽", "풍량"),
            answer = "${if (sproutUp) "얼굴 쪽" else "발 쪽"}, 풍량 ${p.speed.first}~${p.speed.last}단",
        )
    }

    private fun together(p: Puzzle.Together): Scene {
        val speech = when (p.rhythm) {
            Rhythm.SAME_TIME -> "토끼 기관사가 고친 기차 문은 앞자리와 뒷자리 스위치를 함께 눌러야 열려. 하나, 둘, 셋 하면 같이 켜자!"
            Rhythm.CHILD_ONCE_PARENT_TWICE -> "토끼 기관사가 만든 이번 문은 박자 문이야. 탐험대장이 한 번, 안전 지킴이가 두 번! 번갈아 켜 보자."
            Rhythm.FAN_WHILE_LIT -> "토끼 기관사가 그러는데, 탐험대장 불이 켜져 있는 동안 안전 지킴이가 바람을 세게! 그러면 문이 열린대."
        }
        return Scene(
            bubble = "하나, 둘, 셋! 같이 켜자!",
            speech = speech,
            hint = "서로 눈을 맞추고 다시 해 보자. 하나, 둘, 셋!",
            pictures = listOf("scene_together"),
            buttons = listOf("운전석 독서등") + if (p.rhythm == Rhythm.FAN_WHILE_LIT) listOf("풍량") else emptyList(),
            answer = "${p.rhythm.label} (아이 자리: ${p.childSeat.label})",
        )
    }
}
