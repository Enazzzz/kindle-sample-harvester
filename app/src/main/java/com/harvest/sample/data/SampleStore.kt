package com.harvest.sample.data

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * Resolves the Samples directory, allocates the next sample_####.wav name,
 * and keeps invisible provenance metadata.
 */
class SampleStore(private val context: Context) {
	private val prefs = context.getSharedPreferences("harvester", Context.MODE_PRIVATE)

	/** Directory where harvested WAVs live (SD preferred, internal fallback). */
	fun samplesDir(): File {
		val preferred = preferredSamplesRoot()
		if (!preferred.exists()) preferred.mkdirs()
		return preferred
	}

	/**
	 * Prefer a removable SD card Samples folder when one is mounted and writable.
	 * Fall back to shared primary storage, then app-specific external files.
	 */
	private fun preferredSamplesRoot(): File {
		// Removable volumes appear as secondary entries from getExternalFilesDirs.
		val externalDirs = context.getExternalFilesDirs(null)?.filterNotNull().orEmpty()
		for (dir in externalDirs.drop(1)) {
			val root = findVolumeRoot(dir)
			if (root != null && root.canWrite()) {
				val samples = File(root, "Samples")
				if (samples.exists() || samples.mkdirs()) return samples
			}
		}

		// Primary shared storage: /storage/emulated/0/Samples
		val primary = Environment.getExternalStorageDirectory()
		if (primary != null && primary.exists()) {
			val samples = File(primary, "Samples")
			if (samples.exists() || samples.mkdirs()) return samples
		}

		// Last resort: app-specific folder (always available).
		val app = context.getExternalFilesDir("Samples") ?: File(context.filesDir, "Samples")
		if (!app.exists()) app.mkdirs()
		return app
	}

	/** Walk up from an app-specific path to the volume root when possible. */
	private fun findVolumeRoot(appSpecific: File): File? {
		// Typical path: /storage/XXXX-XXXX/Android/data/<pkg>/files
		var current: File? = appSpecific
		repeat(6) {
			val parent = current?.parentFile ?: return current
			if (parent.name.equals("Android", ignoreCase = true)) {
				return parent.parentFile
			}
			current = parent
		}
		return null
	}

	/** Next free sample_0001.wav style file in the Samples directory. */
	fun nextSampleFile(): File {
		val dir = samplesDir()
		val existing = dir.listFiles()
			?.mapNotNull { parseSampleNumber(it.name) }
			?.toSet()
			.orEmpty()

		var n = 1
		while (existing.contains(n)) n++
		return File(dir, "sample_%04d.wav".format(n))
	}

	/** List harvested samples newest-last for the browser. */
	fun listSamples(): List<File> {
		val dir = samplesDir()
		return dir.listFiles { f -> f.isFile && f.name.matches(SAMPLE_REGEX) }
			?.sortedBy { parseSampleNumber(it.name) ?: 0 }
			.orEmpty()
	}

	/** Persist invisible provenance for a saved sample. */
	fun rememberProvenance(sampleName: String, sourceName: String, start: Double, end: Double) {
		val root = JSONObject(prefs.getString(KEY_PROVENANCE, "{}") ?: "{}")
		root.put(
			sampleName,
			JSONObject()
				.put("source", sourceName)
				.put("start", start)
				.put("end", end)
		)
		prefs.edit().putString(KEY_PROVENANCE, root.toString()).apply()
	}

	private fun parseSampleNumber(name: String): Int? {
		val match = SAMPLE_REGEX.matchEntire(name) ?: return null
		return match.groupValues[1].toIntOrNull()
	}

	companion object {
		private val SAMPLE_REGEX = Regex("""sample_(\d{4})\.wav""", RegexOption.IGNORE_CASE)
		private const val KEY_PROVENANCE = "provenance_json"
	}
}

/**
 * Remembers the last harvesting session so the user can continue later.
 */
class SessionStore(context: Context) {
	private val prefs = context.getSharedPreferences("harvester_session", Context.MODE_PRIVATE)

	data class Session(
		val sourcePath: String,
		val positionSeconds: Double,
		val selectionStart: Double?,
		val selectionEnd: Double?,
	)

	fun save(session: Session) {
		prefs.edit()
			.putString(KEY_PATH, session.sourcePath)
			.putFloat(KEY_POSITION, session.positionSeconds.toFloat())
			.putBoolean(KEY_HAS_SELECTION, session.selectionStart != null && session.selectionEnd != null)
			.putFloat(KEY_SEL_START, (session.selectionStart ?: 0.0).toFloat())
			.putFloat(KEY_SEL_END, (session.selectionEnd ?: 0.0).toFloat())
			.apply()
	}

	fun load(): Session? {
		val path = prefs.getString(KEY_PATH, null) ?: return null
		if (!File(path).exists()) return null
		val hasSelection = prefs.getBoolean(KEY_HAS_SELECTION, false)
		return Session(
			sourcePath = path,
			positionSeconds = prefs.getFloat(KEY_POSITION, 0f).toDouble(),
			selectionStart = if (hasSelection) prefs.getFloat(KEY_SEL_START, 0f).toDouble() else null,
			selectionEnd = if (hasSelection) prefs.getFloat(KEY_SEL_END, 0f).toDouble() else null,
		)
	}

	companion object {
		private const val KEY_PATH = "source_path"
		private const val KEY_POSITION = "position_sec"
		private const val KEY_HAS_SELECTION = "has_selection"
		private const val KEY_SEL_START = "sel_start"
		private const val KEY_SEL_END = "sel_end"
	}
}
