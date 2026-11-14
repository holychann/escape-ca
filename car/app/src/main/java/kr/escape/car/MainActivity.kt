package kr.escape.car

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kr.escape.car.car.VehicleSource
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Friend
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Safety
import kr.escape.car.engine.StopReason
import kr.escape.car.story.StoryClient
import kr.escape.car.story.Story
import kr.escape.car.story.StoryRepo
import kr.escape.car.ui.AssetImage
import kr.escape.car.ui.Badge
import kr.escape.car.ui.BodyText
import kr.escape.car.ui.CheckLabel
import kr.escape.car.ui.ChoiceTile
import kr.escape.car.ui.Clay
import kr.escape.car.ui.ClayButton
import kr.escape.car.ui.ClayGlyph
import kr.escape.car.ui.ClayIcon
import kr.escape.car.ui.ClayStyle
import kr.escape.car.ui.Fonts
import kr.escape.car.ui.KidText
import kr.escape.car.ui.Portrait
import kr.escape.car.ui.StatusChip
import kr.escape.car.ui.starPath
import kr.escape.car.voice.Lumi
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var source: VehicleSource
    private lateinit var lumi: Lumi
    private lateinit var game: Game
    private lateinit var stories: StoryRepo
    private lateinit var book: FriendBook
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(assets)
        source = VehicleSource(this).also { it.start() }
        lumi = Lumi(this)
        stories = StoryRepo(File(filesDir, "next_episode.json"), installId(), StoryClient(BuildConfig.STORY_URL), scope)
        book = FriendBook(File(filesDir, "friends.txt"))
        game = Game(source.state, lumi, stories, book)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Clay.Ink, background = Clay.NightBottom, surface = Clay.Cream)) {
                LaunchedEffect(Unit) {
                    while (true) {
                        game.tick(SystemClock.elapsedRealtime())
                        delay(200)
                    }
                }
                App(game, source, lumi, stories, book)
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
private fun App(game: Game, source: VehicleSource, lumi: Lumi, stories: StoryRepo, book: FriendBook) {
    val ui by game.ui.collectAsState()
    val met by book.met.collectAsState()
    val car by source.state.collectAsState()
    val speaking by lumi.speaking.collectAsState()
    val storyStatus by stories.status.collectAsState()
    val preview by stories.preview.collectAsState()
    val rejection by stories.rejection.collectAsState()
    val view = StoryView(storyStatus, preview, rejection)
    // while the parent is choosing, the next episode is written in the background
    var askedWish by remember { mutableStateOf(ui.setup.wish) }
    LaunchedEffect(ui.phase, ui.setup, car.devices, car.fanSpeedRange) {
        if (ui.phase != Phase.SETUP) return@LaunchedEffect
        // a new story wish is asked for at once, so the last story's "준비 완료" does not linger;
        // other changes wait a moment for the car to report every device
        if (ui.setup.wish != askedWish) askedWish = ui.setup.wish else delay(1500)
        stories.prepare(ui.setup, source.state.value)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Clay.NightTop, Clay.NightBottom)))) {
        val portrait = maxWidth < maxHeight
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 36.dp)) {
            Header(ui, car)
            Spacer(Modifier.height(24.dp))
            if (portrait) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    KidStage(ui, met, speaking, preview, Modifier.weight(1f).fillMaxWidth())
                    ParentPanel(game, ui, car, speaking, lumi, view, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val panelWidth = if (ui.phase == Phase.SETUP) 680.dp else 500.dp
                    ParentPanel(game, ui, car, speaking, lumi, view, Modifier.width(panelWidth).fillMaxHeight())
                    KidStage(ui, met, speaking, preview, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        if (ui.paused && ui.phase in listOf(Phase.INTRO, Phase.PLAYING, Phase.SOLVED)) PauseOverlay(game, ui)
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(ui: GameUi, car: CarState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Portrait("lumi_neutral", 64.dp, ringWidth = 0.dp)
        KidText("차량탈출", 42.sp, color = Clay.Cream)
        StatusChip("주차 중", car.gear == Gear.PARK && !car.uxRestricted)
        StatusChip("운전석 보호자", car.driverSeated == true)
        Spacer(Modifier.weight(1f))
        if (ui.puzzles.isNotEmpty() && ui.phase !in listOf(Phase.SETUP, Phase.RESTORE)) ProgressBeads(ui)
    }
}

/** One bead per puzzle (mint star solved, sun ring now, dashed next) and the treasure at the end. */
@Composable
private fun ProgressBeads(ui: GameUi) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in ui.puzzles.indices) {
            val done = i < ui.stars
            val now = !done && i == ui.stars && ui.phase != Phase.TREASURE
            Canvas(Modifier.size(52.dp)) {
                val r = size.minDimension / 2
                when {
                    done -> {
                        drawCircle(Clay.Mint, r)
                        drawPath(starPath(r, r, r * 0.55f, r * 0.24f), Color.White)
                    }
                    now -> {
                        drawCircle(Clay.Sun.copy(alpha = 0.2f), r)
                        drawCircle(Clay.Sun, r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                        drawCircle(Clay.Sun, r * 0.3f)
                    }
                    else -> drawCircle(
                        Color.White.copy(alpha = 0.35f), r - 2.dp.toPx(),
                        style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                    )
                }
            }
        }
        val treasure = ui.phase == Phase.TREASURE
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (treasure) Clay.Sun else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { ClayGlyph(ClayIcon.GIFT, if (treasure) Clay.Ink else Clay.Sun, Modifier.size(30.dp)) }
    }
}

