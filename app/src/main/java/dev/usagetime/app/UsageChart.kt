package dev.usagetime.app

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawWithCache
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

private const val DETAILED_BUCKET_LIMIT = 32

private val TOP_HOUR_STEPS = doubleArrayOf(
    0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 12.0, 18.0, 24.0,
    48.0, 96.0, 192.0, 384.0, 768.0, 1536.0
)

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
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    // Dense charts would repaint thousands of marks on every reveal frame.
    val reveal = if (count <= DETAILED_BUCKET_LIMIT) {
        animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(220),
            label = "chartReveal"
        )
    } else null

    val density = LocalDensity.current
    val textSize = with(density) { 10.sp.toPx() }
    val paint = remember(c.textFaint, textSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.textFaint.toArgb()
            this.textSize = textSize
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
    }

    val (topHours, maxMs) = remember(left, right) {
        val maxValue = max(left.maxOfOrNull { it.millis } ?: 0L, right?.maxOfOrNull { it.millis } ?: 0L)
        val maxHours = (maxValue / 3_600_000.0 * 1.12).coerceAtLeast(0.5)
        val top = TOP_HOUR_STEPS.firstOrNull { it >= maxHours } ?: (ceil(maxHours / 100) * 100)
        top to (top * 3_600_000)
    }
    val selectedBarBrush = remember(c.accent, c.accentDeep) {
        Brush.verticalGradient(listOf(c.accent, c.accentDeep))
    }
    val barBrush = remember(c.accent) {
        Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.88f), c.accent.copy(alpha = 0.42f)))
    }
    val compareBarBrush = remember(c.accent) {
        Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.92f), c.accent.copy(alpha = 0.45f)))
    }
    val gridDash = remember(density) {
        PathEffect.dashPathEffect(floatArrayOf(with(density) { 2.dp.toPx() }, with(density) { 4.dp.toPx() }))
    }
    val seriesDash = remember(density) {
        PathEffect.dashPathEffect(floatArrayOf(with(density) { 6.dp.toPx() }, with(density) { 5.dp.toPx() }))
    }
    val solidLineStroke = remember(density) {
        Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round)
    }
    val dashedLineStroke = remember(density, seriesDash) {
        Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round, pathEffect = seriesDash)
    }
    val compareStroke = remember(density) { Stroke(width = with(density) { 1.4.dp.toPx() }) }
    val dotRingStroke = remember(density) { Stroke(with(density) { 1.5.dp.toPx() }) }
    val highlightCorner = remember(density) {
        val px = with(density) { 2.dp.toPx() }
        CornerRadius(px, px)
    }
    val compareCorner = remember(density) {
        val px = with(density) { 5.dp.toPx() }
        CornerRadius(px, px)
    }
    val labelIndices = remember(count) {
        if (count <= 6) {
            (0 until count).toList()
        } else {
            listOf(0, count / 4, count / 2, count * 3 / 4, count - 1).distinct()
        }
    }

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
        Spacer(
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
                .drawWithCache {
                    val leftInset = 42.dp.toPx()
                    val rightInset = 8.dp.toPx()
                    val topInset = 10.dp.toPx()
                    val bottomInset = 27.dp.toPx()
                    val chartWidth = (size.width - leftInset - rightInset).coerceAtLeast(1f)
                    val chartHeight = (size.height - topInset - bottomInset).coerceAtLeast(1f)
                    val baseline = topInset + chartHeight
                    val slot = chartWidth / count

                    fun x(index: Int) = leftInset + slot * (index + 0.5f)
                    fun barHeight(millis: Long) = (millis.toDouble() / maxMs * chartHeight).toFloat()
                    fun pointY(millis: Long, progress: Float) =
                        baseline - barHeight(if (progress == 1f) millis else (millis * progress).toLong())

                    val gridLines = (0..3).map { tick ->
                        val value = topHours * tick / 3
                        val label = when {
                            tick == 0 -> "0"
                            value < 1 -> "${(value * 60).roundToInt()}分"
                            value == value.toLong().toDouble() -> "${value.toLong()}时"
                            else -> "%.1f时".format(java.util.Locale.CHINA, value)
                        }
                        (baseline - chartHeight * tick / 3) to label
                    }
                    val axisLabels = labelIndices.map { index ->
                        x(index) to (left.getOrNull(index) ?: right?.getOrNull(index))?.label.orEmpty()
                    }
                    val gridStrokeWidth = 1.dp.toPx()
                    val labelStartX = 1.dp.toPx()
                    val labelBaselineY = size.height - 5.dp.toPx()
                    val dotRadius = 3.5.dp.toPx()
                    val minBarHeight = 1.5.dp.toPx()
                    val highlightMinHeight = 8.dp.toPx()
                    val highlightInset = 3.dp.toPx()
                    val highlightThickness = 2.dp.toPx()
                    val bar = min(24.dp.toPx(), slot * if (right == null) 0.58f else 0.34f)
                    val corner = min(6.dp.toPx(), bar / 2)
                    val singleCornerRadius = CornerRadius(corner, corner)
                    val compareFill = c.peach.copy(alpha = 0.16f)
                    val compareOutline = c.peach.copy(alpha = 0.72f)
                    val selectionFill = c.accent.copy(alpha = if (c.dark) 0.09f else 0.055f)

                    fun seriesPath(points: List<Bucket>, progress: Float): Path {
                        val path = Path()
                        var started = false
                        points.forEachIndexed { index, bucket ->
                            if (bucket.available) {
                                val y = pointY(bucket.millis, progress)
                                if (started) path.lineTo(x(index), y) else path.moveTo(x(index), y)
                            }
                            started = bucket.available
                        }
                        return path
                    }

                    fun barHeights(points: List<Bucket>) = FloatArray(points.size) { index ->
                        val bucket = points[index]
                        if (bucket.available) barHeight(bucket.millis) else 0f
                    }

                    val leftPath = if (line) seriesPath(left, 1f) else null
                    val rightPath = if (line) right?.let { seriesPath(it, 1f) } else null
                    val leftBars = if (line) null else barHeights(left)
                    val rightBars = if (line) null else right?.let(::barHeights)
                    // Subpixel bars share a screen column. Keep its peak, with selection on the original buckets.
                    val denseBarSegments = if (reveal == null && right == null && leftBars != null && count > chartWidth) {
                        val peaks = FloatArray(chartWidth.toInt().coerceAtLeast(1))
                        leftBars.forEachIndexed { index, height ->
                            val column = (slot * (index + 0.5f)).toInt().coerceAtMost(peaks.lastIndex)
                            peaks[column] = max(peaks[column], height)
                        }
                        val segments = FloatArray(peaks.count { it > 0f } * 4)
                        var next = 0
                        peaks.forEachIndexed { column, height ->
                            if (height > 0f) {
                                val center = leftInset + column + 0.5f
                                segments[next++] = center
                                segments[next++] = baseline - height.coerceAtLeast(minBarHeight)
                                segments[next++] = center
                                segments[next++] = baseline
                            }
                        }
                        segments
                    } else null
                    val denseBarPaint = denseBarSegments?.let {
                        Paint().apply {
                            strokeWidth = 1f
                            shader = LinearGradient(
                                0f, 0f, 0f, size.height.coerceAtLeast(1f),
                                c.accent.copy(alpha = 0.88f).toArgb(),
                                c.accent.copy(alpha = 0.42f).toArgb(),
                                Shader.TileMode.CLAMP
                            )
                        }
                    }

                    onDrawBehind {
                        val progress = reveal?.value ?: 1f
                        val selectedIndex = selected
                        drawRect(
                            color = selectionFill,
                            topLeft = Offset(leftInset + slot * (selectedIndex + 0.08f), topInset),
                            size = Size(slot * 0.84f, chartHeight)
                        )

                        paint.textAlign = Paint.Align.LEFT
                        val canvas = drawContext.canvas.nativeCanvas
                        gridLines.forEach { (at, label) ->
                            drawLine(
                                color = c.borderSubtle,
                                start = Offset(leftInset, at),
                                end = Offset(size.width - rightInset, at),
                                strokeWidth = gridStrokeWidth,
                                pathEffect = gridDash
                            )
                            canvas.drawText(label, labelStartX, at + textSize * 0.35f, paint)
                        }
                        paint.textAlign = Paint.Align.CENTER
                        axisLabels.forEach { (at, label) ->
                            canvas.drawText(label, at, labelBaselineY, paint)
                        }

                        fun drawSeries(points: List<Bucket>, fullPath: Path, color: Color, dashed: Boolean) {
                            drawPath(
                                if (progress == 1f) fullPath else seriesPath(points, progress),
                                color,
                                style = if (dashed) dashedLineStroke else solidLineStroke
                            )
                            if (count > DETAILED_BUCKET_LIMIT) return
                            points.forEachIndexed { index, bucket ->
                                if (bucket.available) {
                                    val point = Offset(x(index), pointY(bucket.millis, progress))
                                    drawCircle(if (dashed) c.card else color, dotRadius, point)
                                    if (dashed) drawCircle(color, dotRadius, point, style = dotRingStroke)
                                }
                            }
                        }

                        fun drawLeftBar(index: Int, rawHeight: Float) {
                            if (rawHeight <= 0f) return
                            val center = x(index)
                            val height = (rawHeight * progress).coerceAtLeast(minBarHeight)
                            val inset = if (right == null) bar / 2 else bar * 1.05f
                            val brush = when {
                                right != null -> compareBarBrush
                                index == selectedIndex -> selectedBarBrush
                                else -> barBrush
                            }
                            drawRoundRect(
                                brush = brush,
                                topLeft = Offset(center - inset, baseline - height),
                                size = Size(bar, height),
                                cornerRadius = if (right == null) singleCornerRadius else compareCorner
                            )
                            if (right == null && index == selectedIndex && height > highlightMinHeight) {
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.32f),
                                    topLeft = Offset(center - bar * 0.26f, baseline - height + highlightInset),
                                    size = Size(bar * 0.52f, highlightThickness),
                                    cornerRadius = highlightCorner
                                )
                            }
                        }

                        if (leftPath != null) {
                            drawSeries(left, leftPath, c.accent, dashed = false)
                            if (right != null && rightPath != null) {
                                drawSeries(right, rightPath, c.peach, dashed = true)
                            }
                        } else if (leftBars != null) {
                            if (denseBarSegments != null && denseBarPaint != null) {
                                canvas.drawLines(denseBarSegments, denseBarPaint)
                                drawLeftBar(selectedIndex, leftBars[selectedIndex])
                                return@onDrawBehind
                            }
                            for (index in 0 until count) {
                                drawLeftBar(index, leftBars.getOrElse(index) { 0f })
                                val rightHeight = rightBars?.getOrElse(index) { 0f } ?: 0f
                                if (rightHeight > 0f) {
                                    val height = (rightHeight * progress).coerceAtLeast(minBarHeight)
                                    val position = Offset(x(index) + bar * 0.05f, baseline - height)
                                    val dimensions = Size(bar, height)
                                    drawRoundRect(compareOutline, position, dimensions, compareCorner, style = compareStroke)
                                    drawRoundRect(compareFill, position, dimensions, compareCorner)
                                }
                            }
                        }
                    }
                }
        )
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
