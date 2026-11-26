package kr.escape.car

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.ui.platform.LocalContext
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.delay
import kr.escape.car.car.VehicleSource
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Friend
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.Book
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Safety
import kr.escape.car.engine.StopReason
import kr.escape.car.story.Lines
import kr.escape.car.story.Progress
import kr.escape.car.story.Question
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
import kr.escape.car.ui.FriendFace
import kr.escape.car.ui.Hearts
import kr.escape.car.ui.KidText
import kr.escape.car.ui.Portrait
import kr.escape.car.ui.StatusChip
import kr.escape.car.ui.starPath
import kr.escape.car.voice.Lumi

class MainActivity : ComponentActivity() {
    /** The car, Lumi and the game live here, so a rebuilt screen goes on with the same episode. */
    private val model: GameModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(assets)
        val m = model
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Clay.Ink, background = Clay.NightBottom, surface = Clay.Cream)) {
                LaunchedEffect(Unit) {
                    while (true) {
                        m.game.tick(SystemClock.elapsedRealtime())
                        delay(200)
                    }
                }
                App(m.game, m.source, m.lumi, m.stories, m.book)
            }
        }
    }
}

@Composable
private fun App(game: Game, source: VehicleSource, lumi: Lumi, stories: StoryRepo, book: FriendBook) {
    val ui by game.ui.collectAsState()
    val friends by book.book.collectAsState()
    val car by source.state.collectAsState()
    val speaking by lumi.speaking.collectAsState()
    val subtitle by lumi.subtitle.collectAsState()
    val noSound by lumi.noSound.collectAsState()
    // 아이 화면 자막: on unless the parent turned it off, remembered across drives
    val prefs = LocalContext.current.getSharedPreferences("screen", android.content.Context.MODE_PRIVATE)
    var subtitlesOn by remember { mutableStateOf(prefs.getBoolean("subtitles", true)) }
    val subs = Subs(subtitlesOn) { on -> subtitlesOn = on; prefs.edit().putBoolean("subtitles", on).apply() }
    // a line with no sound is always shown, whatever the switch says; nothing is shown while the car is not parked
    val shown = subtitle?.takeIf { (subtitlesOn || noSound) && ui.stopped != StopReason.DRIVING }
    val storyStatus by stories.status.collectAsState()
    val preview by stories.preview.collectAsState()
    val rejection by stories.rejection.collectAsState()
    val waited = rememberElapsedSeconds(storyStatus == StoryRepo.Status.WRITING, ui.setup.wish)
    val view = StoryView(storyStatus, preview, rejection, waited)
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
                    KidStage(game, ui, friends, speaking, shown, view, Modifier.weight(1f).fillMaxWidth())
                    ParentPanel(game, ui, car, speaking, lumi, view, subs, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val panelWidth = if (ui.phase == Phase.SETUP) 680.dp else 500.dp
                    ParentPanel(game, ui, car, speaking, lumi, view, subs, Modifier.width(panelWidth).fillMaxHeight())
                    KidStage(game, ui, friends, speaking, shown, view, Modifier.weight(1f).fillMaxHeight())
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
        FriendFace(ui.setup.friend.picture, ui.setup.friend.label, 64.dp, ringWidth = 0.dp)
        KidText("차근차근 탐험단", 42.sp, color = Clay.Cream)
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
    subs: Subs,
    modifier: Modifier,
) {
    when (ui.phase) {
        Phase.SETUP -> {
            // the episode the car was switched off in comes first: the parent goes on with it or starts anew
            val saved by game.saved.collectAsState()
            val resume = saved
            if (resume != null) ResumePanel(game, resume, car, modifier) else SetupPanel(game, ui, car, view, subs, modifier)
        }
        Phase.INTRO -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle(ui.story?.title ?: "오늘의 이야기")
            BodyText(if (ui.story != null) "이야기는 AI가 만들었어요. 정답은 규칙 엔진이 정해요" else "정답은 규칙 엔진이 정해요")
            Notice(Lines.AUTO_OFF_PARENT)
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
        Phase.PLAYING -> PlayingPanel(game, ui, car, speaking, lumi, subs, modifier)
        Phase.SOLVED -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle("정답이에요!")
            BodyText("아이를 듬뿍 칭찬해 주세요. 별 하나를 모았어요")
            Middle { }
            SafetyLine(ui, car)
            ClayButton(
                if (ui.index + 1 < ui.puzzles.size) "다음 퍼즐" else "보물 열기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 104.dp,
            ) { game.next() }
            ClayButton("멈춤", ClayStyle.CORAL, Modifier.fillMaxWidth(), height = 104.dp, icon = ClayIcon.PAUSE) { game.hold() }
        }
        Phase.TREASURE -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle("탐험 배지를 받았어요")
            BodyText("끝까지 해낸 아이를 꼭 안아 칭찬해 주세요")
            Middle {
                ui.heartsNow?.let { CloserCard(ui.setup.friend, it) }
            }
            ClayButton("처음 설정으로", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp) { game.finish() }
        }
        Phase.RESTORE -> PanelCard(modifier) {
            Badge("안전 지킴이 · 부모님")
            PanelTitle("처음 설정으로")
            // live: as the car reports each value back, its line disappears
            val changes = ui.snapshot?.changes(car).orEmpty()
            Middle {
                when {
                    ui.restoring -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Clay.Mint, strokeWidth = 4.dp, trackColor = Clay.MintSoft, strokeCap = StrokeCap.Round)
                        BodyText("게임 전에 저장해 둔 설정으로 차를 되돌리는 중이에요", size = 21.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
                    }
                    changes.isEmpty() -> CheckLabelText(if (ui.restoreTried) "공조와 독서등을 게임 전 설정으로 모두 되돌렸어요" else "모두 처음 설정과 같아요")
                    else -> BodyText(
                        if (ui.restoreTried) "아래는 되돌리지 못했어요. 직접 돌려 주세요"
                        else "게임 전에 저장해 둔 설정과 달라진 곳이에요",
                    )
                }
                if (changes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { changes.forEach { RestoreRow(it) } }
                ui.restoreNote?.let { Notice(it) }
                if (changes.isNotEmpty() && !ui.restoring) {
                    ClayButton("다시 되돌리기", ClayStyle.GHOST, Modifier.fillMaxWidth()) { game.restore() }
                }
            }
            ReportRow(game, ui)
            ClayButton(if (changes.isEmpty()) "처음 화면으로" else "다 돌렸어요", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp, enabled = !ui.restoring) {
                game.reset()
            }
        }
    }
}

