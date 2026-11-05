package com.winlator.cmod.ui.shortcut

import android.content.Context
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.contents.ContainerConfigCatalog
import com.winlator.cmod.core.DeviceInfo
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.gameprofile.ConfigChooser
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.linux.LinuxShortcutDefaults

/** The registry's presets (container configs) of a game's version, and what one of them makes of a Linux shortcut's settings. */
internal object LinuxPresetSettings {
    /** The version's presets made for this device, in the order the registry lists them, and the one the registry recommends. Blocks: call it off the main thread. */
    class Choices(val presets: List<ContainerConfigCatalog.Config>, val recommendedId: String?)

    fun choices(context: Context, version: GameProfile.Version?): Choices {
        if (version == null) return Choices(emptyList(), null)
        val soc = DeviceInfo.soc(context)
        val renderer = GPUInformation.getRenderer(null, context)
        val all = ContainerConfigCatalog.all(context, true).filterValues {
            it.platform == GameProfile.Platform.LINUX && it.suits(soc, renderer)
        }
        val recommended = ConfigChooser.choose(version, soc.family, all, GameProfile.Platform.LINUX)?.configId
        return Choices(version.containers.mapNotNull { all[it] }, recommended)
    }

    /**
     * Every Linux shortcut setting the preset stands for, as the shortcut file keeps it (a missing key is one the preset leaves alone).
     * "graphicsDriverConfig" is always there: empty when the preset has none, so that an earlier one does not stay. Null when the preset
     * cannot be read or is not a Linux one. Blocks: call it off the main thread.
     */
    fun load(context: Context, config: ContainerConfigCatalog.Config): Map<String, String>? = runCatching {
        val text = ContainerConfigCatalog.profileText(context, config, true) ?: return null
        val data = ContainerProfile.parse(text).container
        if (data.optString("type") != "linux") return null
        val values = LinkedHashMap(LinuxShortcutDefaults.fromData(context, ContainerManager(context), data))
        values["graphicsDriverConfig"] = data.optString("graphicsDriverConfig")
        values
    }.getOrNull()
}
