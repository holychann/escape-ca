package kr.escape.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.escape.car.engine.Friend
import kr.escape.car.engine.StopReason
import kr.escape.car.guide.GuideText
import kr.escape.car.guide.Rulebook
import kr.escape.car.guide.Rulebook.Block

/*
 * The rulebook in the app: 「놀이 전 약속」 before each episode and [사용법] at any time. Both use the app's own clay
 * look: cream cards with a lip, the Jua kid font, sun/mint/coral buttons and the existing pictures. Every sentence is
 * the rulebook's own (docs/차근차근탐험단_룰북.md); the few labels around them are in GuideText.
 */

// ---------------------------------------------------------------- 「놀이 전 약속」

/** The parent's side: the five checks, what is not used and what is, and the start button once all are checked. */
@Composable
fun PromiseParent(
    promise: Rulebook.Promise,
    checked: List<Boolean>,
    onCheck: (Int) -> Unit,
    skipToday: Boolean,
    onSkipToday: () -> Unit,
    notices: List<String>,
    canGo: Boolean,
    onBack: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier,
) {
    GuideCard(modifier) {
        Badge(GuideText.PROMISE_BADGE)
        KidText(promise.title, 48.sp)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (promise.intro.isNotBlank()) BodyText(md(promise.intro), size = 20.sp)
            BodyText(promise.checkTitle, size = 20.sp, weight = FontWeight.Medium)
            promise.checks.forEachIndexed { i, text -> CheckRow(text, checked.getOrElse(i) { false }) { onCheck(i) } }
            BodyText(promise.notUsedTitle, size = 20.sp, weight = FontWeight.Medium)
            TableCards(promise.notUsed, ClayIcon.FORBID, Clay.CoralLip, kid = false)
            BodyText(promise.usedTitle, size = 20.sp, weight = FontWeight.Medium)
            IconCard(ClayIcon.CHECK, Clay.MintDeep) { BodyText(md(promise.used), size = 20.sp, color = Clay.Ink) }
        }
        ToggleRow(GuideText.PROMISE_SKIP_TODAY, GuideText.PROMISE_SKIP_NOTE, skipToday, onSkipToday)
        notices.forEach { BodyText("• $it", size = 20.sp, color = Clay.CoralLip, weight = FontWeight.Medium) }
        if (checked.any { !it }) BodyText(GuideText.PROMISE_ALL_CHECKED, size = 19.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ClayButton(GuideText.PROMISE_BACK, ClayStyle.GHOST, Modifier.width(200.dp), onClick = onBack)
            ClayButton(GuideText.PROMISE_GO, ClayStyle.SUN, Modifier.weight(1f), height = 104.dp, enabled = canGo, onClick = onGo)
        }
    }
}

/** The child's side: the six promises in big words, what is not touched, the buttons used, and [읽어 줘]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromiseKid(
    promise: Rulebook.Promise,
    reading: Boolean,
    onRead: (List<String>) -> Unit,
    onStop: () -> Unit,
    subtitle: String?,
    modifier: Modifier,
) {
    KidPage(modifier, subtitle) {
        KidText(promise.pledgeTitle, 52.sp)
        ReadButton(reading, onStop) { onRead(listOf(promise.pledgeTitle) + promise.pledges) }
        promise.pledges.forEachIndexed { i, line -> NumberLine(i + 1, line, 38.sp, 64.dp) }
        Spacer16()
        KidText(GuideText.KID_NOT_USED, 36.sp, color = Clay.CoralLip)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            promise.notUsed.rows.forEach { row -> Chip(row.first(), ClayIcon.FORBID, Clay.CoralLip) }
        }
        Spacer16()
        KidText(GuideText.KID_USED, 36.sp, color = Clay.MintDeep)
        HvacRow(88.dp)
    }
}

// ---------------------------------------------------------------- [사용법]

/** The whole rulebook for the parent, a chapter per folding card. */
@Composable
fun GuideParent(book: Rulebook, gamePaused: Boolean, onClose: () -> Unit, modifier: Modifier) {
    GuideCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge(GuideText.GUIDE_BUTTON)
        }
        KidText(book.title, 44.sp)
        if (gamePaused) BodyText(GuideText.GUIDE_PAUSED, size = 19.sp, color = Clay.CoralLip, weight = FontWeight.Medium)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            book.chapters.forEachIndexed { i, c ->
                Fold(c.title, startOpen = i == 0) { c.parts.forEach { PartView(it, kid = false) } }
            }
        }
        ClayButton(GuideText.GUIDE_CLOSE, ClayStyle.SUN, Modifier.fillMaxWidth(), onClick = onClose)
    }
}

