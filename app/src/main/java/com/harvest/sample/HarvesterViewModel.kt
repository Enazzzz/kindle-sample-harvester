package com.harvest.sample

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harvest.sample.audio.HarvestPlayer
import com.harvest.sample.audio.WavExporter
import com.harvest.sample.audio.WavReader
import com.harvest.sample.audio.WavSource
import com.harvest.sample.audio.WaveformBuilder
import com.harvest.sample.data.SampleStore
import com.harvest.sample.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Which top-level screen is visible. */
enum class Screen { Harvest, Samples }

/**
 * UI state for the single-purpose harvester.
 */
data class HarvestUiState(
	val screen: Screen = Screen.Harvest,
	val sourceName: String = "Open a WAV",
	val sourcePath: String? = null,
	val durationSeconds: Double = 0.0,
	val positionSeconds: Double = 0.0,
	val isPlaying: Boolean = false,
	val peaks: FloatArray = FloatArray(0),
	val selectionStart: Double? = null,
	val selectionEnd: Double? = null,
	val isAuditioning: Boolean = false,
	/** When true, waveform shows a ~30s window around the playhead. */
	val zoomed: Boolean = false,
	val samples: List<String> = emptyList(),
	val playingSample: String? = null,
	val statusMessage: String? = null,
	val isBusy: Boolean = false,
) {
	/**
	 * Visible waveform time window.
	 * Zoom centers on the selection midpoint when both edges exist, otherwise the playhead.
	 */
	fun viewWindow(spanSeconds: Double = ZOOM_SPAN_SECONDS): Pair<Double, Double> {
		if (!zoomed || durationSeconds <= 0.0) return 0.0 to durationSeconds
		val span = minOf(spanSeconds, durationSeconds)
		val center = when {
			selectionStart != null && selectionEnd != null ->
				(selectionStart + selectionEnd) / 2.0
			else -> positionSeconds
		}
		var start = center - span / 2.0
		var end = center + span / 2.0
		if (start < 0.0) {
			end = (end - start).coerceAtMost(durationSeconds)
			start = 0.0
		}
		if (end > durationSeconds) {
			start = (start - (end - durationSeconds)).coerceAtLeast(0.0)
			end = durationSeconds
		}
		return start to end
	}

	companion object {
		const val ZOOM_SPAN_SECONDS = 30.0
	}
}

/**
 * Coordinates loading, playback, selection, export, and session restore.
 */
class HarvesterViewModel(app: Application) : AndroidViewModel(app) {
	private val sampleStore = SampleStore(app)
	private val sessionStore = SessionStore(app)
	private val player = HarvestPlayer()
	private val samplePlayer = HarvestPlayer()

	private val _state = MutableStateFlow(HarvestUiState())
	val state: StateFlow<HarvestUiState> = _state.asStateFlow()

	private var source: WavSource? = null
	private var tickJob: Job? = null
	private var statusJob: Job? = null
	private var auditionEndSeconds: Double? = null
	/** Durable import copies so session restore still finds the last source. */
	private var importDir = File(app.filesDir, "imports").also { it.mkdirs() }

	init {
		refreshSampleList()
		restoreSession()
		startTicker()
	}

	/** Switch between harvest and sample browser. */
	fun showScreen(screen: Screen) {
		if (screen == Screen.Samples) {
			samplePlayer.pause()
			refreshSampleList()
		}
		_state.update { it.copy(screen = screen) }
	}

	/** Import a user-picked WAV URI into a local cache file and open it. */
	fun openUri(uri: Uri) {
		viewModelScope.launch {
			_state.update { it.copy(isBusy = true, statusMessage = null) }
			try {
				val local = withContext(Dispatchers.IO) { copyUriToCache(uri) }
				openFile(local)
			} catch (t: Throwable) {
				flash("Unsupported file")
			} finally {
				_state.update { it.copy(isBusy = false) }
			}
		}
	}

	/** Open a local WAV path for harvesting. */
	fun openFile(file: File) {
		viewModelScope.launch {
			_state.update { it.copy(isBusy = true) }
			try {
				val wav = withContext(Dispatchers.IO) { WavReader.open(file) }
				val peaks = withContext(Dispatchers.IO) { WaveformBuilder.buildPeaks(wav) }
				source = wav
				player.load(
					file = file,
					onPrepared = {
						_state.update {
							it.copy(
								sourceName = file.nameWithoutExtension,
								sourcePath = file.absolutePath,
								durationSeconds = wav.durationSeconds,
								peaks = peaks,
								isBusy = false,
							)
						}
						persistSession()
					},
					onError = { msg ->
						flash(msg)
						_state.update { it.copy(isBusy = false) }
					}
				)
			} catch (t: Throwable) {
				flash("Can't play this file")
				_state.update { it.copy(isBusy = false) }
			}
		}
	}

