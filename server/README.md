# 차량탈출 AI 이야기 서버

차 앱이 정한 퍼즐 정답(규칙 엔진)을 받아, 그 정답에 맞는 루미의 대사를 Claude 또는 Gemini로 쓰고, 검사를 통과한 문장만 차로 돌려보냅니다.
AI는 정답을 정하지 않고 문장만 씁니다. 서버나 AI가 실패하면 차 앱은 앱 안의 기본 이야기(`Lines.kt`)로 그대로 진행합니다.

## 흐름

```
차 앱 (부모 준비 화면, 판이 시작될 때)
  └ POST /v1/episode  { 나이 단계, 시간, 계절, 정답 키 목록 }   ← 이름, 목소리, 위치, 자유 문장은 보내지 않음
       └ Claude 또는 Gemini (구조화 출력, 정해진 JSON 틀) → 검사기 → 통과한 장면만 응답
  ← 다음 판 이야기를 기기에 저장해 두었다가, 같은 설정으로 시작하면 바로 사용 (차 안에서 기다리지 않음)
```

- **정답 키**: `ROOMS:LEFT`(펭귄 방 왼쪽), `DUST:OUTSIDE`, `WIND:FACE:GENTLE`, `SEAT:REAR_LEFT`, `TOGETHER:SAME_TIME:REACH`, `FOG`, `BELT`.
  퍼즐을 시작할 때 차 상태 때문에 정답이 바뀔 수 있어서(`Planner.refresh`), 앱은 바뀔 수 있는 정답까지 함께 보내고 서버는 정답마다 장면을 씁니다.
- **검사기** (`app/checker.py`)
  1. 틀: 길이(가장 어린 나이 단계 기준), 빠진 장면
  2. 말: 무서운 말, 차에서 위험한 말(문, 창문 열기, 벨트 풀기, 출발, 기어 등), 개인정보 질문, 영어와 주소
  3. 정답 일치: 장면마다 정답 말(예: "왼쪽")이 있고 다른 정답 말(예: "오른쪽")이 없어야 함. 벨트 미션이 아닌 장면은 "안전 지킴이"에게 부탁해야 함
- **규칙 파일** (`app/story_rules.toml`): 길이 제한, 금지 패턴, 정답 키 20개마다 모델에게 알려 줄 장면 설명과 꼭 넣을 말, 쓰지 말 말이 모두 여기 있습니다. 작성기에게 알려 주는 말과 검사기가 확인하는 말이 같은 곳에서 나옵니다.
  서버가 시작할 때 파일을 검사해서, 모르는 항목 이름, 깨진 정규식, 빈 단어 묶음이 있으면 서버가 뜨지 않습니다(오타가 검사를 몰래 약하게 만들지 않도록). 바꾼 뒤에는 `pytest`로 서로 바뀔 수 있는 정답끼리 상대의 정답 말을 막는지도 확인합니다.
- 판 전체 문장(제목, 첫인사, 칭찬)에 문제가 있으면 그 초안은 버립니다. 장면 하나만 문제면 그 장면만 빼고, 차는 그 장면을 기본 문장으로 진행합니다. 한 번 더 써 보고 더 깨끗한 초안을 고릅니다.
- **기록**: `data/episodes.jsonl`(보낸 이야기), `data/reports.jsonl`(부모의 이야기 신고). 설치 ID는 기록하지 않습니다.
- **비용 제한**: 설치 하나당 하루 20편, 주소 하나당 하루 200편 (환경 변수로 조정)

## 실행

```powershell
cd escape\server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env      # ANTHROPIC_API_KEY 또는 GEMINI_API_KEY 입력 (저장소에 올리지 않음)
.\.venv\Scripts\python -m uvicorn app.main:app --env-file .env --host 127.0.0.1 --port 8787
```

에뮬레이터의 차 앱은 `http://127.0.0.1:8787`로 접속하므로 PC로 포트를 넘겨 줍니다.

```powershell
adb reverse tcp:8787 tcp:8787
```

배포한 서버를 쓰는 APK는 `-PstoryUrl=https://주소`로 빌드합니다.

## 시험

```powershell
.\.venv\Scripts\python -m pytest -q          # 검사기, API, 작성기 (실제 모델을 부르지 않음)
.\.venv\Scripts\python -m tools.sample 30    # 실제 모델로 30편을 써서 검사 통과율, 토큰 수, 비용 측정 (유료 호출)
.\.venv\Scripts\python -m tools.dev_server   # 키 없이 앱 연결만 확인하는 개발용 서버 (문장에 [개발용] 표시)
```

## 모델과 정책

- 시제품 모델: Gemini `gemini-3.6-flash`. 정해진 JSON 틀(`response_json_schema`)로 받고 `thinking_level: LOW`로 기다림을 줄입니다(한 편에 대개 5~10초). Google 쪽이 붐비거나 분당 횟수 제한에 걸리면 몇 초 뒤 최대 두 번 다시 요청합니다. `.env`에 `GEMINI_API_KEY`가 있으면 Gemini를 씁니다.
- 작성기(`app/writer.py`)는 바꿔 끼울 수 있게 나뉘어 있습니다. Claude(`claude-opus-5-5`)도 들어 있어 `STORY_PROVIDER=claude`와 `ANTHROPIC_API_KEY`만 넣으면 바뀌고, 모델 이름은 `STORY_MODEL`로 바꿉니다. 어떤 모델이 쓰든 같은 검사기를 거칩니다.
- 출시 전 교체: Gemini API 약관은 18세 미만을 대상으로 하거나 18세 미만이 이용할 가능성이 높은 서비스에 API를 쓰는 것을 허용하지 않습니다. 그래서 실제 출시 때는 Pleos 차량 인앱 LLM(앱용 SDK가 공개되면) 또는 미성년자 서비스를 허용하는 제공사(예: Anthropic, 안전장치를 갖추는 조건)로 작성기를 바꿉니다.
- 결제를 켠 유료 등급으로 호출하므로 보낸 입력과 출력은 Google의 제품 개선에 쓰이지 않습니다. 서버는 정답 키, 나이 단계, 시간대, 계절만 보내고 아이에 대한 정보는 보내지 않습니다.
- 아이는 AI와 직접 대화하지 않고 검사를 통과한 문장만 듣습니다. 앱은 시작 화면에 AI가 만든 이야기임을 알리고, 부모 화면에 이야기 신고 버튼을 둡니다.
