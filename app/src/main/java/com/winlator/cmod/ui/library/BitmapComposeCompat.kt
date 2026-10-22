package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun Bitmap.asImageBitmap(): ImageBitmap = this.composeAsImageBitmap()

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
    var filterName by remember { mutableStateOf(sessionState.getString("filter", LibraryFilter.All.name)) }
    val filter = restoreLibraryFilter(filterName)
    val visible = remember(items, filter, query) {
        filterLibraryItems(items, filter, query)
    }
    val activity = LocalContext.current as? MainActivity
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    LaunchedEffect(filter, selectedShortcutPath.value) {
        sessionState.putString("selected_shortcut_path", selectedShortcutPath.value)
        sessionState.putString("selection_${filter.name}", selectedShortcutPath.value)
    }
    val selectFilter: (LibraryFilter) -> Unit = { option ->
        if (option != filter) {
            sessionState.putString("selection_${filter.name}", selectedShortcutPath.value)
            selectedShortcutPath.value = sessionState.getString("selection_${option.name}")
            filterName = option.name
            sessionState.putString("filter", option.name)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val sidebarWidth = if (maxWidth < 600.dp) 160.dp else 192.dp
        DisposableEffect(activity, landscape) {
            activity?.setBottomNavigationVisible(false)
            activity?.setMainToolbarVisible(!landscape)
            onDispose {
                activity?.refreshMainNavigation()
            }
        }
        Row(Modifier.fillMaxSize().displayCutoutPadding().imePadding().navigationBarsPadding()) {
            if (landscape) {
                Surface(Modifier.width(sidebarWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            LibraryFilter.values().forEach { option ->
                                LibraryNavigationAction(
                                    libraryFilterLabel(option), option == filter,
                                    icon = { LibraryFilterIcon(option) }
                                ) { selectFilter(option) }
                            }
                            LibraryNavigationAction(stringResource(R.string.library_add_game), icon = {
                                Icon(Icons.Outlined.Add, null)
                            }) { activity?.addGame() }
                        }
                        LibraryNavigationAction(stringResource(R.string.settings), icon = {
                            Icon(Icons.Outlined.Settings, null)
                        }) { activity?.navigateToMainDestination(R.id.main_menu_settings) }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (landscape) {
                    LibraryLandscapeHeader(activity, grid, query, cb::onGridViewChanged, onQueryChanged)
                } else {
                    ScrollableTabRow(selectedTabIndex = filter.ordinal, edgePadding = 8.dp) {
                        LibraryFilter.values().forEach { option ->
                            Tab(
                                selected = option == filter,
                                onClick = { selectFilter(option) },
                                text = { Text(libraryFilterLabel(option)) },
                                icon = { LibraryFilterIcon(option) }
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(if (query.isNotBlank()) R.string.library_no_search_results
                                    else if (items.isEmpty()) R.string.library_no_games else R.string.library_empty_filter),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (landscape && !grid) {
                        var menu by remember { mutableStateOf<LibraryItem?>(null) }
                        key(filter) {
                            LandscapePagerCore(visible, selectedShortcutPath, cb, header = {}, footerActions = { item ->
                                IconButton(onClick = { menu = item }) {
                                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.library_more), tint = Color.White)
                                }
                            })
                        }
                        menu?.let { LibraryItemMenuCompat(it, cb) { menu = null } }
                    } else {
                        key(filter, grid) {
                            val stateKey = "grid_${filter.name}_$grid"
                            val gridState = rememberLazyGridState(
                                sessionState.getInt("${stateKey}_index"),
                                sessionState.getInt("${stateKey}_offset")
                            )
                            LaunchedEffect(gridState) {
                                snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                                    .collect { (index, offset) ->
                                        sessionState.putInt("${stateKey}_index", index)
                                        sessionState.putInt("${stateKey}_offset", offset)
                                    }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(if (grid) 172.dp else 360.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(visible, key = { it.id }) { item ->
                                    if (grid) CoverArtworkCard(item, cb) else CompactArtworkCard(item, cb)
                                }
                            }
                        }
                    }
                }
                if (!landscape) {
                    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { activity?.addGame() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.library_add_game))
                            }
                            TextButton(onClick = { activity?.navigateToMainDestination(R.id.main_menu_settings) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Settings, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryLandscapeHeader(
    activity: MainActivity?,
    grid: Boolean,
    query: String,
    onGridViewChanged: (Boolean) -> Unit,
    onQueryChanged: (String) -> Unit
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            LibraryTopIcon(Icons.Outlined.Search, stringResource(R.string.library_search)) { searching = !searching }
            LibraryTopIcon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, stringResource(R.string.library_change_view)) {
                onGridViewChanged(!grid)
            }
            LibraryOrientationMenu(activity)
        }
        if (searching || query.isNotEmpty()) {
            OutlinedTextField(
                value = query, onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.library_search)) }
            )
        }
    }
}

@Composable
private fun libraryFilterLabel(filter: LibraryFilter): String = stringResource(when (filter) {
    LibraryFilter.All -> R.string.library_filter_all
    LibraryFilter.Favorites -> R.string.library_filter_favorites
    LibraryFilter.Steam -> R.string.library_filter_steam
    LibraryFilter.Custom -> R.string.library_filter_custom
})

@Composable
private fun LibraryFilterIcon(filter: LibraryFilter) {
    if (filter == LibraryFilter.Steam) Icon(painterResource(R.drawable.library_ic_steam), null, Modifier.size(24.dp))
    else Icon(when (filter) {
        LibraryFilter.Favorites -> Icons.Outlined.Star
        LibraryFilter.Custom -> Icons.Outlined.FolderOpen
        else -> Icons.Outlined.GridView
    }, null)
}

@Composable
private fun LibraryNavigationAction(label: String, isSelected: Boolean = false, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { selected = isSelected },
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LibraryOrientationMenu(activity: MainActivity?) {
    var expanded by remember { mutableStateOf(false) }
    var orientationRevision by remember { mutableStateOf(0) }
    val orientationState = remember(activity, orientationRevision) {
        Triple(
            activity?.isOrientationLocked ?: false,
            activity?.isVerticalModeEnabled ?: false,
            activity?.isHorizontalModeEnabled ?: false
        )
    }

    Box {
        LibraryTopIcon(Icons.Outlined.MoreVert, stringResource(R.string.library_more)) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.containers)) }, onClick = {
                expanded = false
                activity?.navigateToMainDestination(R.id.main_menu_containers)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.input_controls)) }, onClick = {
                expanded = false
                activity?.navigateToMainDestination(R.id.main_menu_input_controls)
            })
            OrientationToggleMenuItem("Lock screen orientation", orientationState.first) {
                activity?.toggleOrientationLock()
                orientationRevision++
            }
            OrientationToggleMenuItem("Vertical mode", orientationState.second) {
                activity?.toggleVerticalMode()
                orientationRevision++
            }
            OrientationToggleMenuItem("Horizontal mode", orientationState.third) {
                activity?.toggleHorizontalMode()
                orientationRevision++
            }
        }
    }
}

