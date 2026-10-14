package com.winlator.cmod.ui.library

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.StatFs
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.GameFolder
import com.winlator.cmod.core.GameTransfer
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where a game's files are, and the two ways to put them on the app's own (fast) storage: Copy keeps the original, Move
 * deletes it afterwards. The job runs in [GameTransfer]; this dialog shows its progress and can be closed and opened again
 * while it runs.
 */
object GameStorageComposeDialog {
    @JvmStatic
    fun show(fragment: Fragment, shortcut: Shortcut) = show(fragment, shortcut, null)

    /** @param onChanged called (on the main thread) when a copy or move has finished, so the screen can reload its shortcuts */
    @JvmStatic
    fun show(fragment: Fragment, shortcut: Shortcut, onChanged: Runnable?) {
        val dialog = ComponentDialog(fragment.requireContext(), R.style.GameSavesDialog)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setBackgroundColor(Color.TRANSPARENT)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        dialog.setContentView(ComposeView(fragment.requireContext()).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                WinZTheme {
                    GameStoragePanel(shortcut = shortcut, onClose = dialog::dismiss, onChanged = { onChanged?.run() })
                }
            }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private fun wineName(imageFs: ImageFs, path: String): String =
    "Z:" + path.removePrefix(imageFs.rootDir.path).replace('/', '\\')

private fun duration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun GameStoragePanel(shortcut: Shortcut, onClose: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val transfer = remember { GameTransfer.get() }
    var state by remember { mutableStateOf(transfer.state) }
    DisposableEffect(transfer) {
        val listener = Runnable { state = transfer.state }
        transfer.addListener(listener)
        state = transfer.state
        onDispose { transfer.removeListener(listener) }
    }
    // A copy of 50 GB takes minutes: keep the screen on while it runs.
    val view = LocalView.current
    DisposableEffect(state.running()) {
        view.keepScreenOn = state.running()
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == GameTransfer.Phase.DONE) onChanged()
    }

    val imageFs = remember { ImageFs.find(context) }
    val game = remember(shortcut.file.path) { GameFolder.of(imageFs, shortcut.container, shortcut.path) }
    val internal = game != null && GameFolder.isInternal(context, game.folder)
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(game?.folder?.path) {
        sizeBytes = withContext(Dispatchers.IO) { game?.let { GameFolder.measure(it.folder, null)[0] } }
    }
    val freeBytes = remember(state.phase) { StatFs(context.filesDir.path).availableBytes }
    var confirm by remember { mutableStateOf<GameTransfer.Mode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Game storage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(shortcut.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        if (state.finished()) transfer.acknowledge()
                        onClose()
                    }) { Text(if (state.running()) "Hide" else "Close") }
                }

                when {
                    state.running() -> RunningCard(state, transfer, imageFs)
                    state.finished() -> FinishedCard(state, imageFs)
                    else -> {
                        StatusCard(
                            internal = internal,
                            path = game?.folder?.path,
                            wine = if (internal && game != null) wineName(imageFs, game.folder.path) else null,
                            sizeBytes = sizeBytes
                        )
                        if (game == null) {
                            Text("This shortcut's game folder could not be found.", color = MaterialTheme.colorScheme.error)
                        } else if (!internal) {
                            Text(
                                "Games run much faster from internal storage: Wine reads thousands of small files and each one is slow on shared storage.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { confirm = GameTransfer.Mode.COPY }, enabled = sizeBytes != null, modifier = Modifier.weight(1f)) {
                                    Text("Copy")
                                }
                                OutlinedButton(onClick = { confirm = GameTransfer.Mode.MOVE }, enabled = sizeBytes != null, modifier = Modifier.weight(1f)) {
                                    Text("Move")
                                }
                            }
                            Text(
                                "Copy keeps the original folder. Move deletes it after the copy is checked. Either way every shortcut of this game " +
                                    "starts the copy. Free space on the device: ${GameTransfer.formatSize(freeBytes)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    confirm?.let { mode ->
        val size = sizeBytes ?: 0L
        val enough = freeBytes >= size + (1L shl 30)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (mode == GameTransfer.Mode.COPY) "Copy to internal storage?" else "Move to internal storage?") },
            text = {
                Text(
                    (if (mode == GameTransfer.Mode.COPY) "The game (${GameTransfer.formatSize(size)}) is copied to internal storage. The original stays where it is."
                    else "The game (${GameTransfer.formatSize(size)}) is copied to internal storage and, once the copy is checked, the original is deleted.") +
                        " Shortcuts of this game are changed to start the copy.\n\nFree space: ${GameTransfer.formatSize(freeBytes)}." +
                        (if (enough) "" else "\nThere is not enough free space for this.") +
                        "\n\nKeep the app open while it runs; it can take a few minutes."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = enough,
                    onClick = {
                        confirm = null
                        error = transfer.start(context, shortcut, mode)
                    }
                ) { Text(if (mode == GameTransfer.Mode.COPY) "Copy" else "Move") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusCard(internal: Boolean, path: String?, wine: String?, sizeBytes: Long?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                if (internal) "On internal storage" else "On shared storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (internal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Size: " + (sizeBytes?.let { GameTransfer.formatSize(it) } ?: "measuring…"),
                style = MaterialTheme.typography.bodyMedium
            )
            if (path != null) {
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (wine != null) {
                Text("Seen in Wine as $wine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RunningCard(state: GameTransfer.State, transfer: GameTransfer, imageFs: ImageFs) {
    val verb = if (state.mode == GameTransfer.Mode.COPY) "Copying" else "Moving"
    val label = when (state.phase) {
        GameTransfer.Phase.MEASURING -> "Measuring ${state.gameName}…"
        GameTransfer.Phase.COPYING -> "$verb ${state.gameName}"
        GameTransfer.Phase.VERIFYING -> "Checking the copy…"
        GameTransfer.Phase.REPOINTING -> "Updating shortcuts…"
        GameTransfer.Phase.DELETING -> "Deleting the original…"
        else -> ""
    }
    val fraction = if (state.bytesTotal > 0) (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f) else 0f
    val elapsedMs = System.currentTimeMillis() - state.startedAtMs
    val speed = if (elapsedMs >= 500) state.bytesDone * 1000 / elapsedMs else 0L
    val left = if (speed > 0) (state.bytesTotal - state.bytesDone) / speed else -1L
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.phase == GameTransfer.Phase.COPYING) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(fraction * 100).toInt()}%   ${GameTransfer.formatSize(state.bytesDone)} / ${GameTransfer.formatSize(state.bytesTotal)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${state.filesDone} of ${state.filesTotal} files   ${GameTransfer.formatSize(speed)}/s" +
                        (if (left >= 0) "   ${duration(left)} left" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    state.currentFile,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.target.isNotEmpty()) {
                Text("To ${wineName(imageFs, state.target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            val cancellable = state.phase == GameTransfer.Phase.MEASURING || state.phase == GameTransfer.Phase.COPYING ||
                state.phase == GameTransfer.Phase.VERIFYING
            OutlinedButton(onClick = transfer::cancel, enabled = cancellable, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun FinishedCard(state: GameTransfer.State, imageFs: ImageFs) {
    val ok = state.phase == GameTransfer.Phase.DONE
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (state.phase) {
                    GameTransfer.Phase.DONE -> "On internal storage"
                    GameTransfer.Phase.CANCELLED -> "Cancelled"
                    else -> "Not copied"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            if (ok) {
                Text("Seen in Wine as ${wineName(imageFs, state.target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (state.shortcutsChanged == 1) "1 shortcut now starts the copy." else "${state.shortcutsChanged} shortcuts now start the copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
