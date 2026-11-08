package dev.usagetime.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.usagetime.core.durationText

data class HindsightColors(
    val dark: Boolean,
    val canvas: Color,
    val card: Color,
    val cardElevated: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentDeep: Color,
    val peach: Color,
    val peachSoft: Color,
    val mint: Color,
    val sky: Color,
    val rose: Color,
    val gold: Color,
    val textStrong: Color,
    val textRegular: Color,
    val textMuted: Color,
    val textFaint: Color,
    val border: Color,
    val borderSubtle: Color,
    val fill: Color,
    val fillStrong: Color,
    val control: Color,
    val nav: Color,
    val success: Color,
    val warning: Color,
    val danger: Color
)

private val LightHindsight = HindsightColors(
    dark = false,
    canvas = Color(0xFFFAF9FC),
    card = Color(0xFFFFFFFF),
    cardElevated = Color(0xFFFCFBFE),
    accent = Color(0xFF6C5CE7),
    accentSoft = Color(0xFFEFEBFF),
    accentDeep = Color(0xFF5547C6),
    peach = Color(0xFFE79673),
    peachSoft = Color(0xFFFFF0E7),
    mint = Color(0xFF2FC7AC),
    sky = Color(0xFF55A6F2),
    rose = Color(0xFFF26B85),
    gold = Color(0xFFF0AC43),
    textStrong = Color(0xFF1D1C25),
    textRegular = Color(0xFF2E2C3A),
    textMuted = Color(0xFF6B6680),
    textFaint = Color(0xFF9A96AA),
    border = Color(0x14000000),
    borderSubtle = Color(0x0B000000),
    fill = Color(0x0B000000),
    fillStrong = Color(0x14000000),
    control = Color(0xEDFFFFFF),
    nav = Color(0xF7FFFFFF),
    success = Color(0xFF22A06B),
    warning = Color(0xFFE28B19),
    danger = Color(0xFFD84B5F)
)

private val DarkHindsight = HindsightColors(
    dark = true,
    canvas = Color(0xFF0E0E11),
    card = Color(0xFF16161A),
    cardElevated = Color(0xFF1D1D23),
    accent = Color(0xFF9B8DF8),
    accentSoft = Color(0xFF292442),
    accentDeep = Color(0xFFB4A8FF),
    peach = Color(0xFFF0A27E),
    peachSoft = Color(0xFF2A201B),
    mint = Color(0xFF62DCC6),
    sky = Color(0xFF7DBDF6),
    rose = Color(0xFFFF8CA1),
    gold = Color(0xFFF3C06A),
    textStrong = Color(0xFFF0EEF5),
    textRegular = Color(0xFFD9D6E4),
    textMuted = Color(0xFFA7A2B8),
    textFaint = Color(0xFF777285),
    border = Color(0x1AFFFFFF),
    borderSubtle = Color(0x0FFFFFFF),
    fill = Color(0x0FFFFFFF),
    fillStrong = Color(0x17FFFFFF),
    control = Color(0xFF1D1D23),
    nav = Color(0xF51D1D23),
    success = Color(0xFF64D69F),
    warning = Color(0xFFF2B34B),
    danger = Color(0xFFFF8A9D)
)

val LocalHindsightColors = staticCompositionLocalOf { LightHindsight }

