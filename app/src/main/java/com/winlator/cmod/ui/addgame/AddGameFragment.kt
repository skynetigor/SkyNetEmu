package com.winlator.cmod.ui.addgame

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.MainActivity
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerImporter
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.container.EnvironmentProvider
import com.winlator.cmod.container.ShortcutWriter
import com.winlator.cmod.contents.ContainerConfigCatalog
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.contents.RegistryCache
import com.winlator.cmod.core.DeviceInfo
import com.winlator.cmod.core.ExecutableKind
import com.winlator.cmod.gameprofile.ConfigChooser
import com.winlator.cmod.gameprofile.GameMatcher
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.gameprofile.GameProfiles
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxProgramInstaller
import com.winlator.cmod.linux.LinuxProgramSupport
import com.winlator.cmod.telemetry.ErrorReporter
import com.winlator.cmod.telemetry.Telemetry
import com.winlator.cmod.ui.onboarding.TileCard
import com.winlator.cmod.ui.theme.WinZTheme
import java.io.File

private sealed class Phase {
    object Loading : Phase()
    class Choose(val model: Model) : Phase()
    class Working(val text: String) : Phase()
    class Failed(val text: String) : Phase()
}

private class Tile(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val note: String,
    val accent: Int,
    val onClick: () -> Unit
)

private class Model(
    val gameName: String,
    val subtitle: String,
    val recommended: Tile?,
    val tiles: List<Tile>
)


/**
 * "Add game": the user has picked an exe; this screen says what the registry knows about it and offers the ways to run it. Each
 * way ends in a container (an earlier one is reused when it fits) and a shortcut in it, then the game's own screen opens.
 */
class AddGameFragment : Fragment() {
    companion object {
        fun newInstance(exePath: String) = AddGameFragment().apply { arguments = Bundle().apply { putString("exe", exePath) } }
    }

    private sealed class Pick {
        class Registry(val config: ContainerConfigCatalog.Config) : Pick()
        class Standard(val arm64ec: Boolean) : Pick()
        class StandardLinux : Pick()
        class Existing(val container: Container) : Pick()
        class Imported(val envelope: ContainerProfile.Envelope) : Pick()
    }

