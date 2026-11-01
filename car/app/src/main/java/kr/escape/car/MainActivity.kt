package kr.escape.car

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kr.escape.car.car.VehicleSource
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Safety
import kr.escape.car.engine.StopReason
import kr.escape.car.story.StoryClient
import kr.escape.car.story.StoryRepo
import kr.escape.car.voice.Lumi
import java.io.File
import java.util.UUID

// colors from the Figma "차량탈출" variables
private val Ink = Color(0xFF1B2240)
private val Secondary = Color(0xFF5A6280)
private val Soft = Color(0xFFF2F4F7)
private val Line = Color(0xFFDDE1E7)
private val Card = Color(0xFFFFFFFF)
private val Stage = Color(0xFFEAF1FC)
private val Sun = Color(0xFFFFC531)
private val Coral = Color(0xFFFF7A59)
private val Mint = Color(0xFF23C2A0)
private val Space = Color(0xFF16224D)

class MainActivity : ComponentActivity() {
    private lateinit var source: VehicleSource
    private lateinit var lumi: Lumi
    private lateinit var game: Game
    private lateinit var stories: StoryRepo
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = VehicleSource(this).also { it.start() }
        lumi = Lumi(this)
        stories = StoryRepo(File(filesDir, "next_episode.json"), installId(), StoryClient(BuildConfig.STORY_URL), scope)
        game = Game(source.state, lumi, stories)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Soft, surface = Card)) {
                LaunchedEffect(Unit) {
                    while (true) {
                        game.tick(SystemClock.elapsedRealtime())
                        delay(200)
                    }
                }
                App(game, source, lumi, stories)
            }
        }
    }

    /** A random id made once per install, only for the story server's daily limit. */
    private fun installId(): String {
        val prefs = getSharedPreferences("story", MODE_PRIVATE)
        return prefs.getString("installId", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("installId", it).apply() }
    }

    override fun onDestroy() {
        scope.cancel()
        lumi.stop()
        source.stop()
        super.onDestroy()
    }
}

@Composable
private fun App(game: Game, source: VehicleSource, lumi: Lumi, stories: StoryRepo) {
    val ui by game.ui.collectAsState()
    val car by source.state.collectAsState()
    val speaking by lumi.speaking.collectAsState()
    val storyStatus by stories.status.collectAsState()
    // while the parent is choosing, the next episode is written in the background
    LaunchedEffect(ui.phase, ui.setup, car.devices, car.fanSpeedRange) {
        if (ui.phase != Phase.SETUP) return@LaunchedEffect
        delay(1500)
        stories.prepare(ui.setup, source.state.value)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Soft)) {
        val portrait = maxWidth < maxHeight
        Column(Modifier.fillMaxSize().padding(if (portrait) 24.dp else 36.dp)) {
            Header(ui, car)
            Spacer(Modifier.height(24.dp))
            if (portrait) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    KidStage(ui, speaking, Modifier.weight(1f).fillMaxWidth())
                    ParentPanel(game, ui, car, speaking, lumi, storyStatus, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    ParentPanel(game, ui, car, speaking, lumi, storyStatus, Modifier.width(440.dp).fillMaxHeight())
                    KidStage(ui, speaking, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        if (ui.paused && ui.phase in listOf(Phase.INTRO, Phase.PLAYING, Phase.SOLVED)) PauseOverlay(game, ui)
    }
}

@Composable
private fun Header(ui: GameUi, car: CarState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("차량탈출", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.width(16.dp))
        StatusChip("주차 중", car.gear == Gear.PARK && !car.uxRestricted)
        Spacer(Modifier.width(10.dp))
        StatusChip("운전석 보호자", car.driverSeated == true)
        Spacer(Modifier.weight(1f))
        if (ui.puzzles.isNotEmpty()) Progress(ui)
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Card).border(2.dp, Line, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (ok) Mint else Coral), contentAlignment = Alignment.Center) {
            Text(if (ok) "✓" else "!", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 22.sp, color = Ink)
    }
}

@Composable
private fun Progress(ui: GameUi) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Space).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val done = ui.stars
        for (i in 0..ui.puzzles.size) {
            val (bg, mark) = when {
                i < done -> Mint to "★"
                i == done && i < ui.puzzles.size -> Sun to "▲"
                i == ui.puzzles.size -> Color.Transparent to "◎"
                else -> Color.Transparent to ""
            }
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(bg).border(2.dp, Color(0x66FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(mark, color = if (bg == Sun) Ink else Color.White, fontSize = 22.sp) }
        }
    }
}