@Composable
private fun SetupPanel(game: Game, ui: GameUi, car: CarState, view: StoryView, subs: Subs, modifier: Modifier) {
    val blockers = Safety.startBlockers(car)
    // with no puzzle this car can play, game.begin() would return without a word
    val playable = game.playable(car)
    PanelCard(modifier) {
        PanelTitle("시작하기 전에 골라 주세요", 48)
        Middle {
            WishBox(ui.setup.wish, view) { wish -> game.setup { it.copy(wish = wish) } }
            FieldLabel("함께할 친구")
            ChosenFriend(ui.setup.friend)
            FieldLabel("아이 나이")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (age in Age.entries) ChoiceTile(age.years, age.label, ui.setup.ages.firstOrNull() == age, Modifier.weight(1f)) {
                    game.setup { it.copy(ages = listOf(age)) }
                }
            }
            FieldLabel("기다릴 시간")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (m in listOf(5, 10, 15)) ChoiceTile("${m}분", "퍼즐 ${Planner.puzzleCount(m)}개", ui.setup.minutes == m, Modifier.weight(1f)) {
                    game.setup { it.copy(minutes = m) }
                }
            }
            SubtitleToggle(subs)
            Spacer(Modifier.height(4.dp))
            blockers.forEach { Notice(it.message) }
            if (blockers.isEmpty() && !playable) Notice("차량 장치(공조, 독서등)를 아직 읽지 못했어요. 잠시 기다려 주세요")
            BodyText("아이가 버튼을 찾아 가리키면, 안전 지킴이가 확인하고 눌러 줘요", size = 19.sp)
        }
        // the one button that starts the game; its words say which story it starts
        val start = when (view.status) {
            StoryRepo.Status.READY -> "이 이야기로 시작하기"
            StoryRepo.Status.WRITING -> "기다리지 않고 기본 이야기로 시작"
            else -> "기본 이야기로 시작하기"
        }
        ClayButton(start, ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp, enabled = blockers.isEmpty() && playable) {
            game.begin()
        }
    }
}