@Composable
private fun OrientationToggleMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
        onClick = onClick
    )
}

@Composable
private fun LibraryTopIcon(icon: ImageVector, label: String, click: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()
    ) {
        IconButton(onClick = click, modifier = Modifier.size(48.dp)) {
            Icon(icon, label, modifier = Modifier.size(24.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().height(92.dp).combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            ArtworkCompat(
                userArtworkPath(item) ?: item.bannerPath ?: item.coverPath ?: item.iconPath,
                item.fallbackIcon,
                Modifier.fillMaxSize().alpha(.52f)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(.38f),
                            MaterialTheme.colorScheme.surface.copy(.82f),
                            MaterialTheme.colorScheme.surface.copy(.96f)
                        )
                    )
                )
            )
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkCompat(
                    userArtworkPath(item) ?: item.coverPath ?: item.bannerPath ?: item.iconPath,
                    item.fallbackIcon,
                    Modifier.size(68.dp).clip(RoundedCornerShape(11.dp))
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.steam) { Spacer(Modifier.width(8.dp)); SteamBadge() }
                    }
                    Text(item.containerName, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    GameVersionBadge(item.version)
                }
                PlayCompat(item, cb, false)
                MenuButtonCompat(item, cb, false)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(Modifier.aspectRatio(.72f)) {
            ArtworkCompat(userArtworkPath(item) ?: item.coverPath ?: item.bannerPath ?: item.iconPath, item.fallbackIcon, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 50.dp, bottom = 13.dp)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.containerName, color = Color.White.copy(.72f), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (item.steam) SteamBadge()
                GameVersionBadge(item.version)
            }
            Box(Modifier.align(Alignment.TopEnd)) { MenuButtonCompat(item, cb, true) }
            Box(Modifier.align(Alignment.BottomEnd).padding(9.dp)) { PlayCompat(item, cb, true) }
        }
    }
}

