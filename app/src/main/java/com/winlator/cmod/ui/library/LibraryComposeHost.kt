package com.winlator.cmod.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class LibraryItem @JvmOverloads constructor(
    val id: String,
    val shortcutPath: String,
    val name: String,
    val containerName: String,
    val coverPath: String?,
    val bannerPath: String?,
    val iconPath: String?,
    val fallbackIcon: Bitmap?,
    val favorite: Boolean,
    val lastRunAt: Long,
    /** A game of the Steam client (or the client itself): the card shows a Steam badge. */
    val steam: Boolean = false,
    /** Installed version detected using the game profile, empty when unknown. */
    val version: String = "",
    val steamClient: Boolean = false,
    val manual: Boolean = false,
    val testApp: Boolean = false
)

@Stable
interface LibraryCallbacks {
    fun onOpen(shortcutPath: String)
    fun onRun(shortcutPath: String)
    fun onGridViewChanged(gridView: Boolean)
    fun onAction(shortcutPath: String, action: String)
    fun onArtworkNeeded(shortcutPath: String, kind: String)
}

class LibraryComposeController internal constructor(
    private val context: Context,
    private val items: MutableState<List<LibraryItem>>,
    private val grid: MutableState<Boolean>,
    private val query: MutableState<String>,
    private val selectedShortcutPath: MutableState<String?>,
    private val sessionState: Bundle
) {
    private val statePreferences = context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
    private val metadataGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setItems(value: List<LibraryItem>) {
        val snapshot = value.toList()
        items.value = snapshot
        val generation = metadataGeneration.incrementAndGet()
        Thread {
            val resolved = resolveLibraryMetadata(context, snapshot)
            mainHandler.post {
                if (metadataGeneration.get() == generation) items.value = resolved
            }
        }.start()
    }

    fun setGridView(value: Boolean) { grid.value = value }
    fun dispose() { metadataGeneration.incrementAndGet() }
    fun setSearchQuery(value: String?) {
        query.value = value.orEmpty()
        sessionState.putString("query", query.value)
    }

    fun setSelectedShortcutPath(value: String?) {
        selectedShortcutPath.value = value
        sessionState.putString("selected_shortcut_path", value)
        statePreferences.edit().apply {
            if (value.isNullOrEmpty()) remove("selected_shortcut_path")
            else putString("selected_shortcut_path", value)
        }.apply()
    }
}

class LibraryComposeBinding internal constructor(
    val view: ComposeView,
    val controller: LibraryComposeController
)

object LibraryComposeHost {
    const val ACTION_SETTINGS = "settings"
    const val ACTION_STORAGE = "storage"
    const val ACTION_CONTAINER = "container"
    const val ACTION_ICON = "icon"
    const val ACTION_CLONE = "clone"
    const val ACTION_HOME = "home"
    const val ACTION_EXPORT = "export"
    const val ACTION_REMOVE = "remove"
    const val ACTION_FAVORITE = "favorite"
    const val ACTION_ENTER = "enter"
    const val ACTION_SAVES = "saves"

    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        initialGridView: Boolean,
        callbacks: LibraryCallbacks,
        sessionState: Bundle = Bundle()
    ): LibraryComposeBinding {
        val activity = context as? MainActivity
        applyAppFullscreen(activity)

        val items = mutableStateOf<List<LibraryItem>>(emptyList())
        val grid = mutableStateOf(initialGridView)
        val query = mutableStateOf(sessionState.getString("query").orEmpty())
        val selectedShortcutPath = mutableStateOf(
            sessionState.getString("selected_shortcut_path") ?: context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
                .getString("selected_shortcut_path", null)
        )
        val controller = LibraryComposeController(
            context.applicationContext,
            items,
            grid,
            query,
            selectedShortcutPath,
            sessionState
        )
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    LibraryRoot(
                        items.value,
                        grid.value,
                        query.value,
                        selectedShortcutPath,
                        callbacks,
                        sessionState,
                        controller::setSearchQuery
                    )
                }
            }
        }
        return LibraryComposeBinding(view, controller)
    }
}

internal enum class LibraryFilter { All, Favorites, Steam, Custom, TestApps }

internal fun availableLibraryFilters(testAppsEnabled: Boolean): List<LibraryFilter> =
    LibraryFilter.entries.filter { it != LibraryFilter.TestApps || testAppsEnabled }

internal fun restoreLibraryFilter(name: String?, testAppsEnabled: Boolean): LibraryFilter =
    availableLibraryFilters(testAppsEnabled).firstOrNull { it.name == name } ?: LibraryFilter.All

internal fun filterLibraryItems(items: List<LibraryItem>, filter: LibraryFilter, query: String): List<LibraryItem> {
    val matching = items.filter { item ->
        if (item.testApp != (filter == LibraryFilter.TestApps)) return@filter false
        if (item.steamClient && filter != LibraryFilter.Steam) return@filter false
        val included = when (filter) {
            LibraryFilter.All -> true
            LibraryFilter.Favorites -> item.favorite
            LibraryFilter.Steam -> item.steam
            LibraryFilter.Custom -> item.manual && !item.steamClient
            LibraryFilter.TestApps -> item.testApp
        }
        included && (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
    }
    return matching.filter { it.steamClient } + matching.filterNot { it.steamClient }
}
