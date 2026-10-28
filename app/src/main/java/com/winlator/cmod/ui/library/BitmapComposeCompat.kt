package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.GamepadNavigation
import com.winlator.cmod.ui.UiNavigationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

internal fun Bitmap.asImageBitmap(): ImageBitmap = this.composeAsImageBitmap()

internal fun Bitmap.fillLibraryIcon(): Bitmap {
    if (!hasAlpha() || width < 8 || height < 8) return this
    val side = minOf(width, height)
    val left = (width - side) / 2
    val top = (height - side) / 2
    fun alpha(x: Float, y: Float): Int =
        getPixel(left + ((side - 1) * x).toInt(), top + ((side - 1) * y).toInt()) ushr 24
    val transparentCorners = listOf(.05f to .05f, .95f to .05f, .05f to .95f, .95f to .95f)
        .all { (x, y) -> alpha(x, y) < 16 }
    val filledCircle = listOf(.5f to .05f, .95f to .5f, .5f to .95f, .05f to .5f,
        .2f to .2f, .8f to .2f, .2f to .8f, .8f to .8f, .5f to .5f)
        .all { (x, y) -> alpha(x, y) >= 240 }
    if (!transparentCorners || !filledCircle) return this
    // Fill the tile from inside a circular artwork mask, rather than displaying its transparent corners.
    val crop = ((side - 2) / kotlin.math.sqrt(2.0)).toInt().coerceAtLeast(1)
    return Bitmap.createBitmap(this, (width - crop) / 2, (height - crop) / 2, crop, crop)
}

internal enum class LibraryIconSize(val cell: Int, val icon: Int, val label: Int) {
    Small(88, 56, R.string.library_view_small_icons),
    Medium(112, 72, R.string.library_view_medium_icons),
    Large(140, 96, R.string.library_view_large_icons)
}

private const val ADD_GAME_TILE = "library:add-game"

