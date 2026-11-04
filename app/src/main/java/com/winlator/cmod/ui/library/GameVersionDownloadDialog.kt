package com.winlator.cmod.ui.library

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.gameprofile.GameProfileCatalog
import com.winlator.cmod.linux.LinuxSteamGames
import com.winlator.cmod.linux.SteamAppInfo
import com.winlator.cmod.linux.SteamDepotMerger
import com.winlator.cmod.linux.SteamDownloadJob
import com.winlator.cmod.linux.SteamOwnedApps
import com.winlator.cmod.linux.SteamPaths
import com.winlator.cmod.linux.SteamVersionCatalog
import com.winlator.cmod.linux.SteamVersionDownloader
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Long-tap on the Steam tile > "Download game version": pick a game of the account (one the registry has a profile for), a platform and a
 * version; the app then opens a Steam session that fetches exactly those depots (see [SteamVersionDownloader]) and makes the shortcut.
 */
object GameVersionDownloadDialog {
    /** The extra of the session intent that names the job. */
    const val EXTRA_JOB = "steam_download_job"

    @JvmStatic
    fun show(fragment: Fragment, shortcut: Shortcut, onChanged: Runnable?) {
        val dialog = ComponentDialog(fragment.requireContext(), com.winlator.cmod.R.style.GameSavesDialog)
        dialog.show()
        val controllerInput = dialog.window?.let(com.winlator.cmod.ui.GamepadNavigation::install)
        dialog.setOnDismissListener { controllerInput?.close(); onChanged?.run() }
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
                    DownloadPanel(shortcut, onClose = dialog::dismiss) { job ->
                        // The Steam client's own session: it signs in, and the session types the depot commands into its console.
                        val activity = fragment.requireActivity()
                        activity.startActivity(
                            Intent(activity, XServerDisplayActivity::class.java)
                                .putExtra("container_id", shortcut.container.id)
                                .putExtra("shortcut_path", shortcut.file.path)
                                .putExtra("shortcut_name", shortcut.name)
                                .putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"))
                                .putExtra("native_rendering", shortcut.rendererNative)
                                .putExtra(EXTRA_JOB, job.key)
                        )
                        dialog.dismiss()
                    }
                }
            }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private class Loaded(
    val games: List<GameProfile>,
    val store: SteamAppInfo.Store?,
    val owned: Set<Long>,
    val installed: Set<String>,
    val jobs: List<SteamDownloadJob>,
    val message: String?,
)

private fun load(context: android.content.Context, shortcut: Shortcut): Loaded {
    val home = shortcut.container.linuxHomeDir
    val paths = SteamPaths(home)
    val jobs = SteamDownloadJob.unfinished(paths.jobsDir())
    if (!paths.appInfo().isFile || !paths.packageInfo().isFile) {
        return Loaded(emptyList(), null, emptySet(), emptySet(), jobs,
            "Steam has not fetched its game data yet. Open Steam, sign in, wait a minute, close it and open this again.")
    }
    return try {
        val store = SteamAppInfo.open(paths.appInfo())
        val owned = SteamOwnedApps.read(paths.packageInfo())
        val registry = GameProfileCatalog.index(context, false).mapNotNull { GameProfileCatalog.game(context, it.id, false) }
        val games = SteamVersionCatalog.downloadableGames(registry, store, owned)
        val installed = runCatching { LinuxSteamGames.installed(shortcut.container).keys }.getOrDefault(emptySet())
        Loaded(games, store, owned, installed, jobs,
            if (games.isEmpty()) "None of the games of your Steam library has a profile with depots yet." else null)
    } catch (e: Exception) {
        Loaded(emptyList(), null, emptySet(), emptySet(), jobs, "Steam's game data could not be read: ${e.message}")
    }
}

private fun size(bytes: Long): String = when {
    bytes <= 0 -> "size unknown"
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / 1073741824.0)
    else -> "%d MB".format(Locale.US, bytes shr 20)
}

private enum class Step { GAMES, PLATFORM, VERSIONS, CONFIRM }

