package kr.escape.car

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kr.escape.car.ui.AssetImage
import kr.escape.car.ui.BodyText
import kr.escape.car.ui.Clay
import kr.escape.car.ui.ClayButton
import kr.escape.car.ui.ClayGlyph
import kr.escape.car.ui.ClayIcon
import kr.escape.car.ui.ClayStyle
import kr.escape.car.ui.KidText

/** What the parent agrees to once, before anything else; each with a short summary under 보기. */
object Terms {
    /** Set once the parent agreed and started; "--ez terms true" asks again (for a demo). */
    const val AGREED = "terms_agreed"

    /** How long the logo stays on later starts, unless it is touched. */
    const val LOGO_MS = 1500L

    data class Item(val title: String, val lines: List<String>)

    val ITEMS = listOf(
        Item(
            "서비스 이용약관",
            listOf(
                "차근차근 탐험단은 주차한 차 안에서 아이와 보호자가 차의 장치로 퍼즐을 푸는 놀이예요.",
                "이야기, 그림, 친구 목소리는 AI가 만들어요. 어색한 내용이 나오면 끝내기로 언제든 그만둘 수 있어요.",
                "퍼즐을 풀며 바꾼 공조와 실내등은 놀이가 끝나면 처음대로 되돌려요.",
            ),
        ),
        Item(
            "개인정보 수집, 이용",
            listOf(
                "이야기를 만들 때 아이 나이대, 고른 친구, 소원(장소와 이야기 종류)을 이야기 서버로 보내요.",
                "아이 이름, 사는 곳, 얼굴, 목소리는 받지 않아요.",
                "만든 이야기와 그림은 서비스를 고치는 데 쓰려고 서버에 보관해요. 진행 기록과 친구 하트는 이 차에만 저장돼요.",
            ),
        ),
        Item(
            "보호자 동의",
            listOf(
                "만 14세 미만 아이가 쓰는 앱이라, 보호자가 내용을 읽고 동의해야 해요.",
                "놀이하는 동안 보호자가 운전석에 앉아 함께해 주세요.",
            ),
        ),
        Item(
            "정차 중에만 쓰기 약속",
            listOf(
                "기어가 P에 있고 차가 멈춰 있을 때만 놀 수 있어요.",
                "차가 움직이거나 문이 열리면 놀이가 멈춰요.",
                "운전하는 동안에는 화면을 보지 말아 주세요.",
            ),
        ),
    )

    /** Every item ticked, as a bit mask. */
    val ALL = (1 shl ITEMS.size) - 1
}

// warm cream, like the friends' clay world
private val BgTop = Color(0xFFFFF6E8)
private val BgBottom = Color(0xFFFDEBD2)

/**
 * The first screen: the logo over the friends, in the middle, and one thing at the bottom. The first time
 * ([askTerms]) that is 시작하기, which brings the terms up from the bottom; later it is a loading mark, and the app
 * opens by itself after a moment (or at a touch).
 */
@Composable
fun Splash(askTerms: Boolean, onStart: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    var ticked by rememberSaveable { mutableIntStateOf(0) }
    if (!askTerms) {
        LaunchedEffect(Unit) {
            delay(Terms.LOGO_MS)
            onStart()
        }
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .then(if (askTerms) Modifier else Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onStart() }),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 44.dp, bottom = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Title(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(28.dp))
            Box(Modifier.width(420.dp).height(108.dp), contentAlignment = Alignment.Center) {
                if (askTerms) ClayButton("시작하기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 100.dp) { asking = true }
                else Loading()
            }
        }
        if (askTerms) {
            TermsSheet(
                open = asking,
                ticked = ticked,
                onTick = { i -> ticked = ticked xor (1 shl i) },
                onAll = { ticked = if (ticked == Terms.ALL) 0 else Terms.ALL },
                onClose = { asking = false },
                onStart = onStart,
            )
        }
    }
}

