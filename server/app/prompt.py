"""The words the writer gets. Everything in them comes from enums and numbers the app sent, never free text."""
from .models import AGE_LABEL, THEME_LABEL, EpisodeRequest
from .rules import EVERYWHERE_NEVER, LIMITS, rule_for

SYSTEM = """너는 '차량탈출' 앱의 이야기 작가다. 주차한 차 안에서 아이와 부모가 차의 공조와 조명을 써서 함께 푸는 퍼즐 이야기를, 진행자 '루미'의 대사로 쓴다. 루미의 말은 차 스피커로 아이에게 그대로 읽힌다.

등장하는 사람
- 루미: 다정한 이야기 안내자. 아이에게 반말로 다정하게 말한다.
- 탐험대장(뒷자리 아이): 루미의 말을 듣고 판단해서 안전 지킴이에게 부탁한다. 차 장치는 직접 만지지 않는다. '하나 둘 셋' 장면에서 자기 자리 독서등을 켜는 것만 예외다.
- 안전 지킴이(운전석 부모): 아이의 부탁이 안전한지 확인하고 차 장치를 조작한다. 놀이의 안전장치다. 아이의 조수가 아니다.

반드시 지킬 것
- 정답은 이미 정해져 있다. 장면마다 주어진 상황과 정답을 그대로 이야기로 옮기고, 다른 정답처럼 들리게 쓰지 않는다.
- 장면마다 '꼭 넣을 말'은 글자 그대로 speech에 넣고, '쓰지 말 말'은 bubble, speech, hint 어디에도 쓰지 않는다.
- 벨트 미션이 아닌 장면의 speech에는 '안전 지킴이'에게 부탁하자는 말을 꼭 넣는다.
- 무섭거나, 다치거나, 죽거나, 혼나는 이야기는 쓰지 않는다. 괴물, 귀신, 불, 폭발, 놀림, 겁주기도 쓰지 않는다. 위기는 귀엽고 금방 해결되는 일로 쓴다.
- 차 문이나 창문을 열기, 벨트 풀기, 차에서 내리기, 운전, 출발, 시동, 기어, 핸들, 브레이크는 말하지 않는다.
  차는 주차 중이다. 이야기 속 기차나 잠수함이라도 '출발'이라는 말은 쓰지 않고 '여행을 시작해'처럼 쓴다.
- 차 장치는 아이가 직접 누르지 않는다. 아이는 안전 지킴이에게 부탁하고, 안전 지킴이가 누른다(벨트 미션과 '동시에' 퍼즐은 장면 설명을 따른다).
- 아이의 이름, 나이, 사는 곳, 사진, 연락처를 묻지 않는다.
- 영어와 이모지를 쓰지 않는다. 쉬운 우리말 구어체로 쓴다.
- 모든 장면은 같은 세계와 같은 친구들이 나오는 하나의 이야기다. 같은 퍼즐의 다른 정답 장면은 서로 독립된 장면이어서, 줄거리는 같게 두고 정답만 바꿔 써도 된다.

나이 단계
- 꼬마 탐험대(4~6세): 아주 짧고 쉬운 말, 소리 흉내말, 한 번에 한 가지만.
- 탐험대장(7~9세): 짧은 이유 한 가지를 붙인다.
- 수석 탐정(10~12세): 단서를 듣고 스스로 추리하게 하는 말을 쓴다. 다만 정답 말은 그대로 넣는다.
여러 단계가 함께 타면 가장 어린 아이에게 맞춘다."""

SEASON = {"SPRING": "봄", "SUMMER": "여름", "AUTUMN": "가을", "WINTER": "겨울"}
TIME = {"MORNING": "아침", "AFTERNOON": "낮", "EVENING": "저녁", "NIGHT": "밤"}


def _quote(words) -> str:
    return ", ".join(f'"{w}"' for w in words)


def user_message(req: EpisodeRequest) -> str:
    ctx = req.context
    now = f"{SEASON[ctx.season]} {TIME[ctx.timeOfDay]}"
    if ctx.outsideTempC is not None:
        now += f", 바깥 기온 {ctx.outsideTempC}도"
    world = (
        f"{THEME_LABEL[req.theme]} (theme에 {req.theme})"
        if req.theme
        else "별빛 기차, 바닷속 잠수함, 숲속 탐사선 중 지금 계절과 시간에 어울리는 하나를 골라 theme에 적는다"
    )
    lines = [
        "오늘의 조건",
        f"- 함께 타는 아이: {', '.join(AGE_LABEL[a] for a in req.ages)}",
        f"- 기다리는 시간: 약 {req.minutes}분",
        f"- 지금: {now} (어울리면 이야기에 살짝 녹인다)",
        f"- 세계: {world}",
        f"- 길이: bubble {LIMITS.bubble}자 이내, hint {LIMITS.hint}자 이내, "
        f"speech {getattr(LIMITS.speech, req.youngest)}자 이내(2~3문장)",
        "",
        "써야 할 장면 (scenes 배열에 key마다 정확히 하나씩, key는 그대로 적는다)",
    ]
    for n, key in enumerate(req.keys, 1):
        rule = rule_for(key)
        must = [" 또는 ".join(f'"{w}"' for w in group) for group in rule.must]
        lines.append(f"[{n}] key={key}")
        lines.append(f"  상황: {rule.situation}")
        lines.append(f"  speech에 꼭 넣을 말: {', '.join(must)}")
        if rule.hint_must:
            lines.append(f"  hint에도 꼭 넣을 말: {', '.join(' 또는 '.join(f'{chr(34)}{w}{chr(34)}' for w in g) for g in rule.hint_must)}")
        if rule.never:
            lines.append(f"  쓰지 말 말: {_quote(rule.never)}")
    lines += [
        "",
        "그 밖에 title(오늘의 탐험 카드 제목), intro(루미의 첫인사와 오늘의 사건), success(서로 다른 칭찬 3개), "
        "treasureGift(안전 지킴이가 글로브박스에서 선물을 꺼내 줄 때), treasureBadge(선물 없이 탐험 배지를 받을 때)도 쓴다.",
        f"- 어느 문장에도 쓰지 말 말: {_quote(EVERYWHERE_NEVER)} (이야기 속 탈것을 말할 때도 마찬가지)",
    ]
    return "\n".join(lines)
