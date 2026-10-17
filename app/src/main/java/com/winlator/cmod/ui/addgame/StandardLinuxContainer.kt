package com.winlator.cmod.ui.addgame

import android.content.Context
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerImporter
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.container.EnvironmentProvider
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.ExecutableKind
import com.winlator.cmod.linux.LinuxDriverManager
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxRuntime
import com.winlator.cmod.linux.LinuxSession
import com.winlator.cmod.steam.SteamContainer
import org.json.JSONObject

/**
 * The Linux container for a program the registry has no config for: the app's default Linux settings (the "steam" block of the
 * registry's linux.json, which names the runtime, the emulator and the Turnip driver). An x86-64 program or a script runs through
 * Box64, an ARM64 program needs no emulator. What is not installed is downloaded first.
 */
internal object StandardLinuxContainer {
    const val CONFIG_BOX64 = "builtin-linux-box64"
    const val CONFIG_NATIVE = "builtin-linux-native"

    fun shortcutSettings(context: Context, kind: ExecutableKind): Map<String, String> {
        val defaults = SteamContainer.defaults(context, false)
        val choice = if (kind == ExecutableKind.LINUX_X86) LinuxEmulator.FEX
            else if (usesBox64(kind)) LinuxEmulator.BOX64 else LinuxEmulator.NONE
        return mapOf(LinuxEmulator.EXTRA_EMULATOR to choice,
            LinuxEmulator.EXTRA_ID to if (choice == LinuxEmulator.BOX64) defaults.emulator else if (choice == LinuxEmulator.FEX) defaults.fex else "",
            LinuxEmulator.EXTRA_PRESET to defaults.emulatorPreset,
            LinuxEmulator.EXTRA_ROOTFS to defaults.rootfs)
    }

    /** Programs that need an x86 emulator: x86-64 ones and scripts (a script may start x86-64 programs). */
    fun usesBox64(kind: ExecutableKind) = kind != ExecutableKind.LINUX_ARM64

    fun configId(kind: ExecutableKind) = if (usesBox64(kind)) CONFIG_BOX64 else CONFIG_NATIVE

    /** The container made for the kind earlier, or a new one. Call off the main thread; it downloads what is missing. */
    fun ensure(
        context: Context,
        manager: ContainerManager,
        contents: ContentsManager,
        kind: ExecutableKind,
        progress: ContainerImporter.Progress,
        warnings: MutableList<String>
    ): Container? {
        val id = configId(kind)
        val defaults = SteamContainer.defaults(context, true)
        val box64 = usesBox64(kind)

        val runtime = if (LinuxRuntime.isInstalled(context, defaults.runtime)) defaults.runtime
        else LinuxRuntime.listInstalled(context).firstOrNull()?.id ?: defaults.runtime
        val emulatorId = if (!box64) "" else if (LinuxEmulator.isInstalled(context, defaults.emulator)) defaults.emulator
        else LinuxEmulator.list(context, LinuxEmulator.BOX64).firstOrNull()?.id ?: defaults.emulator
        val driver = if (LinuxDriverManager.isInstalled(context, defaults.driver)) defaults.driver
        else LinuxDriverManager.list(context).firstOrNull()?.id ?: defaults.driver

        val extra = JSONObject()
            .put(EnvironmentProvider.EXTRA_CONFIG_ID, id)
            .put(EnvironmentProvider.EXTRA_AUTO_CREATED, "1")
            .put(LinuxEmulator.EXTRA_EMULATOR, if (box64) LinuxEmulator.BOX64 else LinuxEmulator.NONE)
            .put(LinuxEmulator.EXTRA_ID, emulatorId)
            .put(LinuxEmulator.EXTRA_PRESET, if (box64) defaults.emulatorPreset else "")
            .put(LinuxDriverManager.EXTRA_DRIVER, driver)
            .put(LinuxSession.EXTRA_GL_DRIVER, defaults.glDriver)
            .put(LinuxSession.EXTRA_VULKAN_PRESENT, defaults.vulkanPresent)
            .put(LinuxSession.EXTRA_TOUCHSCREEN, "1")
            .put(LinuxSession.EXTRA_KEYBOARD_BUTTON, "1")
            .put(LinuxSession.EXTRA_AUTO_KEYBOARD, "1")
        val data = JSONObject()
            .put("type", Container.TYPE_LINUX)
            .put("name", if (box64) "Linux (x86_64, Box64)" else "Linux (ARM64)")
            .put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            .put("linuxRuntime", runtime)
            .put("envVars", "")
            .put("extraData", extra)
        val plan = ContainerImporter.planLinux(context, data)
        if (plan.unavailable.isNotEmpty()) {
            warnings.add("Not in the catalogs: " + plan.unavailable.joinToString(", ") + ". Check the connection.")
            return null
        }
        val result = ContainerImporter.run(context, manager, contents, ContainerProfile.Envelope(data, org.json.JSONArray()), progress, plan)
        warnings.addAll(result.warnings)
        return result.container
    }
}