/** The logo over the friends, the friends a good deal wider, as big as the space allows. */
@Composable
private fun Title(modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // the friends' width; the logo is 0.68 of it, and the friends stand a little in front of its bottom edge
        val wide = minOf(maxHeight / 0.92f, maxWidth * 0.6f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AssetImage("splash_logo", Modifier.width(wide * 0.68f).height(wide * 0.34f), scale = ContentScale.Fit)
            AssetImage(
                "splash_friends", Modifier.zIndex(1f).offset(y = -wide * 0.04f).width(wide).height(wide / 1.66f),
                scale = ContentScale.Fit,
            )
        }
    }
}

/** In 시작하기's place on later starts, while the app gets ready. */
@Composable
private fun Loading() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        CircularProgressIndicator(
            Modifier.size(44.dp), color = Clay.SunLip, strokeWidth = 6.dp, trackColor = Clay.Line, strokeCap = StrokeCap.Round,
        )
        KidText("준비하고 있어요", 32.sp, color = Clay.Sub)
    }
}

/**
 * The terms, up from the bottom over the dimmed screen: 모두 동의해요, each item with its tick and 보기 (its summary opens
 * in place), and 동의하고 시작하기 once all are ticked. A touch above it puts it away.
 */
@Composable
private fun BoxScope.TermsSheet(
    open: Boolean, ticked: Int, onTick: (Int) -> Unit, onAll: () -> Unit, onClose: () -> Unit, onStart: () -> Unit,
) {
    AnimatedVisibility(open, Modifier.matchParentSize(), enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Clay.NightBottom.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    }
    AnimatedVisibility(
        open, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(280)) { it }, exit = slideOutVertically(tween(220)) { it },
    ) {
        var reading by rememberSaveable { mutableIntStateOf(-1) }
        val all = ticked == Terms.ALL
        val shape = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp)
        Column(
            Modifier.width(1100.dp).heightIn(max = 1040.dp).clip(shape).background(Clay.Cream)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 56.dp, end = 56.dp, top = 18.dp, bottom = 36.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(80.dp).height(8.dp).clip(RoundedCornerShape(50)).background(Clay.Line))
            Spacer(Modifier.height(22.dp))
            KidText("시작하기 전에 확인해 주세요", 38.sp)
            Spacer(Modifier.height(4.dp))
            BodyText("보호자가 읽고 체크해 주세요. 모두 필수예요", size = 20.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Clay.CreamDeep)
                    .toggleable(all, role = Role.Checkbox) { onAll() }.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TickBox(all, 44.dp)
                Spacer(Modifier.width(18.dp))
                KidText("모두 동의해요", 32.sp)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Terms.ITEMS.forEachIndexed { i, item ->
                    val on = ticked and (1 shl i) != 0
                    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                .toggleable(on, role = Role.Checkbox) { onTick(i) }.padding(start = 28.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TickBox(on, 36.dp)
                            Spacer(Modifier.width(18.dp))
                            BodyText(item.title, size = 23.sp, color = Clay.Ink, weight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            BodyText("(필수)", size = 19.sp, color = Clay.CoralLip, weight = FontWeight.Medium)
                        }
                        Text(
                            if (reading == i) "접기" else "보기", color = Clay.Sub, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { reading = if (reading == i) -1 else i }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                        )
                    }
                    if (reading == i) {
                        Column(
                            Modifier.padding(start = 82.dp, end = 24.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item.lines.forEach { line ->
                                Row {
                                    Box(Modifier.padding(top = 12.dp).size(8.dp).clip(CircleShape).background(Clay.SunLip))
                                    Spacer(Modifier.width(14.dp))
                                    BodyText(line, size = 21.sp, color = Clay.Ink, balanced = true)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            ClayButton("동의하고 시작하기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 96.dp, enabled = all, onClick = onStart)
        }
    }
}

/** A tick box: mint with a white check when ticked, white with a soft edge when not. */
@Composable
private fun TickBox(on: Boolean, size: Dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        Modifier.size(size).clip(shape).background(if (on) Clay.Mint else Color.White)
            .then(if (on) Modifier else Modifier.border(3.dp, Clay.Line, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) ClayGlyph(ClayIcon.CHECK, Color.White, Modifier.size(size * 0.62f))
    }
}
