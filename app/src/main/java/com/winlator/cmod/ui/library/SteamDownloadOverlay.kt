package com.winlator.cmod.ui.library

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.linux.SteamDownloadJob
import com.winlator.cmod.linux.SteamVersionDownloader
import com.winlator.cmod.ui.theme.WinZTheme
import java.util.Locale

/**
 * The screen of a version download: it covers the Steam client (which runs underneath, typing the depot commands into its own console),
 * shows how far the files are, and has Cancel. When the download is done it offers to leave.
 */
class SteamDownloadOverlay(
    private val activity: Activity,
    private val title: String,
    private val onCancel: Runnable,
    private val onClose: Runnable,
) {
    private var status by mutableStateOf<SteamVersionDownloader.Status?>(null)
    private val view = ComposeView(activity).apply {
        setContent { WinZTheme { Panel() } }
    }

    fun show() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun update(next: SteamVersionDownloader.Status) {
        status = next
    }

    fun remove() {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun bytes(value: Long): String =
        if (value >= 1L shl 30) "%.2f GB".format(Locale.US, value / 1073741824.0) else "%d MB".format(Locale.US, value shr 20)

    private fun time(seconds: Long): String =
        if (seconds >= 3600) "%d h %02d min".format(seconds / 3600, seconds % 3600 / 60) else if (seconds >= 60) "%d min".format(seconds / 60) else "$seconds s"

    @Composable
    private fun Panel() {
        val s = status
        var confirm by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val state = s?.state
                    Text(s?.message ?: "Starting Steam…", style = MaterialTheme.typography.titleMedium)
                    val finished = state == SteamDownloadJob.State.DONE || state == SteamDownloadJob.State.FAILED
                    if (!finished) {
                        val fraction = s?.fraction() ?: -1f
                        if (fraction < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        if (s != null && s.totalBytes > 0) {
                            val speed = if (s.bytesPerSecond > 0) ", ${bytes(s.bytesPerSecond)}/s" else ""
                            val eta = s.etaSeconds().takeIf { it >= 0 && s.bytesPerSecond > 0 }?.let { ", ${time(it)} left" } ?: ""
                            Text("${bytes(s.doneBytes)} of ${bytes(s.totalBytes)} (${(fraction.coerceAtLeast(0f) * 100).toInt()}%)$speed$eta")
                        }
                        if (s != null && s.depotCount > 0) Text("Depot ${minOf(s.depotIndex + 1, s.depotCount)} of ${s.depotCount}", style = MaterialTheme.typography.bodySmall)
                        Text("You can leave the app: the download goes on while it runs, and the notification shows how far it is.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state == SteamDownloadJob.State.FAILED) Text(s?.error ?: "", color = MaterialTheme.colorScheme.error)
                    if (state == SteamDownloadJob.State.DONE) Text("The shortcut is in your library.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (finished) {
                            Button(onClick = { onClose.run() }) { Text("Close") }
                        } else {
                            OutlinedButton(onClick = { confirm = true }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Cancel the download?") },
                text = { Text("The files fetched so far are deleted.") },
                confirmButton = { TextButton(onClick = { confirm = false; onCancel.run() }) { Text("Cancel the download") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going") } },
            )
        }
    }
}