	/** Toggle transport play/pause for the source recording. */
	fun togglePlay() {
		val s = _state.value
		if (s.sourcePath == null) {
			flash("Open a WAV")
			return
		}
		if (s.isPlaying) {
			player.pause()
			_state.update { it.copy(isPlaying = false, isAuditioning = false) }
			auditionEndSeconds = null
		} else {
			player.play()
			_state.update { it.copy(isPlaying = true) }
		}
	}

	/** Seek playback to an absolute position in seconds. */
	fun seekTo(seconds: Double) {
		val duration = _state.value.durationSeconds
		val clamped = seconds.coerceIn(0.0, duration)
		player.seekMs((clamped * 1000).toInt())
		_state.update { it.copy(positionSeconds = clamped, isAuditioning = false) }
		auditionEndSeconds = null
		persistSession()
	}

	/**
	 * Replace the current selection with a new region in seconds.
	 * @param audition when true, immediately play just that region.
	 */
	fun setSelection(start: Double, end: Double, audition: Boolean = true) {
		val duration = _state.value.durationSeconds
		val a = start.coerceIn(0.0, duration)
		val b = end.coerceIn(0.0, duration)
		if (kotlin.math.abs(b - a) < 0.01) {
			_state.update { it.copy(selectionStart = null, selectionEnd = null) }
			persistSession()
			return
		}
		_state.update {
			it.copy(
				selectionStart = minOf(a, b),
				selectionEnd = maxOf(a, b),
			)
		}
		persistSession()
		if (audition) auditionSelection()
	}

	/** Clear the selection. */
	fun clearSelection() {
		_state.update { it.copy(selectionStart = null, selectionEnd = null, isAuditioning = false) }
		auditionEndSeconds = null
		persistSession()
	}

	/** Toggle playhead-centered zoom for precise grabs on long recordings. */
	fun toggleZoom() {
		_state.update { it.copy(zoomed = !it.zoomed) }
	}

	/** Jump back a couple seconds — “I just heard something.” */
	fun jumpBack(seconds: Double = 2.0) {
		if (_state.value.sourcePath == null) return
		seekTo(_state.value.positionSeconds - seconds)
	}

	/**
	 * Mark selection start at the playhead while listening.
	 * Keeps an existing end if it is still ahead of the playhead.
	 */
	fun markIn() {
		if (_state.value.sourcePath == null) {
			flash("Open a WAV")
			return
		}
		val pos = _state.value.positionSeconds
		val end = _state.value.selectionEnd
		if (end != null && end > pos + 0.01) {
			setSelection(pos, end, audition = false)
		} else {
			_state.update { it.copy(selectionStart = pos, selectionEnd = null, isAuditioning = false) }
			auditionEndSeconds = null
			persistSession()
		}
	}

	/**
	 * Mark selection end at the playhead, then audition.
	 * If IN was never set, uses the previous 1 second.
	 */
	fun markOut() {
		if (_state.value.sourcePath == null) {
			flash("Open a WAV")
			return
		}
		val pos = _state.value.positionSeconds
		val start = _state.value.selectionStart
		if (start != null && pos > start + 0.01) {
			setSelection(start, pos, audition = true)
		} else {
			setSelection((pos - 1.0).coerceAtLeast(0.0), pos, audition = true)
		}
	}

	/** Play only the selected region, then pause. */
	fun auditionSelection() {
		val start = _state.value.selectionStart ?: return
		val end = _state.value.selectionEnd ?: return
		auditionEndSeconds = end
		player.seekMs((start * 1000).toInt())
		player.play()
		_state.update { it.copy(isPlaying = true, isAuditioning = true, positionSeconds = start) }
	}