/** 이어 하기 카드: the episode the car was switched off in (or rested after 20 minutes), offered before a new one. */
@Composable
private fun ResumePanel(game: Game, saved: Progress, car: CarState, modifier: Modifier) {
    val blockers = Safety.startBlockers(car)
    // the rest after 20 minutes counts down while the card is open
    val restLeft by produceState(game.restLeftMs(), saved) {
        while (true) {
            value = game.restLeftMs()
            delay(1_000)
        }
    }
    // restLeft is read first, never skipped: the card then redraws when the rest ends and asks again whether it may go on
    // (read after a false canContinue, only the notices below would redraw and the button would stay off)
    val ready = restLeft == 0L && game.canContinue(car)
    val where = if (saved.next < saved.puzzles.size) "${saved.next + 1}번째 퍼즐부터" else "보물만 남았어요"
    PanelCard(modifier) {
        Badge("안전 지킴이 · 부모님")
        PanelTitle("하다 만 탐험이 있어요", 48)
        Middle {
            FieldLabel("이어 할 이야기")
            BodyText("‘${saved.story?.title ?: "기본 이야기"}’", size = 28.sp, weight = FontWeight.Bold)
            BodyText("함께한 친구 ${saved.setup.friend.label} · $where · 별 ${saved.stars}개", size = 22.sp)
            Spacer(Modifier.height(4.dp))
            // the car may have put AUTO back on while it was off
            Notice(Lines.AUTO_OFF_PARENT)
            blockers.forEach { Notice(it.message) }
            if (restLeft > 0) Notice("${(restLeft + 59_999) / 60_000}분 뒤에 이어 할 수 있어요")
            if (blockers.isEmpty() && restLeft == 0L && !ready) Notice("차량 장치(공조, 독서등)를 아직 읽지 못했어요. 잠시 기다려 주세요")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ClayButton("이어 하기", ClayStyle.SUN, Modifier.weight(1f), height = 112.dp, enabled = ready) { game.continueSaved() }
            ClayButton("새로 시작", ClayStyle.GHOST, Modifier.width(220.dp), height = 112.dp) { game.startOver() }
        }
    }
}

/** What the story server is doing for the setup screen: its status, the story it has ready, why it refused a wish. */
private data class StoryView(val status: StoryRepo.Status, val preview: Story?, val rejection: List<String>, val waited: Int = 0)

/** Whole seconds since [running] turned on (and since [key] last changed while it was on); 0 while it is off. */
@Composable
private fun rememberElapsedSeconds(running: Boolean, key: Any?): Int {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(running, key) {
        seconds = 0
        if (!running) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            seconds = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
        }
    }
    return seconds
}

/**
 * What Lumi is doing while a wish is made. The server does not report its steps, so this follows the measured
 * times: the words take about 10 s, the pictures about 15 s more.
 */
private fun wishStep(waited: Int) = when {
    waited < 10 -> "이야기를 쓰고 있어요"
    waited < 25 -> "친구들 그림을 그리고 있어요"
    else -> "거의 다 됐어요!"
}

// kinds that suit any place the family types and need no character beyond the friend book, and carry any lesson
private val STORY_KINDS = listOf("보물찾기", "구조대", "탐정 놀이", "고장 수리", "축제 준비", "발명 대회")
private const val PLACE_MAX = 20
private val WISH_FORM = Regex("^(.+)에서 펼쳐지는 (.+) 이야기$")

