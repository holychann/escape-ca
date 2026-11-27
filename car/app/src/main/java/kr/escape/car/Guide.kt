package kr.escape.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kr.escape.car.engine.Book
import kr.escape.car.story.Progress
import kr.escape.car.ui.BodyText
import kr.escape.car.ui.Clay
import kr.escape.car.ui.ClayButton
import kr.escape.car.ui.ClayStyle
import kr.escape.car.ui.KidText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The parts of the setup screen and of the episode's first screen the first-run guide points at. */
enum class Spot { STATUS, FRIENDS, AGE, TIME, WISH, WISH_BUTTON, PUZZLES, SAFETY, START }

/** Where the guide's card goes next to the part it points at. */
enum class Side { RIGHT, LEFT, BELOW, ABOVE }

/** One page of the guide: a title, at most one short line under it, and the parts of the screen left bright. */
data class GuideStep(
    val title: String,
    val line: String? = null,
    val spots: List<Spot>,
    val side: Side = Side.RIGHT,
)

/**
 * 처음 사용 가이드: the first time the app is opened, before anything was played, the screen goes dark and the guide
 * walks the parent through it one part at a time, the part it talks about left bright. It is split between the setup
 * screen (what to choose) and the episode's first screen (how the play goes), and says only what neither screen says
 * already: 공조 AUTO, for one, is left to that screen's own notice.
 */
object Guide {
    /** Kept in the "screen" preferences once the setup screen's part was closed. */
    const val SEEN = "guide_seen"
    /** The same for the episode's first screen. 건너뛰기 on the setup screen sets both. */
    const val SEEN_INTRO = "guide_seen_intro"

    val SETUP = listOf(
        GuideStep("차를 꼭 정차하고 진행해 주세요", "기어 P, 문 닫기, 운전석 착석, 공조 전원 켜기", listOf(Spot.STATUS), Side.BELOW),
        GuideStep("친구는 아이가 골라요", "하트 5개를 채우면 새로운 친구가 열려요", listOf(Spot.FRIENDS), Side.ABOVE),
        GuideStep("나이와 시간을 골라요", "난이도와 퍼즐 수가 정해져요", listOf(Spot.AGE, Spot.TIME)),
        GuideStep("소원을 빌면 시작해요", "장소와 이야기 종류를 고르고 소원 빌기를 눌러요", listOf(Spot.WISH, Spot.WISH_BUTTON)),
    )

    val INTRO = listOf(
        // who presses the buttons is said on the setup screen itself, under its title
        GuideStep("오늘 풀 퍼즐이에요", "답을 넣고 셋을 셀 동안 그대로 두면 정답이에요", listOf(Spot.PUZZLES)),
        GuideStep("기어나 문이 바뀌면 멈춰요", "놀이는 20분까지 할 수 있어요", listOf(Spot.SAFETY)),
        GuideStep("이야기가 오면 탐험 시작", "기다리는 동안 아이 벨트를 봐 주세요", listOf(Spot.START)),
    )

    /** Nothing was played on this car yet: no heart, no rare friend open, no episode to go on with. */
    fun fresh(book: Book, saved: Progress?): Boolean = book.hearts.isEmpty() && book.opened.isEmpty() && saved == null

    /**
     * Where the card of [card] size goes on a [screen] for a bright [hole]: on the [prefer] side when it fits there,
     * else on the first side that fits, kept [margin] inside the screen and [gap] away from the hole; in the middle
     * when there is no hole or no side has room.
     */
    fun place(hole: Rect?, card: Size, screen: Size, gap: Float, margin: Float, prefer: Side): Offset {
        val middle = Offset((screen.width - card.width) / 2, (screen.height - card.height) / 2)
        if (hole == null) return middle
        fun x(at: Float) = at.coerceIn(margin, max(margin, screen.width - margin - card.width))
        fun y(at: Float) = at.coerceIn(margin, max(margin, screen.height - margin - card.height))
        fun on(side: Side): Offset? = when (side) {
            Side.RIGHT -> (hole.right + gap).takeIf { it + card.width <= screen.width - margin }
                ?.let { Offset(it, y(hole.center.y - card.height / 2)) }
            Side.LEFT -> (hole.left - gap - card.width).takeIf { it >= margin }
                ?.let { Offset(it, y(hole.center.y - card.height / 2)) }
            Side.BELOW -> (hole.bottom + gap).takeIf { it + card.height <= screen.height - margin }
                ?.let { Offset(x(hole.center.x - card.width / 2), it) }
            Side.ABOVE -> (hole.top - gap - card.height).takeIf { it >= margin }
                ?.let { Offset(x(hole.center.x - card.width / 2), it) }
        }
        return (listOf(prefer) + Side.entries).firstNotNullOfOrNull(::on) ?: middle
    }