private val HindsightTypography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.7).sp),
    displayMedium = TextStyle(fontSize = 42.sp, lineHeight = 45.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun HindsightTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkHindsight else LightHindsight
    val scheme = if (dark) darkColorScheme(
        primary = palette.accent,
        onPrimary = Color(0xFF171322),
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.accentDeep,
        secondary = palette.peach,
        onSecondary = Color(0xFF24160F),
        secondaryContainer = palette.peachSoft,
        onSecondaryContainer = palette.peach,
        background = palette.canvas,
        onBackground = palette.textStrong,
        surface = palette.card,
        onSurface = palette.textStrong,
        surfaceVariant = palette.fill,
        onSurfaceVariant = palette.textMuted,
        surfaceContainer = palette.cardElevated,
        surfaceContainerHigh = palette.control,
        outline = palette.borderStrong(),
        outlineVariant = palette.border,
        error = palette.danger
    ) else lightColorScheme(
        primary = palette.accent,
        onPrimary = Color.White,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.accentDeep,
        secondary = palette.peach,
        onSecondary = Color.White,
        secondaryContainer = palette.peachSoft,
        onSecondaryContainer = Color(0xFF7A3E22),
        background = palette.canvas,
        onBackground = palette.textStrong,
        surface = palette.card,
        onSurface = palette.textStrong,
        surfaceVariant = palette.fill,
        onSurfaceVariant = palette.textMuted,
        surfaceContainer = palette.cardElevated,
        surfaceContainerHigh = palette.control,
        outline = palette.borderStrong(),
        outlineVariant = palette.border,
        error = palette.danger
    )
    CompositionLocalProvider(LocalHindsightColors provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HindsightTypography,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(28.dp)
            ),
            content = content
        )
    }
}

private fun HindsightColors.borderStrong() = if (dark) Color(0xFF403B4F) else Color(0xFFD8D3E5)

@Composable
fun hindsightColors() = LocalHindsightColors.current

fun appAccent(index: Int): Color {
    val colors = listOf(
        Color(0xFF8B7CF6),
        Color(0xFF55A6F2),
        Color(0xFF2FC7AC),
        Color(0xFFF26B85),
        Color(0xFFF0AC43),
        Color(0xFFA8A2B8)
    )
    return colors[index.coerceIn(0, colors.lastIndex)]
}

@Composable
fun HindsightBackdrop(content: @Composable () -> Unit) {
    val c = hindsightColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to c.accentSoft.copy(alpha = if (c.dark) 0.45f else 0.62f),
                    0.25f to c.canvas,
                    1f to c.canvas
                )
            )
    ) { content() }
}

@Composable
fun BrandMark(size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(8.dp, RoundedCornerShape(size * 0.3f), clip = false)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF9B8DF8),
                        Color(0xFFF07FA5),
                        Color(0xFFFFB17E)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

@Composable
fun HindsightPageTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null
) {
    val c = hindsightColors()
    Column(modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            Text(
                eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = c.accent,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = c.textStrong,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun HindsightCard(
    modifier: Modifier = Modifier,
    background: Brush? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = hindsightColors()
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .then(
                if (background == null) Modifier.background(c.card)
                else Modifier.background(background)
            )
            .border(1.dp, c.border, shape)
            .padding(contentPadding)
    ) {
        CompositionLocalProvider(LocalContentColor provides c.textStrong) {
            Column(content = content)
        }
    }
}

@Composable
fun HindsightCardTitle(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val c = hindsightColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textStrong)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

@Composable
fun HindsightDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.fillMaxWidth(), thickness = 1.dp, color = hindsightColors().borderSubtle)
}

@Composable
fun HindsightBadge(
    text: String,
    tint: Color? = null,
    background: Color? = null
) {
    val c = hindsightColors()
    val color = tint ?: c.accent
    Surface(
        color = background ?: c.accentSoft,
        shape = CircleShape
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val c = hindsightColors()
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.control)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) c.textRegular else c.textFaint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun HindsightChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = hindsightColors()
    val bg by animateColorAsState(
        if (selected) c.accent else c.control,
        animationSpec = tween(180),
        label = "chipBackground"
    )
    val fg by animateColorAsState(
        if (selected) Color.White else c.textMuted,
        animationSpec = tween(180),
        label = "chipForeground"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, c.border),
        modifier = modifier.semantics { this.selected = selected }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1
        )
    }
}

