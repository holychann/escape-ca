# 차근차근 탐험단

> 주차·충전 대기 시간 중 버려지던 아이의 지루한 시간을, 차 안에서만 할 수 있는 색다른 놀이로 채워주는 인카 체험형 퍼즐 콘텐츠 앱

주차 중인 차 안에서 아이와 보호자가 함께 푸는 방탈출입니다. 아이가 고른 친구가 "나"로 이야기를 들려주며 매 판 새로운 문제를 내고, 아이는 화면을 누르지 않고 차의 진짜 버튼으로 정답을 넣습니다. 계산, 영어, 규칙 찾기는 답만큼 장치(풍량, 온도, 독서등, 시트 열선)를 바꾸고, 추론은 "나 추운데 더울 때 켜는 시원한 바람이 켜져 있어" 같은 상황을 듣고 알맞은 버튼(에어컨, 내기 순환, 앞 유리 성에 제거, 온도, 바람 세기, 운전석 열선, 독서등)을 조작합니다. 앱은 차량 값을 읽어 정답을 판정하고, 차가 이미 정답인 상태면 그 문제는 내지 않습니다. 문제는 Claude로 미리 만들고 검산해 둔 문제은행(304개, 숫자 문제 196개와 추론 108개)에서 아이 나이, 장치, 정답에 맞게 무작위로 뽑고, AI 이야기 서버는 그 문제로 이어지는 이야기를 씁니다. 서버가 없으면 앱에 든 은행 사본에서 뽑아 진행합니다. (개발 중 이름: 차량탈출, 앱 목록에도 "차량탈출"로 표시됩니다.)

## 공모전 제출 정보

| 항목 | 내용 |
|---|---|
| 앱 이름 | 차근차근 탐험단 (팀 컴퓨터와경영) |
| APK | [`apk/컴퓨터와경영_차근차근탐험단_release.apk`](apk/컴퓨터와경영_차근차근탐험단_release.apk), release 빌드(누구나 설치할 수 있게 Android debug 키로 서명), 패키지 `kr.escape.car` |
| 개발·테스트 에뮬레이터 | Pleos Connect 에뮬레이터, 시스템 이미지 **Pleos Connect v2.1.0 APIs** (Android 14, API 34, Android Automotive) |
| 화면 규격 | **2560 × 1440 (가로), 160 dpi** |
| 사용 SDK | Pleos Vehicle SDK 2.3.2 (루미 목소리는 이야기 서버의 Gemini TTS, 서버가 없으면 자막) |

### APK로 바로 실행하기

1. Pleos 에뮬레이터를 켭니다. [Pleos 문서의 에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)대로 만들고 화면은 2560 × 1440으로 둡니다.
2. APK를 설치합니다. Pleos 차량 권한은 설치할 때 부여되고, 차량 속도 권한(`android.car.permission.CAR_SPEED`)은 런타임 권한이라 `-g`로 함께 부여합니다.
   ```bash
   adb install -r -g apk/컴퓨터와경영_차근차근탐험단_release.apk
   ```
3. 앱 목록에서 **차량탈출**을 엽니다.
4. 시작 조건 세 가지를 맞춥니다. 시작 버튼은 이 세 가지가 맞아야 눌립니다.
   - 기어 P (에뮬레이터 기본값)
   - 운전석에 보호자: Virtual Console의 Seat Occupancy → Driver → Occupied, 또는 `node tools/vc.mjs seat driver on`
   - 공조 전원 켜기: Virtual Console의 CLIMATE → Power, 또는 `node tools/vc.mjs power on`
5. 퍼즐은 차량 값을 바꿔서 풉니다. 에뮬레이터 화면 아래 공조 바는 눌러도 차량 값이 바뀌지 않으므로 Virtual Console(`adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088`)이나 `tools/vc.mjs`를 씁니다. 부모 화면의 "정답 보기"를 길게 누르면 이번 판의 정답이 나옵니다. 퍼즐별 정답 넣는 법은 [에뮬레이터_테스트_방법.md](에뮬레이터_테스트_방법.md)에 있습니다.

### 테스트 환경과 필요한 설정

