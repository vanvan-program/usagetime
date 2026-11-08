package dev.usagetime.app

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.usagetime.core.Bucket
import dev.usagetime.core.durationText
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun UsageChart(left: List<Bucket>, right: List<Bucket>? = null, line: Boolean = false) {
    val count = max(left.size, right?.size ?: 0)
    if (count == 0) {
        Text(
            "暂无可展示的记录",
            Modifier.padding(vertical = 24.dp),
            color = hindsightColors().textMuted
        )
        return
    }

    val c = hindsightColors()
    var selected by remember(left, right) {
        mutableIntStateOf(
            left.indexOfLast { it.available }.takeIf { it >= 0 }
                ?: right?.indexOfLast { it.available }?.takeIf { it >= 0 }
                ?: (count - 1)
        )
    }
    var entered by remember(left, right, line) { mutableStateOf(false) }
    LaunchedEffect(left, right, line) { entered = true }
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(440),
        label = "chartReveal"
    )

    val density = LocalDensity.current
    val textSize = with(density) { 10.sp.toPx() }
    val paint = remember(c.textFaint, textSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.textFaint.toArgb()
            this.textSize = textSize
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
    }

    val maxValue = remember(left, right) {
        max(left.maxOfOrNull { it.millis } ?: 0L, right?.maxOfOrNull { it.millis } ?: 0L)
    }
    val maxHours = (maxValue / 3_600_000.0 * 1.12).coerceAtLeast(0.5)
    val topHours = listOf(
        0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 12.0, 18.0, 24.0,
        48.0, 96.0, 192.0, 384.0, 768.0, 1536.0
    ).firstOrNull { it >= maxHours } ?: ceil(maxHours / 100) * 100
    val maxMs = topHours * 3_600_000

    fun valueText(bucket: Bucket?): String =
        if (bucket?.available == true) durationText(bucket.millis) else "未记录"

    fun pointText(index: Int): Pair<String, String> {
        val a = left.getOrNull(index)
        val b = right?.getOrNull(index)
        val label = a?.label ?: b?.label ?: ""
        val value = if (right == null) valueText(a)
        else "统计 ${valueText(a)}  ·  对照 ${valueText(b)}"
        return label to value
    }

    val (selectedLabel, selectedValue) = pointText(selected)
    val chartDescription = "${if (line) "折线图" else "柱状图"}。$selectedLabel，$selectedValue。"

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .semantics {
                    contentDescription = "$chartDescription 点击或滑动查看各时段。"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        selected.toFloat(),
                        0f..(count - 1).toFloat(),
                        (count - 2).coerceAtLeast(0)
                    )
                    setProgress {
                        selected = it.roundToInt().coerceIn(0, count - 1)
                        true
                    }
                }
                .pointerInput(left, right, line) {
                    detectTapGestures { tap ->
                        val leftMargin = with(density) { 42.dp.toPx() }
                        val rightMargin = with(density) { 8.dp.toPx() }
                        val usable = (size.width - leftMargin - rightMargin).coerceAtLeast(1f)
                        selected = (((tap.x - leftMargin) / usable) * count)
                            .toInt()
                            .coerceIn(0, count - 1)
                    }
                }
        ) {
            val leftInset = 42.dp.toPx()
            val rightInset = 8.dp.toPx()
            val topInset = 10.dp.toPx()
            val bottomInset = 27.dp.toPx()
            val chartWidth = (size.width - leftInset - rightInset).coerceAtLeast(1f)
            val chartHeight = (size.height - topInset - bottomInset).coerceAtLeast(1f)
            val slot = chartWidth / count

            fun x(index: Int) = leftInset + slot * (index + 0.5f)
            fun y(value: Long) = topInset + chartHeight - (value.toDouble() / maxMs * chartHeight).toFloat()

            val selectedRectLeft = leftInset + slot * selected
            drawRect(
                color = c.accent.copy(alpha = if (c.dark) 0.09f else 0.055f),
                topLeft = Offset(selectedRectLeft + slot * 0.08f, topInset),
                size = Size(slot * 0.84f, chartHeight)
            )

            paint.textAlign = Paint.Align.LEFT
            for (i in 0..3) {
                val value = topHours * i / 3
                val at = topInset + chartHeight - chartHeight * i / 3
                drawLine(
                    color = c.borderSubtle,
                    start = Offset(leftInset, at),
                    end = Offset(size.width - rightInset, at),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
                )
                val label = when {
                    i == 0 -> "0"
                    value < 1 -> "${(value * 60).roundToInt()}分"
                    value == value.toLong().toDouble() -> "${value.toLong()}时"
                    else -> "%.1f时".format(java.util.Locale.CHINA, value)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    1.dp.toPx(),
                    at + textSize * 0.35f,
                    paint
                )
            }

            val labelIndices = if (count <= 6) {
                (0 until count).toList()
            } else {
                listOf(0, count / 4, count / 2, count * 3 / 4, count - 1).distinct()
            }
            labelIndices.forEach { i ->
                val label = (left.getOrNull(i) ?: right?.getOrNull(i))?.label ?: ""
                paint.textAlign = Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x(i),
                    size.height - 5.dp.toPx(),
                    paint
                )
            }

            fun drawBars() {
                if (line) return
                for (i in 0 until count) {
                    val a = left.getOrNull(i)
                    val b = right?.getOrNull(i)
                    val bar = min(24.dp.toPx(), slot * if (right == null) 0.58f else 0.34f)

                    if (right == null) {
                        if (a?.available == true) {
                            val rawHeight = (a.millis.toDouble() / maxMs * chartHeight).toFloat()
                            val height = (rawHeight * reveal).coerceAtLeast(if (a.millis > 0) 1.5.dp.toPx() else 0f)
                            val center = x(i)
                            val fill = if (i == selected) {
                                Brush.verticalGradient(listOf(c.accent, c.accentDeep))
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        c.accent.copy(alpha = 0.88f),
                                        c.accent.copy(alpha = 0.42f)
                                    )
                                )
                            }
                            drawRoundRect(
                                brush = fill,
                                topLeft = Offset(center - bar / 2, topInset + chartHeight - height),
                                size = Size(bar, height),
                                cornerRadius = CornerRadius(min(6.dp.toPx(), bar / 2), min(6.dp.toPx(), bar / 2))
                            )
                            if (i == selected && height > 8.dp.toPx()) {
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.32f),
                                    topLeft = Offset(center - bar * 0.26f, topInset + chartHeight - height + 3.dp.toPx()),
                                    size = Size(bar * 0.52f, 2.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                )
                            }
                        }
                    } else {
                        val leftBar = a?.takeIf { it.available && it.millis > 0 }?.let {
                            ((it.millis.toDouble() / maxMs * chartHeight).toFloat() * reveal).coerceAtLeast(1.5.dp.toPx())
                        }
                        val rightBar = b?.takeIf { it.available && it.millis > 0 }?.let {
                            ((it.millis.toDouble() / maxMs * chartHeight).toFloat() * reveal).coerceAtLeast(1.5.dp.toPx())
                        }
                        val center = x(i)
                        if (leftBar != null) {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(c.accent.copy(alpha = 0.92f), c.accent.copy(alpha = 0.45f))
                                ),
                                topLeft = Offset(center - bar * 1.05f, topInset + chartHeight - leftBar),
                                size = Size(bar, leftBar.coerceAtLeast(1.5.dp.toPx())),
                                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                            )
                        }
                        if (rightBar != null) {
                            drawRoundRect(
                                color = c.peach.copy(alpha = 0.72f),
                                topLeft = Offset(center + bar * 0.05f, topInset + chartHeight - rightBar),
                                size = Size(bar, rightBar.coerceAtLeast(1.5.dp.toPx())),
                                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                                style = Stroke(width = 1.4.dp.toPx())
                            )
                            drawRoundRect(
                                color = c.peach.copy(alpha = 0.16f),
                                topLeft = Offset(center + bar * 0.05f, topInset + chartHeight - rightBar),
                                size = Size(bar, rightBar.coerceAtLeast(1.5.dp.toPx())),
                                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                            )
                        }
                    }
                }
            }

            fun drawSeries() {
                if (!line) return
                fun series(points: List<Bucket>, color: Color, dashed: Boolean) {
                    val path = Path()
                    var started = false
                    points.forEachIndexed { index, bucket ->
                        if (!bucket.available) {
                            started = false
                            return@forEachIndexed
                        }
                        val px = x(index)
                        val py = y((bucket.millis * reveal).toLong())
                        if (!started) {
                            path.moveTo(px, py)
                            started = true
                        } else {
                            path.lineTo(px, py)
                        }
                    }
                    drawPath(
                        path,
                        color,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (dashed) {
                                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                            } else null
                        )
                    )
                    if (count <= 32) {
                        points.forEachIndexed { index, bucket ->
                            if (bucket.available) {
                                val point = Offset(x(index), y((bucket.millis * reveal).toLong()))
                                drawCircle(if (dashed) c.card else color, 3.5.dp.toPx(), point)
                                if (dashed) {
                                    drawCircle(color, 3.5.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                                }
                            }
                        }
                    }
                }
                series(left, c.accent, false)
                right?.let { series(it, c.peach, true) }
            }

            drawBars()
            drawSeries()
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.fill)
                .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (line) c.accent else c.accent.copy(alpha = 0.78f))
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selectedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textFaint,
                    maxLines = 1
                )
                Text(
                    selectedValue,
                    style = MaterialTheme.typography.labelLarge,
                    color = c.textStrong,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
            }
            IconButton(
                onClick = { selected = (selected - 1).coerceAtLeast(0) },
                enabled = selected > 0,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    "上一个数据点",
                    tint = if (selected > 0) c.textMuted else c.textFaint.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = { selected = (selected + 1).coerceAtMost(count - 1) },
                enabled = selected < count - 1,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    "下一个数据点",
                    tint = if (selected < count - 1) c.textMuted else c.textFaint.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
