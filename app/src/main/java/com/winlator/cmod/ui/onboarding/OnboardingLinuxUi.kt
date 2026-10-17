package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.linux.LinuxRuntimeInstallTask
import com.winlator.cmod.linux.LinuxRuntimeInstaller
import com.winlator.cmod.steam.SteamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Offers the Linux runtime (the root filesystem, Box64 and the Turnip driver Linux containers and Steam run on). The download is the app's one
 * Linux install, so it keeps going if the user moves on to the next screen; skipping is fine, it can be done later when a Linux container is made.
 */
@Composable
internal fun OnboardingLinuxScreen(onContinue: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<SteamContainer.Plan?>(null) }
    var running by remember { mutableStateOf(LinuxRuntimeInstallTask.runningId() != null) }
    var currentId by remember { mutableStateOf(LinuxRuntimeInstallTask.runningId().orEmpty()) }
    var phase by remember { mutableStateOf(LinuxRuntimeInstaller.Phase.DOWNLOAD) }
    var percent by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // What is still to be downloaded: read off the main thread, again after a download ended or on "Try again".
    LaunchedEffect(tick) {
        plan = null
        plan = withContext(Dispatchers.IO) { SteamContainer.plan(context) }
    }
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
                tick++
            }
            wasRunning = running
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text("Linux games", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "To run Linux games and the Steam client, the app needs a Linux runtime: a Linux root filesystem, the Box64 emulator and a graphics driver.",
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "But no worries, you can skip this and do it later: open Containers, tap the + button and choose Linux (or Steam). " +
                    "The download starts there, and Windows games do not need it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            val current = plan
            when {
                current == null -> {
                    Text("Checking what has to be downloaded…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                current.error != null -> {
                    Text(current.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { tick++ }) { Text("Try again") }
                }
                current.install.isEmpty() -> Text("The Linux runtime is installed.", fontWeight = FontWeight.SemiBold)
                else -> {
                    current.install.forEach { entry ->
                        val state = when {
                            running && currentId == entry.id -> when (phase) {
                                LinuxRuntimeInstaller.Phase.DOWNLOAD -> "downloading $percent%"
                                LinuxRuntimeInstaller.Phase.VERIFY -> "checking $percent%"
                                LinuxRuntimeInstaller.Phase.EXTRACT -> "unpacking $percent%"
                            }
                            running -> "waiting"
                            else -> sizeText(entry.size)
                        }
                        Text("${entry.name} · $state", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        if (running && currentId == entry.id) {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    val free = context.filesDir.usableSpace
                    val notEnoughSpace = current.space > 0 && free < current.space
                    if (notEnoughSpace) {
                        Text(
                            "Not enough free space: needs about ${sizeText(current.space)}, ${sizeText(free)} free.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    if (running) {
                        OutlinedButton(onClick = { LinuxRuntimeInstallTask.cancel() }) { Text("Cancel") }
                    } else {
                        Button(
                            onClick = {
                                error = null
                                percent = 0
                                phase = LinuxRuntimeInstaller.Phase.DOWNLOAD
                                if (LinuxRuntimeInstallTask.startAll(context, current.install)) {
                                    running = true
                                    currentId = LinuxRuntimeInstallTask.runningId().orEmpty()
                                }
                            },
                            enabled = !notEnoughSpace,
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) { Text("Install the Linux runtime (${sizeText(current.download)})") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            val done = plan?.let { it.error == null && it.install.isEmpty() } == true
            if (done) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Continue") }
            } else {
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(if (running) "Continue while it downloads" else "Not now, I'll do it later")
                }
            }
            TextButton(onClick = back) { Text("Back") }
        }
    }
}

private fun sizeText(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "${(bytes + 524288) shr 20} MB"
