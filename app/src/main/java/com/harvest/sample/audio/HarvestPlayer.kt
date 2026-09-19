package com.harvest.sample.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Thin MediaPlayer wrapper tuned for reliable local WAV playback on Fire OS.
 */
class HarvestPlayer {
	private var player: MediaPlayer? = null
	private var prepared = false

	val isPlaying: Boolean get() = player?.isPlaying == true

	/** Current playback position in milliseconds, or 0 if idle. */
	fun positionMs(): Int = try {
		player?.currentPosition ?: 0
	} catch (_: Exception) {
		0
	}

	/** Source duration in milliseconds, or 0 if unknown. */
	fun durationMs(): Int = try {
		if (prepared) player?.duration ?: 0 else 0
	} catch (_: Exception) {
		0
	}

	/**
	 * Load a local audio file and prepare for playback.
	 * Replaces any previous source.
	 */
	fun load(file: File, onPrepared: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
		release()
		try {
			val mp = MediaPlayer()
			mp.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_MEDIA)
					.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
					.build()
			)
			mp.setDataSource(file.absolutePath)
			mp.setOnPreparedListener {
				prepared = true
				onPrepared?.invoke()
			}
			mp.setOnErrorListener { _, what, extra ->
				prepared = false
				onError?.invoke("Can't play this file ($what/$extra)")
				true
			}
			mp.setOnCompletionListener {
				// Stay prepared so the user can press play again.
			}
			player = mp
			mp.prepareAsync()
		} catch (t: Throwable) {
			release()
			onError?.invoke("Can't play this file")
		}
	}

	/** Start or resume playback from the current position. */
	fun play() {
		val mp = player ?: return
		if (!prepared) return
		try {
			mp.start()
		} catch (_: Exception) {
			// Ignore transient start failures.
		}
	}

	/** Pause without resetting position. */
	fun pause() {
		try {
			player?.pause()
		} catch (_: Exception) {
			// Ignore.
		}
	}

	/** Seek to an absolute position in milliseconds. */
	fun seekMs(ms: Int) {
		val mp = player ?: return
		if (!prepared) return
		try {
			mp.seekTo(ms.coerceAtLeast(0))
		} catch (_: Exception) {
			// Ignore.
		}
	}

	/** Release the underlying player. */
	fun release() {
		try {
			player?.reset()
			player?.release()
		} catch (_: Exception) {
			// Ignore.
		}
		player = null
		prepared = false
	}
}
