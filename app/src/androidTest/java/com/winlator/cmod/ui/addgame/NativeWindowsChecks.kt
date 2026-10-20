package com.winlator.cmod.ui.addgame

import android.app.Instrumentation
import android.os.Bundle
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.ContainerImporter
import com.winlator.cmod.container.ContainerProfile
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.container.ShortcutWriter
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.EnvVars
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineUtils
import com.winlator.cmod.core.WindowsExecutable
import com.winlator.cmod.core.WindowsLaunchPlan
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the same preset, prefix provisioning and shortcut writer as Add Game without touching existing prefixes. */
object NativeWindowsChecks {
    @JvmStatic
    fun missingRuntime(test: Instrumentation): String {
        val context = test.targetContext
        val manager = ContainerManager(context)
        val contents = ContentsManager(context).apply { syncContents() }
        val before = manager.containers.associate { it.configFile.absolutePath to FileUtils.readString(it.configFile) }
        val requested = "wine-0.0-skynet-missing-runtime-arm64"
        val data = JSONObject().apply {
            put("name", "Missing native runtime check")
            put("wineVersion", requested)
            put("dxwrapper", "wined3d")
            put("extraData", JSONObject().put(WineInfo.NATIVE_ARM64_CONTAINER_KEY, WineInfo.NATIVE_ARM64_CONTAINER_VALUE))
        }
        val result = ContainerImporter.run(context, manager, contents, ContainerProfile.Envelope(data, JSONArray()), null)
        // An older importer could create a fallback prefix; remove only that isolated test prefix.
        result.container?.let { manager.removeManagedContainer(it) }
        check(result.container == null) { "A missing explicit native runtime must not use another installed Wine" }
        check(data.optString("wineVersion") == requested) { "The explicit native runtime selection must remain unchanged" }
        check(result.warnings.any { it.contains(requested) && it.contains("Settings > Components") }) {
            "A missing native runtime must explain which runtime to install"
        }
        check(before.all { (path, text) -> FileUtils.readString(File(path)) == text }) { "Existing container configurations changed" }
        return "PASS missing explicit native runtime is rejected without fallback or container mutation\n" +
                "PASS missing native runtime error names the exact runtime and Settings > Components\n"
    }

