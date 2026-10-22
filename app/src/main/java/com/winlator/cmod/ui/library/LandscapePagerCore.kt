package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LandscapePagerCore(
    items: List<LibraryItem>,
    selectedShortcutPath: MutableState<String?>,
    callbacks: LibraryCallbacks,
    header: @Composable () -> Unit,
    footerActions: @Composable (LibraryItem) -> Unit
) {
    val initialPage = remember(items, selectedShortcutPath.value) {
        items.indexOfFirst { it.shortcutPath == selectedShortcutPath.value }
            .takeIf { it >= 0 } ?: 0
    }
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { items.size })
    val item = items[pager.currentPage.coerceIn(items.indices)]
    val environmentLabel = libraryEnvironmentLabel(item)
    LaunchedEffect(items.map { it.id }, selectedShortcutPath.value) {
        val selectedPage = items.indexOfFirst { it.shortcutPath == selectedShortcutPath.value }
        val targetPage = if (selectedPage >= 0) selectedPage else pager.currentPage.coerceIn(items.indices)
        if (pager.currentPage != targetPage) pager.scrollToPage(targetPage)
    }

    LaunchedEffect(pager, items) {
        snapshotFlow { pager.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                items.getOrNull(page)?.let { selectedShortcutPath.value = it.shortcutPath }
            }
    }

    LaunchedEffect(item.id) {
        if (item.coverPath == null) callbacks.onArtworkNeeded(item.shortcutPath, "cover")
        if (item.bannerPath == null) callbacks.onArtworkNeeded(item.shortcutPath, "banner")
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PagerImage(userArtworkPath(item) ?: item.bannerPath ?: item.coverPath, item.fallbackIcon, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(.84f), Color.Black.copy(.48f), Color.Black.copy(.20f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.18f), Color.Transparent, Color.Black.copy(.72f)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 12.dp)) {
            header()
            Spacer(Modifier.size(9.dp))
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1.16f)
                        .fillMaxHeight()
                ) {
                    val pageWidth = minOf(214.dp, maxWidth)
                    val edgePadding = 8.dp
                    val trailingPadding = (maxWidth - pageWidth - edgePadding).coerceAtLeast(edgePadding)

                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        pageSize = PageSize.Fixed(pageWidth),
                        pageSpacing = 6.dp,
                        contentPadding = PaddingValues(
                            start = edgePadding,
                            end = trailingPadding
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        userScrollEnabled = items.size > 1,
                        beyondViewportPageCount = 2,
                        snapPosition = SnapPosition.Start,
                        key = { page -> items.getOrNull(page)?.id ?: "stale-library-page-$page" }
                    ) { page ->
                        val candidate = items.getOrNull(page) ?: return@HorizontalPager
                        val pageOffset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                            .absoluteValue
                            .coerceIn(0f, 1f)
                        val cardScale = 1f - (0.30f * pageOffset)
                        val selected = pageOffset < 0.5f
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val cardHeight = minOf(322.dp, maxHeight, maxWidth * (322f / 206f))
                            val cardWidth = cardHeight * (206f / 322f)
                            Surface(
                                onClick = { callbacks.onOpen(candidate.shortcutPath) },
                                modifier = Modifier
                                    .size(cardWidth, cardHeight)
                                    .scale(cardScale),
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black.copy(.30f),
                                border = BorderStroke(if (selected) 2.dp else 1.dp, Color.White.copy(if (selected) .88f else .20f))
                            ) {
                                Box {
                                    PagerImage(userArtworkPath(candidate) ?: candidate.coverPath ?: candidate.bannerPath ?: candidate.iconPath, candidate.fallbackIcon, Modifier.fillMaxSize())
                                    GameVersionBadge(candidate.version, Modifier.align(Alignment.TopStart).padding(10.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(.9f).verticalScroll(rememberScrollState())) {
                    Text(item.name, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(environmentLabel, color = Color.White.copy(.72f), style = MaterialTheme.typography.bodyMedium)
                    if (item.steam) { Spacer(Modifier.size(7.dp)); SteamBadge() }
                    if (item.version.isNotBlank()) { Spacer(Modifier.size(7.dp)); GameVersionBadge(item.version) }
                    Spacer(Modifier.size(13.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = { callbacks.onOpen(item.shortcutPath) },
                            shape = RoundedCornerShape(11.dp),
                            color = Color.White.copy(.20f),
                            contentColor = Color.White,
                            border = BorderStroke(1.dp, Color.White.copy(.22f))
                        ) { Text(if (item.steamClient) stringResource(R.string.library_open_steam) else "View details",
                            Modifier.padding(horizontal = 21.dp, vertical = 11.dp)) }
                        if (!item.steamClient) {
                            Surface(
                                onClick = { callbacks.onRun(item.shortcutPath) },
                                modifier = Modifier.size(44.dp),
                                shape = CircleShape,
                                color = Color.Black.copy(.62f),
                                contentColor = Color.White
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, "Play") } }
                        }
                        footerActions(item)
                    }
                }
            }
        }
    }
}

private fun userArtworkPath(item: LibraryItem): String? =
    item.iconPath?.takeIf { it.endsWith(".user.png", ignoreCase = true) }

@Composable
private fun PagerImage(path: String?, fallback: Bitmap?, modifier: Modifier) {
    val image by produceState<Bitmap?>(fallback, path, fallback) {
        value = withContext(Dispatchers.IO) {
            path?.takeIf { File(it).isFile }?.let { BitmapFactory.decodeFile(it) } ?: fallback
        }
    }
    if (image != null) Image(image!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}
