package io.github.loganrosen.wattdog.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.loganrosen.wattdog.batterystats.analysis.HistorySummary
import io.github.loganrosen.wattdog.batterystats.analysis.formatPercent
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.batterystats.model.BatteryHistory
import io.github.loganrosen.wattdog.batterystats.model.HistoryMarker
import io.github.loganrosen.wattdog.batterystats.model.TimeSpan
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.data.Baseline
import io.github.loganrosen.wattdog.ui.HistoryLoad
import io.github.loganrosen.wattdog.ui.components.BarRow
import io.github.loganrosen.wattdog.ui.components.EmptyNote
import io.github.loganrosen.wattdog.ui.components.SectionCard
import io.github.loganrosen.wattdog.ui.components.StatRow
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun HistoryScreen(load: HistoryLoad, analysis: Analysis, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val full = load.history
    when {
        full == null && load.error != null -> Column(modifier.padding(24.dp)) {
            Text("Couldn't load battery history", style = MaterialTheme.typography.titleMedium)
            Text(load.error, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text("Retry") }
        }

        full == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                EmptyNote("Reading battery history…")
            }
        }

        else -> HistoryContent(full, analysis, modifier)
    }
}

@Composable
private fun HistoryContent(full: BatteryHistory, analysis: Analysis, modifier: Modifier = Modifier) {
    val from = remember(full, analysis) { windowStart(full, analysis) }
    val window = remember(full, from) { full.window(from) }
    val summary = remember(window) { HistorySummary.from(window) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (from < full.startMillis) {
            item {
                EmptyNote(
                    "The history buffer only reaches back to ${formatClock(full.startMillis, full.durationMillis)}; " +
                        "older events were dropped.",
                )
            }
        }
        item {
            SectionCard("Timeline") {
                if (window.durationMillis <= 0) EmptyNote("No history in this window yet.") else Timeline(window)
            }
        }
        item { SummaryCard(summary) }
        item {
            UsageCard("Foreground apps", summary.topApps.map { Usage(analysis.label(it.uid).label, null, it.millis, it.count) })
        }
        item {
            UsageCard(
                "Wakelocks (first holder)",
                summary.wakelocks.map { Usage(it.uid?.let(analysis::label)?.label ?: "Unknown", it.tag, it.millis, it.count) },
                note = "The history names only the app that took the first wakelock while none were held.",
            )
        }
        item { UsageCard("Jobs", summary.jobs.map { Usage(analysis.label(it.uid).label, null, it.millis, it.count) }) }
    }
}

/** Since unplugged → the stats' start clock; snapshot → when it was captured, both on the history's wall clock. */
private fun windowStart(h: BatteryHistory, analysis: Analysis): Long = when (val b = analysis.baseline) {
    Baseline.SinceCharge ->
        analysis.report.startClockTime?.let(HistorySummary::clockMillis)
            ?: h.markers.lastOrNull { it.kind == HistoryMarker.Kind.RESET }?.millis
            ?: h.startMillis

    is Baseline.Snapshot -> {
        val instant = Instant.ofEpochMilli(b.meta.capturedAtMillis)
        b.meta.capturedAtMillis + ZoneId.systemDefault().rules.getOffset(instant).totalSeconds * 1000L
    }
}

@Composable
private fun SummaryCard(s: HistorySummary) {
    SectionCard("Summary") {
        if (s.levelStart != null && s.levelEnd != null) StatRow("Battery", "${s.levelStart}% → ${s.levelEnd}%")
        s.screenOffDropPerHour?.let { StatRow("Screen-off drain", "%.1f%%/h".format(it)) }
        s.screenOnDropPerHour?.let { StatRow("Screen-on drain", "%.1f%%/h".format(it)) }
        StatRow("Screen on", s.screenOnMillis.milliseconds.formatShort())
        StatRow("Screen off", s.screenOffMillis.milliseconds.formatShort())
        s.screenOffAwakeRatio?.let {
            StatRow(
                "Awake while screen off",
                "${s.screenOffAwakeMillis.milliseconds.formatShort()} (${formatPercent(it)})",
                emphasis = if (it > 0.15) MaterialTheme.colorScheme.error else null,
            )
        }
        if (s.screenDozeMillis > 0) StatRow("Always-on display", s.screenDozeMillis.milliseconds.formatShort())
        StatRow("Deep doze", s.deepIdleMillis.milliseconds.formatShort())
        if (s.pluggedMillis > 0) StatRow("Charging", s.pluggedMillis.milliseconds.formatShort())
    }
}

private data class Usage(val title: String, val subtitle: String?, val millis: Long, val count: Int)

@Composable
private fun UsageCard(title: String, rows: List<Usage>, note: String? = null) {
    SectionCard(title) {
        note?.let { EmptyNote(it) }
        if (rows.isEmpty()) {
            EmptyNote("Nothing recorded in this window.")
        } else {
            val max = rows.maxOf { it.millis }.coerceAtLeast(1)
            rows.forEach {
                BarRow(
                    title = it.title,
                    value = it.millis.milliseconds.formatShort(),
                    fraction = it.millis.toFloat() / max,
                    subtitle = listOfNotNull(it.subtitle, "${it.count}×").joinToString(" · "),
                )
            }
        }
    }
}