/** The child's chapters in big words; the friend reads any part aloud with [읽어 줘]. */
@Composable
fun GuideKid(
    book: Rulebook,
    reading: Boolean,
    onRead: (List<String>) -> Unit,
    onStop: () -> Unit,
    subtitle: String?,
    modifier: Modifier,
) {
    // which part asked to be read, so only its button turns into 그만 읽기
    var asked by remember { mutableStateOf<String?>(null) }
    KidPage(modifier, subtitle) {
        BodyText(GuideText.GUIDE_KID_TITLE, size = 22.sp, color = Clay.Sub, weight = FontWeight.Medium)
        book.childChapters.forEach { c ->
            KidText(c.name, 50.sp)
            c.parts.forEach { part ->
                val key = c.title + "/" + part.title
                part.title?.let { KidText(it, 40.sp, color = Clay.Ink) }
                ReadButton(reading && asked == key, onStop) {
                    asked = key
                    onRead(Rulebook.spoken(part))
                }
                PartView(part.copy(title = null), kid = true)
            }
            Spacer16()
        }
    }
}

/** Over the rulebook while the car is not parked: the same rule as the rest of the app, nothing to read. */
@Composable
fun ParkedOnly(modifier: Modifier) {
    val shape = RoundedCornerShape(48.dp)
    Column(
        modifier.clip(shape).background(Clay.Cream).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
    ) {
        AssetImage("pause_parking", Modifier.widthIn(max = 720.dp).fillMaxWidth().aspectRatio(720f / 420f).clip(RoundedCornerShape(32.dp)))
        KidText(StopReason.DRIVING.message, 40.sp)
        BodyText(GuideText.PARKED_ONLY, size = 22.sp)
    }
}

/** The parent's [사용법] button above their panel. */
@Composable
fun GuideButton(onClick: () -> Unit) {
    ClayButton(GuideText.GUIDE_BUTTON, ClayStyle.GHOST, Modifier.width(230.dp), height = 64.dp, icon = ClayIcon.BOOK, onClick = onClick)
}

// ---------------------------------------------------------------- the pieces

/** The parent panel's card: cream with the same shadow and corners as the app's own panel. */
@Composable
private fun GuideCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(44.dp)
    Column(
        modifier.shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(Clay.Cream).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** The child's page: a cream stage with big words, scrolling, and the subtitles at the bottom while the friend reads. */
@Composable
private fun KidPage(modifier: Modifier, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(48.dp)
    Box(modifier.clip(shape).background(Clay.CreamDeep)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
        subtitle?.let { GuideSubtitle(it) }
    }
}

/** The same subtitle bar as on the child's stage. */
@Composable
private fun BoxScope.GuideSubtitle(text: String) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 40.dp, vertical = 36.dp).widthIn(max = 1240.dp)
            .shadow(12.dp, shape).clip(shape).background(Clay.Cream.copy(alpha = 0.9f)).padding(horizontal = 36.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) { KidText(text, 46.sp, color = Clay.Ink, maxLines = 2) }
}

@Composable
private fun Spacer16() = Box(Modifier.size(16.dp))