    private var phase by mutableStateOf<Phase>(Phase.Loading)
    private lateinit var exe: File
    /** What the picked file runs on: decides the container kind, the registry programs it is matched with and the configs offered. */
    private var kind = ExecutableKind.WINDOWS
    private val platform get() = if (kind.isLinux) GameProfile.Platform.LINUX else GameProfile.Platform.WINDOWS
    private var match: GameMatcher.Result? = null
    private var configs: Map<String, ContainerConfigCatalog.Config> = emptyMap()

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) importFrom(uri) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        exe = File(requireArguments().getString("exe").orEmpty())
        kind = ExecutableKind.of(exe).takeIf { it != ExecutableKind.NONE } ?: ExecutableKind.WINDOWS
        if (phase is Phase.Loading) load()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    AddGameScreen(
                        phase,
                        title = ShortcutWriter.smartName(exe),
                        exeName = exe.name,
                        onBack = { parentFragmentManager.popBackStack() },
                        onRetry = { phase = Phase.Loading; load() }
                    )
                }
            }
        }
    }

    // -- loading what the registry says -----------------------------------------------------------------------------------

    private fun load() {
        val context = requireContext().applicationContext
        Thread {
            RegistryCache.get(context, "socs.json", RegistryCache.TTL_INDEX, true)
            Telemetry.event("skynet.funnel", "exe_picked")
            val found = GameMatcher.match(context, exe.absolutePath, platform, true)
            Telemetry.event("skynet.funnel", if (found == null) "game_unrecognised" else "game_recognised",
                "skynet.game.id", found?.game?.id, "skynet.game.version", found?.version?.id)
            // Only the configs made for the file's platform: a Windows exe never gets a Linux container, nor the other way round.
            val all = ContainerConfigCatalog.all(context, true).filterValues { it.platform == platform }
            val soc = DeviceInfo.soc(context)
            val manager = ContainerManager(context)
            val model = buildModel(found, all, soc, manager)
            match = found
            configs = all
            phase = Phase.Choose(model)
        }.start()
    }

    private fun buildModel(found: GameMatcher.Result?, all: Map<String, ContainerConfigCatalog.Config>, soc: DeviceInfo.Soc, manager: ContainerManager): Model {
        val context = requireContext()
        val shown = LinkedHashSet<String>()
        val configTile = { config: ContainerConfigCatalog.Config, accent: Int, recommended: Boolean ->
            shown.add(config.id)
            val reused = manager.containers.any { it.getExtra(EnvironmentProvider.EXTRA_CONFIG_ID) == config.id }
            val notes = mutableListOf<String>()
            if (config.testedOn.isNotEmpty()) notes.add("Tested on ${config.testedOn.first()}")
            if (!config.suits(soc.family)) notes.add("Not tested on your processor")
            if (config.channel == "prerelease") notes.add("Experimental")
            if (reused) notes.add("Uses the environment you already have")
            Tile(
                if (recommended) Icons.Outlined.Star else Icons.Outlined.SportsEsports,
                config.name, config.description,
                notes.joinToString(". ").ifEmpty { "Settings made for this game." }, accent
            ) { choose(Pick.Registry(config)) }
        }

        var recommended: Tile? = null
        val tiles = mutableListOf<Tile>()
        val version = found?.version
        if (found != null) {
            val choice = ConfigChooser.choose(version, soc.family, all, platform)
            val listed = (version?.containers ?: found.game.versions.flatMap { it.containers }).distinct().mapNotNull { all[it] }
            if (choice != null) recommended = configTile(all.getValue(choice.configId), 0, true)
            listed.filter { it.id != choice?.configId }.forEach { tiles.add(configTile(it, 1, false)) }
        }
        if (kind.isLinux) {
            val box64 = StandardLinuxContainer.usesBox64(kind)
            val x86 = kind == ExecutableKind.LINUX_X86
            tiles.add(Tile(
                Icons.Outlined.Terminal,
                if (x86) "Standard Linux, x86 32-bit" else if (box64) "Standard Linux, x86_64" else "Standard Linux, ARM64",
                if (x86) "The app's default Linux settings: the Linux runtime and Turnip driver, the 32-bit program run through FEX (it needs FEX and an x86-64 root filesystem installed)."
                else if (box64) "The app's default Linux settings: the Linux runtime and Turnip driver, the program run through Box64."
                else "The app's default Linux settings: the Linux runtime and Turnip driver, the ARM64 program run as it is.",
                if (found == null) "A good start for a program the list does not know." else "Not tuned for this game.", 2
            ) { choose(Pick.StandardLinux()) })
        } else for (arm64ec in listOf(false, true)) {
            if (StandardContainer.runtimeFor(context, arm64ec) == null) continue
            tiles.add(Tile(
                Icons.Outlined.DesktopWindows,
                if (arm64ec) "Standard Windows, ARM64EC" else "Standard Windows, x86_64",
                if (arm64ec) "The app's default settings on an ARM64EC Wine or Proton, run through FEX."
                else "The app's default settings on an x86_64 Wine or Proton, run through Box64.",
                if (found == null) "A good start for a game the list does not know." else "Not tuned for this game.", 2
            ) { choose(Pick.Standard(arm64ec)) })
        }
        val others = all.values.filter { it.id !in shown }
        if (others.isNotEmpty()) {
            tiles.add(Tile(Icons.Outlined.SportsEsports, "Settings of another game",
                "Takes the settings made for another game from the list, for a game that is similar.", "Pick one from the list.", 2) { pickOther(others) })
        }
        // Only the containers of the file's platform that can run it (a Linux one: the emulator, the root filesystem it needs).
        val mine = manager.containers.filter { !kind.isLinux && !it.isLinux }
        if (mine.isNotEmpty()) {
            val refusals = if (kind.isLinux) mine.associateWith { LinuxProgramSupport.refusal(context, it, kind) } else emptyMap()
            val fits = mine.filter { refusals[it] == null }
            val why = mine.mapNotNull { c -> refusals[c]?.let { "${c.name}: $it" } }.joinToString("\n")
            tiles.add(Tile(Icons.Outlined.Folder, "An environment you already have",
                "Puts the game into one of your ${if (kind.isLinux) "Linux" else "Windows"} containers that can run it, and leaves it as it is.",
                if (fits.isEmpty()) "None of your containers can run this program yet." else "Pick one from your list (${fits.size} of ${mine.size} fit).", 2) {
                if (fits.isEmpty()) Toast.makeText(requireContext(), why, Toast.LENGTH_LONG).show() else pickExisting(fits)
            })
        }
        tiles.add(Tile(Icons.Outlined.Download, "Import a .wcfg file",
            "Makes an environment from a profile exported on another device or shared by someone.", "You have a profile file.", 2) {
            importPicker.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        })

        val subtitle = when {
            found == null -> "The list has nothing for this ${if (kind.isLinux) "Linux program" else "game"}. Choose how to run it."
            version == null -> "${found.game.name}. The version was not recognised."
            else -> "${found.game.name} ${version.label} · ${soc.label()}"
        } + if (kind.isLinux && found != null) " · Linux" else ""
        return Model(found?.game?.name ?: ShortcutWriter.smartName(exe), subtitle, recommended, tiles)
    }

    private fun pickOther(others: List<ContainerConfigCatalog.Config>) {
        AlertDialog.Builder(requireContext())
            .setTitle("Settings of another game")
            .setItems(others.map { it.name }.toTypedArray()) { _, which -> choose(Pick.Registry(others[which])) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickExisting(containers: List<Container>) {
        AlertDialog.Builder(requireContext())
            .setTitle("Add the game to")
            .setItems(containers.map { it.name }.toTypedArray()) { _, which -> choose(Pick.Existing(containers[which])) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFrom(uri: Uri) {
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) { null }
        if (text.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Couldn't read the profile file", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val envelope = ContainerProfile.parse(text)
            if (envelope.container.optString("type") == Container.TYPE_LINUX != kind.isLinux) {
                Toast.makeText(requireContext(),
                    if (kind.isLinux) "This profile is for a Windows environment, the program is a Linux one." else "This profile is for a Linux environment, the game is a Windows one.",
                    Toast.LENGTH_LONG).show()
                return
            }
            choose(Pick.Imported(envelope))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Invalid container profile: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -- the Linux shortcut ---------------------------------------------------------------------------------------------

    /**
     * The shortcut of a Linux program in [container]: the program stays where it is when the container can run it there,
     * otherwise the user says what to copy in. Sets [phase] when it fails or is cancelled; null then.
     */
    private fun addLinuxProgram(context: android.content.Context, container: Container, name: String, game: GameMatcher.Result?,
                                extras: MutableMap<String, String?>): File? {
        val configured = container.copyWithExtras(extras.filterValues { it != null }.mapValues { it.value!! })
        val refusal = LinuxProgramSupport.refusal(context, configured, kind)
        if (container.isLinux && refusal != null) {
            phase = Phase.Failed("This container cannot run this program: $refusal.")
            return null
        }
        var guest = exe.absolutePath
        if (LinuxProgramInstaller.needsCopy(configured, exe)) {
            val mode = askCopyMode(container) ?: run {
                phase = Phase.Failed("Nothing was added.")
                return null
            }
            phase = Phase.Working("Copying into the container…")
            guest = LinuxProgramInstaller.copy(configured, exe, mode) ?: run {
                phase = Phase.Failed("Copying failed. Is there room on the device?")
                return null
            }
        }
        game?.let {
            // The game's folder as the container sees it, when the program lies where the registry says it does.
            val root = it.game.linuxExecutables.firstOrNull { rel -> guest.endsWith("/$rel") }?.let { rel -> guest.removeSuffix("/$rel") }
            extras[GameProfiles.EXTRA_ROOT] = root.orEmpty()
        }
        return LinuxProgramInstaller.addShortcut(requireContext(), container, name, guest, extras)
    }

    /** Asks (on the screen, from the worker thread) what of the program's folder goes into the container; null when cancelled. */
    private fun askCopyMode(container: Container): LinuxProgramInstaller.CopyMode? {
        val host = activity ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var answer: LinuxProgramInstaller.CopyMode? = null
        val game = LinuxProgramInstaller.gameFolder(exe)
        val modes = if (game != null) listOf(LinuxProgramInstaller.CopyMode.PROGRAM_ONLY, LinuxProgramInstaller.CopyMode.WHOLE_GAME, LinuxProgramInstaller.CopyMode.FOLDER)
        else listOf(LinuxProgramInstaller.CopyMode.FOLDER)
        val labels = modes.map {
            when (it) {
                LinuxProgramInstaller.CopyMode.PROGRAM_ONLY -> "Program only, the game's data stays on storage"
                LinuxProgramInstaller.CopyMode.WHOLE_GAME -> "The whole game folder \"${game?.name}\""
                else -> "The folder \"${exe.parentFile?.name}\" only"
            }
        }.toTypedArray()
        host.runOnUiThread {
            // A dialog with a message shows no list of items: the reason and the choices go into one view.
            val pad = (20 * host.resources.displayMetrics.density).toInt()
            val reason = android.widget.TextView(host).apply {
                text = LinuxProgramInstaller.copyReason(container, exe) + "\n\nWhat goes into the container?"
                setPadding(pad, pad / 2, pad, pad / 2)
                textSize = 16f
            }
            val list = android.widget.ListView(host).apply {
                adapter = android.widget.ArrayAdapter(host, android.R.layout.simple_list_item_1, labels)
            }
            val body = android.widget.LinearLayout(host).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(reason)
                addView(list)
            }
            val dialog = AlertDialog.Builder(host)
                .setTitle("Copy into the container?")
                .setView(body)
                .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            list.setOnItemClickListener { _, _, which, _ -> answer = modes[which]; dialog.dismiss(); latch.countDown() }
            dialog.show()
        }
        latch.await()
        return answer
    }

    // -- making the container and the shortcut ---------------------------------------------------------------------------

    private fun choose(pick: Pick) {
        val context = requireContext().applicationContext
        val game = match
        Telemetry.event("skynet.funnel", "config_chosen",
            "skynet.game.id", game?.game?.id, "skynet.game.version", game?.version?.id,
            "skynet.config.id", when (pick) {
                is Pick.Registry -> pick.config.id
                is Pick.Standard -> if (pick.arm64ec) StandardContainer.CONFIG_ARM64EC else StandardContainer.CONFIG_X64
                is Pick.StandardLinux -> StandardLinuxContainer.configId(kind)
                is Pick.Existing -> "existing"
                is Pick.Imported -> "imported"
            })
        phase = Phase.Working("Preparing…")
        Thread {
            val manager = ContainerManager(context)
            val contents = ContentsManager(context).apply { syncContents() }
            val progress = ContainerImporter.Progress { text -> phase = Phase.Working(text) }
            val warnings = mutableListOf<String>()
            var linuxSettings: Map<String, String> = emptyMap()
            val container: Container? = when (pick) {
                is Pick.Registry -> {
                    phase = Phase.Working("Getting the environment…")
                    val result = EnvironmentProvider.ensure(context, manager, contents, pick.config, null, progress)
                    warnings.addAll(result.warnings)
                    linuxSettings = result.linuxSettings
                    result.container
                }
                is Pick.Standard -> {
                    phase = Phase.Working("Making the environment…")
                    StandardContainer.ensure(context, manager, contents, pick.arm64ec)
                        .also { if (it == null) warnings.add("No installed Wine or Proton of this kind. Install one in Settings.") }
                }
                is Pick.StandardLinux -> {
                    phase = Phase.Working("Making the environment…")
                    linuxSettings = StandardLinuxContainer.shortcutSettings(context, kind)
                    StandardLinuxContainer.ensure(context, manager, contents, kind, progress, warnings)
                }
                is Pick.Existing -> pick.container
                is Pick.Imported -> {
                    val result = ContainerImporter.run(context, manager, contents, pick.envelope, progress)
                    warnings.addAll(result.warnings)
                    linuxSettings = result.linuxSettings
                    result.container
                }
            }
            if (container == null) {
                val why = warnings.joinToString("\n").ifEmpty { "The environment could not be made." }
                ErrorReporter.report("container_create", "ContainerCreate", why)
                phase = Phase.Failed(why)
                return@Thread
            }
            Telemetry.event("skynet.funnel", "container_created", "skynet.game.id", game?.game?.id)
            phase = Phase.Working("Adding the game…")
            val settings = game?.game?.effective(game.version, platform)
            val extras = LinkedHashMap<String, String?>()
            extras.putAll(linuxSettings)
            if (settings != null) extras.putAll(settings.extras)
            if (game != null) {
                extras[GameProfiles.EXTRA_PROFILE] = game.game.id
                game.version?.let { extras[GameProfiles.EXTRA_VERSION] = it.id }
            }
            (pick as? Pick.Registry)?.let { extras["containerConfig"] = it.config.id }
            val name = ShortcutWriter.freeName(container, game?.game?.name ?: ShortcutWriter.smartName(exe))
            val desktop: File? = if (container.isLinux) {
                addLinuxProgram(context, container, name, game, extras)
            } else {
                if (!ShortcutWriter.ensureExternalStorageMapped(container, exe)) {
                    ErrorReporter.report("add_game", "NoDriveLetter", "No free drive letter")
                    phase = Phase.Failed("No free drive letter for the SD card the game is on.")
                    return@Thread
                }
                ShortcutWriter.write(context, exe, container, name, extras, settings?.args)
            }
            if (desktop == null) {
                if (phase !is Phase.Failed) {
                    ErrorReporter.report("add_game", "ShortcutWrite", "The shortcut could not be written")
                    phase = Phase.Failed("The shortcut could not be written.")
                }
                return@Thread
            }
            Telemetry.event("skynet.funnel", "shortcut_created",
                "skynet.game.id", game?.game?.id, "skynet.game.version", game?.version?.id)
            activity?.runOnUiThread { (activity as? MainActivity)?.gameAdded(desktop.path) }
        }.start()
    }
}

@Composable
private fun AddGameScreen(phase: Phase, title: String, exeName: String, onBack: () -> Unit, onRetry: () -> Unit) {
    val accents = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 40.dp else 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(Modifier.widthIn(max = 1000.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Outlined.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                        }
                        Column(Modifier.weight(1f).padding(top = 6.dp, end = 12.dp)) {
                            Text("Add $title", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text(
                                when (phase) {
                                    is Phase.Choose -> phase.model.subtitle
                                    else -> exeName
                                },
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    when (phase) {
                        is Phase.Loading -> Status("Looking the game up…", null)
                        is Phase.Working -> Status(phase.text, null)
                        is Phase.Failed -> Status(phase.text, onRetry)
                        is Phase.Choose -> {
                            phase.model.recommended?.let { tile ->
                                Text("Recommended", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 10.dp))
                                TileCard(tile.icon, tile.title, tile.description, tile.note, accents[tile.accent],
                                    Modifier.fillMaxWidth().padding(bottom = 22.dp), onClick = tile.onClick)
                            }
                            if (phase.model.tiles.isNotEmpty()) {
                                Text(if (phase.model.recommended != null) "Other ways" else "Ways to run it",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 10.dp))
                            }
                            val rows = if (wide) phase.model.tiles.chunked(2) else phase.model.tiles.map { listOf(it) }
                            rows.forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(bottom = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    row.forEach { tile ->
                                        TileCard(tile.icon, tile.title, tile.description, tile.note, accents[tile.accent],
                                            Modifier.weight(1f).fillMaxSize(), onClick = tile.onClick)
                                    }
                                    if (row.size == 1 && wide) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onRetry == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) Button(onClick = onRetry) { Text("Back to the choices") }
    }
}