/** Marks a game that Steam owns: Steam's dark blue with its light blue lettering. */
@Composable
internal fun SteamBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF1B2838),
        border = BorderStroke(1.dp, Color(0xFF66C0F4).copy(.55f))
    ) {
        Text(
            "STEAM",
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = Color(0xFFC7D5E0),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun RequestArtworkCompat(item: LibraryItem, cb: LibraryCallbacks) {
    LaunchedEffect(item.id, item.coverPath, item.bannerPath) {
        if (item.coverPath == null) cb.onArtworkNeeded(item.shortcutPath, "cover")
        if (item.bannerPath == null) cb.onArtworkNeeded(item.shortcutPath, "banner")
    }
}

private fun userArtworkPath(item: LibraryItem): String? =
    item.iconPath?.takeIf { it.endsWith(".user.png", ignoreCase = true) }

@Composable
private fun ArtworkCompat(path: String?, fallback: Bitmap?, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(fallback, path, fallback) {
        value = withContext(Dispatchers.IO) {
            path?.takeIf { File(it).isFile }?.let(BitmapFactory::decodeFile) ?: fallback
        }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun PlayCompat(item: LibraryItem, cb: LibraryCallbacks, overlay: Boolean) {
    if (item.steamClient) return
    Surface(
        onClick = { cb.onRun(item.shortcutPath) },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = if (overlay) Color.Black.copy(.72f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (overlay) Color.White else MaterialTheme.colorScheme.onSurface
    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, "Play") } }
}

@Composable
private fun MenuButtonCompat(item: LibraryItem, cb: LibraryCallbacks, light: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, "More options", tint = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) LibraryItemMenuCompat(item, cb) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryItemMenuCompat(item: LibraryItem, cb: LibraryCallbacks, close: () -> Unit) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
            ) {}
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = if (landscape) 920.dp else 760.dp)
                .padding(horizontal = if (landscape) 22.dp else 16.dp)
                .padding(bottom = if (landscape) 10.dp else 24.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            Text(
                item.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
            )
            val favoriteLabel = if (item.favorite) "Unfavorite" else "Favorite"
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(
                    if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    favoriteLabel,
                    Modifier.weight(1f),
                    horizontal = landscape
                ) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_FAVORITE)
                }
                if (!item.steamClient) {
                    LibraryActionTileCompat(Icons.Outlined.Settings, "Configure", Modifier.weight(1f), horizontal = landscape) {
                        close()
                        cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS)
                    }
                }
                LibraryActionTileCompat(Icons.Outlined.Photo, "Artwork", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_ICON)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(Icons.Outlined.Home, "Home screen", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_HOME)
                }
                LibraryActionTileCompat(Icons.Outlined.ContentCopy, "Clone", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_CLONE)
                }
                LibraryActionTileCompat(Icons.Outlined.FileUpload, "Export", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_EXPORT)
                }
            }
            LibraryActionTileCompat(
                Icons.Outlined.Storage,
                "Storage",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_STORAGE)
            }
            if (!item.steamClient) {
                LibraryActionTileCompat(
                    Icons.Outlined.Tune,
                    "Container settings",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontal = landscape
                ) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_CONTAINER)
                }
            }
            LibraryActionTileCompat(
                Icons.Outlined.DeleteOutline,
                "Remove from library",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                destructive = true,
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_REMOVE)
            }
        }
    }
}

@Composable
private fun LibraryActionTileCompat(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    destructive: Boolean = false,
    horizontal: Boolean = false,
    click: () -> Unit
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = click,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (destructive)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = .22f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        border = BorderStroke(
            1.dp,
            if (destructive) MaterialTheme.colorScheme.error.copy(alpha = .46f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .70f)
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (horizontal) 13.dp else 12.dp,
                vertical = if (horizontal) 9.dp else 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(if (horizontal) 21.dp else 23.dp), tint = tint)
            Spacer(Modifier.width(9.dp))
            Text(
                label,
                style = if (horizontal) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