@Composable
internal fun LibraryRoot(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    cb: LibraryCallbacks,
    sessionState: Bundle,
    onQueryChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val prefs = remember { context.getSharedPreferences("library_compose_state", 0) }
    val background = rememberLibraryBackgroundSettings()
    var size by remember {
        mutableStateOf(LibraryIconSize.entries.firstOrNull { it.name == prefs.getString("icon_size", null) }
            ?: LibraryIconSize.Medium)
    }
    var filterName by remember { mutableStateOf(sessionState.getString("filter", LibraryFilter.All.name)) }
    val testAppsEnabled = com.winlator.cmod.linux.LinuxTestPrograms.enabled(context)
    val filters = availableLibraryFilters(testAppsEnabled)
    val filter = restoreLibraryFilter(filterName, testAppsEnabled)
    val visible = remember(items, filter, query) { filterLibraryItems(items, filter, query) }
    var menu by remember { mutableStateOf<LibraryItem?>(null) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val inputMode = LocalInputModeManager.current
    val railFocus = remember { FocusRequester() }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var movingFocus by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRevision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(selectedShortcutPath.value, filter) {
        sessionState.putString("selected_shortcut_path", selectedShortcutPath.value)
        sessionState.putString("filter", filter.name)
        prefs.edit().putString("selected_shortcut_path", selectedShortcutPath.value).apply()
    }
    val showMenu: (LibraryItem, Rect) -> Unit = { item, bounds ->
        selectedShortcutPath.value = item.shortcutPath
        anchor = bounds
        menu = item
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = UiNavigationPolicy.usesLibraryRail(maxWidth.value)
        DisposableEffect(activity, wide) {
            activity?.setBottomNavigationVisible(true)
            activity?.setMainToolbarVisible(false)
            onDispose { activity?.refreshMainNavigation() }
        }
        Row(Modifier.fillMaxSize().displayCutoutPadding().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (wide) {
                Surface(Modifier.width(176.dp).fillMaxHeight()) {
                    Column(Modifier.fillMaxHeight().padding(8.dp).verticalScroll(rememberScrollState())) {
                        filters.forEach { option ->
                            TextButton(onClick = { filterName = option.name }, modifier = Modifier.fillMaxWidth()
                                .then(if (option == filter) Modifier.focusRequester(railFocus) else Modifier),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (option == filter) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                                )) {
                                LibraryFilterIcon(option)
                                Spacer(Modifier.width(10.dp))
                                Text(libraryFilterLabel(option), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LibraryHeader(activity, grid, size, query, cb::onGridViewChanged, { value ->
                    size = value
                    prefs.edit().putString("icon_size", value.name).apply()
                }, onQueryChanged, { searchFocused = it })
                if (!wide) {
                    ScrollableTabRow(selectedTabIndex = filters.indexOf(filter), edgePadding = 8.dp) {
                        filters.forEach { option ->
                            Tab(selected = option == filter, onClick = { filterName = option.name },
                                icon = { LibraryFilterIcon(option) },
                                text = { Text(libraryFilterLabel(option)) })
                        }
                    }
                }
                if (GamepadNavigation.inUse.value) {
                    Text(stringResource(R.string.library_controller_hints), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryBackground(background, Modifier.matchParentSize())
                    val cell = if (grid) 172.dp else size.cell.dp
                    val sidePadding = if (wide) UiNavigationPolicy.LIBRARY_GRID_PADDING
                        else UiNavigationPolicy.LIBRARY_PORTRAIT_GRID_PADDING
                    val columns = UiNavigationPolicy.libraryColumns(maxWidth.value, cell.value, sidePadding)
                    val stateKey = "library_${filter.name}_$grid"
                    val gridState = key(stateKey) {
                        rememberLazyGridState(sessionState.getInt("${stateKey}_index"), sessionState.getInt("${stateKey}_offset"))
                    }
                    LaunchedEffect(gridState, stateKey) {
                        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                            .collect { (index, offset) ->
                                sessionState.putInt("${stateKey}_index", index)
                                sessionState.putInt("${stateKey}_offset", offset)
                            }
                    }
                    val showAddGame = filter == LibraryFilter.Custom
                    val ids = (if (showAddGame) listOf(ADD_GAME_TILE) else emptyList()) + visible.map { it.shortcutPath }
                    val moveFocus: (Int, Int) -> Boolean = { index, code ->
                        val target = UiNavigationPolicy.gridTarget(index, ids.size, columns, code, wide)
                        if (target < 0) false else {
                            if (target != index && !movingFocus) {
                                movingFocus = true
                                scope.launch {
                                    try {
                                        gridState.scrollToItem(target)
                                        pendingFocus = ids[target]
                                    } finally { movingFocus = false }
                                }
                            }
                            true
                        }
                    }
                    val gridEdges: (Int) -> Modifier = { index ->
                        Modifier.focusProperties {
                            if (index % columns == 0) left = if (wide) railFocus else FocusRequester.Cancel
                            if (index % columns == columns - 1 || index == ids.lastIndex) right = FocusRequester.Cancel
                        }
                    }
                    val onFocused: (String) -> Unit = { path ->
                        selectedShortcutPath.value = path
                        if (pendingFocus == path) pendingFocus = null
                    }
                    LaunchedEffect(ids, grid, size, columns, resumeRevision, menu == null, GamepadNavigation.inUse.value) {
                        if (menu == null && ids.isNotEmpty() && GamepadNavigation.inUse.value && !searchFocused) {
                            inputMode.requestInputMode(InputMode.Keyboard)
                            val path = selectedShortcutPath.value.takeIf { it in ids } ?: ids.first()
                            val index = ids.indexOf(path)
                            gridState.scrollToItem(index)
                            pendingFocus = path
                        }
                    }
                    if (ids.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(if (query.isNotBlank()) R.string.library_no_search_results
                                else if (items.isEmpty()) R.string.library_no_games else R.string.library_empty_filter),
                                color = MaterialTheme.colorScheme.onBackground)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns), state = gridState,
                            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = sidePadding.dp,
                                vertical = UiNavigationPolicy.LIBRARY_GRID_PADDING.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (showAddGame) item(key = ADD_GAME_TILE) {
                                AddGameTile(grid, size, pendingFocus == ADD_GAME_TILE, gridEdges(0),
                                    customBackground = background.kind != LibraryBackgroundKind.Theme,
                                    onFocused = { onFocused(ADD_GAME_TILE) },
                                    moveFocus = { moveFocus(0, it) },
                                    onClick = { activity?.addGame() })
                            }
                            itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                                val gridIndex = index + if (showAddGame) 1 else 0
                                LibraryTile(item, grid, size, cb, pendingFocus == item.shortcutPath,
                                    gridEdges(gridIndex),
                                    customBackground = background.kind != LibraryBackgroundKind.Theme,
                                    onFocused = { onFocused(item.shortcutPath) },
                                    showMenu = { bounds -> showMenu(item, bounds) },
                                    moveFocus = { moveFocus(gridIndex, it) })
                            }
                        }
                    }
                }
            }
        }
    }
    menu?.let { selected ->
        LibraryItemMenu(selected, anchor, cb) { menu = null }
    }
}

@Composable
private fun LibraryFilterIcon(filter: LibraryFilter) {
    when (filter) {
        LibraryFilter.Steam -> Icon(painterResource(R.drawable.library_ic_steam), null, Modifier.size(24.dp))
        LibraryFilter.All -> Icon(Icons.Outlined.Apps, null, Modifier.size(24.dp))
        LibraryFilter.Favorites -> Icon(Icons.Outlined.Favorite, null, Modifier.size(24.dp))
        LibraryFilter.Custom -> Icon(Icons.Outlined.FolderOpen, null, Modifier.size(24.dp))
        LibraryFilter.TestApps -> Icon(Icons.Outlined.BugReport, null, Modifier.size(24.dp))
    }
}

@Composable
private fun LibraryHeader(
    activity: MainActivity?, cards: Boolean, size: LibraryIconSize, query: String,
    onCards: (Boolean) -> Unit, onSize: (LibraryIconSize) -> Unit, onQuery: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("") }
    var orientationRevision by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { searching = !searching }) { Icon(Icons.Outlined.Search, stringResource(R.string.library_search)) }
            Box {
                IconButton(onClick = { section = ""; expanded = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.library_more))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    GamepadNavigation.Bind()
                    when (section) {
                        "view" -> {
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_view_cards)) },
                                trailingIcon = { if (cards) Icon(Icons.Outlined.Check, null) },
                                onClick = { onCards(true); expanded = false })
                            LibraryIconSize.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(stringResource(option.label)) },
                                    trailingIcon = { if (!cards && size == option) Icon(Icons.Outlined.Check, null) },
                                    onClick = { onSize(option); onCards(false); expanded = false })
                            }
                        }
                        else -> {
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_view)) }, onClick = { section = "view" })
                            HorizontalDivider()
                            key(orientationRevision) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_lock_orientation)) },
                                    trailingIcon = { Checkbox(activity?.isOrientationLocked == true, null) },
                                    onClick = { activity?.toggleOrientationLock(); orientationRevision++ })
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_vertical_mode)) },
                                    onClick = { expanded = false; activity?.toggleVerticalMode() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_horizontal_mode)) },
                                    onClick = { expanded = false; activity?.toggleHorizontalMode() })
                            }
                        }
                    }
                }
            }
        }
        if (searching || query.isNotEmpty()) OutlinedTextField(query, onQuery,
            Modifier.fillMaxWidth().onFocusChanged { onSearchFocus(it.isFocused) },
            singleLine = true, label = { Text(stringResource(R.string.library_search)) })
    }
}

