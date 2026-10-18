package com.winlator.cmod.ui.settings

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

internal class RendererDriverPickerState {
    var options by mutableStateOf(listOf(DriverOption("system", "System", true)))
    var installing by mutableStateOf<Set<String>>(emptySet())
    var loading by mutableStateOf(true)
    var install: (DriverOption) -> Unit = {}
}

/** Keep downloads and selection alive when the editor switches categories. */
@Composable
internal fun rememberRendererDriverPicker(
    selected: String, onSelected: (String) -> Unit
): RendererDriverPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { RendererDriverPickerState() }
    val select by rememberUpdatedState(onSelected)
    LaunchedEffect(Unit) {
        state.options = loadAdrenoDriverOptions(context, selected, includeRemote = false)
        val remote = loadAdrenoDriverOptions(context, selected)
        // An install may finish while the registry is loading: retain its actual installed ID.
        val installed = state.options.filter { it.installed }
        state.options = installed + remote.filter { candidate ->
            installed.none { it.id == candidate.id || it.label == candidate.label }
        }
        state.loading = false
    }
    state.install = { option ->
        if (state.installing.isEmpty()) {
            val key = "driver:${option.remoteUrl ?: option.id}"
            state.installing = setOf(key)
            scope.launch {
                try {
                    val id = installAdrenoDriver(context, option)
                    if (id == null) {
                        Toast.makeText(context, "Unable to install ${option.label}. Previous driver kept.", Toast.LENGTH_LONG).show()
                    } else {
                        // Installation may assign an ID different from the registry's display name.
                        state.options = state.options.filterNot {
                            (option.remoteUrl != null && it.remoteUrl == option.remoteUrl) || it.id == id
                        } + DriverOption(id, option.label, true)
                        select(id)
                    }
                } finally {
                    state.installing = emptySet()
                }
            }
        }
    }
    return state
}

/** Android AdrenoTools drivers, shared with Wine; Linux's glibc Turnip packages are separate. */
@Composable
internal fun RendererDriverPicker(selected: String, state: RendererDriverPickerState, onSelected: (String) -> Unit) {
    SettingDriverChoice("Renderer driver", selected, state.options, state.installing, state.install, {
        if (state.installing.isEmpty()) onSelected(it)
    })
    if (state.loading) Text("Loading available drivers…")
}
