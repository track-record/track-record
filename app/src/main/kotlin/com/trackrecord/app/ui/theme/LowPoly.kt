package com.trackrecord.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import kotlin.random.Random

private val Amber = Color(0xFFFFC857)
private val Teal = Color(0xFF4ECDC4)
private val Ink = Color(0xFF101A30)

/** Colours from top to bottom of the low-poly scene. */
private val Stops = listOf(0f to Color(0xFF141A3A), 0.35f to Color(0xFF2B2F6B), 0.62f to Color(0xFF1F6F8B), 0.85f to Color(0xFF2FA39A), 1f to Color(0xFFE0B34A))

/** Low Poly: a triangulated gradient from indigo through teal to a warm glow; amber and teal accents. */
val LowPolyPalette: ThemePalette = illustratedScheme(
    primary = Amber,
    onPrimary = Color(0xFF2E1F00),
    secondary = Teal,
    onSecondary = Color(0xFF00302C),
    tertiary = Color(0xFFFF6B6B),
    onTertiary = Color(0xFF3D0000),
    panel = Color(0xFF16213E),
    onPanel = Color(0xFFEFF4FF),
    onPanelVariant = Color(0xFFC3CEE6),
    outline = Color(0xFF5C7AA8),
).let { colors ->
    ThemePalette(
        colors = colors,
        // Cards see-through over the scene, bars and sheets solid (the guest page's shades, TH-02).
        panels = Panels(panel = colors.surface, solid = colors.surfaceContainerLowest, high = colors.surfaceContainerHigh),
        headingWeight = FontWeight.Black,
        cardColors = CardColors.Palette(Color(0xFFEFF4FF), listOf(Color(0xFF1F3F6B), Color(0xFF16545F), Color(0xFF2B2F6B), Color(0xFF5A4520), Color(0xFF1E5A50))),
        dimColor = Ink,
    )
}

private fun gradientAt(t: Float): Color {
    val i = Stops.indexOfLast { it.first <= t }.coerceIn(0, Stops.size - 2)
    val (a, ca) = Stops[i]
    val (b, cb) = Stops[i + 1]
    return lerp(ca, cb, ((t - a) / (b - a)).coerceIn(0f, 1f))
}

private fun Color.shade(f: Float) = Color((red * f).coerceIn(0f, 1f), (green * f).coerceIn(0f, 1f), (blue * f).coerceIn(0f, 1f), alpha)

@Composable
fun LowPolyScene(modifier: Modifier) {
    val cols = 8
    val rows = 15
    // Jittered grid points (relative), edges kept on the border, and a light factor per triangle.
    val points = remember {
        val r = Random(21)
        List(rows) { y ->
            List(cols) { x ->
                val jx = if (x == 0 || x == cols - 1) 0f else (r.nextFloat() - 0.5f) * 0.7f
                val jy = if (y == 0 || y == rows - 1) 0f else (r.nextFloat() - 0.5f) * 0.7f
                (x + jx) / (cols - 1) to (y + jy) / (rows - 1)
            }
        }
    }
    val light = remember { Random(5).let { r -> List(rows * cols * 2) { 0.82f + r.nextFloat() * 0.32f } } }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        var n = 0
        for (y in 0 until rows - 1) for (x in 0 until cols - 1) {
            val p00 = points[y][x]
            val p10 = points[y][x + 1]
            val p01 = points[y + 1][x]
            val p11 = points[y + 1][x + 1]
            for (tri in listOf(listOf(p00, p10, p11), listOf(p00, p11, p01))) {
                val cy = tri.sumOf { it.second.toDouble() }.toFloat() / 3f
                val cx = tri.sumOf { it.first.toDouble() }.toFloat() / 3f
                val color = gradientAt((cy * 0.85f + cx * 0.15f).coerceIn(0f, 1f)).shade(light[n++])
                val path = Path().apply {
                    moveTo(tri[0].first * w, tri[0].second * h)
                    lineTo(tri[1].first * w, tri[1].second * h)
                    lineTo(tri[2].first * w, tri[2].second * h)
                    close()
                }
                drawPath(path, color)
                drawPath(path, color, style = Stroke(width = 1.2f)) // no hairline seams
            }
        }
    }
}
