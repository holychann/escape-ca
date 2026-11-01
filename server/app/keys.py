"""Answer keys the car app sends, and the words each answer's scene must and must not contain.

A key names one puzzle with its answer already chosen by the app's rule engine, e.g. ``ROOMS:LEFT`` is the
two-rooms puzzle with the penguin on the left. The story writer only dresses the answer up; these rules are how
the checker makes sure the words still point at the same answer.
"""
from dataclasses import dataclass

SEATS = {"DRIVER": "운전석", "PASSENGER": "조수석", "REAR_LEFT": "뒷좌석 왼쪽", "REAR_RIGHT": "뒷좌석 오른쪽"}
RHYTHMS = ("SAME_TIME", "CHILD_ONCE_PARENT_TWICE", "FAN_WHILE_LIT")


@dataclass(frozen=True)
class Rule:
    key: str
    situation: str
    # each group is "one of these words", and every group must appear in the speech
    must: tuple[tuple[str, ...], ...]
    # none of these may appear in the bubble, speech or hint
    never: tuple[str, ...] = ()
    # the hint must point the same way as the speech
    hint_must: tuple[tuple[str, ...], ...] = ()
    # Lumi asks the 안전 지킴이 to do it (the belt mission is the child's own job)
    needs_guardian: bool = True


def rule_for(key: str) -> Rule:
    """Raises ValueError for a key the app could not have sent."""
    parts = key.split(":")
    kind = parts[0]
    if kind == "WIND" and len(parts) == 3 and parts[1] in ("FACE", "FLOOR") and parts[2] in ("GENTLE", "STRONG"):
        up = parts[1] == "FACE"
        gentle = parts[2] == "GENTLE"
        where, other = ("위쪽", "아래쪽") if up else ("아래쪽", "위쪽")
        how, not_how = ("살살", "세게") if gentle else ("세게", "살살")
        return Rule(
            key,
            f"바람길 열기: 바람이 필요한 친구(새싹 등)가 {where}에 있고, 반대쪽에는 바람을 싫어하는 친구가 있다. 그 친구가 어느 쪽인지는 말하지 않는다. "
            f"바람을 {where}으로 {how} 보내야 한다. 정답 조작은 풍향 {'얼굴 쪽' if up else '발 쪽'}, 풍량 {'약하게' if gentle else '세게'}.",
            must=((where,), (how,)),
            never=(other, not_how),
            hint_must=((where,),),
        )
    if kind == "DUST" and len(parts) == 2 and parts[1] in ("OUTSIDE", "INSIDE"):
        if parts[1] == "OUTSIDE":
            return Rule(
                key,
                "먼지 구름 피하기: 먼지 구름이 밖에서 몰려온다. 바깥 공기가 들어오지 않게 막아야 한다. 정답 조작은 내기 순환 켜기.",
                must=(("밖에서",),),
                never=("안에서",),
            )
        return Rule(
            key,
            "먼지 구름 피하기: 차 안에서 김이 모락모락 피어오른다. 바깥의 맑은 공기를 들여보내야 한다. 정답 조작은 내기 순환 끄기(외기 유입).",
            must=(("안에서",),),
            never=("밖에서",),
        )
    if kind == "ROOMS" and len(parts) == 2 and parts[1] in ("LEFT", "RIGHT"):
        side, other = ("왼쪽", "오른쪽") if parts[1] == "LEFT" else ("오른쪽", "왼쪽")
        return Rule(
            key,
            f"두 방 온도 맞추기: 추위를 좋아하는 펭귄과 따뜻한 걸 좋아하는 사막여우가 두 방에 산다. 펭귄 방은 {side}이다. "
            f"펭귄 방은 시원하게, 여우 방은 따뜻하게 해야 한다. 여우 방이 어느 쪽인지는 말하지 않는다. "
            f"정답 조작은 좌우 온도 따로, {side}을 반대쪽보다 2도 이상 낮게.",
            must=(("펭귄",), (side,)),
            never=(other,),
            hint_must=((side,),),
        )
    if kind == "FOG" and len(parts) == 1:
        return Rule(
            key,
            "김 서린 창 닦기: 앞 유리가 뿌옇게 흐려 단서가 안 보인다. 유리를 향해 부는 바람이 닦아 준다. 정답 조작은 앞 유리 성에 제거 켜기.",
            must=(("유리", "창"),),
        )
    if kind == "SEAT" and len(parts) == 2 and parts[1] in SEATS:
        label = SEATS[parts[1]]
        return Rule(
            key,
            f"우리 자리 조사: 반짝이는 단서가 우리 차 {label} 자리에 숨었다. 그 자리 독서등을 켜면 찾을 수 있다. "
            f"다른 자리 이름은 말하지 않는다. 정답 조작은 {label} 독서등 켜기.",
            must=((label,),),
            never=tuple(v for k, v in SEATS.items() if k != parts[1]),
            hint_must=((label,),),
        )
    if kind == "TOGETHER" and len(parts) == 3 and parts[1] in RHYTHMS and parts[2] in ("REACH", "TIMING"):
        if parts[2] == "TIMING":
            return Rule(
                key,
                "하나 둘 셋, 동시에!: 문을 열려면 딱 맞는 순간에 눌러야 한다. 탐험대장이 \"지금!\" 하고 외치면 안전 지킴이가 운전석 독서등을 켠다.",
                must=(("지금",),),
            )
        how = {
            "SAME_TIME": ("탐험대장은 뒷자리 독서등, 안전 지킴이는 운전석 독서등을 하나, 둘, 셋에 맞춰 동시에 켠다.", (("하나둘셋",),)),
            "CHILD_ONCE_PARENT_TWICE": ("박자 문: 탐험대장이 뒷자리 독서등을 한 번 켜고, 이어서 안전 지킴이가 운전석 독서등을 두 번 켠다.",
                                        (("한번",), ("두번",))),
            "FAN_WHILE_LIT": ("탐험대장이 뒷자리 독서등을 켜 둔 동안 안전 지킴이가 바람을 세게 올린다.", (("바람",),)),
        }[parts[1]]
        return Rule(key, f"하나 둘 셋, 동시에!: 탐험대장과 안전 지킴이가 함께해야 문이 열린다. {how[0]}", must=how[1])
    if kind == "BELT" and len(parts) == 1:
        return Rule(
            key,
            "벨트 딸깍 미션: 탐험대장이 스스로 안전벨트를 딸깍 맨다. 이미 매고 있다면 버클을 손으로 짚고 \"딸깍 확인!\"이라고 말한다. "
            "안전 지킴이는 보고 확인 버튼만 누른다. 차는 주차 중이므로 \"출발\" 대신 \"탐험 준비\"처럼 말한다.",
            must=(("딸깍",),),
            never=("풀어", "풀고", "풀면", "풀러"),
            needs_guardian=False,
        )
    raise ValueError(f"unknown puzzle key: {key}")
