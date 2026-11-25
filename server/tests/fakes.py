"""Hand-written drafts that pass every rule, and a writer that hands them out."""
from app.models import Draft, DraftScene, NewFriend

GOOD = {
    "WIND:FACE:GENTLE": ("새싹에게 바람을!", "위쪽 새싹이 목말라. 바람을 살살 보내는 버튼을 찾아서 안전 지킴이에게 가리켜 줄까?", "새싹은 위쪽에 있어!"),
    "WIND:FLOOR:STRONG": ("새싹에게 바람을!", "아래쪽 새싹이 목말라. 바람을 세게 보내는 버튼을 찾아서 안전 지킴이에게 가리켜 줄까?", "새싹은 아래쪽에 있어!"),
    "DUST:OUTSIDE": ("먼지 구름이 와!", "먼지 구름이 밖에서 몰려와! 바깥 공기를 막는 버튼을 찾아 안전 지킴이에게 가리켜 알려 주자.", "먼지가 어디서 오는지 들어 봐."),
    "DUST:INSIDE": ("김이 모락모락!", "기차 안에서 김이 모락모락! 맑은 공기를 들여보내는 버튼을 찾아 안전 지킴이에게 가리켜 알려 주자.", "김이 어디서 생겼을까?"),
    "ROOMS:LEFT": ("펭귄은 어느 방?", "펭귄 발자국이 왼쪽 방으로 갔대. 그 방을 시원하게 할 버튼을 찾아 안전 지킴이에게 알려 주자.", "펭귄은 왼쪽 방이야."),
    "ROOMS:RIGHT": ("펭귄은 어느 방?", "펭귄 발자국이 오른쪽 방으로 갔대. 그 방을 시원하게 할 버튼을 찾아 안전 지킴이에게 알려 주자.", "펭귄은 오른쪽 방이야."),
    "FOG": ("앞이 안 보여!", "앞 유리가 뿌예! 유리를 닦는 바람 버튼을 찾아 안전 지킴이에게 가리켜 줄까?", "유리 쪽으로 부는 바람을 찾아봐."),
    "SEAT:REAR_LEFT": ("단서는 어디에?", "단서가 뒷좌석 왼쪽에 숨었대! 그 자리 불을 찾아 안전 지킴이에게 가리켜 알려 주자.", "뒷좌석 왼쪽을 비춰 봐!"),
    "SEAT:DRIVER": ("단서는 어디에?", "단서가 운전석에 숨었대! 그 자리 불을 찾아 안전 지킴이에게 가리켜 알려 주자.", "운전석을 비춰 봐!"),
    "TOGETHER:SAME_TIME": ("하나, 둘, 셋!", "안전 지킴이와 함께 하나, 둘, 셋 하면 같이 불을 켜자!", "눈을 맞추고 다시 해 보자."),
    "BELT": ("벨트를 딸깍!", "탐험대장, 벨트를 딸깍! 이미 맸다면 버클을 짚고 딸깍 확인!", "끝까지 넣으면 딸깍 소리가 나."),
}


FRIEND = NewFriend(name="별똥 차장", look="노란 별 모양 몸에 작은 차장 모자를 쓴 꼬마", trait="기차 노래를 좋아하는 수줍은 친구야.", hello="반가워! 나도 이제 도감 친구야!")


def draft(keys, **override) -> Draft:
    scenes = [DraftScene(key=k, bubble=GOOD[k][0], speech=GOOD[k][1], hint=GOOD[k][2]) for k in keys]
    fields = dict(
        theme="STAR_TRAIN",
        title="별빛 기차를 구해라",
        intro="안녕, 탐험대장! 나는 루미야. 별빛 기차가 멈췄대. 같이 도와주자!",
        success=["정답! 별 하나!", "와, 해냈어!", "역시 탐험대장!"],
        treasureBadge="모두 도왔어! 탐험 배지를 받았어!",
        scenes=scenes,
        friend=FRIEND,
    )
    fields.update(override)
    return Draft(**fields)


class FakeWriter:
    def __init__(self, *drafts):
        self.drafts = list(drafts)
        self.calls = 0

    def write(self, system, user):
        self.calls += 1
        d = self.drafts.pop(0)
        if isinstance(d, Exception):
            raise d
        return d, "fake-model"