/** A chapter that opens and closes, so a long rulebook stays easy to look through. */
@Composable
private fun Fold(title: String, startOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var open by remember(title) { mutableStateOf(startOpen) }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(3.dp, Clay.Line, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KidText(title, 30.sp, modifier = Modifier.weight(1f))
            KidText(
                if (open) GuideText.CLOSE else GuideText.OPEN, 22.sp, color = if (open) Clay.Sub else Clay.Ink,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (open) Clay.CreamDeep else Clay.Sun)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        if (open) Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** One `###` part: its title and its blocks, in the parent's size or the child's. */
@Composable
private fun PartView(part: Rulebook.Part, kid: Boolean) {
    part.title?.let { if (kid) KidText(it, 40.sp) else KidText(it, 26.sp, color = Clay.Sub) }
    part.blocks.forEach { BlockView(it, kid) }
}

@Composable
private fun BlockView(b: Block, kid: Boolean) {
    val size = if (kid) 32.sp else 20.sp
    when (b) {
        is Block.Para -> Words(b.text, size, kid)
        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { b.items.forEach { Words("• $it", size, kid) } }
        is Block.Steps -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            b.items.forEach { step ->
                NumberLine(step.number, step.text, size, if (kid) 52.dp else 36.dp, kid)
                step.more.forEach { Words("• $it", if (kid) 28.sp else 18.sp, kid, Modifier.padding(start = if (kid) 68.dp else 50.dp)) }
            }
        }
        is Block.Checks -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { b.items.forEach { CheckRow(it, null) {} } }
        is Block.Table -> TableCards(b, iconFor(b), if (iconFor(b) == ClayIcon.FORBID) Clay.CoralLip else Clay.MintDeep, kid)
        is Block.Picture -> PictureView(b.caption, kid)
    }
}

/** The table of things not used gets the forbid mark; every other table is a plain card list. */
private fun iconFor(t: Block.Table): ClayIcon? = if (t.header.firstOrNull()?.contains("쓰지 않") == true) ClayIcon.FORBID else null

/** A table as cards: the first cell as the card's title, the other cells under it with their column names. */
@Composable
private fun TableCards(t: Block.Table, icon: ClayIcon?, iconColor: Color, kid: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        t.rows.forEach { row ->
            val card = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KidText(Rulebook.plain(row.first()), if (kid) 34.sp else 24.sp)
                    row.drop(1).forEachIndexed { i, cell ->
                        if (cell.isNotBlank()) Words(cell, if (kid) 28.sp else 19.sp, kid, label = t.header.getOrNull(i + 1)?.takeIf { it.isNotBlank() })
                    }
                }
            }
            if (icon != null) IconCard(icon, iconColor, card) else PlainCard(card)
        }
    }
}

@Composable
private fun PlainCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, Clay.LineSoft, shape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        content()
    }
}

@Composable
private fun IconCard(icon: ClayIcon, color: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, Clay.LineSoft, shape).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ClayGlyph(icon, color, Modifier.size(40.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A check the parent ticks (or, with [checked] null, the rulebook's empty box shown as it is). */
@Composable
private fun CheckRow(text: String, checked: Boolean?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val on = checked == true
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) Clay.MintSoft else Color.White)
            .border(3.dp, if (on) Clay.Mint else Clay.Line, shape)
            .then(if (checked != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Clay.Mint else Clay.Cream)
                .border(3.dp, if (on) Clay.MintLip else Clay.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { if (on) ClayGlyph(ClayIcon.CHECK, Color.White, Modifier.size(28.dp)) }
        BodyText(md(text), size = 21.sp, color = Clay.Ink, weight = FontWeight.Medium)
    }
}

/** 오늘은 다시 안 보기: the same switch look as 아이 화면 자막. */
@Composable
private fun ToggleRow(title: String, note: String, on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(3.dp, Clay.Line, shape)
            .clickable(onClick = onToggle).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KidText(title, 26.sp)
            BodyText(note, size = 17.sp)
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Clay.Mint else Clay.Cream)
                .border(3.dp, if (on) Clay.MintLip else Clay.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { if (on) ClayGlyph(ClayIcon.CHECK, Color.White, Modifier.size(30.dp)) }
    }
}

@Composable
private fun NumberLine(n: Int, text: String, size: TextUnit, dot: Dp, kid: Boolean = true) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(dot).clip(CircleShape).background(Clay.Sun), contentAlignment = Alignment.Center) {
            KidText("$n", (dot.value * 0.5f).sp)
        }
        Words(text, size, kid, Modifier.weight(1f).padding(top = (dot.value * 0.12f).dp))
    }
}

