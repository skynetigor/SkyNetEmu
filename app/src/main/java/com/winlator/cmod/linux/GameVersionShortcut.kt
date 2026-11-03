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
        val choice = ConfigChooser.choose(version, soc.family, all, platform)
            ?: throw IllegalStateException("No environment preset of the registry is made for this version on this device " +
                "(version ${version?.id}, it lists ${version?.containers?.size ?: 0}; ${all.size} presets suit this device and platform; processor family ${soc.family}).")
        val config = all.getValue(choice.configId)

        progress.status("Getting the environment…")
        val manager = ContainerManager(context)
        val contents = ContentsManager(context).apply { syncContents() }
        val result = EnvironmentProvider.ensure(context, manager, contents, config, null, progress)
        val container: Container = result.container
            ?: throw IllegalStateException(result.warnings.joinToString("\n").ifEmpty { "The environment could not be made." })
        if (nativeArm64 && (!isNativeContainer(container) || !WineInfo.fromIdentifier(context, contents, container.wineVersion).supportsNativeArm64())) {
            throw IllegalStateException("This environment does not support native Windows ARM64 games.")
        }

        progress.status("Adding the game…")
        val settings = game.effective(version, platform)
        val extras = buildExtras(result.linuxSettings, settings?.extras, game, version, config.id)
        val name = ShortcutWriter.freeName(container, job.folderName)
        val desktop: File? = if (container.isLinux) {
            val configured = container.copyWithExtras(extras.filterValues { it != null }.mapValues { it.value!! })
            val refusal = LinuxProgramSupport.refusal(context, configured, kind)
            if (refusal != null) throw IllegalStateException("This container cannot run this program: $refusal.")
            if (LinuxProgramInstaller.needsCopy(configured, exe)) throw IllegalStateException("The downloaded files are not inside the container.")
            val guest = LinuxProgramInstaller.guestPath(configured, exe)
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