@Composable
private fun asset(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) {
        runCatching { context.assets.open("img/$name.webp").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }
}

@Composable
private fun Picture(name: String, modifier: Modifier = Modifier) {
    val bmp = asset(name) ?: return
    Image(bmp, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Fit)
}

@Composable
private fun PanelCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(Card).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun Badge(text: String) {
    Text(
        text, color = Color.White, fontSize = 20.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ink).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun BigButton(
    label: String,
    color: Color,
    textColor: Color = Color.White,
    height: Dp = 88.dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(50)).background(if (enabled) color else Line)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) textColor else Secondary, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun OutlineButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(80.dp).clip(RoundedCornerShape(50)).background(Card).border(2.dp, Line, RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Choice(label: String, sub: String?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(if (selected) Sun else Soft)
            .border(2.dp, if (selected) Sun else Line, RoundedCornerShape(22.dp)).clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (sub != null) Text(sub, fontSize = 18.sp, color = Ink)
    }
}

@Composable
private fun SafetyLine(ui: GameUi, car: CarState) {
    val sec = ui.playedMs / 1000
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(2.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Check("주차", car.gear == Gear.PARK)
            Check("문 닫힘", !car.doorOpen)
            Check("운전석", car.driverSeated == true)
        }
        Text("%02d:%02d / %02d:00".format(sec / 60, sec % 60, Safety.MAX_PLAY_MS / 60000), fontSize = 20.sp, color = Ink)
        if (ui.recirculationWarning) Text("내기 순환 3분이 지났어요. 외기로 돌려 주세요", fontSize = 18.sp, color = Coral)
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text((if (ok) "✓ " else "✕ ") + label, fontSize = 20.sp, color = if (ok) Mint else Coral, fontWeight = FontWeight.Bold)
}