// ---------------------------------------------------------------- parent panel

@Composable
private fun PanelCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(44.dp)
    Column(
        modifier.shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(Clay.Cream).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun PanelTitle(text: String, size: Int = 52) = KidText(text, size.sp)

@Composable
private fun FieldLabel(text: String) = BodyText(text, size = 20.sp, weight = FontWeight.Medium)

/**
 * The part of a panel between its title and its bottom buttons. It takes the room that is left and scrolls when
 * there is more (a long restore list, a notice plus three car buttons), so 멈춤 and the main button never get squeezed.
 */
@Composable
private fun ColumnScope.Middle(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun ParentPanel(
    game: Game,
    ui: GameUi,
    car: CarState,
    speaking: Boolean,
    lumi: Lumi,
    view: StoryView,
    modifier: Modifier,
) {
    when (ui.phase) {
        Phase.SETUP -> SetupPanel(game, ui, car, view, modifier)
        Phase.INTRO -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle(ui.story?.title ?: "오늘의 이야기")
            BodyText(if (ui.story != null) "루미의 이야기는 AI가 만들었어요. 정답은 규칙 엔진이 정해요" else "정답은 규칙 엔진이 정해요")
            Middle {
                FieldLabel("오늘의 퍼즐")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.puzzles.forEachIndexed { i, p -> PuzzleRow(i + 1, p.title, first = i == 0) }
                }
            }
            ReportRow(game, ui)
            SafetyLine(ui, car)
            ClayButton("탐험 시작", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp) { game.startPuzzle(0) }
        }
        Phase.PLAYING -> PlayingPanel(game, ui, car, speaking, lumi, modifier)
        Phase.SOLVED -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle("정답이에요!")
            BodyText("아이를 듬뿍 칭찬해 주세요. 별 하나를 모았어요")
            Middle { ui.newFriend?.let { NewFriendCard(it) } }
            SafetyLine(ui, car)
            ClayButton(
                if (ui.index + 1 < ui.puzzles.size) "다음 퍼즐" else "보물 열기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 104.dp,
            ) { game.next() }
            ClayButton("멈춤", ClayStyle.CORAL, Modifier.fillMaxWidth(), height = 104.dp, icon = ClayIcon.PAUSE) { game.hold() }
        }
        Phase.TREASURE -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle(if (ui.setup.giftReady) "선물을 건네 주세요" else "탐험 배지를 받았어요")
            BodyText(
                if (ui.setup.giftReady) "글로브박스를 열어 선물을 꺼내, 아이가 앞으로 넘어오지 않게 직접 건네 주세요"
                else "끝까지 해낸 아이를 꼭 안아 칭찬해 주세요",
            )
            Middle {
                if (ui.metToday.isNotEmpty()) {
                    FieldLabel("오늘 만난 새 친구 ${ui.metToday.size}명")
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ui.metToday.forEach { Portrait(it.picture, 96.dp, ring = Clay.Mint, ringWidth = 4.dp) }
                    }
                }
            }
            ClayButton("처음 설정으로", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp) { game.finish() }
        }
        Phase.RESTORE -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle("처음 설정으로")
            val changes = ui.snapshot?.changes(car).orEmpty()
            Middle {
                if (changes.isEmpty()) {
                    BodyText("모두 처음 설정과 같아요", color = Clay.MintDeep, weight = FontWeight.Medium)
                } else {
                    BodyText("게임 전에 저장해 둔 설정과 달라진 곳이에요. 시작할 때 설정으로 돌려 주세요")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { changes.forEach { RestoreRow(it) } }
                }
            }
            ReportRow(game, ui)
            ClayButton("다 돌렸어요", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp) { game.reset() }
        }
    }
}