private fun platformName(p: GameProfile.Platform) = if (p == GameProfile.Platform.LINUX) "Linux" else "Windows"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadPanel(shortcut: Shortcut, onClose: () -> Unit, onStart: (SteamDownloadJob) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var jobs by remember { mutableStateOf<List<SteamDownloadJob>>(emptyList()) }
    var step by remember { mutableStateOf(Step.GAMES) }
    var game by remember { mutableStateOf<GameProfile?>(null) }
    var platform by remember { mutableStateOf(GameProfile.Platform.LINUX) }
    val deviceLanguage = remember { SteamVersionCatalog.steamLanguage(Locale.getDefault()) }
    var language by remember { mutableStateOf(deviceLanguage) }
    var entries by remember { mutableStateOf<List<SteamVersionCatalog.Entry>>(emptyList()) }
    var entry by remember { mutableStateOf<SteamVersionCatalog.Entry?>(null) }
    var custom by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val data = withContext(Dispatchers.IO) { load(context, shortcut) }
        loaded = data
        jobs = data.jobs
    }
    val data = loaded
    val paths = remember { SteamPaths(shortcut.container.linuxHomeDir) }

    fun showVersions() {
        val g = game ?: return
        val d = data ?: return
        val info = d.store?.app(g.steamAppId.toLong()) ?: return
        entries = SteamVersionCatalog.entries(g, info, platform, language, d.owned)
        entry = null
        error = null
    }

    fun begin(chosen: SteamVersionCatalog.Entry) {
        val g = game ?: return
        working = true
        error = null
        scope.launch {
            val result: Pair<String?, SteamDownloadJob?> = withContext(Dispatchers.IO) {
                try {
                    val both = g.supports(GameProfile.Platform.LINUX) && g.supports(GameProfile.Platform.WINDOWS)
                    val root = paths.versionsRoot(SteamVersionCatalog.platformName(platform))
                    val job = SteamVersionCatalog.job(g, chosen, platform, language, root, both)
                    // Steam writes the depots in the container's home first; a Windows version is then copied to shared storage.
                    val problem = SteamVersionDownloader.spaceProblem(shortcut.container.linuxHomeDir, chosen.size())
                    if (problem != null) {
                        Pair(problem, null)
                    } else {
                        job.save(paths.jobsDir())
                        Pair(null, job)
                    }
                } catch (e: Exception) {
                    Pair(e.message ?: e.toString(), null)
                }
            }
            working = false
            result.first?.let { error = it }
            result.second?.let(onStart)
        }
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 18.dp
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Download game version", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (data == null) {
                    Text("Reading your Steam library…")
                } else {
                    // A download that was interrupted: continue it, or throw it away.
                    jobs.forEach { job ->
                        Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Unfinished: ${job.gameName} ${job.versionLabel}", fontWeight = FontWeight.Medium)
                                Text("${job.platform}, ${size(job.totalBytes())}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onStart(job) }) { Text("Resume") }
                                    OutlinedButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            for (root in paths.contentRoots()) SteamDepotMerger.deleteRecursively(java.io.File(root, "app_${job.appId}"))
                                            job.delete(paths.jobsDir())
                                        }
                                        jobs = jobs - job
                                    }) { Text("Discard") }
                                }
                            }
                        }
                    }
                    if (data.message != null) {
                        Text(data.message, color = MaterialTheme.colorScheme.error)
                    } else when (step) {
                        Step.GAMES -> {
                            Text("Games of your library with a profile", style = MaterialTheme.typography.titleMedium)
                            data.games.forEach { g ->
                                OutlinedButton(onClick = {
                                    game = g
                                    platform = if (g.supports(GameProfile.Platform.LINUX)) GameProfile.Platform.LINUX else GameProfile.Platform.WINDOWS
                                    step = Step.PLATFORM
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (data.installed.contains(g.steamAppId)) Text("Installed", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        Step.PLATFORM -> {
                            Text(game?.name ?: "", style = MaterialTheme.typography.titleMedium)
                            Text("Platform")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (p in GameProfile.Platform.values()) {
                                    if (game?.supports(p) != true) continue
                                    FilterChip(selected = platform == p, onClick = { platform = p },
                                        label = { Text(if (p == GameProfile.Platform.LINUX) "Linux" else "Windows") })
                                }
                            }
                            Text("Language of the files")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (l in listOf(deviceLanguage, "english").distinct()) {
                                    FilterChip(selected = language == l, onClick = { language = l }, label = { Text(l.replaceFirstChar { it.uppercase() }) })
                                }
                            }
                            Text(if (platform == GameProfile.Platform.WINDOWS)
                                "The files are kept in SkyNetEmu/Games on the phone's storage (Windows builds need a Windows environment; experimental)."
                            else "The files are kept in SkyNetEmu/Games on the phone's storage; an environment that cannot run them there gets them moved into the container.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { step = Step.GAMES }) { Text("Back") }
                                Button(onClick = { showVersions(); step = Step.VERSIONS }) { Text("Next") }
                            }
                        }
                        Step.VERSIONS -> {
                            Text("${game?.name} (${if (platform == GameProfile.Platform.LINUX) "Linux" else "Windows"})", style = MaterialTheme.typography.titleMedium)
                            if (entries.isEmpty()) Text("Steam lists no version of this game for this platform and language.")
                            entries.forEach { e ->
                                OutlinedButton(onClick = { entry = e; step = Step.CONFIRM }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(e.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val best = if (e.preferredPlatform == platform) ", best on ${platformName(platform)}" else ""
                                        Text("${e.detail}, ${size(e.size())}$best", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Text("Advanced: depot and manifest ids", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(value = custom, onValueChange = { custom = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("depot manifest, depot manifest") }, singleLine = false)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { step = Step.PLATFORM }) { Text("Back") }
                                OutlinedButton(onClick = {
                                    val g = game; val d = data
                                    val info = if (g == null || d.store == null) null else d.store.app(g.steamAppId.toLong())
                                    val made = if (info == null) null else SteamVersionCatalog.custom(info, platform, language, d.owned, custom)
                                    if (made == null) error = "Write pairs like: 284161 7188555249273500625" else { entry = made; step = Step.CONFIRM }
                                }, enabled = custom.isNotBlank()) { Text("Use these") }
                            }
                        }
                        Step.CONFIRM -> {
                            val chosen = entry
                            if (chosen != null) {
                                Text("${game?.name}: ${chosen.label} (${platformName(platform)})", style = MaterialTheme.typography.titleMedium)
                                Text("${chosen.picks.size} depots, ${size(chosen.size())} to download. Steam opens, signs in and fetches the files; " +
                                    "you can leave the app meanwhile. A shortcut is made when it is done. Your installed copy is not touched.")
                                // The profile knows which runtime this version runs best on: a different pick is offered a one-tap switch.
                                val better = chosen.preferredPlatform
                                if (better != null && better != platform && game?.supports(better) == true) {
                                    Text("${platformName(better)} is the better runtime for this version.", color = MaterialTheme.colorScheme.tertiary)
                                    OutlinedButton(onClick = {
                                        platform = better
                                        showVersions()
                                        entry = entries.firstOrNull { it.label == chosen.label } ?: entry
                                    }, enabled = !working) { Text("Use ${platformName(better)}") }
                                }
                                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { step = Step.VERSIONS; error = null }, enabled = !working) { Text("Back") }
                                    Button(onClick = { begin(chosen) }, enabled = !working) { Text(if (working) "Preparing…" else "Download") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
