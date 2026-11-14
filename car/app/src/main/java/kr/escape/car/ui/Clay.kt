package kr.escape.car.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 차량탈출 v2 "clay night" look (Figma page "시안 v2 · 화면"): a night-navy stage, cream parent cards,
 * thick-lipped toy buttons, Jua for the child's words and the system Korean sans for the parent's.
 */
object Clay {
    val NightTop = Color(0xFF1E2B5E)
    val NightBottom = Color(0xFF0E1736)
    val Cream = Color(0xFFFFF8EC)
    val CreamDeep = Color(0xFFF3E7D1)
    val Line = Color(0xFFE6D9C2)
    val LineSoft = Color(0xFFEADFCB)
    val Ink = Color(0xFF1B2240)
    val Sub = Color(0xFF5A6280)
    val Muted = Color(0xFF8A8FA3)
    val Sun = Color(0xFFFFC531)
    val SunLip = Color(0xFFE0A30F)
    val Coral = Color(0xFFFF7A59)
    val CoralLip = Color(0xFFD9583A)
    val Mint = Color(0xFF2EC4A6)
    val MintLip = Color(0xFF1E9E85)
    val MintDeep = Color(0xFF1E7A66)
    val MintSoft = Color(0xFFDFF5EE)
    val Disabled = Color(0xFFE9E2D3)
    val DisabledText = Color(0xFFA7A08F)
}

/** Jua ships in assets (OFL, assets/fonts/Jua-OFL.txt); until [load] runs the system font stands in. */
object Fonts {
    var jua: FontFamily = FontFamily.Default
        private set

    fun load(assets: AssetManager) {
        jua = runCatching { FontFamily(Font("fonts/Jua-Regular.ttf", assets)) }.getOrDefault(FontFamily.Default)
    }
}

@Composable
fun KidText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Clay.Ink,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text, modifier = modifier, color = color, fontSize = size, fontFamily = Fonts.jua,
    lineHeight = size * 1.2f, textAlign = textAlign, maxLines = maxLines,
)

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 22.sp,
    color: Color = Clay.Sub,
    weight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign? = null,
) = Text(text, modifier = modifier, color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.45f, textAlign = textAlign)

// ---------- images ----------

@Composable
fun rememberAsset(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) {
        runCatching { context.assets.open("img/$name.webp").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }
}

/** A picture from assets/img; [dim] turns it grey and faint (a friend not met yet). */
@Composable
fun AssetImage(name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, dim: Boolean = false) {
    val bmp = rememberAsset(name) ?: return
    Image(
        bmp, contentDescription = null, modifier = modifier, contentScale = scale,
        alpha = if (dim) 0.45f else 1f,
        colorFilter = if (dim) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
    )
}

/** A round clay portrait with a ring (Lumi's face, a friend). */
@Composable
fun Portrait(name: String, size: Dp, ring: Color = Clay.Cream, ringWidth: Dp = 5.dp, dim: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (dim) Clay.Disabled else Clay.Cream).border(ringWidth, ring, CircleShape),
    ) { AssetImage(name, Modifier.size(size), dim = dim) }
}

// ---------- buttons and tiles ----------

enum class ClayStyle(val fill: Color, val lip: Color, val text: Color, val border: Color?) {
    SUN(Clay.Sun, Clay.SunLip, Clay.Ink, null),
    CORAL(Clay.Coral, Clay.CoralLip, Color.White, null),
    MINT(Clay.Mint, Clay.MintLip, Color.White, null),
    GHOST(Color.White, Clay.Line, Clay.Ink, Clay.Line),
}

/** A pressable toy button: the face sinks onto its thick bottom lip while pressed. */
@Composable
fun ClayButton(
    label: String,
    style: ClayStyle,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    enabled: Boolean = true,
    icon: ClayIcon? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lip = 8.dp
    val sink = if (pressed && enabled) lip - 2.dp else 0.dp
    val shape = RoundedCornerShape(50)
    val textColor = if (enabled) style.text else Clay.DisabledText
    Box(modifier.height(height + lip)) {
        if (enabled) Box(Modifier.fillMaxWidth().height(height).offset(y = lip).clip(shape).background(style.lip))
        Box(
            Modifier.fillMaxWidth().height(height).offset(y = sink).clip(shape)
                .background(if (enabled) style.fill else Clay.Disabled)
                .then(if (enabled && style.border != null) Modifier.border(3.dp, style.border, shape) else Modifier)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (icon != null) ClayGlyph(icon, textColor, Modifier.size(38.dp))
                KidText(label, 34.sp, color = textColor, maxLines = 1)
            }
        }
    }
}

/** A choice tile (age, waiting time): sun when chosen, white with a lip otherwise. */
@Composable
fun ChoiceTile(big: String, small: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val lip = 6.dp
    Box(modifier) {
        Box(Modifier.matchParentSize().padding(top = lip).clip(shape).background(if (selected) Clay.SunLip else Clay.Line))
        Column(
            Modifier.fillMaxWidth().padding(bottom = lip).clip(shape).background(if (selected) Clay.Sun else Color.White)
                .then(if (selected) Modifier else Modifier.border(3.dp, Clay.Line, shape))
                .clickable(onClick = onClick).padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KidText(big, 40.sp)
            BodyText(small, size = 19.sp, color = if (selected) Clay.Ink else Clay.Sub, weight = FontWeight.Medium)
        }
    }
}