@Composable
private fun SetupPanel(game: Game, ui: GameUi, car: CarState, view: StoryView, modifier: Modifier) {
    val blockers = Safety.startBlockers(car)
    // with no puzzle this car can play, game.begin() would return without a word
    val playable = Planner.plan(ui.setup, car).isNotEmpty()
    PanelCard(modifier) {
        Badge("부모님 준비")
        PanelTitle("시작하기 전에 골라 주세요", 48)
        Middle {
            WishBox(ui.setup.wish, view) { wish -> game.setup { it.copy(wish = wish) } }
            FieldLabel("아이 나이 · 형제자매는 두 명까지")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (age in Age.entries) ChoiceTile(age.years, age.label, ui.setup.ages.contains(age), Modifier.weight(1f)) {
                    game.setup { s ->
                        val ages = if (s.ages.contains(age)) s.ages - age else (s.ages + age).takeLast(2)
                        s.copy(ages = ages.ifEmpty { listOf(age) })
                    }
                }
            }
            FieldLabel("기다릴 시간")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (m in listOf(5, 10, 15)) ChoiceTile("${m}분", "퍼즐 ${Planner.puzzleCount(m)}개", ui.setup.minutes == m, Modifier.weight(1f)) {
                    game.setup { it.copy(minutes = m) }
                }
            }
            GiftToggle(ui.setup.giftReady) { game.setup { it.copy(giftReady = !it.giftReady) } }
            Spacer(Modifier.height(4.dp))
            blockers.forEach { Notice(it.message) }
            if (blockers.isEmpty() && !playable) Notice("차량 장치(공조, 독서등)를 아직 읽지 못했어요. 잠시 기다려 주세요")
            BodyText("아이가 버튼을 찾아 가리키면, 안전 지킴이가 확인하고 눌러 줘요", size = 19.sp)
        }
        ClayButton("오늘의 이야기 만들기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp, enabled = blockers.isEmpty() && playable) {
            game.begin()
        }
    }
}

/** What the story server is doing for the setup screen: its status, the story it has ready, why it refused a wish. */
private data class StoryView(val status: StoryRepo.Status, val preview: Story?, val rejection: List<String>)

private val WISH_IDEAS = listOf("공룡 섬에서 보물찾기", "우주 정거장 구조대", "과자 나라 축제", "눈 오는 숲속 마을", "바닷속 인어 왕국", "로봇 도시 운동회")

/**
 * 오늘의 이야기 소원: the family types or picks the story they want; the story server writes it around today's puzzles
 * and draws its world. The puzzles and their answers stay the rule engine's.
 */
@Composable
private fun WishBox(wish: String, view: StoryView, onWish: (String) -> Unit) {
    var draft by remember(wish) { mutableStateOf(wish) }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(3.dp, if (wish.isNotBlank()) Clay.Sun else Clay.Line, shape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClayGlyph(ClayIcon.STAR, Clay.SunLip, Modifier.size(30.dp))
            KidText("오늘의 이야기 소원", 32.sp)
        }
        BodyText("듣고 싶은 이야기를 적거나 골라 주세요. AI가 오늘 퍼즐에 맞춰 이야기와 그림을 새로 만들어요", size = 19.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val field = RoundedCornerShape(20.dp)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(StoryRepo.WISH_MAX) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 24.sp, color = Clay.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onWish(draft.trim()) }),
                modifier = Modifier.weight(1f).height(80.dp).clip(field).background(Clay.Cream).border(2.dp, Clay.LineSoft, field),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) BodyText("예: 공룡 섬에서 보물찾기", size = 22.sp, color = Clay.Muted)
                        inner()
                    }
                },
            )
            ClayButton("만들기", ClayStyle.SUN, Modifier.width(150.dp), height = 80.dp, enabled = draft.isNotBlank() && draft.trim() != wish) {
                onWish(draft.trim())
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WISH_IDEAS.forEach { idea -> WishChip(idea, idea == wish) { draft = idea; onWish(idea) } }
        }
        WishStatus(wish, view) { onWish("") }
        BodyText("아이 이름이나 사는 곳은 적지 말아 주세요", size = 17.sp, color = Clay.Muted)
    }
}