private sealed interface LaneData {
    data class Spans(val spans: List<TimeSpan>) : LaneData

    /** Shaded by value rank, e.g. signal strength from none to great. */
    data class Levels(val spans: List<Pair<TimeSpan, Float>>) : LaneData
}

private data class Lane(val label: String, val color: Color, val data: LaneData)

private val signalRank = mapOf("none" to 0.15f, "poor" to 0.35f, "moderate" to 0.55f, "good" to 0.8f, "great" to 1f)

@Composable
private fun lanes(h: BatteryHistory): List<Lane> {
    val c = MaterialTheme.colorScheme
    val warn = Color(0xFFE8A33D)
    val ok = Color(0xFF3FA66B)
    return remember(h, c) {
        fun flag(label: String, name: String, color: Color) = Lane(label, color, LaneData.Spans(h.flag(name)))
        fun idle(label: String, value: String, color: Color) =
            Lane(label, color, LaneData.Spans(h.state(BatteryHistory.DEVICE_IDLE).filter { it.value == value }))
        val signal = h.state(BatteryHistory.SIGNAL).mapNotNull { s -> signalRank[s.value]?.let { s to it } }
        listOf(
            flag("Screen", BatteryHistory.SCREEN, c.primary),
            flag("AOD", BatteryHistory.SCREEN_DOZE, c.primary.copy(alpha = 0.5f)),
            flag("Charging", BatteryHistory.PLUGGED, ok),
            flag("Awake", BatteryHistory.RUNNING, c.error),
            flag("Wakelock", BatteryHistory.WAKE_LOCK, c.error.copy(alpha = 0.6f)),
            idle("Deep doze", "full", c.tertiary),
            idle("Light doze", "light", c.tertiary.copy(alpha = 0.5f)),
            flag("Cell radio", BatteryHistory.MOBILE_RADIO, c.secondary),
            flag("Cell TX", BatteryHistory.CELL_HIGH_TX, warn),
            Lane("Signal", c.secondary, LaneData.Levels(signal)),
            flag("Wi-Fi radio", BatteryHistory.WIFI_RADIO, c.secondary.copy(alpha = 0.6f)),
            flag("GPS", BatteryHistory.GPS, warn),
            flag("BLE scan", BatteryHistory.BLE_SCAN, warn.copy(alpha = 0.6f)),
            flag("Audio", BatteryHistory.AUDIO, c.primary.copy(alpha = 0.7f)),
            flag("Video", BatteryHistory.VIDEO, c.primary.copy(alpha = 0.7f)),
            flag("Camera", BatteryHistory.CAMERA, c.primary.copy(alpha = 0.7f)),
        ).filter { lane ->
            lane.label == "Screen" ||
                when (val d = lane.data) {
                    is LaneData.Spans -> d.spans.isNotEmpty()
                    is LaneData.Levels -> d.spans.isNotEmpty()
                }
        }
    }
}