private fun wishOf(place: String, kind: String) = "${place.trim()}에서 펼쳐지는 $kind 이야기"

/** The place and the kind of story a wish was made from, so the box shows them again. */
private fun wishParts(wish: String): Pair<String, String?> =
    WISH_FORM.matchEntire(wish)?.let { it.groupValues[1] to it.groupValues[2].takeIf { k -> k in STORY_KINDS } } ?: ("" to null)

/**
 * 오늘의 이야기 소원: the family types a place and picks a kind of story; the story server writes it around today's
 * puzzles and draws its world. The puzzles and their answers stay the rule engine's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WishBox(wish: String, view: StoryView, onWish: (String) -> Unit) {
    var place by remember(wish) { mutableStateOf(wishParts(wish).first) }
    var kind by remember(wish) { mutableStateOf(wishParts(wish).second) }
    val draft = kind?.takeIf { place.isNotBlank() }?.let { wishOf(place, it) }
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
        BodyText("장소를 적고 이야기 종류를 골라 주세요. AI가 오늘 퍼즐에 맞춰 이야기와 그림을 새로 만들어요", size = 19.sp)
        FieldLabel("장소")
        val field = RoundedCornerShape(20.dp)
        BasicTextField(
            value = place,
            onValueChange = { place = it.take(PLACE_MAX) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 24.sp, color = Clay.Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (draft != null) onWish(draft) }),
            modifier = Modifier.fillMaxWidth().height(80.dp).clip(field).background(Clay.Cream).border(2.dp, Clay.LineSoft, field),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    if (place.isEmpty()) BodyText("예: 공룡 섬", size = 22.sp, color = Clay.Muted)
                    inner()
                }
            },
        )
        FieldLabel("이야기 종류")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            STORY_KINDS.forEach { k -> WishChip(k, k == kind) { kind = k } }
        }
        ClayButton("소원 빌기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 80.dp, enabled = draft != null && draft != wish) {
            draft?.let(onWish)
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
        StoryRepo.Status.WRITING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(26.dp), color = Clay.Mint, strokeWidth = 4.dp, trackColor = Clay.MintSoft, strokeCap = StrokeCap.Round)
            BodyText(
                if (wish.isBlank()) "AI 이야기를 만드는 중이에요. 기다리지 않고 시작해도 돼요"
                else "${wishStep(view.waited)} · ${view.waited}초",
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

/** The friend picked in the child's friend book, on the parent's setup panel. */
@Composable
private fun ChosenFriend(friend: Friend) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White).border(3.dp, Clay.Line, RoundedCornerShape(28.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FriendFace(friend.picture, friend.label, 72.dp, ring = Clay.Sun, ringWidth = 4.dp)
        Column(Modifier.weight(1f)) {
            KidText(friend.label, 32.sp)
            BodyText("아이 화면의 친구 도감에서 바꿀 수 있어요", size = 19.sp)
        }
    }
}