@Composable
private fun WishChip(text: String, selected: Boolean, onClick: () -> Unit) {
    KidText(
        text, 22.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Clay.Sun else Clay.CreamDeep)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun WishStatus(wish: String, view: StoryView, onClear: () -> Unit) {
    val story = view.preview
    when (view.status) {
        StoryRepo.Status.WRITING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClayGlyph(ClayIcon.WAVE, Clay.Mint, Modifier.size(28.dp))
            BodyText(
                if (wish.isBlank()) "AI 이야기를 만드는 중이에요. 기다리지 않고 시작해도 돼요"
                else "‘$wish’ 이야기와 그림을 만드는 중이에요 · 30초~1분",
                size = 19.sp, color = Clay.MintDeep, weight = FontWeight.Medium,
            )
        }
        StoryRepo.Status.READY -> CheckLabelText(
            if (story == null) "AI 이야기가 준비됐어요"
            else "‘${story.title}’ 준비 완료" + if (story.images.isNotEmpty()) " · 그림 ${story.images.size}장" else "",
        )
        StoryRepo.Status.REJECTED -> Notice("이 소원은 다르게 적어 주세요 (${view.rejection.joinToString(", ").ifEmpty { "안전 규칙" }})")
        StoryRepo.Status.OFFLINE -> BodyText("이야기 서버에 연결하지 못해 기본 이야기로 해요", size = 19.sp)
        StoryRepo.Status.NONE -> Unit
    }
    if (wish.isNotBlank()) Text(
        "소원 지우기", fontSize = 18.sp, color = Clay.Muted, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClear).padding(vertical = 4.dp),
    )
}

@Composable
private fun CheckLabelText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Clay.Mint), contentAlignment = Alignment.Center) {
            ClayGlyph(ClayIcon.CHECK, Color.White, Modifier.size(14.dp))
        }
        BodyText(text, size = 19.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
    }
}

@Composable
private fun Notice(text: String) = BodyText("• $text", size = 20.sp, color = Clay.CoralLip, weight = FontWeight.Medium)

@Composable
private fun GiftToggle(on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(3.dp, Clay.Line, shape).clickable(onClick = onToggle)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AssetImage("icon_glovebox", Modifier.size(64.dp), scale = ContentScale.Fit)
        Column(Modifier.weight(1f)) {
            KidText("선물 준비됨", 32.sp)
            BodyText("글로브박스에 선물을 넣어 두었다면 켜요", size = 19.sp)
        }
        Box(
            Modifier.width(96.dp).height(56.dp).clip(RoundedCornerShape(50)).background(if (on) Clay.Mint else Clay.Line).padding(6.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White)) }
    }
}

@Composable
private fun PuzzleRow(number: Int, title: String, first: Boolean) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, Clay.LineSoft, shape)
            .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (first) Clay.Sun else Clay.CreamDeep), contentAlignment = Alignment.Center) {
            KidText("$number", 26.sp)
        }
        KidText(title, 30.sp)
    }
}

@Composable
private fun RestoreRow(text: String) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, Clay.LineSoft, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Clay.Cream).border(3.dp, Color(0xFFD9CBB1), RoundedCornerShape(9.dp)))
        KidText(text, 30.sp)
    }
}

@Composable
private fun NewFriendCard(friend: Friend) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.MintSoft).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Portrait(friend.picture, 104.dp, ring = Clay.Mint, ringWidth = 4.dp)
        Column {
            BodyText("새 친구가 도감에 들어왔어요", size = 19.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
            KidText(friend.label, 40.sp)
        }
    }
}

@Composable
private fun SafetyLine(ui: GameUi, car: CarState) {
    val sec = ui.playedMs / 1000
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CheckLabel("주차", car.gear == Gear.PARK)
            CheckLabel("문 닫힘", !car.doorOpen)
            CheckLabel("운전석", car.driverSeated == true)
            Spacer(Modifier.weight(1f))
            Text(
                "%02d:%02d / %02d:00".format(sec / 60, sec % 60, Safety.MAX_PLAY_MS / 60000),
                fontSize = 19.sp, color = Clay.Sub, fontWeight = FontWeight.Medium,
            )
        }
        if (ui.recirculationWarning) Notice("내기 순환 3분이 지났어요. 외기로 돌려 주세요")
    }
}

