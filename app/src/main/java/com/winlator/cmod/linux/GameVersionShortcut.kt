package com.winlator.cmod.linux

import android.content.Context
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerImporter
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.container.EnvironmentProvider
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.container.ShortcutWriter
import com.winlator.cmod.contents.ContainerConfigCatalog
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DeviceInfo
import com.winlator.cmod.core.ExecutableKind
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.WindowsExecutable
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.gameprofile.ConfigChooser
import com.winlator.cmod.gameprofile.GameMatcher
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.gameprofile.GameProfiles
import com.winlator.cmod.ui.addgame.StandardLinuxContainer
import java.io.File

/**
 * The shortcut of a game whose files are in a folder the app made (a downloaded version): the same steps as "Add game" takes for a picked
 * program (see AddGameFragment), without a person choosing the settings: the game's profile names the version and the environment preset.
 */
object GameVersionShortcut {
    /**
     * The shortcut's settings, in the order that matters (a later one replaces an earlier one of the same key): the preset's Linux settings,
     * the game's own for the platform, the execution mode of the preset over them, then where the shortcut comes from and which game,
     * version and preset it is. Shared with AddGameFragment.
     */
    @JvmStatic
    fun buildExtras(
        linuxSettings: Map<String, String>,
        gameExtras: Map<String, String>?,
        game: GameProfile?,
        version: GameProfile.Version?,
        configId: String?,
    ): LinkedHashMap<String, String?> {
        val extras = LinkedHashMap<String, String?>()
        extras.putAll(linuxSettings)
        if (gameExtras != null) extras.putAll(gameExtras)
        // Execution mode belongs to the selected Linux preset, not the game's generic defaults.
        linuxSettings["linuxNoProot"]?.takeIf { it.isNotEmpty() }?.let { extras["linuxNoProot"] = it }
        extras[Shortcut.EXTRA_LIBRARY_ORIGIN] = Shortcut.ORIGIN_MANUAL
        if (game != null) {
            extras[GameProfiles.EXTRA_PROFILE] = game.id
            version?.let { extras[GameProfiles.EXTRA_VERSION] = it.id }
        }
        if (configId != null) extras["containerConfig"] = configId
        return extras
    }

