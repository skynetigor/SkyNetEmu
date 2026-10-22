package com.winlator.cmod.ui.settings

import android.app.Activity
import android.app.Dialog
import androidx.activity.ComponentDialog
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.perf.PerformanceSession
import com.winlator.cmod.perf.PerformanceTuning
import com.winlator.cmod.ui.theme.WinZTheme

private val tuningLabels = listOf("Sustained performance", "Boost thread priority", "Prefer performance cores", "GPU turbo")
private val tuningHelp = listOf(
    "Ask Android for a sustainable performance policy. Requires device support and can reduce peak speed.",
    "Try higher priority for game threads and audio/render workers. Android may deny the request.",
    "Keep game threads on cores at least 70% as fast as the fastest core, within your CPU selection. May keep all cores on 8 Elite.",
    "Unavailable: the legacy KGSL override caused severe performance regressions. Saved On values are ignored."
)

internal class PerformanceTuningEditorState(shortcut: Shortcut) {
    val values = mutableStateMapOf<String, String>().apply {
        PerformanceTuning.KEYS.forEach { put(it, shortcut.getExtra(it, "")) }
    }
    fun saveTo(shortcut: Shortcut) {
        PerformanceTuning.KEYS.forEach { shortcut.putExtra(it, values[it]?.ifEmpty { null }) }
    }
}

@Composable
private fun FramePipelineDiagnosticsToggle() {
    val prefs = PreferenceManager.getDefaultSharedPreferences(LocalContext.current)
    val key = com.winlator.cmod.linux.FramePipelineDiagnostics.KEY
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(key, false)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key || changed == null) enabled = prefs.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingToggle("Frame pipeline diagnostics", enabled,
        description = "Global setting for all games. Restart the game after changing this. Frame counters appear in the overlay and in-game Performance tuning dialog.") {
        prefs.edit().putBoolean(key, it).apply()
    }
}

@Composable
internal fun PerformanceTuningGlobalCard() {
    val prefs = PreferenceManager.getDefaultSharedPreferences(LocalContext.current)
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsCard {
        Text("Defaults for every game. Shortcuts can override each option. All options start off.", Modifier.padding(14.dp))
        SettingsDivider()
        FramePipelineDiagnosticsToggle()
        tuningLabels.forEachIndexed { i, label ->
            SettingsDivider()
            @Suppress("UNUSED_EXPRESSION") revision
            SettingToggle(label, i != 3 && prefs.getBoolean(PerformanceTuning.KEYS[i], false), enabled = i != 3, description = tuningHelp[i]) {
                prefs.edit().putBoolean(PerformanceTuning.KEYS[i], it).apply()
            }
        }
    }
}

@Composable
internal fun PerformanceTuningGameCard(state: PerformanceTuningEditorState, onChanged: () -> Unit = {}) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(LocalContext.current)
    SettingsCard {
        FramePipelineDiagnosticsToggle()
        SettingsDivider()
        Text("Overrides apply to this game in Linux and Wine. Requests are restored when the session ends or goes into the background.", Modifier.padding(14.dp))
        tuningLabels.forEachIndexed { i, label ->
            SettingsDivider()
            val key = PerformanceTuning.KEYS[i]
            if (i == 3) SettingToggle(label, false, enabled = false) {}
            else SettingMappedChoice(label, state.values[key] ?: "", linkedMapOf(
                "" to "Inherit global (${if (prefs.getBoolean(key, false)) "On" else "Off"})", "1" to "On", "0" to "Off"
            )) { state.values[key] = it; onChanged() }
            Text(tuningHelp[i], Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

object PerformanceTuningDialog {
    @JvmStatic fun show(activity: Activity, shortcut: Shortcut?, session: PerformanceSession?): Dialog {
        val dialog = ComponentDialog(activity)
        val content = ComposeView(activity).apply {
            setContent {
                WinZTheme {
                    Surface {
                        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Performance tuning", style = MaterialTheme.typography.titleLarge)
                            if (shortcut != null) {
                                val state = remember { PerformanceTuningEditorState(shortcut) }
                                PerformanceTuningGameCard(state) { state.saveTo(shortcut); shortcut.saveData(); session?.refresh() }
                            } else PerformanceTuningGlobalCard()
                            var status by remember { mutableStateOf(session?.status().orEmpty()) }
                            DisposableEffect(session) {
                                val handler = Handler(Looper.getMainLooper())
                                val poll = object : Runnable {
                                    override fun run() { status = session?.status().orEmpty() + "\n\n" + com.winlator.cmod.linux.FramePipelineDiagnostics.display(); handler.postDelayed(this, 1000) }
                                }
                                handler.post(poll)
                                onDispose { handler.removeCallbacks(poll) }
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { dialog.dismiss() }) { Text("Done") }
                        }
                    }
                }
            }
        }
        dialog.setContentView(content)
        dialog.setOnDismissListener { content.disposeComposition() }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .85).toInt(), (activity.resources.displayMetrics.heightPixels * .85).toInt())
        return dialog
    }
}
