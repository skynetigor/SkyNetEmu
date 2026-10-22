package com.winlator.cmod.ui.container

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.linux.FexComponents
import com.winlator.cmod.linux.LinuxRuntimeCatalog
import com.winlator.cmod.linux.LinuxRuntimeInstallTask
import com.winlator.cmod.linux.LinuxRuntimeInstaller
import com.winlator.cmod.steam.SteamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What FEX is missing to work, with a button that downloads all of it (a FEX build and the x86-64 root filesystem it runs in).
 * Shows nothing once everything is installed, so it can be put wherever FEX is chosen. It follows the app's one runtime install
 * by asking it, not by listening, so a screen that has its own listener keeps it. [onInstalled] is called when a download that
 * this prompt started or saw has finished, to let the screen read what is installed again.
 */
@Composable
fun FexDownloadPrompt(modifier: Modifier = Modifier, onInstalled: () -> Unit = {}) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val complete = remember(tick) { FexComponents.complete(context) }
    if (!complete) FexDownloadBody(modifier, tick, { tick++ }, onInstalled)
}

@Composable
private fun FexDownloadBody(modifier: Modifier, tick: Int, bump: () -> Unit, onInstalled: () -> Unit) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf<List<LinuxRuntimeCatalog.Entry>?>(null) }
    var planFailed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(LinuxRuntimeInstallTask.runningId() != null) }
    var currentId by remember { mutableStateOf(LinuxRuntimeInstallTask.runningId().orEmpty()) }
    var phase by remember { mutableStateOf(LinuxRuntimeInstaller.Phase.DOWNLOAD) }
    var percent by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // What is still to be downloaded: the catalog is read off the main thread, again after a download or on "Try again".
    LaunchedEffect(tick) {
        plan = null
        planFailed = false
        val defaults = SteamContainer.defaults(context, false)
        val missing = withContext(Dispatchers.IO) { FexComponents.missing(context, defaults.fex, defaults.rootfs) }
        plan = missing
        planFailed = missing == null
    }
    // The install is the app's one: its progress is read here, and the end of it is noticed when it stops running.
    LaunchedEffect(Unit) {
        var wasRunning = running
        while (true) {
            delay(400)
            val id = LinuxRuntimeInstallTask.runningId()
            running = id != null
            if (id != null) {
                currentId = id
                phase = LinuxRuntimeInstallTask.phase()
                percent = LinuxRuntimeInstallTask.percent()
            }
            if (wasRunning && !running) {
                error = LinuxRuntimeInstallTask.lastError()
                bump()
                onInstalled()
            }
            wasRunning = running
        }
    }

    Column(modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val entries = plan
        Text(
            "FEX needs " + (if (!FexComponents.fexInstalled(context)) "a FEX build" else "") +
                (if (!FexComponents.fexInstalled(context) && !FexComponents.rootfsInstalled(context)) " and " else "") +
                (if (!FexComponents.rootfsInstalled(context)) "an x86-64 root filesystem" else "") + ", which are not installed.",
            color = MaterialTheme.colorScheme.error
        )
        when {
            planFailed -> {
                Text("The component catalog cannot be read. Check the connection.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = bump) { Text("Try again") }
            }
            entries == null -> {
                Text("Checking what has to be downloaded…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            else -> {
                entries.forEach { entry ->
                    val state = when {
                        running && currentId == entry.id -> when (phase) {
                            LinuxRuntimeInstaller.Phase.DOWNLOAD -> "downloading $percent%"
                            LinuxRuntimeInstaller.Phase.VERIFY -> "checking $percent%"
                            LinuxRuntimeInstaller.Phase.EXTRACT -> "unpacking $percent%"
                        }
                        running -> "waiting"
                        else -> formatSize(entry.size)
                    }
                    Text("${entry.name} · $state", style = MaterialTheme.typography.bodySmall)
                    if (running && currentId == entry.id) {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                val space = entries.maxOfOrNull { LinuxRuntimeInstaller.requiredBytes(it) } ?: 0L
                val free = context.filesDir.usableSpace
                val notEnoughSpace = space > 0 && free < space
                if (notEnoughSpace) {
                    Text(
                        "Not enough free space: needs about ${formatSize(space)}, ${formatSize(free)} free.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (running) {
                    OutlinedButton(onClick = { LinuxRuntimeInstallTask.cancel() }) { Text("Cancel") }
                } else {
                    Button(
                        onClick = {
                            error = null
                            percent = 0
                            phase = LinuxRuntimeInstaller.Phase.DOWNLOAD
                            if (LinuxRuntimeInstallTask.startAll(context, entries)) {
                                running = true
                                currentId = LinuxRuntimeInstallTask.runningId().orEmpty()
                            }
                        },
                        enabled = entries.isNotEmpty() && !notEnoughSpace && LinuxRuntimeInstallTask.runningId() == null
                    ) { Text("Download (${formatSize(entries.sumOf { it.size })})") }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "${(bytes + 524288) shr 20} MB"
