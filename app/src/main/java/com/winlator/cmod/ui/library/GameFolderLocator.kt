package com.winlator.cmod.ui.library

import android.content.Context
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.GameFolder
import com.winlator.cmod.gameprofile.GameProfiles
import com.winlator.cmod.linux.LinuxSession
import com.winlator.cmod.linux.LinuxSteamGames
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File

/** Where a shortcut's game is on the device, for the screens that show its files. */
object GameFolderLocator {
    private val BINARIES = Regex("(?i)bin.*|win64|x64|x86_64|arm64|game")

    /** The game's folder, or null when the shortcut has none (the Steam client, a program that is not a game's). */
    @JvmStatic
    fun folderOf(context: Context, shortcut: Shortcut): File? {
        val container = shortcut.container
        if (container.isLinux) {
            val root = shortcut.getExtra(GameProfiles.EXTRA_ROOT)
            if (root.isNotEmpty()) LinuxSession.hostFile(context, container, root)?.takeIf { it.isDirectory }?.let { return it }
            if (shortcut.isSteamGame) {
                val game = LinuxSteamGames.game(container, LinuxSession.steamAppId(shortcut.path))
                if (game != null) LinuxSteamGames.installFolder(container, game).takeIf { it.isDirectory }?.let { return it }
                return null
            }
            if (shortcut.path.startsWith("/")) {
                // A program of the guest: its folder, or the one above when it is a binaries folder (BinLinux, bin64, ...).
                val exeDir = LinuxSession.hostFile(context, container, shortcut.path.trim())?.parentFile ?: return null
                val parent = exeDir.parentFile
                val folder = if (parent != null && parent.path.length > 1 && BINARIES.matches(exeDir.name)) parent else exeDir
                return folder.takeIf { it.isDirectory }
            }
            return null
        }
        return GameFolder.of(ImageFs.find(context), container, shortcut.path)?.folder?.takeIf { it.isDirectory }
    }
}
