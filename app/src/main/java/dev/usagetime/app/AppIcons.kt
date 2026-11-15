package dev.usagetime.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Local vector definitions for the seven outlined Material icons not included in
 * `material-icons-core`, keeping APK size small without the full extended icon pack.
 */
internal object AppIcons {
    private val FILL = SolidColor(Color.Black)

    val Schedule: ImageVector by lazy {
        outlined("AppIcons.Schedule") {
            vectorPath {
                moveTo(11.99f, 2f)
                curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
                reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
                reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
                reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
                close()
                moveTo(12.5f, 7f)
                lineTo(11f, 7f)
                verticalLineToRelative(6f)
                lineToRelative(5.25f, 3.15f)
                lineToRelative(0.75f, -1.23f)
                lineToRelative(-4.5f, -2.67f)
                close()
            }
        }
    }

    val Computer: ImageVector by lazy {
        outlined("AppIcons.Computer") {
            vectorPath {
                moveTo(20f, 18f)
                curveToRelative(1.1f, 0f, 1.99f, -0.9f, 1.99f, -2f)
                lineTo(22f, 6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineTo(4f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineTo(0f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(24f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-4f)
                close()
                moveTo(4f, 6f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(10f)
                horizontalLineTo(4f)
                verticalLineTo(6f)
                close()
            }
        }
    }

    val QueryStats: ImageVector by lazy {
        outlined("AppIcons.QueryStats") {
            vectorPath {
                moveTo(19.88f, 18.47f)
                curveToRelative(0.44f, -0.7f, 0.7f, -1.51f, 0.7f, -2.39f)
                curveToRelative(0f, -2.49f, -2.01f, -4.5f, -4.5f, -4.5f)
                reflectiveCurveToRelative(-4.5f, 2.01f, -4.5f, 4.5f)
                reflectiveCurveToRelative(2.01f, 4.5f, 4.49f, 4.5f)
                curveToRelative(0.88f, 0f, 1.7f, -0.26f, 2.39f, -0.7f)
                lineTo(21.58f, 23f)
                lineTo(23f, 21.58f)
                lineTo(19.88f, 18.47f)
                close()
                moveTo(16.08f, 18.58f)
                curveToRelative(-1.38f, 0f, -2.5f, -1.12f, -2.5f, -2.5f)
                curveToRelative(0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
                reflectiveCurveToRelative(2.5f, 1.12f, 2.5f, 2.5f)
                curveTo(18.58f, 17.46f, 17.46f, 18.58f, 16.08f, 18.58f)
                close()
                moveTo(15.72f, 10.08f)
                curveToRelative(-0.74f, 0.02f, -1.45f, 0.18f, -2.1f, 0.45f)
                lineToRelative(-0.55f, -0.83f)
                lineToRelative(-3.8f, 6.18f)
                lineToRelative(-3.01f, -3.52f)
                lineToRelative(-3.63f, 5.81f)
                lineTo(1f, 17f)
                lineToRelative(5f, -8f)
                lineToRelative(3f, 3.5f)
                lineTo(13f, 6f)
                curveTo(13f, 6f, 15.72f, 10.08f, 15.72f, 10.08f)
                close()
                moveTo(18.31f, 10.58f)
                curveToRelative(-0.64f, -0.28f, -1.33f, -0.45f, -2.05f, -0.49f)
                curveToRelative(0f, 0f, 5.12f, -8.09f, 5.12f, -8.09f)
                lineTo(23f, 3.18f)
                lineTo(18.31f, 10.58f)
                close()
            }
        }
    }

    val Apps: ImageVector by lazy {
        outlined("AppIcons.Apps") {
            vectorPath {
                moveTo(4f, 8f)
                horizontalLineToRelative(4f)
                lineTo(8f, 4f)
                lineTo(4f, 4f)
                verticalLineToRelative(4f)
                close()
                moveTo(10f, 20f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(4f)
                close()
                moveTo(4f, 20f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-4f)
                lineTo(4f, 16f)
                verticalLineToRelative(4f)
                close()
                moveTo(4f, 14f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-4f)
                lineTo(4f, 10f)
                verticalLineToRelative(4f)
                close()
                moveTo(10f, 14f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(4f)
                close()
                moveTo(16f, 4f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(4f)
                lineTo(20f, 4f)
                horizontalLineToRelative(-4f)
                close()
                moveTo(10f, 8f)
                horizontalLineToRelative(4f)
                lineTo(14f, 4f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(4f)
                close()
                moveTo(16f, 14f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(4f)
                close()
                moveTo(16f, 20f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(4f)
                close()
            }
        }
    }

    val BarChart: ImageVector by lazy {
        outlined("AppIcons.BarChart") {
            vectorPath {
                moveTo(4f, 9f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(11f)
                horizontalLineToRelative(-4f)
                close()
            }
            vectorPath {
                moveTo(16f, 13f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(7f)
                horizontalLineToRelative(-4f)
                close()
            }
            vectorPath {
                moveTo(10f, 4f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(16f)
                horizontalLineToRelative(-4f)
                close()
            }
        }
    }

    val CalendarMonth: ImageVector by lazy {
        outlined("AppIcons.CalendarMonth") {
            vectorPath {
                moveTo(19f, 4f)
                horizontalLineToRelative(-1f)
                verticalLineTo(2f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2f)
                horizontalLineTo(8f)
                verticalLineTo(2f)
                horizontalLineTo(6f)
                verticalLineToRelative(2f)
                horizontalLineTo(5f)
                curveTo(3.89f, 4f, 3.01f, 4.9f, 3.01f, 6f)
                lineTo(3f, 20f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(6f)
                curveTo(21f, 4.9f, 20.1f, 4f, 19f, 4f)
                close()
                moveTo(19f, 20f)
                horizontalLineTo(5f)
                verticalLineTo(10f)
                horizontalLineToRelative(14f)
                verticalLineTo(20f)
                close()
                moveTo(19f, 8f)
                horizontalLineTo(5f)
                verticalLineTo(6f)
                horizontalLineToRelative(14f)
                verticalLineTo(8f)
                close()
                moveTo(9f, 14f)
                horizontalLineTo(7f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(14f)
                close()
                moveTo(13f, 14f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(14f)
                close()
                moveTo(17f, 14f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(14f)
                close()
                moveTo(9f, 18f)
                horizontalLineTo(7f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(18f)
                close()
                moveTo(13f, 18f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(18f)
                close()
                moveTo(17f, 18f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(18f)
                close()
            }
        }
    }

    val SwapVert: ImageVector by lazy {
        outlined("AppIcons.SwapVert") {
            vectorPath {
                moveTo(16f, 17.01f)
                lineTo(16f, 10f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(7.01f)
                horizontalLineToRelative(-3f)
                lineTo(15f, 21f)
                lineToRelative(4f, -3.99f)
                horizontalLineToRelative(-3f)
                close()
                moveTo(9f, 3f)
                lineTo(5f, 6.99f)
                horizontalLineToRelative(3f)
                lineTo(8f, 14f)
                horizontalLineToRelative(2f)
                lineTo(10f, 6.99f)
                horizontalLineToRelative(3f)
                lineTo(9f, 3f)
                close()
            }
        }
    }

    private inline fun outlined(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private inline fun ImageVector.Builder.vectorPath(block: PathBuilder.() -> Unit): ImageVector.Builder =
        addPath(
            pathData = PathBuilder().apply(block).nodes,
            pathFillType = PathFillType.NonZero,
            fill = FILL,
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f
        )
}