@Composable
private fun libraryFilterLabel(filter: LibraryFilter): String = stringResource(when (filter) {
    LibraryFilter.All -> R.string.library_filter_all
    LibraryFilter.Favorites -> R.string.library_filter_favorites
    LibraryFilter.Steam -> R.string.library_filter_steam
    LibraryFilter.Custom -> R.string.library_filter_custom
    LibraryFilter.TestApps -> R.string.library_filter_test_apps
})

@Composable
private fun AddGameTile(
    cards: Boolean, size: LibraryIconSize, requestFocus: Boolean, modifier: Modifier,
    onFocused: () -> Unit, moveFocus: (Int) -> Boolean, onClick: () -> Unit, customBackground: Boolean
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus) { if (requestFocus) requester.requestFocus() }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().focusRequester(requester)
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocused() }
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && moveFocus(code)
            },
        shape = RoundedCornerShape(16.dp),
        color = if (cards) MaterialTheme.colorScheme.surface else Color.Transparent,
        border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = if (cards) Modifier.aspectRatio(.72f).padding(16.dp) else Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(size.icon.dp).clip(RoundedCornerShape(22))
                .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, null, Modifier.size((size.icon / 2).dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.library_add_game), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium.copy(
                    shadow = if (customBackground) Shadow(MaterialTheme.colorScheme.background, blurRadius = 4f) else null),
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    item: LibraryItem, cards: Boolean, size: LibraryIconSize, cb: LibraryCallbacks,
    requestFocus: Boolean, modifier: Modifier, onFocused: () -> Unit,
    showMenu: (Rect) -> Unit, moveFocus: (Int) -> Boolean, customBackground: Boolean
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) requester.requestFocus()
    }
    val actionsLabel = stringResource(R.string.library_more)
    LaunchedEffect(item.id, cards, item.iconPath, item.fallbackIcon, item.coverPath) {
        if (cards && item.coverPath == null) cb.onArtworkNeeded(item.shortcutPath, "cover")
        if (!cards && item.iconPath == null && item.fallbackIcon == null) cb.onArtworkNeeded(item.shortcutPath, "icon")
    }
    val tileModifier = modifier.fillMaxWidth()
        .onGloballyPositioned { bounds = it.boundsInWindow() }
        .focusRequester(requester)
        .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocused() }
        .onPreviewKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            if (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && moveFocus(code)
            } else if (code in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_MENU)) {
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) showMenu(bounds)
                true
            } else false
        }
        .semantics { customActions = listOf(CustomAccessibilityAction(actionsLabel) { showMenu(bounds); true }) }
        .combinedClickable(onClick = {
            onFocused()
            if (cards && !item.steamClient) cb.onOpen(item.shortcutPath) else cb.onRun(item.shortcutPath)
        }, onLongClickLabel = actionsLabel, onLongClick = { showMenu(bounds) })
    Surface(
        modifier = tileModifier, shape = RoundedCornerShape(16.dp),
        color = if (cards) MaterialTheme.colorScheme.surface else Color.Transparent,
        border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        if (cards) Box(Modifier.aspectRatio(.72f)) {
            LibraryArtwork(item, true, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 6.dp)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.containerName, Modifier.weight(1f), color = Color.White.copy(.72f),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!item.steamClient) {
                        IconButton(onClick = { onFocused(); cb.onRun(item.shortcutPath) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.PlayArrow, stringResource(R.string.library_play), tint = Color.White)
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp, end = 54.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (item.steam) SteamBadge()
                GameVersionBadge(item.version)
            }
            Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(24.dp), color = Color.Black.copy(.65f)) {
                IconButton(onClick = { showMenu(bounds) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, actionsLabel, tint = Color.White)
                }
            }
        } else LibraryIconContent(item, size, customBackground)
    }
}

