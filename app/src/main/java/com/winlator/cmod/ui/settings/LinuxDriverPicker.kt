package com.winlator.cmod.ui.settings

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.linux.LinuxDriverManager
import com.winlator.cmod.linux.LinuxRuntimeCatalog
import com.winlator.cmod.linux.LinuxRuntimeInstallTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LinuxDriverPickerState {
    var options by mutableStateOf(listOf(DriverOption("", "Runtime default", true)))
    var installing by mutableStateOf<Set<String>>(emptySet())
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var retry by mutableIntStateOf(0)
    var install: (DriverOption) -> Unit = {}
}

/** Install through the shared checksum-verifying Linux task; selection changes only after success. */
@Composable
internal fun rememberLinuxDriverPicker(selected: String, onSelected: (String) -> Unit): LinuxDriverPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LinuxDriverPickerState() }
    val select by rememberUpdatedState(onSelected)
    var catalog by remember { mutableStateOf<List<LinuxRuntimeCatalog.Entry>>(emptyList()) }
    LaunchedEffect(state.retry) {
        state.loading = true
        val installed = withContext(Dispatchers.IO) { LinuxDriverManager.list(context) }
        val remote = withContext(Dispatchers.IO) { LinuxRuntimeCatalog.fetchDrivers() }
        catalog = remote.orEmpty()
        val options = mutableListOf(DriverOption("", "Runtime default", true))
        options += installed.map { DriverOption(it.id, it.label(), true) }
        // Keep installs that completed while the registry request was in flight.
        options += state.options.filter { it.installed && options.none { known -> known.id == it.id } }
        options += catalog.filter { entry -> options.none { it.id == entry.id } }.map {
            val label = it.name + if (it.channel == "prerelease" && !it.name.contains("experimental", true)) " (experimental)" else ""
            DriverOption(it.id, label, false, it.url)
        }
        if (selected.isNotEmpty() && options.none { it.id == selected })
            options += DriverOption(selected, "$selected (not installed)", false)
        state.options = options
        state.failed = remote == null
        state.loading = false
    }
    state.install = install@ { option ->
        if (state.installing.isNotEmpty()) return@install
        val entry = catalog.firstOrNull { it.id == option.id } ?: return@install
        if (!LinuxRuntimeInstallTask.start(context, entry)) {
            Toast.makeText(context, "Another Linux component is being installed. Try again when it finishes.", Toast.LENGTH_LONG).show()
            return@install
        }
        state.installing = setOf("driver:${option.remoteUrl ?: option.id}")
        scope.launch {
            try {
                // Do not replace the shared task's listener: other screens may be observing it.
                while (LinuxRuntimeInstallTask.runningId() != null) delay(200)
                val installed = withContext(Dispatchers.IO) { LinuxDriverManager.isInstalled(context, entry.id) }
                if (installed) {
                    state.options = state.options.filterNot { it.id == entry.id } + DriverOption(entry.id, entry.name, true)
                    select(entry.id)
                } else {
                    Toast.makeText(context, LinuxRuntimeInstallTask.lastError() ?: "Driver installation failed. Previous driver kept.", Toast.LENGTH_LONG).show()
                }
            } finally {
                state.installing = emptySet()
            }
        }
    }
    return state
}

@Composable
internal fun LinuxDriverPicker(selected: String, state: LinuxDriverPickerState, onSelected: (String) -> Unit) {
    SettingDriverChoice("Turnip driver", selected, state.options, state.installing, state.install, {
        if (state.installing.isEmpty()) onSelected(it)
    })
    if (state.loading) Text("Loading Linux drivers…")
    if (state.failed) TextButton(onClick = { state.retry++ }) { Text("Unable to load Linux drivers. Retry") }
}
