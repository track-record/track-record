package com.trackrecord.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * One bar per year, [years] consecutive, [values] null where a year has no bar (zero is a stub). Year labels sit under their own
 * bars (first, last and round decades). The line above shows [topLabel] (the scale) until a bar is tapped, then
 * that bar's [detail] (feedback round 47; the library's songs per year since round 5).
 */
@Composable
fun YearBarChart(years: List<Int>, values: List<Double?>, max: Double, topLabel: String, description: String, detail: (Int) -> String) {
    var selected by remember(years, values) { mutableStateOf<Int?>(null) }
    val top = max.coerceAtLeast(Double.MIN_VALUE)
    val barColor = MaterialTheme.colorScheme.primary
    val dimColor = barColor.copy(alpha = 0.45f)
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    Column(Modifier.fillMaxWidth()) {
        Text(selected?.let(detail) ?: topLabel, style = MaterialTheme.typography.labelMedium)
        Canvas(
            Modifier.fillMaxWidth().height(184.dp).semantics { contentDescription = description }.pointerInput(years) {
                detectTapGestures { pos ->
                    val i = (pos.x / (size.width.toFloat() / years.size)).toInt().coerceIn(years.indices)
                    selected = if (selected == i || values[i] == null) null else i
                }
            },
        ) {
            val labelSpace = 24.dp.toPx()
            val chart = size.height - labelSpace
            val slot = size.width / years.size
            val gap = if (slot > 6f) maxOf(2.dp.toPx(), slot * 0.2f).coerceAtMost(slot / 2) else 0f
            val radius = minOf(4.dp.toPx(), (slot - gap) / 2)
            // Rounded tops on a square base: the bars are drawn a radius taller and clipped at the baseline.
            clipRect(bottom = chart) {
                values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    // A zero still shows, as a stub: a year with rounds but nothing to count isn't an empty year.
                    val h = (chart * (v / top)).toFloat().coerceAtLeast(2.dp.toPx())
                    val color = if (selected == null || selected == i) barColor else dimColor
                    drawRoundRect(color, Offset(i * slot + gap / 2, chart - h), Size(slot - gap, h + radius), CornerRadius(radius))
                }
            }
            drawLine(axisColor, Offset(0f, chart), Offset(size.width, chart), strokeWidth = 2f)
            val candidates = listOf(0, years.lastIndex) + years.indices.filter { years[it] % 10 == 0 }
            var taken = emptyList<ClosedFloatingPointRange<Float>>()
            for (i in candidates.distinct()) {
                val text = measurer.measure(formatYear(years[i]), labelStyle)
                val center = i * slot + slot / 2
                val x = (center - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                val span = (x - 8f)..(x + text.size.width + 8f)
                if (taken.any { it.start < span.endInclusive && span.start < it.endInclusive }) continue
                taken = taken + span
                drawText(text, topLeft = Offset(x, chart + 4.dp.toPx()))
            }
        }
    }
}
