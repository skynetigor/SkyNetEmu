package com.winlator.cmod.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The layout of the Windows container and shortcut editors (ContainerEditorV2, ShortcutEditorV2) for a list of [EditorCategory]:
 * in landscape a 220dp rail of the groups beside the page of the chosen one, in portrait a row of chips above it. A group
 * that needs attention is shown in the error colour and is the one that opens first. [header] stands above the page.
 */
@Composable
internal fun RailEditor(
    title: String,
    categories: List<EditorCategory>,
    header: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    var chosen by rememberSaveable { mutableStateOf(categories.firstOrNull { it.attention }?.id ?: categories.firstOrNull()?.id ?: "") }
    val page = categories.firstOrNull { it.id == chosen } ?: categories.firstOrNull()
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    @Composable
    fun Page(modifier: Modifier, contentPadding: PaddingValues) {
        LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(page?.id) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header?.invoke()
                    if (page != null) DisabledWhen(!enabled) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { page.content() } }
                }
            }
        }
    }

    if (landscape) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                Modifier.width(220.dp).fillMaxHeight(),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    items(categories, key = { it.id }) { item -> RailNavItem(item, item.id == page?.id, Modifier.fillMaxWidth()) { chosen = item.id } }
                }
            }
            Page(Modifier.weight(1f).fillMaxHeight(), PaddingValues(bottom = 14.dp))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(categories, key = { it.id }) { item -> RailNavItem(item, item.id == page?.id) { chosen = item.id } }
            }
            Page(Modifier.fillMaxSize().padding(horizontal = 14.dp), PaddingValues(bottom = 14.dp))
        }
    }
}

@Composable
private fun RailNavItem(category: EditorCategory, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        Text(
            category.title,
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (category.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
