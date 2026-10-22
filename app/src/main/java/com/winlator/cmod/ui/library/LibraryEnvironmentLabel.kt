package com.winlator.cmod.ui.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.linux.LinuxContainerInfo
import com.winlator.cmod.gameprofile.ShortcutGameVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun environmentText(
    context: Context,
    contents: ContentsManager,
    shortcutPath: String,
    fallback: String,
    shortcuts: Map<String, Shortcut>
): String = runCatching {
    val shortcut = shortcuts[shortcutPath] ?: return@runCatching fallback
    // A Linux container has no Wine: name the system, the x86 emulator it uses (if any) and the output.
    if (shortcut.container.isLinux) return@runCatching LinuxContainerInfo.summary(context, shortcut.container, " · ")
    val info = WineInfo.fromIdentifier(context, contents, shortcut.container.getWineVersion())
    var version = info.fullVersion()
    if (version.endsWith(".0")) version = version.dropLast(2)
    val runtime = (if (info.type.equals("proton", true)) "Proton " else "Wine ") + version + " " + info.getArch()
    val renderer = when {
        shortcut.getUseDisplayX() -> "DisplayX"
        shortcut.getRendererNative() -> "EGL"
        else -> "Vulkan"
    }
    "$runtime · $renderer"
}.getOrDefault(fallback)

private fun resolveLibraryLabels(context: Context, items: List<LibraryItem>, detectVersions: Boolean): List<LibraryItem> = runCatching {
    val manager = ContainerManager(context)
    val shortcuts = manager.loadShortcuts().filterNotNull().associateBy { it.file.path }
    val contents = ContentsManager(context).apply { syncContents() }
    val versions = if (detectVersions) ShortcutGameVersions(context) else null
    items.map { item ->
        item.copy(
            containerName = environmentText(context, contents, item.shortcutPath, item.containerName, shortcuts),
            version = if (versions == null) item.version else runCatching {
                shortcuts[item.shortcutPath]?.let(versions::label).orEmpty()
            }.getOrDefault("")
        )
    }
}.getOrDefault(items)

/** Called on every library resume/open; detectors run off the UI thread and never rewrite shortcut settings. */
internal fun resolveLibraryMetadata(context: Context, items: List<LibraryItem>): List<LibraryItem> =
    resolveLibraryLabels(context, items, detectVersions = true)

internal fun resolveLibraryEnvironmentLabel(context: Context, item: LibraryItem): String =
    resolveLibraryLabels(context, listOf(item), detectVersions = false).firstOrNull()?.containerName ?: item.containerName

@Composable
internal fun libraryEnvironmentLabel(item: LibraryItem): String {
    val context = LocalContext.current
    val label by produceState(item.containerName, item.shortcutPath) {
        value = withContext(Dispatchers.IO) { resolveLibraryEnvironmentLabel(context, item) }
    }
    return label
}
