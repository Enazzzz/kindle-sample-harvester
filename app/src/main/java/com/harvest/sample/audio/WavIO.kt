package com.harvest.sample.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed PCM WAV source used for waveform drawing and lossless region export.
 */
data class WavSource(
	val file: File,
	val sampleRate: Int,
	val channels: Int,
	val bitsPerSample: Int,
	val byteRate: Int,
	val blockAlign: Int,
	val dataOffset: Long,
	val dataSize: Long,
) {
	val bytesPerSample: Int get() = bitsPerSample / 8
	val frameCount: Long get() = if (blockAlign == 0) 0 else dataSize / blockAlign
	val durationSeconds: Double get() = if (sampleRate == 0) 0.0 else frameCount.toDouble() / sampleRate

	/** Convert a playback position in seconds to a PCM frame index. */
	fun frameAt(seconds: Double): Long {
		val frame = (seconds * sampleRate).toLong()
		return frame.coerceIn(0L, frameCount)
	}

	/** Convert a frame index to seconds. */
	fun secondsAt(frame: Long): Double {
		if (sampleRate == 0) return 0.0
		return frame.toDouble() / sampleRate
	}
}

/**
 * Reads standard PCM WAV headers (fmt + data chunks) without altering the source file.
 */
object WavReader {
	/** Open and validate a PCM WAV file. Throws on unsupported layouts. */
	fun open(file: File): WavSource {
		RandomAccessFile(file, "r").use { raf ->
			fun readId(): String {
				val bytes = ByteArray(4)
				raf.readFully(bytes)
				return String(bytes, Charsets.US_ASCII)
			}

			fun readIntLe(): Int {
				val bytes = ByteArray(4)
				raf.readFully(bytes)
				return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
			}

			fun readShortLe(): Int {
				val bytes = ByteArray(2)
				raf.readFully(bytes)
				return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
			}

			val riff = readId()
			if (riff != "RIFF") error("Not a WAV file")
			readIntLe() // file size minus 8
			val wave = readId()
			if (wave != "WAVE") error("Not a WAV file")

			var audioFormat = 0
			var channels = 0
			var sampleRate = 0
			var byteRate = 0
			var blockAlign = 0
			var bitsPerSample = 0
			var dataOffset = -1L
			var dataSize = -1L

			while (raf.filePointer < raf.length()) {
				val chunkId = readId()
				val chunkSize = readIntLe().toLong() and 0xFFFFFFFFL
				val chunkStart = raf.filePointer

				when (chunkId) {
					"fmt " -> {
						audioFormat = readShortLe()
						channels = readShortLe()
						sampleRate = readIntLe()
						byteRate = readIntLe()
						blockAlign = readShortLe()
						bitsPerSample = readShortLe()
					}
					"data" -> {
						dataOffset = chunkStart
						dataSize = chunkSize
						break
					}
					else -> {
						// Skip unknown chunks (including LIST/INFO).
					}
				}

				val next = chunkStart + chunkSize + (chunkSize % 2)
				if (next <= raf.filePointer) break
				raf.seek(next)
			}

			if (dataOffset < 0 || dataSize < 0) error("WAV has no data chunk")
			if (audioFormat != 1) error("Only PCM WAV is supported")
			if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
				error("Unsupported bit depth: $bitsPerSample")
			}
			if (channels < 1 || channels > 2) error("Only mono or stereo WAV is supported")

			return WavSource(
				file = file,
				sampleRate = sampleRate,
				channels = channels,
				bitsPerSample = bitsPerSample,
				byteRate = byteRate,
				blockAlign = blockAlign,
				dataOffset = dataOffset,
				dataSize = dataSize,
			)
		}
	}
}

/**
 * Builds a compact peak array for full-width waveform drawing.
 */
