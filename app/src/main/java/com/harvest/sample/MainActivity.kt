package com.harvest.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.harvest.sample.ui.HarvesterApp

/**
 * Single activity host for the harvester appliance UI.
 */
class MainActivity : ComponentActivity() {
	private val viewModel: HarvesterViewModel by viewModels()

	private val openDoc = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri != null) {
			try {
				contentResolver.takePersistableUriPermission(
					uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION
				)
			} catch (_: SecurityException) {
				// Some providers do not support persistable permissions.
			}
			viewModel.openUri(uri)
		}
	}

	private val permissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) {
		// Storage may still work via SAF even if write permission is denied.
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(window, window.decorView).let { controller ->
			controller.hide(WindowInsetsCompat.Type.systemBars())
			controller.systemBarsBehavior =
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}

		requestStorageIfNeeded()

		setContent {
			val state by viewModel.state.collectAsState()
			HarvesterApp(
				state = state,
				onOpen = { openDoc.launch(arrayOf("audio/wav", "audio/*", "application/octet-stream")) },
				onShowSamples = { viewModel.showScreen(Screen.Samples) },
				onShowHarvest = { viewModel.showScreen(Screen.Harvest) },
				onTogglePlay = viewModel::togglePlay,
				onSave = viewModel::saveSelection,
				onSeek = viewModel::seekTo,
				onSelectionChanged = viewModel::setSelection,
				onAudition = viewModel::auditionSelection,
				onToggleSample = viewModel::toggleSample,
			)
		}
	}

	override fun onPause() {
		super.onPause()
		// Session persistence happens continuously in the ViewModel.
	}

	/** Ask for legacy storage access on Fire OS builds that still need it. */
	private fun requestStorageIfNeeded() {
		val needed = mutableListOf<String>()
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
			!= PackageManager.PERMISSION_GRANTED
		) {
			needed += Manifest.permission.READ_EXTERNAL_STORAGE
		}
		if (Build.VERSION.SDK_INT <= 32 &&
			ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
			!= PackageManager.PERMISSION_GRANTED
		) {
			needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
		}
		if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
	}
}