    /** The folder's game and platform, the container, and the .desktop file; the path of the file. Blocks: call it off the main thread. */
    @JvmStatic
    @Throws(Exception::class)
    fun create(context: Context, job: SteamDownloadJob, folder: File, progress: ContainerImporter.Progress): String {
        val platform = if (job.platform == "linux") GameProfile.Platform.LINUX else GameProfile.Platform.WINDOWS
        val found = GameMatcher.matchFolder(context, folder, platform, false)
            ?: throw IllegalStateException("The downloaded files do not contain ${job.gameName}'s program.")
        val game = found.game
        val exe = found.executable ?: throw IllegalStateException("The game's program is not in the downloaded files.")
        // The version the person chose when it is one of the profile's; otherwise the one the files say they are.
        val version = job.versionId.takeIf { it.isNotEmpty() }?.let { game.version(it) } ?: found.version
        val kind = ExecutableKind.of(exe).takeIf { it != ExecutableKind.NONE }
            ?: if (platform == GameProfile.Platform.LINUX) ExecutableKind.LINUX_X64 else ExecutableKind.WINDOWS
        val nativeArm64 = !kind.isLinux && WindowsExecutable.detect(exe) == WindowsExecutable.Architecture.ARM64

        val soc = DeviceInfo.soc(context)
        val renderer = GPUInformation.getRenderer(null, context)
        val all = ContainerConfigCatalog.all(context, true).filterValues { config ->
            config.suits(soc, renderer) && config.platform == platform && when {
                kind.isLinux -> true
                config.windowsArchitecture.isNotEmpty() -> (config.windowsArchitecture == "arm64") == nativeArm64
                else -> runCatching {
                    val profile = ContainerConfigCatalog.profileText(context, config, false)
                    if (profile == null) !nativeArm64 else isNativeProfile(ContainerProfile.parse(profile).container) == nativeArm64
                }.getOrDefault(!nativeArm64)
            }
        }
        // The registry's pick for this processor; on a device the registry does not know (no family) nothing is "recommended", and the
        // version's own list (the order the registry gives it) supplies the preset, as the tiles of "Add game" would.
        val chosenId = ConfigChooser.choose(version, soc.family, all, platform)?.configId
            ?: version?.containers?.firstOrNull { all.containsKey(it) }
            // None of the version's presets is made for this device: any preset made for this device and platform (a generic one).
            ?: all.keys.firstOrNull()
        val config = chosenId?.let { all.getValue(it) }

        progress.status("Getting the environment…")
        val manager = ContainerManager(context)
        val contents = ContentsManager(context).apply { syncContents() }
        val linuxSettings: Map<String, String>
        val container: Container
        if (config != null) {
            val result = EnvironmentProvider.ensure(context, manager, contents, config, null, progress)
            container = result.container
                ?: throw IllegalStateException(result.warnings.joinToString("\n").ifEmpty { "The environment could not be made." })
            linuxSettings = result.linuxSettings
        } else if (kind.isLinux) {
            // No registry preset suits this device (its processor is not in the registry): the app's standard Linux environment, as "Add game" offers.
            val warnings = mutableListOf<String>()
            linuxSettings = StandardLinuxContainer.shortcutSettings(context, kind)
            container = StandardLinuxContainer.ensure(context, manager, contents, kind, progress, warnings)
                ?: throw IllegalStateException(warnings.joinToString("\n").ifEmpty { "The environment could not be made." })
        } else {
            throw IllegalStateException("No environment preset of the registry is made for this version on this device " +
                "(version ${version?.id}, it lists ${version?.containers?.size ?: 0}; ${all.size} presets suit this device and platform; processor family ${soc.family}).")
        }
        if (nativeArm64 && (!isNativeContainer(container) || !WineInfo.fromIdentifier(context, contents, container.wineVersion).supportsNativeArm64())) {
            throw IllegalStateException("This environment does not support native Windows ARM64 games.")
        }

        progress.status("Adding the game…")
        val settings = game.effective(version, platform)
        val extras = buildExtras(linuxSettings, settings?.extras, game, version, config?.id)
        val name = ShortcutWriter.freeName(container, job.folderName)
        val desktop: File? = if (container.isLinux) {
            val configured = container.copyWithExtras(extras.filterValues { it != null }.mapValues { it.value!! })
            val refusal = LinuxProgramSupport.refusal(context, configured, kind)
            if (refusal != null) throw IllegalStateException("This container cannot run this program: $refusal.")
            var program = exe
            if (LinuxProgramInstaller.needsCopy(configured, exe)) {
                // The environment needs executable files (FEX), which shared storage does not allow: the version moves into the container.
                progress.status("Moving the game into the container…")
                val root = SteamPaths(container.linuxHomeDir).containerVersionsRoot()
                val moved = File(root, SteamDepotMerger.freeFolderName(root, folder.name))
                SteamDepotMerger.moveInto(folder, moved)
                program = File(moved, exe.relativeTo(folder).path)
                if (!program.isFile) throw IllegalStateException("The game's program was lost on the way into the container.")
                if (LinuxProgramInstaller.needsCopy(configured, program)) throw IllegalStateException("The downloaded files are not inside the container.")
            }
            val guest = LinuxProgramInstaller.guestPath(configured, program)
            val root = game.linuxExecutables.firstOrNull { rel -> guest.endsWith("/$rel") }?.let { rel -> guest.removeSuffix("/$rel") }
            extras[GameProfiles.EXTRA_ROOT] = root.orEmpty()
            LinuxProgramInstaller.addShortcut(context, container, name, guest, extras)
        } else {
            if (!ShortcutWriter.ensureExternalStorageMapped(container, exe)) throw IllegalStateException("No free drive letter for the storage the game is on.")
            ShortcutWriter.write(context, exe, container, name, extras, settings?.args)
        }
        return desktop?.path ?: throw IllegalStateException("The shortcut could not be written.")
    }

    private fun isNativeContainer(container: Container): Boolean =
        container.getExtra(WineInfo.NATIVE_ARM64_CONTAINER_KEY) == WineInfo.NATIVE_ARM64_CONTAINER_VALUE

    private fun isNativeProfile(data: org.json.JSONObject): Boolean =
        data.optJSONObject("extraData")?.optString(WineInfo.NATIVE_ARM64_CONTAINER_KEY) == WineInfo.NATIVE_ARM64_CONTAINER_VALUE
}