/** 안전 지킴이 · 부모님 / 부모님 준비: a navy pill on the cream card. */
@Composable
fun Badge(text: String) {
    Text(
        text, color = Clay.Cream, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Clay.Ink).padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** A header chip on the night background: mint check when fine, coral ! when not. */
@Composable
fun StatusChip(label: String, ok: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f))
            .border(2.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(start = 14.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (ok) Clay.Mint else Clay.Coral), contentAlignment = Alignment.Center) {
            ClayGlyph(if (ok) ClayIcon.CHECK else ClayIcon.ALERT, Color.White, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = Clay.Cream, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

/** A small mint check (or coral cross) with a word, for the parent's safety line. */
@Composable
fun CheckLabel(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (ok) Clay.Mint else Clay.Coral), contentAlignment = Alignment.Center) {
            ClayGlyph(if (ok) ClayIcon.CHECK else ClayIcon.ALERT, Color.White, Modifier.size(14.dp))
        }
        Text(label, color = if (ok) Clay.Ink else Clay.CoralLip, fontSize = 19.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------- line icons ----------

enum class ClayIcon { CHECK, ALERT, PAUSE, HINT, SPEAKER, EYE, STAR, GIFT, WAVE }

/** Simple rounded line icons drawn in code, so they take any color and need no resources. */
@Composable
fun ClayGlyph(icon: ClayIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (icon) {
            ClayIcon.CHECK -> drawPath(Path().apply {
                moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.74f); lineTo(w * 0.8f, w * 0.3f)
            }, color, style = Stroke(w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            ClayIcon.ALERT -> {
                drawLine(color, Offset(w / 2, w * 0.22f), Offset(w / 2, w * 0.58f), w * 0.14f, StrokeCap.Round)
                drawCircle(color, w * 0.08f, Offset(w / 2, w * 0.8f))
            }
            ClayIcon.PAUSE -> {
                drawCircle(color, w * 0.44f, Offset(w / 2, w / 2), style = s)
                drawLine(color, Offset(w * 0.4f, w * 0.33f), Offset(w * 0.4f, w * 0.67f), w * 0.12f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.6f, w * 0.33f), Offset(w * 0.6f, w * 0.67f), w * 0.12f, StrokeCap.Round)
            }
            ClayIcon.HINT -> {
                drawCircle(color, w * 0.28f, Offset(w / 2, w * 0.38f), style = s)
                drawLine(color, Offset(w * 0.4f, w * 0.72f), Offset(w * 0.6f, w * 0.72f), w * 0.1f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.43f, w * 0.88f), Offset(w * 0.57f, w * 0.88f), w * 0.1f, StrokeCap.Round)
            }
            ClayIcon.SPEAKER -> {
                drawPath(Path().apply {
                    moveTo(w * 0.12f, w * 0.38f); lineTo(w * 0.3f, w * 0.38f); lineTo(w * 0.52f, w * 0.18f)
                    lineTo(w * 0.52f, w * 0.82f); lineTo(w * 0.3f, w * 0.62f); lineTo(w * 0.12f, w * 0.62f); close()
                }, color, style = s)
                drawArc(color, -45f, 90f, false, Offset(w * 0.42f, w * 0.3f), Size(w * 0.4f, w * 0.4f), style = s)
            }
            ClayIcon.EYE -> {
                drawOval(color, Offset(w * 0.08f, w * 0.28f), Size(w * 0.84f, w * 0.44f), style = s)
                drawCircle(color, w * 0.12f, Offset(w / 2, w / 2), style = s)
            }
            ClayIcon.STAR -> drawPath(starPath(w / 2, w / 2, w * 0.46f, w * 0.2f), color)
            ClayIcon.GIFT -> {
                drawRoundRect(color, Offset(w * 0.16f, w * 0.42f), Size(w * 0.68f, w * 0.44f), style = s)
                drawRoundRect(color, Offset(w * 0.1f, w * 0.28f), Size(w * 0.8f, w * 0.16f), style = s)
                drawLine(color, Offset(w / 2, w * 0.28f), Offset(w / 2, w * 0.86f), w * 0.1f)
            }
            ClayIcon.WAVE -> wave(color, w)
        }
    }
}

private fun DrawScope.wave(color: Color, w: Float) {
    val heights = floatArrayOf(0.3f, 0.6f, 1f, 0.6f, 0.3f)
    val bar = w / 9
    heights.forEachIndexed { i, h ->
        val x = bar * (1 + 2 * i) - bar / 2
        drawLine(color, Offset(x + bar / 2, w * (0.5f - h / 2.2f)), Offset(x + bar / 2, w * (0.5f + h / 2.2f)), bar, StrokeCap.Round)
    }
}

fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
