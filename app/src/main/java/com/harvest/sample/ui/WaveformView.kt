package com.harvest.sample.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-width touch waveform: tap seeks, drag selects, edge-drag adjusts.
 * [viewStart]/[viewEnd] define the visible time window (full file or zoomed).
 */
@Composable
fun WaveformView(
	peaks: FloatArray,
	durationSeconds: Double,
	viewStart: Double,
	viewEnd: Double,
	positionSeconds: Double,
	selectionStart: Double?,
	selectionEnd: Double?,
	onSeek: (Double) -> Unit,
	onSelectionChanged: (Double, Double) -> Unit,
	onAudition: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val waveColor = Color(0xFF8FA3B8)
	val selectionFill = Color(0x553D7EA6)
	val selectionEdge = Color(0xFF7EC8E3)
	val playhead = Color(0xFFE8EEF5)
	val bg = Color(0xFF14181F)
	val edgeHitDp = 36.dp
	val density = LocalDensity.current
	val edgeHitPx = with(density) { edgeHitDp.toPx() }

	var dragMode by remember { mutableStateOf(DragMode.None) }
	var draftStart by remember { mutableStateOf<Double?>(null) }
	var draftEnd by remember { mutableStateOf<Double?>(null) }

	val drawStart = draftStart ?: selectionStart
	val drawEnd = draftEnd ?: selectionEnd
	val windowStart = viewStart.coerceAtLeast(0.0)
	val windowEnd = viewEnd.coerceAtLeast(windowStart + 0.001)
	val windowSpan = windowEnd - windowStart

	fun xToSeconds(x: Float, width: Float): Double {
		if (width <= 0f) return windowStart
		val t = (x / width).toDouble().coerceIn(0.0, 1.0)
		return (windowStart + t * windowSpan).coerceIn(0.0, durationSeconds)
	}

	fun secondsToX(seconds: Double, width: Float): Float {
		val t = ((seconds - windowStart) / windowSpan).toFloat()
		return (t * width)
	}

	Canvas(
		modifier = modifier
			.fillMaxWidth()
			.height(260.dp)
			.pointerInput(durationSeconds, windowStart, windowEnd, selectionStart, selectionEnd) {
				detectTapGestures { offset ->
					val width = size.width.toFloat()
					val seconds = xToSeconds(offset.x, width)
					val selA = selectionStart
					val selB = selectionEnd
					if (selA != null && selB != null && seconds in selA..selB) {
						onAudition()
					} else {
						onSeek(seconds)
					}
				}
			}
			.pointerInput(durationSeconds, windowStart, windowEnd, selectionStart, selectionEnd) {
				detectDragGestures(
					onDragStart = { offset ->
						val width = size.width.toFloat()
						val seconds = xToSeconds(offset.x, width)
						val selA = selectionStart
						val selB = selectionEnd
						dragMode = when {
							selA != null && abs(secondsToX(selA, width) - offset.x) <= edgeHitPx -> DragMode.Start
							selB != null && abs(secondsToX(selB, width) - offset.x) <= edgeHitPx -> DragMode.End
							else -> DragMode.Create
						}
						when (dragMode) {
							DragMode.Create -> {
								draftStart = seconds
								draftEnd = seconds
							}
							DragMode.Start -> {
								draftStart = seconds
								draftEnd = selB
							}
							DragMode.End -> {
								draftStart = selA
								draftEnd = seconds
							}
							DragMode.None -> Unit
						}
					},
					onDrag = { change, _ ->
						change.consume()
						val width = size.width.toFloat()
						val seconds = xToSeconds(change.position.x, width)
						when (dragMode) {
							DragMode.Create -> draftEnd = seconds
							DragMode.Start -> draftStart = seconds
							DragMode.End -> draftEnd = seconds
							DragMode.None -> Unit
						}
					},
					onDragEnd = {
						val a = draftStart
						val b = draftEnd
						if (a != null && b != null) {
							onSelectionChanged(min(a, b), max(a, b))
						}
						draftStart = null
						draftEnd = null
						dragMode = DragMode.None
					},
					onDragCancel = {
						draftStart = null
						draftEnd = null
						dragMode = DragMode.None
					}
				)
			}
	) {
		drawRect(bg)

		val w = size.width
		val h = size.height
		val mid = h / 2f
		val count = peaks.size.coerceAtLeast(1)

		// Map peak buckets that fall inside the visible window.
		val startFrac = if (durationSeconds > 0) (windowStart / durationSeconds).toFloat() else 0f
		val endFrac = if (durationSeconds > 0) (windowEnd / durationSeconds).toFloat() else 1f
		val firstBucket = (startFrac * count).toInt().coerceIn(0, count - 1)
		val lastBucket = (endFrac * count).toInt().coerceIn(firstBucket + 1, count)
		val visibleBuckets = (lastBucket - firstBucket).coerceAtLeast(1)
		val step = w / visibleBuckets

		// Selection highlight behind the waveform.
		if (drawStart != null && drawEnd != null && durationSeconds > 0.0) {
			val left = secondsToX(min(drawStart, drawEnd), w).coerceIn(0f, w)
			val right = secondsToX(max(drawStart, drawEnd), w).coerceIn(0f, w)
			if (right > 0f && left < w) {
				drawRect(
					color = selectionFill,
					topLeft = Offset(left, 0f),
					size = Size((right - left).coerceAtLeast(2f), h),
				)
				if (left in 0f..w) {
					drawLine(selectionEdge, Offset(left, 0f), Offset(left, h), strokeWidth = 5f, cap = StrokeCap.Round)
				}
				if (right in 0f..w) {
					drawLine(selectionEdge, Offset(right, 0f), Offset(right, h), strokeWidth = 5f, cap = StrokeCap.Round)
				}
			}
		}

		// Waveform bars for the visible window only.
		for (i in 0 until visibleBuckets) {
			val bucket = firstBucket + i
			if (bucket !in peaks.indices) continue
			val amp = peaks[bucket].coerceIn(0f, 1f)
			val barH = max(2f, amp * (h * 0.86f))
			val x = i * step + step / 2f
			drawLine(
				color = waveColor,
				start = Offset(x, mid - barH / 2f),
				end = Offset(x, mid + barH / 2f),
				strokeWidth = max(1.5f, step * 0.7f),
				cap = StrokeCap.Round,
			)
		}

		// Playhead (only when inside the visible window).
		if (durationSeconds > 0.0 && positionSeconds in windowStart..windowEnd) {
			val x = secondsToX(positionSeconds, w)
			drawLine(
				color = playhead,
				start = Offset(x, 8f),
				end = Offset(x, h - 8f),
				strokeWidth = 3f,
				cap = StrokeCap.Round,
			)
			drawCircle(playhead, radius = 8f, center = Offset(x, mid))
		}

		drawRect(Color(0x22FFFFFF), style = Stroke(width = 2f))
	}
}

private enum class DragMode { None, Create, Start, End }