/** The episode is over: the friend that went through it is one heart closer. */
@Composable
private fun CloserCard(friend: Friend, hearts: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FriendFace(friend.picture, friend.label, 88.dp, ring = Clay.Coral, ringWidth = 4.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BodyText("${Lines.withAnd(friend.label)} 더 친해졌어요", size = 19.sp, color = Clay.Sub, weight = FontWeight.Medium)
            Hearts(hearts, Friend.MAX_HEARTS, 28.dp)
            if (hearts >= Friend.MAX_HEARTS && !friend.rare) {
                BodyText("하트를 다 채웠어요! 친구 도감에서 새 친구를 만날 수 있어요", size = 17.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
            }
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
private fun PlayingPanel(game: Game, ui: GameUi, car: CarState, speaking: Boolean, lumi: Lumi, subs: Subs, modifier: Modifier) {
    val p = ui.puzzle ?: return
    val scene = ui.scene(p)
    val belt = p == Puzzle.BeltMission
    var reveal by remember(ui.index) { mutableStateOf(false) }
    PanelCard(modifier) {
        Badge("안전 지킴이 · 부모님")
        PanelTitle(p.title)
        BodyText(if (belt) "다음 칸으로 가기 전 준비 시간이에요. 아이가 스스로 매도록 기다려 주세요" else "아이가 말한 답과 버튼을 확인하고 눌러 주세요")
        Middle {
            if (belt) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KidText("이미 매고 있다면", 30.sp)
                    BodyText("버클을 손으로 짚고 \"딸깍 확인!\"을 외치게 해 주세요", size = 21.sp)
                }
            } else {
                VoiceBox(speaking, ui.setup.friend) { lumi.repeat() }
                SubtitleToggle(subs)
                if (scene.buttons.isNotEmpty()) {
                    FieldLabel("이 퍼즐에서 쓰는 버튼")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { scene.buttons.take(3).forEach { CarButton(it) } }
                }
                val answer = "정답: ${scene.answer}" + (ui.answerNote?.let { ", $it" } ?: "")
                AnswerReveal(if (reveal) answer else null, Modifier.pointerInput(ui.index) {
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
private fun VoiceBox(speaking: Boolean, friend: Friend, onRepeat: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClayGlyph(ClayIcon.WAVE, if (speaking) Clay.Mint else Clay.Muted, Modifier.size(32.dp))
            BodyText(
                if (speaking) "${Lines.subject(friend.label)} 문제를 말하고 있어요" else "${Lines.of(friend.label)} 말을 다시 들을 수 있어요",
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
        ) {
            // a button without a drawn glyph shows its name large instead of a picture that is not its own
            glyphFor(label)?.let { AssetImage(it, Modifier.size(80.dp), scale = ContentScale.Fit) }
                ?: KidText(label, 24.sp, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        }
        BodyText(label, size = 18.sp, weight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

private fun glyphFor(label: String) = when {
    "풍량" in label -> "hvac_fan"
    "온도" in label -> "hvac_temp"
    "독서등" in label -> "hvac_light"
    "성에" in label -> "hvac_defrost"
    "내기 순환" in label -> "hvac_recirc"
    else -> null
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

@Composable
private fun KidStage(game: Game, ui: GameUi, book: Book, speaking: Boolean, subtitle: String?, view: StoryView, modifier: Modifier) {
    val preview = view.preview
    val buddy = ui.setup.friend
    val p = ui.puzzle
    val story = ui.story
    val theme = story?.picture("cover") ?: story?.themePicture ?: "theme_star_train"
    val saved by game.saved.collectAsState()
    val resume = saved
    val (bubble, picture) = when (ui.phase) {
        Phase.SETUP -> when {
            // the episode to go on with, while the parent's card offers it
            resume != null -> (resume.story?.title ?: "하던 탐험을 이어 할까?") to
                (resume.story?.picture("cover") ?: resume.story?.themePicture ?: "theme_star_train")
            preview != null -> preview.title to (preview.picture("cover") ?: preview.themePicture)
            // the friend picked waits in the explorer team's clubhouse
            ui.setup.wish.isNotBlank() -> "‘${ui.setup.wish}’ 좋아!" to "home_${buddy.name.lowercase()}"
            else -> "어디로 탐험 갈까?" to "home_${buddy.name.lowercase()}"
        }
        Phase.INTRO -> (ui.story?.title ?: "별빛 기차가 고장 났대!") to theme
        Phase.PLAYING -> p?.let { ui.scene(it).let { s -> s.bubble to (s.pictures.firstOrNull() ?: theme) } } ?: ("" to theme)
        Phase.SOLVED -> "정답! 별 하나 획득!" to (story?.picture("cover") ?: successPicture(p))
        Phase.TREASURE -> "탐험 배지 획득!" to "ending_${buddy.name.lowercase()}"
        Phase.RESTORE -> when {
            ui.restoring -> "차를 처음처럼 정리하는 중!"
            ui.restoreTried -> "정리 끝! 다음에 또 만나"
            else -> "처음 설정으로 돌려 놓자"
        } to theme
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
            Box(Modifier.shadow(16.dp, CircleShape)) { FriendFace(buddy.picture, buddy.label, 156.dp, ringWidth = 6.dp) }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SpeechBubble(bubble)
                if (speaking) SpeakingTag()
            }
        }
        when (ui.phase) {
            Phase.SETUP -> FriendShelf(book, buddy, onPick = game::pick, onInvite = game::invite)
            Phase.INTRO -> GuestPop("오늘 함께할 친구", buddy.label, buddy.picture, buddy.message.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: buddy.symbol)
            Phase.PLAYING, Phase.SOLVED -> TagAlong(buddy.label, buddy.picture)
            Phase.TREASURE -> ui.heartsNow?.let { CloserPop(buddy, it) }
            Phase.RESTORE -> Unit
        }
        val quiz = p as? Puzzle.Quiz
        val card = if (ui.phase == Phase.PLAYING && quiz != null) ui.scene(quiz).question?.takeIf { Lines.card(it) != null } else null
        card?.let { QuestionCard(it) }
        // what the friend is saying, a piece at a time, above the question card when there is one
        subtitle?.let { SubtitleBar(it, aboveCard = card != null) }
        if (ui.phase == Phase.SETUP && view.status == StoryRepo.Status.WRITING && ui.setup.wish.isNotBlank()) {
            WishingOverlay(ui.setup.wish, buddy, view.waited)
        }
        ui.invited?.let { InvitePop(it, game::closeInvite) }
    }
}

/** 아이 화면 자막 switch, and its setter (remembered on the device). */
private data class Subs(val on: Boolean, val set: (Boolean) -> Unit)

/**
 * The child's subtitles: what the friend is saying now, one or two lines, in the round kid font, dark on a light see-
 * through cream at the bottom of the stage, clear of the question card and the friend in the corner.
 */
@Composable
private fun BoxScope.SubtitleBar(text: String, aboveCard: Boolean) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        Modifier.align(Alignment.BottomCenter)
            .padding(start = 40.dp, end = 220.dp, bottom = if (aboveCard) 232.dp else 40.dp)
            .widthIn(max = 1240.dp)
            .shadow(12.dp, shape).clip(shape).background(Clay.Cream.copy(alpha = 0.9f))
            .padding(horizontal = 36.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(text, label = "subtitle") { piece ->
            KidText(piece, 46.sp, color = Clay.Ink, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** The parent's switch for the child's subtitles. A line with no sound is shown even when it is off. */
@Composable
private fun SubtitleToggle(subs: Subs) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(3.dp, Clay.Line, shape)
            .clickable { subs.set(!subs.on) }.padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KidText("아이 화면 자막", 28.sp)
            BodyText("친구가 하는 말을 글자로도 보여 줘요", size = 18.sp)
        }
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(if (subs.on) Clay.Mint else Clay.Line)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) { KidText(if (subs.on) "켜짐" else "꺼짐", 24.sp, color = if (subs.on) Color.White else Clay.Sub) }
    }
}

/** While a wish is being made: the stage dims and the friend waits inside a spinning ring, with what is happening. */
@Composable
private fun BoxScope.WishingOverlay(wish: String, buddy: Friend, waited: Int) {
    Box(Modifier.matchParentSize().background(Clay.NightBottom.copy(alpha = 0.72f)))
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                Modifier.fillMaxSize(), color = Clay.Sun, strokeWidth = 10.dp,
                trackColor = Color.White.copy(alpha = 0.15f), strokeCap = StrokeCap.Round,
            )
            FriendFace(buddy.picture, buddy.label, 176.dp, ringWidth = 0.dp)
        }
        KidText(wishStep(waited), 48.sp, color = Clay.Cream, textAlign = TextAlign.Center)
        BodyText("‘$wish’ · ${waited}초 · 보통 30초쯤 걸려요", size = 22.sp, color = Clay.Cream.copy(alpha = 0.8f), textAlign = TextAlign.Center)
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

/**
 * 친구 도감: the nine friends swiped sideways. A tap on an open friend picks it for the next episode; a rare friend
 * not open yet is a grey silhouette. A base friend at full hearts asks to go and meet a new friend.
 */
@Composable
private fun BoxScope.FriendShelf(book: Book, picked: Friend, onPick: (Friend) -> Unit, onInvite: (Friend) -> Unit) {
    Column(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 32.dp).fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(36.dp)).clip(RoundedCornerShape(36.dp)).background(Clay.Cream.copy(alpha = 0.95f))
            .padding(top = 20.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KidText("친구 도감 ${book.open.size}명", 34.sp)
            BodyText("함께할 친구를 골라 줘. 한 판을 끝내면 하트가 하나 생겨", size = 19.sp, color = Clay.Muted, weight = FontWeight.Medium)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // the bubble's room is kept on every friend while one has it, so the faces stay in one line
            val asking = Friend.entries.any(book::canInvite)
            items(Friend.entries) { f ->
                val open = book.isOpen(f)
                PalItem(
                    // a friend not met yet shows only its outline, so the child can guess who is coming
                    name = f.label, picture = if (open) f.picture else "${f.picture}_silhouette",
                    ring = when { f == picked -> Clay.Sun; open -> Clay.Mint; else -> Color(0xFFD9CBB1) },
                    dim = !open, hidden = !open, hearts = book.hearts(f).takeIf { open },
                    label = when { !open -> "누구일까?"; f == picked -> "함께해요"; else -> null },
                    invite = book.canInvite(f), bubbleRoom = asking,
                    onInvite = { onInvite(f) },
                    onClick = if (open) ({ onPick(f) }) else null,
                )
            }
        }
    }
}

@Composable
private fun PalItem(
    name: String, picture: String?, ring: Color, dim: Boolean = false, hidden: Boolean = false,
    hearts: Int? = null, label: String? = null, invite: Boolean = false, bubbleRoom: Boolean = false,
    onInvite: () -> Unit = {}, onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.width(140.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (bubbleRoom) Box(Modifier.height(64.dp), contentAlignment = Alignment.BottomCenter) { if (invite) InviteBubble(onInvite) }
        // a silhouette is shown as it is; only a friend with no picture at all gets the grey "?"
        val outline = hidden && picture?.endsWith("_silhouette") == true
        FriendFace(
            picture, name, 112.dp, ring = ring, ringWidth = if (dim) 3.dp else 5.dp,
            dim = dim && !outline, hidden = hidden && !outline,
        )
        if (hidden) BodyText(label ?: "", size = 18.sp, color = Clay.Muted, weight = FontWeight.Medium, textAlign = TextAlign.Center)
        else KidText(name, 22.sp, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.height(14.dp), contentAlignment = Alignment.Center) { if (hearts != null) Hearts(hearts, Friend.MAX_HEARTS, 14.dp) }
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (label != null && !hidden) BodyText(label, size = 16.sp, color = Clay.SunLip, weight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

/** "새로운 친구를 만나러 가자": over a base friend at full hearts; tapping it opens the next rare friend. */
@Composable
private fun InviteBubble(onClick: () -> Unit) {
    KidText(
        "새로운 친구를\n만나러 가자!", 17.sp, color = Clay.Ink, textAlign = TextAlign.Center, maxLines = 2,
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Clay.Sun)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A rare friend just opened: big over the stage until a touch. */
@Composable
private fun BoxScope.InvitePop(friend: Friend, onClose: () -> Unit) {
    Box(
        Modifier.matchParentSize().background(Clay.NightBottom.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 640.dp).shadow(32.dp, RoundedCornerShape(44.dp))
                .clip(RoundedCornerShape(44.dp)).background(Clay.Cream).padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KidText("새 친구!", 40.sp, color = Clay.MintLip)
            FriendFace(friend.picture, friend.label, 260.dp, ring = Clay.Sun, ringWidth = 8.dp)
            KidText(friend.label, 56.sp, textAlign = TextAlign.Center)
            BodyText("${friend.animal} · ${friend.symbol}", size = 24.sp, color = Clay.Ink, textAlign = TextAlign.Center)
            BodyText("아무 곳이나 누르면 닫혀요", size = 17.sp, color = Clay.Muted)
        }
    }
}

/** The friend going through the episode, at the start ("오늘 함께할 친구"). */
@Composable
private fun BoxScope.GuestPop(title: String, name: String, picture: String?, line: String) {
    val shape = RoundedCornerShape(40.dp)
    Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 40.dp, end = 40.dp)) {
        Box(Modifier.matchParentSize().padding(top = 10.dp).clip(shape).background(Clay.Sun))
        Row(
            Modifier.padding(bottom = 10.dp).shadow(24.dp, shape).clip(shape).background(Clay.Cream)
                .padding(start = 24.dp, end = 40.dp, top = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            FriendFace(picture, name, 170.dp, ring = Clay.Sun, ringWidth = 6.dp)
            Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KidText(title, 34.sp, color = Clay.SunLip)
                KidText(name, 60.sp, maxLines = 1)
                if (line.isNotBlank()) BodyText(line, size = 22.sp, color = Clay.Sub, weight = FontWeight.Medium)
            }
        }
    }
}

/** The episode is over: the friend that went through it is one heart closer. */
@Composable
private fun BoxScope.CloserPop(friend: Friend, hearts: Int) {
    val shape = RoundedCornerShape(40.dp)
    Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 40.dp)) {
        Box(Modifier.matchParentSize().padding(top = 10.dp).clip(shape).background(Clay.Coral))
        Row(
            Modifier.padding(bottom = 10.dp).shadow(24.dp, shape).clip(shape).background(Clay.Cream)
                .padding(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            FriendFace(friend.picture, friend.label, 140.dp, ring = Clay.Coral, ringWidth = 6.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KidText("${Lines.withAnd(friend.label)} 더 친해졌어!", 40.sp)
                Hearts(hearts, Friend.MAX_HEARTS, 36.dp)
            }
        }
    }
}

/** The question in big type over the stage ("3 + 2 = ?"); a situation has none, the child hears it. */
@Composable
private fun BoxScope.QuestionCard(q: Question) {
    val line = Lines.card(q) ?: return
    val shape = RoundedCornerShape(40.dp)
    Column(
        Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 40.dp, end = 240.dp)
            .shadow(16.dp, shape).clip(shape).background(Clay.Cream).padding(horizontal = 40.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KidText(line, 72.sp, maxLines = 1)
    }
}

/** The friend going through the episode, in the corner while the puzzles go on. */
@Composable
private fun BoxScope.TagAlong(name: String, picture: String?) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.shadow(16.dp, CircleShape)) { FriendFace(picture, name, 128.dp, ring = Clay.Sun, ringWidth = 5.dp) }
        KidText(name, 24.sp, color = Clay.Ink, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Clay.Cream).padding(horizontal = 18.dp, vertical = 6.dp))
    }
}

private fun successPicture(p: Puzzle?) = Lines.successPicture(p)

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
            else FriendFace(ui.setup.friend.picture, ui.setup.friend.label, 260.dp, ring = Clay.Line, ringWidth = 6.dp)
            KidText(if (reason == null) "잠깐 멈췄어요" else "잠깐 쉬어요!", 60.sp)
            BodyText(reason?.message ?: "준비되면 이어 하기를 눌러 주세요", size = 26.sp, weight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (reason == StopReason.TIME_UP || ui.restUntil != null) {
                // the episode is kept: after the rest the setup screen offers 이어 하기. Once the 20 minutes are up this
                // stays the only way out, also while an open door or the gear is what the pause shows
                ClayButton("저장하고 쉬기", ClayStyle.SUN, Modifier.fillMaxWidth()) { game.restAndSave() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ClayButton("이어 하기", ClayStyle.SUN, Modifier.weight(1f), enabled = reason == null) { game.resume() }
                    ClayButton("끝내기", ClayStyle.GHOST, Modifier.width(300.dp)) { game.finish() }
                }
            }
        }
    }
}
