package com.winlator.cmod.ui.addgame

import android.content.Context
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.EnvironmentProvider
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.OpenGLDriverDefaults
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.ui.settings.cleanContainerEnvironment
import com.winlator.cmod.ui.settings.loadWineRuntimeOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** The container for a game the registry knows nothing about: the app's default Windows settings on an installed Wine or Proton. */
internal object StandardContainer {
    const val CONFIG_X64 = "builtin-windows-x64"
    const val CONFIG_ARM64EC = "builtin-windows-arm64ec"

    /** The installed Wine/Proton for the kind (an arm64ec build runs on FEX, the other on Box64), or null when there is none. */
    fun runtimeFor(context: Context, arm64ec: Boolean): String? = runBlocking {
        loadWineRuntimeOptions(context)
            .filter { it.installed && it.label.contains("arm64ec", ignoreCase = true) == arm64ec }
            .firstOrNull()?.id
    }

    /** The container made for the kind earlier, or a new one. Call off the main thread. */
    fun ensure(context: Context, manager: ContainerManager, contents: ContentsManager, arm64ec: Boolean): Container? {
        val runtime = runtimeFor(context, arm64ec) ?: return null
        val configId = if (arm64ec) CONFIG_ARM64EC else CONFIG_X64
        EnvironmentProvider.find(manager, configId, runtime)?.let { return it }
        val wineInfo = WineInfo.fromIdentifier(context, contents, runtime)
        if (wineInfo.path.isNullOrBlank()) return null
        val defaultDriver = if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) {
            DefaultVersion.WRAPPER_ADRENO
        } else {
            DefaultVersion.WRAPPER
        }
        val data = JSONObject().apply {
            put("name", if (arm64ec) "Windows (ARM64EC, FEX)" else "Windows (x86_64, Box64)")
            put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            put("envVars", cleanContainerEnvironment(Container.DEFAULT_ENV_VARS))
            put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER)
            put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG.replace(";version=;", ";version=$defaultDriver;"))
            put("rendererNative", false)
            put("rendererPresentMode", "fifo")
            put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG)
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("emulator", if (wineInfo.isArm64EC) "FEXCore" else "Box64")
            put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
            put("drives", Container.DEFAULT_DRIVES)
            put("box64Version", if (wineInfo.isArm64EC) DefaultVersion.WOWBOX64 else DefaultVersion.BOX64)
            put("box64Preset", Box64Preset.COMPATIBILITY)
            put("fexcoreVersion", DefaultVersion.FEXCORE)
            put("fexcorePreset", FEXCorePreset.INTERMEDIATE)
            put("wineVersion", runtime)
            put("extraData", JSONObject()
                .put(EnvironmentProvider.EXTRA_CONFIG_ID, configId)
                .put(EnvironmentProvider.EXTRA_AUTO_CREATED, "1"))
        }
        OpenGLDriverDefaults.initialize(context, data)
        return manager.createContainerFromData(data, contents)
    }
}