/** 이야기 신고: the parent flags a story; it never stops the game. */
@Composable
private fun ReportRow(game: Game, ui: GameUi) {
    val story = ui.story ?: return
    var open by remember(story.storyId) { mutableStateOf(false) }
    var sent by remember(story.storyId) { mutableStateOf(false) }
    when {
        sent -> BodyText("신고를 받았어요. 이 이야기를 다시 살펴볼게요", size = 18.sp)
        !open -> Text(
            "이야기 신고", fontSize = 19.sp, color = Clay.Muted, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { open = true }.padding(vertical = 6.dp),
        )
        else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((reason, label) in listOf("SCARY" to "무서워요", "WRONG_ANSWER" to "정답과 달라요", "HARD_WORDS" to "말이 어려워요")) {
                Text(
                    label, fontSize = 19.sp, color = Clay.Ink, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White)
                        .border(2.dp, Clay.Line, RoundedCornerShape(50))
                        .clickable { game.report(reason); sent = true }.padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayingPanel(game: Game, ui: GameUi, car: CarState, speaking: Boolean, lumi: Lumi, modifier: Modifier) {
    val p = ui.puzzle ?: return
    val scene = ui.scene(p)
    val belt = p == Puzzle.BeltMission
    var reveal by remember(ui.index) { mutableStateOf(false) }
    PanelCard(modifier) {
        Badge("안전 지킴이 · 부모님")
        PanelTitle(p.title)
        BodyText(if (belt) "다음 칸으로 가기 전 준비 시간이에요. 아이가 스스로 매도록 기다려 주세요" else "아이가 가리킨 버튼을 확인하고 눌러 주세요")
        Middle {
            ui.resetNeeded?.let { BodyText(it, size = 22.sp, color = Clay.CoralLip, weight = FontWeight.Bold) }
            if (belt) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KidText("이미 매고 있다면", 30.sp)
                    BodyText("버클을 손으로 짚고 \"딸깍 확인!\"을 외치게 해 주세요", size = 21.sp)
                }
            } else {
                VoiceBox(speaking) { lumi.repeat() }
                if (scene.buttons.isNotEmpty()) {
                    FieldLabel("이 퍼즐에서 쓰는 버튼")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { scene.buttons.take(3).forEach { CarButton(it) } }
                }
                AnswerReveal(if (reveal) "정답: ${scene.answer}" else null, Modifier.pointerInput(ui.index) {
                    detectTapGestures(onLongPress = { reveal = true })
                })
            }
        }
        SafetyLine(ui, car)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (belt) ClayButton("확인했어요", ClayStyle.MINT, Modifier.weight(1f)) { game.confirmBelt() }
            else ClayButton("힌트 듣기", ClayStyle.SUN, Modifier.weight(1f), icon = ClayIcon.HINT) { game.hint() }
            ClayButton("끝내기", ClayStyle.GHOST, Modifier.width(170.dp)) { game.finish() }
        }
        ClayButton("멈춤", ClayStyle.CORAL, Modifier.fillMaxWidth(), height = 104.dp, icon = ClayIcon.PAUSE) { game.hold() }
    }
}

@Composable
private fun VoiceBox(speaking: Boolean, onRepeat: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClayGlyph(ClayIcon.WAVE, if (speaking) Clay.Mint else Clay.Muted, Modifier.size(32.dp))
            BodyText(
                if (speaking) "루미가 문제를 말하고 있어요" else "루미의 말을 다시 들을 수 있어요",
                size = 21.sp, color = Clay.Ink, weight = FontWeight.Medium,
            )
        }
        ClayButton("다시 듣기", ClayStyle.GHOST, Modifier.fillMaxWidth(), icon = ClayIcon.SPEAKER, onClick = onRepeat)
    }
}