@Composable
private fun ParentPanel(
    game: Game,
    ui: GameUi,
    car: CarState,
    speaking: Boolean,
    lumi: Lumi,
    storyStatus: StoryRepo.Status,
    modifier: Modifier,
) {
    when (ui.phase) {
        Phase.SETUP -> SetupPanel(game, ui, car, storyStatus, modifier)
        Phase.INTRO -> PanelCard(modifier) {
            Badge("안전 지킴이 (부모님)")
            Text(ui.story?.title ?: "오늘의 이야기", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                if (ui.story != null) "루미의 이야기는 AI가 만들었어요. 정답은 규칙 엔진이 정해요" else "정답은 규칙 엔진이 정해요",
                fontSize = 20.sp, color = Secondary,
            )
            ui.puzzles.forEachIndexed { i, p -> Text("${i + 1}. ${p.title}", fontSize = 22.sp, color = Ink) }
            Spacer(Modifier.weight(1f))
            ReportRow(game, ui)
            SafetyLine(ui, car)
            BigButton("탐험 시작", Ink) { game.startPuzzle(0) }
        }
        Phase.PLAYING -> PlayingPanel(game, ui, car, speaking, lumi, modifier)
        Phase.SOLVED -> PanelCard(modifier) {
            Badge("안전 지킴이 (부모님)")
            Text("정답이에요!", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("아이를 칭찬해 주세요", fontSize = 20.sp, color = Secondary)
            Spacer(Modifier.weight(1f))
            SafetyLine(ui, car)
            BigButton(if (ui.index + 1 < ui.puzzles.size) "다음 퍼즐" else "보물 열기", Ink) { game.next() }
            BigButton("멈춤", Coral, height = 104.dp) { game.hold() }
        }
        Phase.TREASURE -> PanelCard(modifier) {
            Badge("안전 지킴이 (부모님)")
            Text(
                if (ui.setup.giftReady) "글로브박스를 열어 선물을 건네 주세요" else "탐험 배지를 받았어요",
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink,
            )
            if (ui.setup.giftReady) Text("아이가 앞으로 넘어오지 않게, 직접 건네 주세요", fontSize = 20.sp, color = Secondary)
            Spacer(Modifier.weight(1f))
            BigButton("처음 설정으로", Ink) { game.finish() }
        }
        Phase.RESTORE -> PanelCard(modifier) {
            Badge("안전 지킴이 (부모님)")
            Text("처음 설정으로", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
            val changes = ui.snapshot?.changes(car).orEmpty()
            if (changes.isEmpty()) {
                Text("모두 처음 설정과 같아요", fontSize = 22.sp, color = Mint)
            } else {
                Text("시작할 때 설정으로 돌려 주세요", fontSize = 20.sp, color = Secondary)
                changes.forEach { Text("• $it", fontSize = 22.sp, color = Ink) }
            }
            Spacer(Modifier.weight(1f))
            ReportRow(game, ui)
            BigButton("다 돌렸어요", Ink) { game.reset() }
        }
    }
}

@Composable
private fun SetupPanel(game: Game, ui: GameUi, car: CarState, storyStatus: StoryRepo.Status, modifier: Modifier) {
    val blockers = Safety.startBlockers(car)
    PanelCard(modifier) {
        Badge("부모님 준비")
        Text("시작하기 전에 골라 주세요", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("아이 나이 (형제자매는 2명까지)", fontSize = 20.sp, color = Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (age in Age.entries) Choice(age.years, age.label, ui.setup.ages.contains(age), Modifier.weight(1f)) {
                game.setup { s ->
                    val ages = if (s.ages.contains(age)) s.ages - age else (s.ages + age).takeLast(2)
                    s.copy(ages = ages.ifEmpty { listOf(age) })
                }
            }
        }
        Text("기다릴 시간", fontSize = 20.sp, color = Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(5, 10, 15)) Choice("${m}분", "퍼즐 ${Planner.puzzleCount(m)}개", ui.setup.minutes == m, Modifier.weight(1f)) {
                game.setup { it.copy(minutes = m) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("선물 준비됨", "글로브박스", ui.setup.giftReady, Modifier.weight(1f)) {
                game.setup { it.copy(giftReady = !it.giftReady) }
            }
            Choice("독서등 손 닿음", "안 닿으면 \"지금!\"", ui.setup.childReachesLight, Modifier.weight(1f)) {
                game.setup { it.copy(childReachesLight = !it.childReachesLight) }
            }
        }
        Spacer(Modifier.weight(1f))
        if (car.hvacPower == false) Text("공조 전원을 켜 주세요", fontSize = 22.sp, color = Coral, fontWeight = FontWeight.Bold)
        blockers.forEach { Text("• ${it.message}", fontSize = 20.sp, color = Coral) }
        Text("아이가 요청하면 안전 지킴이가 확인하고 눌러요", fontSize = 18.sp, color = Secondary)
        val storyLine = when (storyStatus) {
            StoryRepo.Status.READY -> "AI 이야기가 준비됐어요"
            StoryRepo.Status.WRITING -> "AI 이야기를 만드는 중이에요. 기다리지 않고 시작해도 돼요"
            StoryRepo.Status.OFFLINE -> "AI 이야기를 받지 못해 기본 이야기로 해요"
            StoryRepo.Status.NONE -> null
        }
        if (storyLine != null) Text(storyLine, fontSize = 18.sp, color = if (storyStatus == StoryRepo.Status.READY) Mint else Secondary)
        BigButton("오늘의 이야기 만들기", Ink, enabled = blockers.isEmpty()) { game.begin() }
    }
}

/** 이야기 신고: the parent flags a story; it never stops the game. */
@Composable
private fun ReportRow(game: Game, ui: GameUi) {
    val story = ui.story ?: return
    var open by remember(story.storyId) { mutableStateOf(false) }
    var sent by remember(story.storyId) { mutableStateOf(false) }
    when {
        sent -> Text("신고를 받았어요. 이 이야기를 다시 살펴볼게요", fontSize = 18.sp, color = Secondary)
        !open -> Text(
            "이야기 신고", fontSize = 18.sp, color = Secondary,
            modifier = Modifier.clickable { open = true }.padding(vertical = 6.dp),
        )
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((reason, label) in listOf("SCARY" to "무서워요", "WRONG_ANSWER" to "정답과 달라요", "HARD_WORDS" to "말이 어려워요")) {
                Choice(label, null, false, Modifier.weight(1f)) {
                    game.report(reason)
                    sent = true
                }
            }
        }
    }
}

@Composable
private fun PlayingPanel(game: Game, ui: GameUi, car: CarState, speaking: Boolean, lumi: Lumi, modifier: Modifier) {
    val p = ui.puzzle ?: return
    val scene = ui.scene(p)
    var reveal by remember(ui.index) { mutableStateOf(false) }
    PanelCard(modifier) {
        Badge("안전 지킴이 (부모님)")
        Text(p.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            if (p == Puzzle.BeltMission) "아이가 스스로 매도록 기다려 주세요" else "아이가 요청하면 확인하고 바꿔 주세요",
            fontSize = 20.sp, color = Secondary,
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Soft).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (speaking) "루미가 말하고 있어요" else "루미의 말을 다시 들을 수 있어요", fontSize = 20.sp, color = Ink)
            OutlineButton("다시 듣기", Modifier.fillMaxWidth()) { lumi.repeat() }
        }
        if (scene.buttons.isNotEmpty()) Text("이 퍼즐에서 쓰는 버튼: " + scene.buttons.joinToString(", "), fontSize = 20.sp, color = Ink)
        if (p == Puzzle.BeltMission) {
            Text("이미 매고 있다면 버클을 짚고 \"딸깍 확인!\"을 외치게 해 주세요", fontSize = 20.sp, color = Ink)
            BigButton("확인했어요", Mint, height = 80.dp) { game.confirmBelt() }
        } else {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(2.dp, Line, RoundedCornerShape(16.dp))
                    .pointerInput(ui.index) { detectTapGestures(onLongPress = { reveal = true }) }.padding(14.dp),
            ) { Text(if (reveal) "정답: ${scene.answer}" else "정답 보기 (길게 누르기)", fontSize = 20.sp, color = Secondary) }
        }
        Spacer(Modifier.weight(1f))
        SafetyLine(ui, car)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("힌트 듣기", Sun, textColor = Ink, height = 80.dp, modifier = Modifier.weight(1f)) { game.hint() }
            OutlineButton("끝내기", Modifier.weight(1f)) { game.finish() }
        }
        BigButton("멈춤", Coral, height = 104.dp) { game.hold() }
    }
}