    @JvmStatic
    fun run(test: Instrumentation, options: Bundle): String {
        val context = test.targetContext
        val manager = ContainerManager(context)
        val contents = ContentsManager(context).apply { syncContents() }
        val imageFs = ImageFs.find(context)
        val original = manager.wineContainers.associate { it.configFile.absolutePath to FileUtils.readString(it.configFile) }
        val report = StringBuilder()
        fun check(ok: Boolean, message: String) {
            if (!ok) throw AssertionError(message)
            report.append("PASS ").append(message).append('\n')
        }
        val program = File(requireNotNull(options.getString("nativeWindowsProgram")) { "nativeWindowsProgram is required" })
        check(WindowsExecutable.detect(program) == WindowsExecutable.Architecture.ARM64, "selected real executable is Windows ARM64")
        val bundled = WineInfo.fromIdentifier(context, contents, WineInfo.MAIN_WINE_VERSION.identifier())
        check(bundled.supportsNativeArm64(), "bundled Wine has an AArch64 loader and ARM64 Windows core libraries")
        manager.wineContainers.map { WineInfo.fromIdentifier(context, contents, it.wineVersion) }
            .filter { it.arch == "x86_64" }.forEach {
                check(!it.supportsNativeArm64(), "x86-64 Wine is excluded from native ARM64 selection: ${it.identifier()}")
            }
        var created: Container? = null
        var succeeded = false
        val keep = options.getString("nativeWindowsKeep") == "1"
        try {
            val container = requireNotNull(StandardContainer.ensure(context, manager, contents, StandardContainer.Mode.ARM64_NATIVE, false)) {
                "The native ARM64 preset could not create a fresh prefix"
            }
            created = container
            check(container.configFile.absolutePath !in original, "native preset creates a separate prefix")
            check(container.getExtra(WineInfo.NATIVE_ARM64_CONTAINER_KEY) == WineInfo.NATIVE_ARM64_CONTAINER_VALUE,
                "native prefix architecture survives container serialization")
            check(container.wineVersion == WineInfo.MAIN_WINE_VERSION.identifier(), "native preset selects the verified Proton 9 runtime")
            check(container.getDXWrapper() == "wined3d", "native preset retains Wine graphics builtins")
            val system32 = File(container.rootDir, ".wine/drive_c/windows/system32")
            for (name in listOf("ntdll.dll", "kernel32.dll", "kernelbase.dll", "user32.dll", "explorer.exe", "winevulkan.dll", "winex11.drv", "d3d11.dll", "dxgi.dll")) {
                val architecture = WindowsExecutable.detect(File(system32, name))
                check(architecture == WindowsExecutable.Architecture.ARM64 || architecture == WindowsExecutable.Architecture.ARM64X,
                    "fresh prefix $name supports ARM64")
            }
            WineUtils.createDosdevicesSymlinks(container)
            val name = options.getString("nativeWindowsName") ?: "Native ARM64 check ${UUID.randomUUID().toString().take(8)}"
            val extras = linkedMapOf<String, String>()
            if (program.name.equals("BeamNG.drive.arm64.exe", true))
                extras["execArgs"] = options.getString("nativeWindowsArgs")
                    ?: "-gfx vk -nosteam -userpath C:/beamng-arm64-user -level gridmap_v2"
            val desktop = requireNotNull(ShortcutWriter.write(context, program, container, name, extras, null))
            val shortcut = Shortcut(container, desktop)
            check(shortcut.getExtra(WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY) == "arm64", "shortcut writer persists detected ARM64 architecture")
            val wine = WineInfo.fromIdentifier(context, contents, container.wineVersion)
            val plan = WindowsLaunchPlan.resolve(imageFs, container, shortcut, wine)
            check(plan.nativeArm64 && plan.executable.canonicalFile == program.canonicalFile, "launch plan retains the exact selected executable")
            val argv = plan.wineArguments(container.screenSize, shortcut).toList()
            check(argv.take(2) == listOf("wine", "explorer") && argv.contains("C:\\windows\\system32\\winhandler-arm64.exe"),
                "native launch uses Wine explorer and the ARM64 input helper")
            check(argv.contains(plan.nativeWorkingDirectory) && argv.contains(plan.nativeExecutablePath),
                "mapped Windows paths with spaces remain individual launch arguments")
            val environment = EnvVars("HODLL=translator.dll WINE_NO_DUPLICATE_EXPLORER=1")
            plan.applyEnvironment(environment)
            check(!environment.has("HODLL") && !environment.has("WINE_NO_DUPLICATE_EXPLORER"), "native launch removes translator and duplicate-explorer overrides")
            if (plan.isBeamNG()) {
                check(argv.contains("vk") && argv.contains("-nosteam") && argv.contains("gridmap_v2"), "BeamNG native arguments select Vulkan and GridMapv2")
                check(environment.get("WINEDLLOVERRIDES").contains("browseui="), "BeamNG native startup workaround remains game-specific")
            }
            plan.installHelper(context, container)
            check(WindowsExecutable.detect(File(system32, "winhandler-arm64.exe")) == WindowsExecutable.Architecture.ARM64,
                "packaged ARM64 helper installs without replacing the legacy helper")

            // Replacing an executable must invalidate its cached architecture at launch.
            val fixture = File(container.rootDir, "Selected path with spaces/architecture fixture.exe")
            fixture.parentFile!!.mkdirs()
            fixture.writeBytes(pe(0xaa64))
            val fixtureDesktop = requireNotNull(ShortcutWriter.write(context, fixture, container, "Architecture fixture", emptyMap(), null))
            val fixtureShortcut = Shortcut(container, fixtureDesktop)
            fixture.writeBytes(pe(0x8664))
            check(!WindowsLaunchPlan.resolve(imageFs, container, fixtureShortcut, wine).nativeArm64,
                "a replaced x64 executable cannot use cached ARM64 routing")
            fixture.delete()
            val missingRejected = runCatching { WindowsLaunchPlan.resolve(imageFs, container, fixtureShortcut, wine) }.isFailure
            check(missingRejected, "missing cached ARM64 executable produces a clear launch failure")
            fixtureDesktop.delete()
            fixture.parentFile!!.delete()

            for ((path, text) in original) check(FileUtils.readString(File(path)) == text, "existing container configuration is unchanged: $path")
            report.append("NATIVE_WINDOWS_CONTAINER_ID=").append(container.id).append('\n')
            report.append("NATIVE_WINDOWS_SHORTCUT=").append(desktop.absolutePath).append('\n')
            succeeded = true
        } finally {
            if (!keep || !succeeded) created?.let { manager.removeManagedContainer(it) }
        }
        return report.toString()
    }

    private fun pe(machine: Int): ByteArray = ByteArray(200).apply {
        this[0] = 'M'.code.toByte(); this[1] = 'Z'.code.toByte(); this[60] = 64
        this[64] = 'P'.code.toByte(); this[65] = 'E'.code.toByte()
        this[68] = machine.toByte(); this[69] = (machine shr 8).toByte()
        this[84] = 112; this[88] = 0x0b; this[89] = 0x02
    }
}