/** The car's own button, drawn like the real one, so the parent knows what the child is looking for. */
@Composable
private fun CarButton(label: String) {
    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(104.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).border(3.dp, Clay.Line, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { AssetImage(glyphFor(label), Modifier.size(80.dp), scale = ContentScale.Fit) }
        BodyText(label, size = 18.sp, weight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

private fun glyphFor(label: String) = when {
    "얼굴" in label -> "hvac_face"
    "발" in label -> "hvac_floor"
    "풍량" in label -> "hvac_fan"
    "내기" in label -> "hvac_recirc"
    "외기" in label -> "hvac_fresh"
    "성에" in label -> "hvac_defrost"
    "온도" in label -> "hvac_temp"
    "독서등" in label -> "hvac_light"
    else -> "hvac_power"
}

/** 정답 보기 (길게 누르기): a dashed pill that shows the answer only after a long press. */
@Composable
private fun AnswerReveal(answer: String?, modifier: Modifier) {
    Row(
        modifier.drawBehind {
            drawRoundRect(
                Color(0xFFD9CBB1), cornerRadius = CornerRadius(size.height / 2),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
            )
        }.background(if (answer != null) Color.White else Color.Transparent, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClayGlyph(ClayIcon.EYE, if (answer != null) Clay.Ink else Clay.Sub, Modifier.size(26.dp))
        BodyText(
            answer ?: "정답 보기 (길게 누르기)", size = 20.sp, weight = FontWeight.Medium,
            color = if (answer != null) Clay.Ink else Clay.Sub,
        )
    }
}

// ---------------------------------------------------------------- kid stage

/** Which of Lumi's four faces fits this moment. */
private fun lumiFace(ui: GameUi) = when (ui.phase) {
    Phase.SETUP -> "lumi_neutral"
    Phase.INTRO, Phase.SOLVED, Phase.TREASURE -> "lumi_happy"
    Phase.PLAYING -> if (ui.hintHeard) "lumi_thinking" else "lumi_neutral"
    Phase.RESTORE -> "lumi_sleepy"
}

@Composable
private fun KidStage(ui: GameUi, met: Set<Friend>, speaking: Boolean, preview: Story?, modifier: Modifier) {
    val p = ui.puzzle
    val story = ui.story
    val theme = story?.picture("cover") ?: story?.themePicture ?: "theme_star_train"
    val (bubble, picture) = when (ui.phase) {
        Phase.SETUP -> when {
            preview != null -> preview.title to (preview.picture("cover") ?: preview.themePicture)
            ui.setup.wish.isNotBlank() -> "‘${ui.setup.wish}’ 좋아!" to "theme_star_train"
            else -> "어디로 탐험 갈까?" to "theme_star_train"
        }
        Phase.INTRO -> (ui.story?.title ?: "별빛 기차가 고장 났대!") to theme
        Phase.PLAYING -> p?.let { ui.scene(it).let { s -> s.bubble to (s.pictures.firstOrNull() ?: theme) } } ?: ("" to theme)
        Phase.SOLVED -> "정답! 별 하나 획득!" to (story?.picture("cover") ?: successPicture(p))
        Phase.TREASURE -> (if (ui.setup.giftReady) "보물 상자가 열렸어!" else "탐험 배지 획득!") to
            (story?.picture("ending") ?: if (ui.setup.giftReady) "treasure_chest" else "badge_explorer")
        Phase.RESTORE -> "처음 설정으로 돌려 놓자" to theme
    }
    val shape = RoundedCornerShape(48.dp)
    Box(modifier.clip(shape).background(Clay.NightBottom)) {
        AssetImage(picture, Modifier.fillMaxSize())
        // a soft shade at the top keeps the bubble readable over any picture
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .background(Brush.verticalGradient(listOf(Clay.NightBottom.copy(alpha = 0.55f), Color.Transparent))),
        )
        if (ui.phase == Phase.RESTORE) Box(Modifier.fillMaxSize().background(Clay.NightBottom.copy(alpha = 0.35f)))
        Row(Modifier.padding(start = 36.dp, top = 30.dp, end = 36.dp)) {
            Box(Modifier.shadow(16.dp, CircleShape)) { Portrait(lumiFace(ui), 156.dp, ringWidth = 6.dp) }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SpeechBubble(bubble)
                if (speaking) SpeakingTag()
            }
        }
        if (ui.phase == Phase.SOLVED) ui.newFriend?.let { NewFriendPop(it) }
        if (ui.phase == Phase.SETUP) FriendBook(met, ui.metToday)
    }
}

@Composable
private fun SpeechBubble(text: String) {
    if (text.isBlank()) return
    val shape = RoundedCornerShape(44.dp)
    Box {
        Box(Modifier.matchParentSize().padding(top = 8.dp).clip(shape).background(Clay.Line))
        KidText(
            text, 56.sp, maxLines = 2,
            modifier = Modifier.padding(bottom = 8.dp).widthIn(max = 760.dp).clip(shape).background(Clay.Cream)
                .padding(start = 44.dp, end = 44.dp, top = 24.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun SpeakingTag() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Clay.NightBottom.copy(alpha = 0.55f)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClayGlyph(ClayIcon.WAVE, Clay.Mint, Modifier.size(26.dp))
        Text("말하는 중", color = Clay.Cream, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BoxScope.NewFriendPop(friend: Friend) {
    val shape = RoundedCornerShape(40.dp)
    Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 40.dp)) {
        Box(Modifier.matchParentSize().padding(top = 10.dp).clip(shape).background(Clay.Mint))
        Row(
            Modifier.padding(bottom = 10.dp).shadow(24.dp, shape).clip(shape).background(Clay.Cream)
                .padding(start = 24.dp, end = 40.dp, top = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Portrait(friend.picture, 170.dp, ring = Clay.Mint, ringWidth = 6.dp)
            Column {
                KidText("새 친구!", 34.sp, color = Clay.MintLip)
                KidText(friend.label, 64.sp)
            }
        }
    }
}

/** 친구 도감: friends met so far in color, the rest grey and faint until a puzzle brings them. */
@Composable
private fun BoxScope.FriendBook(met: Set<Friend>, today: List<Friend>) {
    Column(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 32.dp).fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(36.dp)).clip(RoundedCornerShape(36.dp)).background(Clay.Cream.copy(alpha = 0.95f))
            .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KidText("친구 도감 ${met.size}/${Friend.entries.size}", 34.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Friend.entries.forEach { f ->
                val found = f in met
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val size: Dp = minOf(maxWidth, 120.dp)
                        Portrait(
                            f.picture, size, dim = !found,
                            ring = when { f in today -> Clay.Sun; found -> Clay.Mint; else -> Color(0xFFD9CBB1) },
                            ringWidth = if (found) 5.dp else 3.dp,
                        )
                    }
                    if (found) KidText(f.label, 24.sp, textAlign = TextAlign.Center, maxLines = 1)
                    else BodyText("누구일까?", size = 18.sp, color = Clay.Muted, weight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun successPicture(p: Puzzle?) = when (p) {
    is Puzzle.WindPath -> "scene_wind_ok"
    is Puzzle.DustCloud -> "scene_dust_ok"
    is Puzzle.TwoRooms -> "scene_rooms_ok"
    Puzzle.FoggyWindow -> "scene_fog_ok"
    is Puzzle.SeatSearch -> "scene_seats_ok"
    is Puzzle.Together -> "scene_together_ok"
    Puzzle.BeltMission -> "scene_belt_ok"
    null -> "badge_explorer"
}

// ---------------------------------------------------------------- pause

@Composable
private fun PauseOverlay(game: Game, ui: GameUi) {
    // the scrim swallows every touch: without it 다음 퍼즐 or 힌트 behind the card could still be pressed while paused
    Box(
        Modifier.fillMaxSize().background(Clay.NightBottom.copy(alpha = 0.82f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(48.dp)
        Column(
            Modifier.width(816.dp).shadow(32.dp, shape).clip(shape).background(Clay.Cream)
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val reason = ui.stopped
            val picture = when (reason) {
                StopReason.DOOR_OPEN -> "pause_door"
                StopReason.DRIVING -> "pause_parking"
                else -> null
            }
            if (picture != null) AssetImage(picture, Modifier.fillMaxWidth().aspectRatio(720f / 420f).clip(RoundedCornerShape(32.dp)))
            else Portrait("lumi_sleepy", 260.dp, ring = Clay.Line, ringWidth = 6.dp)
            KidText(if (reason == null) "잠깐 멈췄어요" else "잠깐 쉬어요!", 60.sp)
            BodyText(reason?.message ?: "준비되면 이어 하기를 눌러 주세요", size = 26.sp, weight = FontWeight.Medium, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ClayButton("이어 하기", ClayStyle.SUN, Modifier.weight(1f), enabled = reason == null) { game.resume() }
                ClayButton("끝내기", ClayStyle.GHOST, Modifier.width(300.dp)) { game.finish() }
            }
        }
    }
}