@Composable
private fun Chip(text: String, icon: ClayIcon, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(3.dp, Clay.Line, RoundedCornerShape(50))
            .padding(start = 14.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClayGlyph(icon, color, Modifier.size(34.dp))
        KidText(text, 30.sp)
    }
}

@Composable
private fun ReadButton(reading: Boolean, onStop: () -> Unit, onRead: () -> Unit) {
    if (reading) ClayButton(GuideText.READ_STOP, ClayStyle.CORAL, Modifier.width(300.dp), height = 80.dp, icon = ClayIcon.PAUSE, onClick = onStop)
    else ClayButton(GuideText.READ, ClayStyle.MINT, Modifier.width(300.dp), height = 80.dp, icon = ClayIcon.SPEAKER, onClick = onRead)
}

/** The rulebook's words with its **bold**: the kid font for the child, the body font for the parent. */
@Composable
private fun Words(text: String, size: TextUnit, kid: Boolean, modifier: Modifier = Modifier, label: String? = null) {
    val words = md(if (label != null) "**$label** $text" else text)
    if (kid) Text(words, modifier = modifier, color = Clay.Ink, fontSize = size, fontFamily = Fonts.jua, lineHeight = size * 1.25f)
    else BodyText(words, modifier = modifier, size = size, color = Clay.Ink)
}

/** **bold** marks as bold spans. */
fun md(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p) }
}

// ---------------------------------------------------------------- [그림: …]

/** A [그림: …] place: the app's own picture that shows it, or a small mark where the app has none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureView(caption: String, kid: Boolean) {
    when (val p = pictureFor(caption)) {
        Picture.Friends -> FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Friend.BASE.forEach { FriendFace(it.picture, it.label, if (kid) 120.dp else 80.dp, ring = Clay.Sun, ringWidth = 4.dp) }
        }
        Picture.Buttons -> HvacRow(if (kid) 96.dp else 68.dp)
        is Picture.Asset -> AssetImage(
            p.name, Modifier.widthIn(max = if (kid) 900.dp else 560.dp).fillMaxWidth().aspectRatio(720f / 420f).clip(RoundedCornerShape(28.dp)),
            scale = ContentScale.Crop,
        )
        Picture.Mark -> Box(
            Modifier.size(if (kid) 96.dp else 64.dp).clip(RoundedCornerShape(20.dp)).background(Clay.Cream).border(2.dp, Clay.LineSoft, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { ClayGlyph(ClayIcon.EYE, Clay.Sub, Modifier.size(if (kid) 52.dp else 36.dp)) }
    }
}

/** The car buttons the game uses, as the app draws them on the parent's panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HvacRow(size: Dp) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HVAC.forEach { name ->
            Box(
                Modifier.size(size).clip(RoundedCornerShape(22.dp)).background(Color.White).border(3.dp, Clay.Line, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) { AssetImage(name, Modifier.size(size * 0.76f), scale = ContentScale.Fit) }
        }
    }
}

private val HVAC = listOf("hvac_fan", "hvac_temp", "hvac_light", "hvac_recirc", "hvac_defrost", "hvac_power")

internal sealed interface Picture {
    data object Friends : Picture
    data object Buttons : Picture
    data class Asset(val name: String) : Picture
    data object Mark : Picture
}

/** Which of the app's pictures fits a [그림: …] caption. */
internal fun pictureFor(caption: String): Picture = when {
    "친구" in caption -> Picture.Friends
    "버튼" in caption || "공조" in caption -> Picture.Buttons
    "문제 카드" in caption -> Picture.Asset("scene_wind_face")
    "잠깐 쉬어요" in caption -> Picture.Asset("pause_parking")
    else -> Picture.Mark
}