	/** Export the selection as the next sample_####.wav and keep moving forward. */
	fun saveSelection() {
		val wav = source
		val start = _state.value.selectionStart
		val end = _state.value.selectionEnd
		if (wav == null || start == null || end == null) {
			flash("Select a region first")
			return
		}
		if (end - start < 0.01) {
			flash("Selection too short")
			return
		}

		val wasPlaying = player.isPlaying || _state.value.isAuditioning
		viewModelScope.launch {
			_state.update { it.copy(isBusy = true) }
			try {
				val out = withContext(Dispatchers.IO) {
					val dest = sampleStore.nextSampleFile()
					WavExporter.exportRegion(wav, dest, start, end)
					sampleStore.rememberProvenance(
						sampleName = dest.name,
						sourceName = wav.file.name,
						start = start,
						end = end,
					)
					dest
				}
				refreshSampleList()
				flash("saved ${out.nameWithoutExtension}")
				// Clear selection, park at the end of what we just took, keep listening.
				auditionEndSeconds = null
				_state.update {
					it.copy(
						selectionStart = null,
						selectionEnd = null,
						isAuditioning = false,
					)
				}
				seekTo(end)
				if (wasPlaying) {
					player.play()
					_state.update { it.copy(isPlaying = true) }
				}
				persistSession()
			} catch (t: Throwable) {
				flash("Couldn't save sample")
			} finally {
				_state.update { it.copy(isBusy = false) }
			}
		}
	}

	/** Tap a harvested sample in the browser to play/pause it. */
	fun toggleSample(name: String) {
		val current = _state.value.playingSample
		if (current == name && samplePlayer.isPlaying) {
			samplePlayer.pause()
			_state.update { it.copy(playingSample = null) }
			return
		}
		val file = File(sampleStore.samplesDir(), "$name.wav")
		if (!file.exists()) {
			flash("Couldn't play sample")
			refreshSampleList()
			return
		}
		samplePlayer.load(
			file = file,
			onPrepared = {
				samplePlayer.play()
				_state.update { it.copy(playingSample = name) }
			},
			onError = { flash("Can't play this file") }
		)
	}

	/** Dismiss the transient status cue. */
	fun consumeStatus() {
		_state.update { it.copy(statusMessage = null) }
	}

	private fun refreshSampleList() {
		val names = sampleStore.listSamples().map { it.nameWithoutExtension }
		_state.update { it.copy(samples = names) }
	}

	private fun restoreSession() {
		val session = sessionStore.load() ?: return
		val file = File(session.sourcePath)
		if (!file.exists()) return
		viewModelScope.launch {
			openFile(file)
			// Apply restored transport state after a short prepare window.
			delay(400)
			seekTo(session.positionSeconds)
			val a = session.selectionStart
			val b = session.selectionEnd
			if (a != null && b != null) setSelection(a, b, audition = false)
		}
	}

	private fun persistSession() {
		val s = _state.value
		val path = s.sourcePath ?: return
		sessionStore.save(
			SessionStore.Session(
				sourcePath = path,
				positionSeconds = s.positionSeconds,
				selectionStart = s.selectionStart,
				selectionEnd = s.selectionEnd,
			)
		)
	}

	private fun startTicker() {
		tickJob?.cancel()
		tickJob = viewModelScope.launch {
			while (isActive) {
				val pos = player.positionMs() / 1000.0
				val playing = player.isPlaying
				val end = auditionEndSeconds
				if (end != null && playing && pos >= end) {
					player.pause()
					auditionEndSeconds = null
					_state.update {
						it.copy(
							positionSeconds = end,
							isPlaying = false,
							isAuditioning = false,
						)
					}
					persistSession()
				} else {
					_state.update {
						it.copy(positionSeconds = pos, isPlaying = playing)
					}
				}
				delay(50)
			}
		}
	}

	private fun flash(message: String) {
		statusJob?.cancel()
		_state.update { it.copy(statusMessage = message) }
		statusJob = viewModelScope.launch {
			delay(1800)
			_state.update { it.copy(statusMessage = null) }
		}
	}

	/** Copy a content URI into app cache so MediaPlayer and WavReader can use a File. */
	private fun copyUriToCache(uri: Uri): File {
		val resolver = getApplication<Application>().contentResolver
		val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
			val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
		} ?: "import_${System.currentTimeMillis()}.wav"

		val safe = name.replace(Regex("""[^\w.\- ]+"""), "_")
		val dest = File(importDir, safe)
		resolver.openInputStream(uri)?.use { input ->
			FileOutputStream(dest).use { output -> input.copyTo(output) }
		} ?: error("Could not read file")
		return dest
	}

	override fun onCleared() {
		persistSession()
		tickJob?.cancel()
		statusJob?.cancel()
		player.release()
		samplePlayer.release()
		super.onCleared()
	}
}
