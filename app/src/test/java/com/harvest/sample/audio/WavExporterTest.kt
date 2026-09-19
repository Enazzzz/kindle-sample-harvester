package com.harvest.sample.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies lossless PCM region export preserves format and sample bytes.
 */
class WavExporterTest {
	@Test
	fun exportRegion_copiesExactPcmBytes() {
		val dir = createTempDir(prefix = "wav-test")
		val sourceFile = File(dir, "source.wav")
		val pcm = shortArrayOf(0, 1000, -1000, 16000, -16000, 0, 500, -500)
		writePcm16MonoWav(sourceFile, sampleRate = 44100, pcm = pcm)

		val source = WavReader.open(sourceFile)
		assertEquals(44100, source.sampleRate)
		assertEquals(1, source.channels)
		assertEquals(16, source.bitsPerSample)
		assertEquals(pcm.size.toLong(), source.frameCount)

		val out = File(dir, "sample_0001.wav")
		// Frames 2..5 inclusive-exclusive → indices 2,3,4
		WavExporter.exportRegion(source, out, startSeconds = 2.0 / 44100.0, endSeconds = 5.0 / 44100.0)

		val exported = WavReader.open(out)
		assertEquals(44100, exported.sampleRate)
		assertEquals(1, exported.channels)
		assertEquals(16, exported.bitsPerSample)
		assertEquals(3L, exported.frameCount)

		val expected = byteArrayOf(
			// -1000
			(0x18).toByte(), (0xFC).toByte(),
			// 16000
			(0x80).toByte(), (0x3E).toByte(),
			// -16000
			(0x80).toByte(), (0xC1).toByte(),
		)
		val actual = out.readBytes().copyOfRange(exported.dataOffset.toInt(), (exported.dataOffset + exported.dataSize).toInt())
		assertArrayEquals(expected, actual)
		assertTrue(out.length() > 44)
	}

	/** Write a tiny mono 16-bit PCM WAV for tests. */
	private fun writePcm16MonoWav(file: File, sampleRate: Int, pcm: ShortArray) {
		val dataBytes = pcm.size * 2
		val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
		buffer.put("RIFF".toByteArray())
		buffer.putInt(36 + dataBytes)
		buffer.put("WAVE".toByteArray())
		buffer.put("fmt ".toByteArray())
		buffer.putInt(16)
		buffer.putShort(1)
		buffer.putShort(1)
		buffer.putInt(sampleRate)
		buffer.putInt(sampleRate * 2)
		buffer.putShort(2)
		buffer.putShort(16)
		buffer.put("data".toByteArray())
		buffer.putInt(dataBytes)
		for (s in pcm) buffer.putShort(s)
		file.writeBytes(buffer.array())
	}
}