@Composable
private fun Timeline(h: BatteryHistory) {
    val lanes = lanes(h)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val chargeShade = Color(0xFF3FA66B).copy(alpha = 0.12f)
    val cursorColor = MaterialTheme.colorScheme.onSurface
    var cursorX by remember(h) { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val gutter = 72.dp
    val chartHeight = 120.dp
    val laneHeight = 14.dp
    val laneGap = 4.dp
    val axisHeight = 18.dp
    val total = chartHeight + 8.dp + (laneHeight + laneGap) * lanes.size + axisHeight

    val cursorT = cursorX?.let { x -> h.startMillis + ((x / widthPx).coerceIn(0f, 1f) * h.durationMillis).toLong() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            cursorT?.let { readout(h, lanes, it) } ?: "Drag across the chart to inspect a moment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
        )
        Box(Modifier.fillMaxWidth().height(total)) {
            Canvas(Modifier.fillMaxSize()) {
                val left = gutter.toPx()
                val plotW = size.width - left
                fun x(t: Long) = left + (t - h.startMillis).toFloat() / h.durationMillis * plotW
                val chartH = chartHeight.toPx()

                h.flag(BatteryHistory.PLUGGED).forEach { s ->
                    drawRect(chargeShade, Offset(x(s.startMillis), 0f), Size((x(s.endMillis) - x(s.startMillis)).coerceAtLeast(1f), chartH))
                }
                listOf(0, 50, 100).forEach { pct ->
                    val y = chartH * (1 - pct / 100f)
                    drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                    label(measurer, "$pct%", labelStyle, Offset(0f, (y - 6.dp.toPx()).coerceIn(0f, chartH - 12.dp.toPx())))
                }
                h.markers.filter { it.kind != HistoryMarker.Kind.OVERFLOW }.forEach { m ->
                    drawLine(
                        gridColor,
                        Offset(x(m.millis), 0f),
                        Offset(x(m.millis), size.height - axisHeight.toPx()),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    )
                }
                if (h.levels.isNotEmpty()) {
                    val path = Path()
                    h.levels.forEachIndexed { i, p ->
                        val y = chartH * (1 - p.level / 100f)
                        if (i == 0) path.moveTo(x(p.millis), y) else path.lineTo(x(p.millis), y)
                    }
                    path.lineTo(x(h.endMillis), chartH * (1 - h.levels.last().level / 100f))
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
                }

                var top = chartH + 8.dp.toPx()
                val lh = laneHeight.toPx()
                lanes.forEach { lane ->
                    label(measurer, lane.label, labelStyle, Offset(0f, top))
                    drawRect(gridColor.copy(alpha = 0.3f), Offset(left, top), Size(plotW, lh))
                    when (val d = lane.data) {
                        is LaneData.Spans -> drawSpans(d.spans, lane.color, top, lh, ::x)

                        is LaneData.Levels -> d.spans.forEach { (s, rank) ->
                            drawRect(
                                lane.color.copy(alpha = rank),
                                Offset(x(s.startMillis), top),
                                Size((x(s.endMillis) - x(s.startMillis)).coerceAtLeast(1f), lh),
                            )
                        }
                    }
                    top += lh + laneGap.toPx()
                }

                val ticks = 4
                for (i in 0..ticks) {
                    val t = h.startMillis + h.durationMillis * i / ticks
                    val text = measurer.measure(formatClock(t, h.durationMillis), labelStyle)
                    val cx = (x(t) - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
                    drawText(text, topLeft = Offset(cx, size.height - text.size.height))
                }
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(start = gutter)
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(h) { detectTapGestures { cursorX = it.x } }
                    .pointerInput(h) {
                        detectHorizontalDragGestures(
                            onDragStart = { cursorX = it.x },
                            onHorizontalDrag = { change, _ -> cursorX = change.position.x },
                        )
                    },
            ) {
                cursorX?.let { x ->
                    drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height - axisHeight.toPx()), strokeWidth = 1.5f)
                }
            }
        }
    }
}

/**
 * Draws spans bucketed per pixel, shaded by how much of each pixel they cover. Dense lanes like
 * cell TX have thousands of toggles, and one-second camera bursts shouldn't read as solid bars.
 */
private fun DrawScope.drawSpans(spans: List<TimeSpan>, color: Color, top: Float, height: Float, x: (Long) -> Float) {
    val cols = size.width.toInt().coerceAtLeast(1)
    val cover = FloatArray(cols)
    for (s in spans) {
        val x0 = x(s.startMillis).coerceIn(0f, cols.toFloat())
        val x1 = x(s.endMillis).coerceIn(0f, cols.toFloat())
        if (x1 <= x0) continue
        var c = x0.toInt()
        while (c < cols && c < x1) {
            cover[c] += minOf(x1, c + 1f) - maxOf(x0, c.toFloat())
            c++
        }
    }
    var c = 0
    while (c < cols) {
        if (cover[c] <= 0f) {
            c++
            continue
        }
        val alpha = MIN_ALPHA + (1f - MIN_ALPHA) * cover[c].coerceAtMost(1f)
        var end = c + 1
        while (end < cols && cover[end] >= 1f && alpha == 1f) end++
        drawRect(color.copy(alpha = color.alpha * alpha), Offset(c.toFloat(), top), Size((end - c).toFloat(), height))
        c = end
    }
}

private const val MIN_ALPHA = 0.3f

private fun DrawScope.label(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset) {
    drawText(measurer, text, topLeft = at, style = style, maxLines = 1)
}

private fun readout(h: BatteryHistory, lanes: List<Lane>, t: Long): String {
    val level = h.levels.lastOrNull { it.millis <= t }?.level
    val active = lanes.filter { lane ->
        when (val d = lane.data) {
            is LaneData.Spans -> d.spans.any { t >= it.startMillis && t < it.endMillis }
            is LaneData.Levels -> false
        }
    }.map { it.label }
    val signal = h.state(BatteryHistory.SIGNAL).firstOrNull { t >= it.startMillis && t < it.endMillis }?.value
    val wakelock = h.tagged(BatteryHistory.WAKE_LOCK).firstOrNull { t >= it.startMillis && t < it.endMillis }?.tag
    val head = listOfNotNull(formatClock(t, h.durationMillis), level?.let { "$it%" }, signal?.let { "signal $it" })
    val states = (active.ifEmpty { listOf("Asleep") }) + listOfNotNull(wakelock?.let { "wakelock $it" })
    return head.joinToString(" · ") + "\n" + states.joinToString(", ")
}

private val clockShort = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
private val clockDay = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneOffset.UTC)

/** History millis are wall-clock time encoded as UTC, so format them in UTC. */
private fun formatClock(t: Long, span: Long): String =
    (if (span > 20 * 3_600_000L) clockDay else clockShort).format(Instant.ofEpochMilli(t))