- **AI 이야기 서버 없이도 됩니다.** 이 APK는 `http://127.0.0.1:8787`의 이야기 서버에 접속하고, 서버가 없으면 앱 안의 기본 이야기(별빛 기차)로 퍼즐, 친구 도감, 보물 상자, 처음 설정 복원까지 전체 탐험이 그대로 진행됩니다.
- AI 이야기와 그림까지 보려면 아래 [3. 이야기 서버](#3-이야기-서버)대로 서버를 켜고(Gemini API 키 필요) `adb reverse tcp:8787 tcp:8787`로 에뮬레이터를 PC의 서버에 연결합니다.
- 차량 상태는 Pleos 공식 도구인 Virtual Console(웹 페이지)로 바꿉니다. 한 줄 명령 `tools/vc.mjs`를 쓸 때만 Node.js 18 이상이 필요합니다.
- 직접 빌드할 때는 JDK 21을 씁니다(아래 [2. 차 앱](#2-차-앱)).

## 폴더

| 폴더 | 내용 |
|---|---|
| `apk/` | 제출용 release APK |
| `car/` | 차 앱. Kotlin, Jetpack Compose, Pleos Vehicle SDK 2.3.2 |
| `server/` | AI 이야기 서버. Python, FastAPI, Gemini. 자세한 설명은 [server/README.md](server/README.md) |
| `tools/` | Virtual Console 명령(`tools/vc.mjs`) |
| `에뮬레이터_테스트_방법.md` | 에뮬레이터에서 퍼즐을 직접 풀어 보는 방법 |
| `TTS_대본.md` | 루미 목소리 대본 |

## 준비물

- Windows PC
- Git, Node.js 18 이상, Python 3.11 이상
- Android Studio (Android SDK, 에뮬레이터, JDK 21 포함)
- Pleos 에뮬레이터: [Pleos 문서의 에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)대로 만들고, **AVD 이름을 `pleos`로** 정합니다. [에뮬레이터_테스트_방법.md](에뮬레이터_테스트_방법.md)의 명령이 이 이름으로 에뮬레이터를 켭니다.

## 1. 받기

경로에 한글이 있으면 Android 빌드 도구(aapt2)가 실패하므로 **`C:\dev\escape`에 받습니다.** 빌드 결과는 `C:\dev\build\escape-car`에 생깁니다.

```powershell
git clone https://github.com/leekwangeun/escape-car.git C:\dev\escape
```

서버가 쓰는 검사 라이브러리 [`textgate`](https://github.com/leekwangeun/textgate)는 공개 저장소라서, 서버의 `pip install -r requirements.txt`가 GitHub에서 같이 받아 설치합니다(git이 깔려 있어야 합니다).

## 2. 차 앱

빌드 없이 바로 보려면 [`apk/`](apk/) 폴더의 제출용 release APK를 설치하고 위의 [APK로 바로 실행하기](#apk로-바로-실행하기)를 따르면 됩니다.

```powershell
cd C:\dev\escape\car
.\gradlew.bat assembleDebug          # APK: C:\dev\build\escape-car\app\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest      # 규칙 엔진, 안전 감시, 이야기 단위 테스트
```

Android Studio에서는 `C:\dev\escape\car` 폴더를 열면 됩니다.

## 3. 이야기 서버

```powershell
cd C:\dev\escape\server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env
```

`.env`의 `GEMINI_API_KEY=` 뒤에 따로 받은 키를 넣습니다. `.env`는 `.gitignore`에 들어 있어 저장소에 올라가지 않으니, 키를 다른 파일이나 커밋 메시지, 단체방에 붙여 넣지 않도록 주의해 주세요.

- 이야기 한 편에 약 7원이 키 주인 계정으로 나갑니다. 여러 편을 한꺼번에 만드는 `textgate sample`은 미리 말하고 돌려 주세요.
- 키 없이 앱 연결만 확인할 때는 `.\.venv\Scripts\python -m tools.dev_server`를 씁니다(문장에 [개발용] 표시).
- 시험: `.\.venv\Scripts\python -m pytest -q` (실제 모델을 부르지 않음)

## 4. 실행

에뮬레이터를 켜고 앱을 설치한 뒤, 차량 값은 Pleos 공식 도구인 **Pleos Connect Virtual Console**로 바꿉니다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088
```

그다음 브라우저에서 `http://127.0.0.1:8088`을 엽니다. 에뮬레이터 켜는 명령, 시작 조건, 퍼즐별 정답 넣는 법은 [에뮬레이터_테스트_방법.md](에뮬레이터_테스트_방법.md)에 있습니다.

## macOS에서

- 아무 폴더에나 받아도 됩니다(경로에 한글만 없으면 됨). 빌드 결과는 `car/app/build.nosync`에 생깁니다. iCloud의 '데스크탑 및 문서' 동기화가 빌드 파일을 `이름 2.dex`처럼 복제해 빌드를 깨뜨리는데, `.nosync`로 끝나는 폴더는 동기화하지 않기 때문입니다.
- JDK는 21을 씁니다. Android Studio에 들어 있는 Java 25로는 이 프로젝트의 Gradle 8.10이 돌지 않으니, Android Studio의 Gradle JDK도 21로 맞춰 주세요.
- 앱: `cd car && ./gradlew assembleDebug`
- 서버: `python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`, 실행은 `.venv/bin/python -m uvicorn app.main:app --env-file .env --host 127.0.0.1 --port 8787`
- Apple Silicon(arm64) 맥에서는 Pleos 에뮬레이터의 arm64-v8a 이미지(Pleos Connect v2.1.0 APIs)로 실행해 시험했습니다.