@Composable
internal fun LibraryIconContent(item: LibraryItem, size: LibraryIconSize, customBackground: Boolean) {
    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LibraryArtwork(item, false, Modifier.size(size.icon.dp).clip(RoundedCornerShape(22))
            .background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.height(8.dp))
        Text(item.name, style = MaterialTheme.typography.bodyMedium.copy(
            shadow = if (customBackground) Shadow(MaterialTheme.colorScheme.background, blurRadius = 4f) else null),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LibraryArtwork(item: LibraryItem, cover: Boolean, modifier: Modifier) {
    val path = if (cover) item.iconPath?.takeIf { it.endsWith(".user.png", ignoreCase = true) }
        ?: item.coverPath ?: item.bannerPath ?: item.iconPath else item.iconPath
    val bitmap by produceState<Bitmap?>(null, path, item.fallbackIcon, cover) {
        value = withContext(Dispatchers.IO) {
            val decoded = path?.takeIf { File(it).isFile }?.let(BitmapFactory::decodeFile) ?: item.fallbackIcon
            if (cover) decoded else decoded?.fillLibraryIcon()
        }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(item.name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).map { it.first() }.joinToString("").uppercase(),
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun SteamBadge(modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = Color(0xFF1B2838)) {
        Text("STEAM", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Color(0xFFC7D5E0),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LibraryItemMenu(item: LibraryItem, anchor: Rect, cb: LibraryCallbacks, close: () -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val location = remember { IntArray(2) }
    view.getLocationInWindow(location)
    val action: (String) -> Unit = { close(); cb.onAction(item.shortcutPath, it) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GamepadNavigation.Bind()
        val firstAction = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            if (GamepadNavigation.inUse.value) firstAction.requestFocus()
        }
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }.padding(12.dp)) {
            var panelHeight by remember { mutableIntStateOf(0) }
            val width = minOf(360.dp, maxWidth)
            val x = with(density) { (anchor.left - location[0]).toDp().coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp)) }
            val y = with(density) { (anchor.bottom - location[1]).toDp().coerceIn(0.dp,
                (maxHeight - panelHeight.toDp()).coerceAtLeast(0.dp)) }
            Surface(
                modifier = Modifier.width(width).offset { IntOffset(with(density) { x.roundToPx() }, with(density) { y.roundToPx() }) }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .onGloballyPositioned { panelHeight = it.size.height },
                shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp
            ) {
                Column(Modifier.heightIn(max = maxHeight)) {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = if (item.steamClient) 12.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!item.steamClient) {
                            IconButton(onClick = { close(); cb.onOpen(item.shortcutPath) }, modifier = Modifier.focusRequester(firstAction)) {
                                Icon(Icons.Outlined.Info, stringResource(R.string.library_view_details))
                            }
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        val rows = if (item.steamClient) listOf(
                            Triple(R.string.library_saves, Icons.Outlined.Save, LibraryComposeHost.ACTION_SAVES),
                            Triple(R.string.library_storage, Icons.Outlined.Storage, LibraryComposeHost.ACTION_STORAGE),
                            Triple(R.string.library_configure, Icons.Outlined.Settings, LibraryComposeHost.ACTION_SETTINGS)
                        ) else listOf(
                            Triple(R.string.library_configure, Icons.Outlined.Settings, LibraryComposeHost.ACTION_SETTINGS),
                            Triple(R.string.library_enter_container, Icons.Outlined.FolderOpen, LibraryComposeHost.ACTION_ENTER),
                            Triple(R.string.library_saves, Icons.Outlined.Save, LibraryComposeHost.ACTION_SAVES),
                            Triple(R.string.library_storage, Icons.Outlined.Storage, LibraryComposeHost.ACTION_STORAGE),
                            Triple(R.string.library_container_settings, Icons.Outlined.Tune, LibraryComposeHost.ACTION_CONTAINER)
                        )
                        rows.forEachIndexed { index, (label, icon, key) ->
                            TextButton(onClick = { action(key) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .then(if (item.steamClient && index == 0) Modifier.focusRequester(firstAction) else Modifier),
                                enabled = !item.steamClient || key != LibraryComposeHost.ACTION_SETTINGS) {
                                Icon(icon, null); Spacer(Modifier.width(16.dp))
                                Text(stringResource(label), Modifier.weight(1f))
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                        MenuStripAction(if (item.steamClient) Icons.Outlined.OpenInNew else Icons.Outlined.PlayArrow,
                            stringResource(if (item.steamClient) R.string.library_open else R.string.library_play),
                            Modifier.weight(1f)) { close(); cb.onRun(item.shortcutPath) }
                        if (!item.steamClient) {
                            MenuStripAction(if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                stringResource(if (item.favorite) R.string.library_unfavorite else R.string.library_favorite), Modifier.weight(1f)) { action(LibraryComposeHost.ACTION_FAVORITE) }
                            MenuStripAction(Icons.Outlined.DeleteOutline, stringResource(R.string.library_remove), Modifier.weight(1f)) { action(LibraryComposeHost.ACTION_REMOVE) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuStripAction(icon: ImageVector, label: String, modifier: Modifier, click: () -> Unit) {
    TextButton(onClick = click, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}