@Composable
private fun KidStage(ui: GameUi, speaking: Boolean, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(32.dp)).background(Stage).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val p = ui.puzzle
        val (bubble, pictures) = when (ui.phase) {
            Phase.SETUP -> "어디로 탐험 갈까?" to listOf("theme_star_train")
            Phase.INTRO -> (ui.story?.title ?: "오늘은 별빛 기차가 고장 났대!") to listOf(ui.story?.themePicture ?: "theme_star_train")
            Phase.PLAYING -> p?.let { ui.scene(it).let { s -> s.bubble to s.pictures } } ?: ("" to emptyList())
            Phase.SOLVED -> "정답! 별 하나 획득!" to listOf(successPicture(p))
            Phase.TREASURE -> (if (ui.setup.giftReady) "보물 상자가 열렸어!" else "탐험 배지 획득!") to
                listOf(if (ui.setup.giftReady) "treasure_chest" else "badge_explorer")
            Phase.RESTORE -> "처음 설정으로 돌려 놓자" to listOf("window_fresh_air")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Card).border(4.dp, Ink, CircleShape), contentAlignment = Alignment.Center) {
                Text("루미", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Spacer(Modifier.width(20.dp))
            Text(
                bubble, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(Card).padding(horizontal = 28.dp, vertical = 18.dp),
            )
            if (speaking) {
                Spacer(Modifier.width(16.dp))
                Text(
                    "말하는 중", fontSize = 22.sp, color = Mint,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Card).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { pictures.forEach { Picture(it, Modifier.weight(1f).fillMaxHeight()) } }
    }
}

private fun successPicture(p: Puzzle?) = when (p) {
    is Puzzle.WindPath -> "sprout_alive"
    is Puzzle.DustCloud -> "window_fresh_air"
    is Puzzle.TwoRooms -> "ice_flower"
    Puzzle.FoggyWindow -> "windshield_clear"
    is Puzzle.SeatSearch -> "seats_topview"
    is Puzzle.Together -> "train_door_open"
    Puzzle.BeltMission -> "belt_buckled"
    null -> "badge_explorer"
}

@Composable
private fun PauseOverlay(game: Game, ui: GameUi) {
    Box(Modifier.fillMaxSize().background(Color(0xEEF2F4F7)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(820.dp).clip(RoundedCornerShape(32.dp)).background(Card).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val reason = ui.stopped
            Picture(
                when (reason) {
                    StopReason.DOOR_OPEN -> "door_open"
                    StopReason.DRIVING -> "parking_p"
                    else -> "window_fresh_air"
                },
                Modifier.height(320.dp),
            )
            Text(if (reason == null) "잠깐 멈췄어요" else "잠깐 쉬어요!", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(reason?.message ?: "준비되면 이어 하기를 눌러 주세요", fontSize = 24.sp, color = Secondary, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("이어 하기", Ink, enabled = reason == null, modifier = Modifier.weight(1f)) { game.resume() }
                OutlineButton("끝내기", Modifier.weight(1f)) { game.finish() }
            }
        }
    }
}