object WaveformBuilder {
	/**
	 * Sample the PCM stream into [bucketCount] absolute peak values in 0f..1f.
	 * Keeps memory low on older Fire tablets by streaming in chunks.
	 */
	fun buildPeaks(source: WavSource, bucketCount: Int = 1200): FloatArray {
		if (source.frameCount <= 0L || bucketCount <= 0) return FloatArray(0)

		val peaks = FloatArray(bucketCount)
		val framesPerBucket = source.frameCount.toDouble() / bucketCount
		val bytesPerFrame = source.blockAlign
		val bufferFrames = 4096
		val buffer = ByteArray(bufferFrames * bytesPerFrame)

		RandomAccessFile(source.file, "r").use { raf ->
			raf.seek(source.dataOffset)
			var frameIndex = 0L
			val totalFrames = source.frameCount

			while (frameIndex < totalFrames) {
				val framesToRead = minOf(bufferFrames.toLong(), totalFrames - frameIndex).toInt()
				val bytesToRead = framesToRead * bytesPerFrame
				raf.readFully(buffer, 0, bytesToRead)

				for (i in 0 until framesToRead) {
					val bucket = ((frameIndex + i) / framesPerBucket).toInt().coerceIn(0, bucketCount - 1)
					val abs = framePeak(buffer, i * bytesPerFrame, source)
					if (abs > peaks[bucket]) peaks[bucket] = abs
				}
				frameIndex += framesToRead
			}
		}

		// Soft floor so quiet recordings still draw something visible.
		var max = 0.0001f
		for (v in peaks) if (v > max) max = v
		for (i in peaks.indices) peaks[i] = (peaks[i] / max).coerceIn(0f, 1f)
		return peaks
	}

	/** Absolute peak across channels for one PCM frame. */
	private fun framePeak(buffer: ByteArray, offset: Int, source: WavSource): Float {
		var peak = 0f
		for (ch in 0 until source.channels) {
			val sampleOffset = offset + ch * source.bytesPerSample
			val sample = when (source.bitsPerSample) {
				8 -> {
					// 8-bit WAV is unsigned.
					((buffer[sampleOffset].toInt() and 0xFF) - 128) / 128f
				}
				16 -> {
					val lo = buffer[sampleOffset].toInt() and 0xFF
					val hi = buffer[sampleOffset + 1].toInt()
					((hi shl 8) or lo).toShort() / 32768f
				}
				24 -> {
					val b0 = buffer[sampleOffset].toInt() and 0xFF
					val b1 = buffer[sampleOffset + 1].toInt() and 0xFF
					val b2 = buffer[sampleOffset + 2].toInt()
					val value = (b2 shl 16) or (b1 shl 8) or b0
					val signed = if (value and 0x800000 != 0) value or -0x1000000 else value
					signed / 8388608f
				}
				32 -> {
					val b0 = buffer[sampleOffset].toInt() and 0xFF
					val b1 = buffer[sampleOffset + 1].toInt() and 0xFF
					val b2 = buffer[sampleOffset + 2].toInt() and 0xFF
					val b3 = buffer[sampleOffset + 3].toInt()
					val value = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
					value / 2147483648f
				}
				else -> 0f
			}
			val abs = kotlin.math.abs(sample)
			if (abs > peak) peak = abs
		}
		return peak
	}
}

/**
 * Exports a selected region to a new PCM WAV without processing the audio.
 */
object WavExporter {
	/**
	 * Copy [startSeconds]..[endSeconds] from [source] into [dest] as a valid WAV.
	 * Preserves sample rate, channels, and bit depth.
	 */
	fun exportRegion(source: WavSource, dest: File, startSeconds: Double, endSeconds: Double) {
		val startFrame = source.frameAt(startSeconds.coerceAtLeast(0.0))
		val endFrame = source.frameAt(endSeconds.coerceAtLeast(0.0)).coerceAtLeast(startFrame)
		val frameCount = endFrame - startFrame
		val dataBytes = frameCount * source.blockAlign

		dest.parentFile?.mkdirs()
		RandomAccessFile(dest, "rw").use { out ->
			out.setLength(0)
			writeHeader(out, source, dataBytes)

			RandomAccessFile(source.file, "r").use { input ->
				input.seek(source.dataOffset + startFrame * source.blockAlign)
				val buffer = ByteArray(64 * 1024)
				var remaining = dataBytes
				while (remaining > 0) {
					val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
					if (n <= 0) break
					out.write(buffer, 0, n)
					remaining -= n
				}
			}
		}
	}

	/** Write a minimal PCM WAV header for [dataBytes] of sample data. */
	private fun writeHeader(out: RandomAccessFile, source: WavSource, dataBytes: Long) {
		val dataSize32 = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		fun writeString(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
		fun writeInt(value: Int) {
			val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
			out.write(bytes)
		}
		fun writeShort(value: Int) {
			val bytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
			out.write(bytes)
		}

		writeString("RIFF")
		writeInt(36 + dataSize32)
		writeString("WAVE")
		writeString("fmt ")
		writeInt(16)
		writeShort(1) // PCM
		writeShort(source.channels)
		writeInt(source.sampleRate)
		writeInt(source.byteRate)
		writeShort(source.blockAlign)
		writeShort(source.bitsPerSample)
		writeString("data")
		writeInt(dataSize32)
	}
}
