package com.harvest.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harvest.sample.HarvestUiState
import com.harvest.sample.Screen

private val BgTop = Color(0xFF0B0D10)
private val BgBottom = Color(0xFF12161C)
private val Ink = Color(0xFFE7EDF5)
private val InkDim = Color(0xFF8B97A8)
private val Accent = Color(0xFF7EC8E3)
private val ButtonFill = Color(0xFF1B222C)
private val ButtonStroke = Color(0xFF2C3644)

/**
 * Root dark appliance chrome hosting harvest + samples screens.
 */
@Composable
fun HarvesterApp(
	state: HarvestUiState,
	onOpen: () -> Unit,
	onShowSamples: () -> Unit,
	onShowHarvest: () -> Unit,
	onTogglePlay: () -> Unit,
	onSave: () -> Unit,
	onSeek: (Double) -> Unit,
	onSelectionChanged: (Double, Double) -> Unit,
	onAudition: () -> Unit,
	onToggleSample: (String) -> Unit,
) {
	val brush = Brush.verticalGradient(listOf(BgTop, BgBottom))
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(brush)
	) {
		when (state.screen) {
			Screen.Harvest -> HarvestScreen(
				state = state,
				onOpen = onOpen,
				onShowSamples = onShowSamples,
				onTogglePlay = onTogglePlay,
				onSave = onSave,
				onSeek = onSeek,
				onSelectionChanged = onSelectionChanged,
				onAudition = onAudition,
			)
			Screen.Samples -> SamplesScreen(
				state = state,
				onBack = onShowHarvest,
				onToggleSample = onToggleSample,
			)
		}

		val message = state.statusMessage
		if (message != null) {
			Text(
				text = message,
				color = Ink,
				fontSize = 18.sp,
				fontFamily = FontFamily.Monospace,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(bottom = 28.dp)
					.background(Color(0xCC1B222C), RoundedCornerShape(10.dp))
					.padding(horizontal = 18.dp, vertical = 10.dp)
			)
		}
	}
}

@Composable
private fun HarvestScreen(
	state: HarvestUiState,
	onOpen: () -> Unit,
	onShowSamples: () -> Unit,
	onTogglePlay: () -> Unit,
	onSave: () -> Unit,
	onSeek: (Double) -> Unit,
	onSelectionChanged: (Double, Double) -> Unit,
	onAudition: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 28.dp, vertical = 20.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			TopLink("Open", onOpen)
			TopLink("Samples", onShowSamples)
		}

		Spacer(Modifier.height(18.dp))

		Text(
			text = state.sourceName,
			color = Ink,
			fontSize = 34.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(Modifier.height(22.dp))

		WaveformView(
			peaks = state.peaks,
			durationSeconds = state.durationSeconds,
			positionSeconds = state.positionSeconds,
			selectionStart = state.selectionStart,
			selectionEnd = state.selectionEnd,
			onSeek = onSeek,
			onSelectionChanged = onSelectionChanged,
			onAudition = onAudition,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(Modifier.height(14.dp))

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			TimeLabel(
				if (state.selectionStart != null) formatTime(state.selectionStart)
				else formatTime(state.positionSeconds)
			)
			TimeLabel(
				if (state.selectionEnd != null) formatTime(state.selectionEnd)
				else formatTime(state.durationSeconds)
			)
		}

		Spacer(Modifier.weight(1f))

		BigButton(
			label = if (state.isPlaying) "PAUSE" else "PLAY",
			emphasized = true,
			onClick = onTogglePlay,
		)

		Spacer(Modifier.height(16.dp))

		BigButton(
			label = "SAVE",
			emphasized = false,
			enabled = state.selectionStart != null && state.selectionEnd != null && !state.isBusy,
			onClick = onSave,
		)

		Spacer(Modifier.height(12.dp))
	}
}

@Composable
private fun SamplesScreen(
	state: HarvestUiState,
	onBack: () -> Unit,
	onToggleSample: (String) -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 28.dp, vertical = 20.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			TopLink("Back", onBack)
			Text(
				text = "SAMPLES",
				color = InkDim,
				fontSize = 16.sp,
				letterSpacing = 2.sp,
				fontWeight = FontWeight.Medium,
			)
			Spacer(Modifier.widthIn(min = 48.dp))
		}

		Spacer(Modifier.height(20.dp))

		if (state.samples.isEmpty()) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text("No samples yet", color = InkDim, fontSize = 22.sp)
			}
		} else {
			LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				items(state.samples, key = { it }) { name ->
					val playing = state.playingSample == name
					Text(
						text = name,
						color = if (playing) Accent else Ink,
						fontSize = 28.sp,
						fontFamily = FontFamily.Monospace,
						modifier = Modifier
							.fillMaxWidth()
							.clickable { onToggleSample(name) }
							.padding(vertical = 14.dp, horizontal = 4.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun TopLink(label: String, onClick: () -> Unit) {
	Text(
		text = label,
		color = Accent,
		fontSize = 20.sp,
		fontWeight = FontWeight.Medium,
		modifier = Modifier
			.clickable(onClick = onClick)
			.padding(8.dp)
	)
}

@Composable
private fun TimeLabel(text: String) {
	Text(
		text = text,
		color = InkDim,
		fontSize = 20.sp,
		fontFamily = FontFamily.Monospace,
	)
}

@Composable
private fun BigButton(
	label: String,
	emphasized: Boolean,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	val bg = when {
		!enabled -> ButtonFill.copy(alpha = 0.45f)
		emphasized -> Color(0xFF243140)
		else -> ButtonFill
	}
	val fg = if (enabled) Ink else InkDim
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(72.dp)
			.background(bg, RoundedCornerShape(14.dp))
			.clickable(enabled = enabled, onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			color = fg,
			fontSize = 28.sp,
			fontWeight = FontWeight.SemiBold,
			letterSpacing = 3.sp,
		)
	}
}

/** Format seconds as mm:ss.mmm for the selection readout. */
fun formatTime(seconds: Double): String {
	val totalMs = (seconds.coerceAtLeast(0.0) * 1000).toLong()
	val m = totalMs / 60000
	val s = (totalMs % 60000) / 1000
	val ms = totalMs % 1000
	return "%02d:%02d.%03d".format(m, s, ms)
}