    /** The smallest rectangle around all of [rects], or null when there is none. */
    fun around(rects: List<Rect>): Rect? = rects.reduceOrNull { a, b ->
        Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }
}

/** Where each part of the screen the guide points at was last laid out, in root coordinates. */
class GuideSpots {
    val rects = mutableStateMapOf<Spot, Rect>()
}

/** The spots being collected while the guide is open; null the rest of the time, when nothing is collected. */
val LocalGuideSpots = staticCompositionLocalOf<GuideSpots?> { null }

/** Marks this part of the screen as [spot], for the guide to leave bright when it talks about it. */
fun Modifier.guideSpot(spot: Spot): Modifier = composed {
    val spots = LocalGuideSpots.current
    if (spots == null) Modifier else Modifier.onGloballyPositioned { spots.rects[spot] = it.boundsInRoot() }
}

/**
 * The guide over the whole screen: everything dark but the part being explained, ringed in sun, and the card next to
 * it. Every touch outside the card is swallowed, so nothing behind it is pressed by mistake.
 */
@Composable
fun GuideOverlay(steps: List<GuideStep>, spots: GuideSpots, onClose: (skipped: Boolean) -> Unit) {
    var index by remember(steps) { mutableIntStateOf(0) }
    val step = steps[index]
    val last = index == steps.lastIndex
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(Size.Zero) }
    val pad = with(density) { 14.dp.toPx() }
    val hole = Guide.around(step.spots.mapNotNull { spots.rects[it] })
        ?.translate(-origin)?.inflate(pad)?.intersect(Rect(Offset.Zero, screen))
        ?.takeIf { it.width > 0f && it.height > 0f }
    // the hole and the card move to the next part at once: sliding them left a trail across the screen
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot(); screen = it.size.toSize() }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Clay.NightBottom.copy(alpha = 0.82f))
            if (hole != null) {
                val corner = CornerRadius(28.dp.toPx())
                drawRoundRect(Color.Black, hole.topLeft, hole.size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(Clay.Sun, hole.topLeft, hole.size, corner, style = Stroke(5.dp.toPx()))
            }
        }
        val gap = with(density) { 28.dp.toPx() }
        val margin = with(density) { 24.dp.toPx() }
        Layout(
            content = {
                GuideCard(
                    step, index, steps.size,
                    onSkip = { onClose(true) },
                    onBack = { index-- },
                    onNext = { if (last) onClose(false) else index++ },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            val screenSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val at = Guide.place(hole, Size(card.width.toFloat(), card.height.toFloat()), screenSize, gap, margin, step.side)
            layout(constraints.maxWidth, constraints.maxHeight) { card.place(at.x.roundToInt(), at.y.roundToInt()) }
        }
    }
}

@Composable
private fun GuideCard(step: GuideStep, index: Int, count: Int, onSkip: () -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val shape = RoundedCornerShape(40.dp)
    val last = index == count - 1
    Column(
        Modifier.width(540.dp).shadow(32.dp, shape).clip(shape).background(Clay.Cream)
            .padding(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepDots(index, count)
            Spacer(Modifier.weight(1f))
            if (!last) Text(
                "건너뛰기", fontSize = 20.sp, color = Clay.Muted, fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onSkip).padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
        KidText(step.title, 38.sp)
        step.line?.let { BodyText(it, size = 23.sp, color = Clay.Sub) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (index > 0) ClayButton("이전", ClayStyle.GHOST, Modifier.width(170.dp), height = 80.dp, onClick = onBack)
            ClayButton(if (last) "알겠어요" else "다음", ClayStyle.SUN, Modifier.weight(1f), height = 80.dp, onClick = onNext)
        }
    }
}

/** Where the guide is: a sun dot for the page shown, small cream dots for the rest. */
@Composable
private fun StepDots(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier.size(if (i == index) 16.dp else 12.dp).clip(CircleShape)
                    .background(if (i == index) Clay.Sun else Clay.Line),
            )
        }
    }
}