@Composable
fun UnderlineTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = hindsightColors()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(index) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) c.accent else c.textMuted
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(if (active) 34.dp else 0.dp)
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(if (active) c.accent else Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun BigTimeValue(
    millis: Long,
    large: Boolean = false,
    muted: Boolean = false,
    available: Boolean = true
) {
    val c = hindsightColors()
    if (!available) {
        Text(
            "未记录",
            style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
            color = c.textMuted
        )
        return
    }
    if (millis in 1..59_999) {
        Text(
            "不足 1 分钟",
            style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
            color = if (muted) c.textMuted else c.textStrong
        )
        return
    }
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hours > 0) {
            Text(
                "$hours",
                style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge,
                color = if (muted) c.textMuted else c.textStrong
            )
            Text(
                "小时",
                modifier = Modifier.padding(bottom = if (large) 8.dp else 5.dp),
                style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                color = c.textMuted
            )
        }
        if (minutes > 0 || hours == 0L) {
            Text(
                "$minutes",
                style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge,
                color = if (muted) c.textMuted else c.textStrong
            )
            Text(
                "分",
                modifier = Modifier.padding(bottom = if (large) 8.dp else 5.dp),
                style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                color = c.textMuted
            )
        }
    }
}

@Composable
fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val c = hindsightColors()
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = c.textStrong, maxLines = 1)
    }
}

@Composable
fun StatusDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = hindsightColors().textMuted)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    val c = hindsightColors()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.fill)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = hindsightColors().textFaint,
        fontWeight = FontWeight.Medium
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    val c = hindsightColors()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 62.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) c.textStrong else c.textFaint
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
        }
        if (trailing != null) trailing()
        else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
fun RatioBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp
) {
    val c = hindsightColors()
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(c.fill)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
    }
}

fun RankingMeta(
    value: Long,
    total: Long,
    confirmed: Long? = null,
    inferred: Long? = null
): String {
    val share = if (total > 0) "%.1f%% 占比".format(java.util.Locale.CHINA, value.toDouble() / total * 100) else "0% 占比"
    return if (confirmed != null && inferred != null) {
        "$share · 确定 ${durationText(confirmed)} · 推断 ${durationText(inferred)}"
    } else share
}

@Composable
fun AppIconTile(model: UsageModel, pkg: String, size: Dp = 40.dp) {
    val label = model.label(pkg)
    var image by androidx.compose.runtime.remember(pkg) { androidx.compose.runtime.mutableStateOf(model.cachedIcon(pkg)) }
    androidx.compose.runtime.LaunchedEffect(pkg) {
        if (image == null) image = model.loadIcon(pkg)
    }
    val bitmap = image
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.25f))
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.25f))
                .background(hindsightColors().accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = hindsightColors().accent
            )
        }
    }
}

@Composable
fun RankedAppRow(
    rank: Int?,
    model: UsageModel,
    pkg: String,
    value: Long,
    total: Long,
    confirmed: Long? = null,
    inferred: Long? = null,
    onClick: (() -> Unit)? = null
) {
    val c = hindsightColors()
    val accent = appAccent((rank ?: 1) - 1)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (rank != null) {
            Text(
                "$rank",
                modifier = Modifier.width(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint,
                textAlign = TextAlign.Center
            )
        }
        AppIconTile(model, pkg, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                model.label(pkg),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatioBar(
                    progress = if (total > 0) value.toFloat() / total else 0f,
                    color = accent,
                    modifier = Modifier.width(46.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    RankingMeta(value, total, confirmed, inferred),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            durationText(value),
            modifier = Modifier.widthIn(min = 62.dp),
            style = MaterialTheme.typography.labelLarge,
            color = c.textStrong,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun AppQualityNote(confirmed: Long, inferred: Long, modifier: Modifier = Modifier) {
    Text(
        "确定 ${durationText(confirmed)} · 推断 ${durationText(inferred)}",
        modifier = modifier.padding(start = 66.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = hindsightColors().textFaint
    )
}
