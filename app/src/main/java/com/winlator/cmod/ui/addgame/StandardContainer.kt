package com.winlator.cmod.ui.addgame

import android.content.Context
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.EnvironmentProvider
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.EnvVars
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
    const val CONFIG_ARM64_NATIVE = "builtin-windows-arm64-native"

    enum class Mode(val configId: String) {
        X64(CONFIG_X64), ARM64EC(CONFIG_ARM64EC), ARM64_NATIVE(CONFIG_ARM64_NATIVE)
    }

    /** Native guests need an AArch64 Unix loader and ARM64 Windows builtins, independently of the runtime's label. */
    fun runtimeFor(context: Context, mode: Mode): String? = runBlocking {
        val contents = ContentsManager(context).apply { syncContents() }
        loadWineRuntimeOptions(context)
            .filter { option ->
                if (!option.installed) false
                else if (mode == Mode.ARM64_NATIVE) WineInfo.fromIdentifier(context, contents, option.id).supportsNativeArm64()
                else WineInfo.fromIdentifier(context, contents, option.id).let {
                    it.arch != "arm64" && it.isArm64EC == (mode == Mode.ARM64EC)
                }
            }
            // Proton 9 is the native Vulkan runtime verified on the phone. Never silently upgrade this prefix to 11.
            .sortedBy { if (mode == Mode.ARM64_NATIVE && it.id == WineInfo.MAIN_WINE_VERSION.identifier()) 0 else 1 }
            .firstOrNull()?.id
    }

    /** The container made for the kind earlier, or a new one. Call off the main thread. */
    fun ensure(context: Context, manager: ContainerManager, contents: ContentsManager, mode: Mode, reuseExisting: Boolean = true): Container? {
        val runtime = runtimeFor(context, mode) ?: return null
        val nativeArm64 = mode == Mode.ARM64_NATIVE
        val configId = mode.configId
        if (reuseExisting) EnvironmentProvider.find(manager, configId, runtime)?.let { return it }
        val wineInfo = WineInfo.fromIdentifier(context, contents, runtime)
        if (wineInfo.path.isNullOrBlank()) return null
        val defaultDriver = if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) {
            DefaultVersion.WRAPPER_ADRENO
        } else {
            DefaultVersion.WRAPPER
        }
        val environment = EnvVars(cleanContainerEnvironment(Container.DEFAULT_ENV_VARS)).apply {
            if (nativeArm64) {
                put("WINEESYNC", "0")
                put("WINEFSYNC", "0")
            }
        }
        val data = JSONObject().apply {
            put("name", when (mode) {
                Mode.ARM64_NATIVE -> "Windows (ARM64 native)"
                Mode.ARM64EC -> "Windows (ARM64EC, FEX)"
                Mode.X64 -> "Windows (x86_64, Box64)"
            })
            put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            put("envVars", environment.toString())
            put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER)
            put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG.replace(";version=;", ";version=$defaultDriver;"))
            put("rendererNative", false)
            put("rendererPresentMode", "fifo")
            put("dxwrapper", if (nativeArm64) "wined3d" else Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", if (nativeArm64) "" else Container.DEFAULT_DXWRAPPERCONFIG)
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("emulator", if (wineInfo.isArm64EC) "FEXCore" else "Box64")
            put("wincomponents", if (nativeArm64) "direct3d=0,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,vcrun2010=0" else Container.DEFAULT_WINCOMPONENTS)
            put("drives", Container.DEFAULT_DRIVES)
            put("box64Version", if (wineInfo.isArm64EC) DefaultVersion.WOWBOX64 else DefaultVersion.BOX64)
            put("box64Preset", Box64Preset.COMPATIBILITY)
            put("fexcoreVersion", DefaultVersion.FEXCORE)
            put("fexcorePreset", FEXCorePreset.INTERMEDIATE)
            put("wineVersion", runtime)
            val extras = JSONObject()
                .put(EnvironmentProvider.EXTRA_CONFIG_ID, configId)
                .put(EnvironmentProvider.EXTRA_AUTO_CREATED, "1")
            if (nativeArm64) extras.put(WineInfo.NATIVE_ARM64_CONTAINER_KEY, WineInfo.NATIVE_ARM64_CONTAINER_VALUE)
            put("extraData", extras)
        }
        OpenGLDriverDefaults.initialize(context, data)
        return manager.createContainerFromData(data, contents)
    }
}
