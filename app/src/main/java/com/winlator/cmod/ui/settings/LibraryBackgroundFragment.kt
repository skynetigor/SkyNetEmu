package com.winlator.cmod.ui.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.library.*
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class LibraryBackgroundFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    LibraryBackgroundScreen { parentFragmentManager.popBackStack() }
                }
            }
        }
}

@Composable
private fun LibraryBackgroundScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberLibraryBackgroundSettings()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val activity = context as? MainActivity
    DisposableEffect(activity) {
        activity?.setMainToolbarVisible(false)
        activity?.setBottomNavigationVisible(true)
        onDispose { activity?.refreshMainNavigation() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val imported = LibraryBackgroundStore.import(context, uri)
                        LibraryBackgroundStore.save(context, imported)
                        LibraryBackgroundStore.deleteUnusedMedia(context, imported.media)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e("LibraryBackground", "Cannot import background", error)
                    Toast.makeText(context, R.string.library_background_import_error, Toast.LENGTH_LONG).show()
                } finally {
                    importing = false
                }
            }
        }
    }
    fun save(value: LibraryBackgroundSettings) = LibraryBackgroundStore.save(context, value)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .displayCutoutPadding().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, stringResource(R.string.library_background_back))
            }
            Text(stringResource(R.string.library_background_title), style = MaterialTheme.typography.titleLarge)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val previewHeight = minOf(220.dp, maxHeight * 0.32f)
            val preview: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.library_background_preview), style = MaterialTheme.typography.titleMedium)
                    LibraryBackgroundPreview(settings, previewHeight)
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                LibraryBackgroundControls(settings, importing, modifier,
                    onImport = { picker.launch(arrayOf("image/*", "video/mp4", "video/webm")) },
                    onSave = ::save,
                    onReset = {
                        save(LibraryBackgroundSettings())
                        scope.launch(Dispatchers.IO) { LibraryBackgroundStore.deleteMedia(context, settings.media) }
                    })
            }
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize()) {
                    preview(Modifier.weight(1f))
                    controls(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    preview(Modifier.fillMaxWidth())
                    controls(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LibraryBackgroundControls(
    settings: LibraryBackgroundSettings, importing: Boolean, modifier: Modifier,
    onImport: () -> Unit, onSave: (LibraryBackgroundSettings) -> Unit, onReset: () -> Unit
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.library_background_description), style = MaterialTheme.typography.bodySmall)
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_background_importing))
            }
            Button(enabled = !importing, onClick = onImport) { Text(stringResource(R.string.library_background_add_media)) }
            Text(stringResource(R.string.library_background_media_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !importing, onClick = {
                onSave(settings.copy(kind = LibraryBackgroundKind.Color))
            }) { Text(stringResource(R.string.library_background_color)) }
            if (settings.kind == LibraryBackgroundKind.Color) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFF101820, 0xFF22304A, 0xFF362442, 0xFF234038, 0xFF68442C).forEach { color ->
                        val value = color.toInt()
                        val description = stringResource(R.string.library_background_color_swatch,
                            "#%06X".format(value and 0xFFFFFF))
                        FilledIconButton(enabled = !importing, onClick = { onSave(settings.copy(color = value)) },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(value)),
                            modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
                            if (settings.color == value) Icon(Icons.Outlined.Check, null, tint = Color.White)
                        }
                    }
                }
                Text("#%06X".format(settings.color and 0xFFFFFF), fontWeight = FontWeight.Medium)
                val color = Color(settings.color)
                listOf(
                    R.string.library_background_red to color.red,
                    R.string.library_background_green to color.green,
                    R.string.library_background_blue to color.blue
                ).forEachIndexed { index, (label, component) ->
                    val name = stringResource(label)
                    Text(name)
                    Slider(value = component, enabled = !importing,
                        modifier = Modifier.semantics { contentDescription = name }, onValueChange = { value ->
                        val updated = when (index) {
                            0 -> Color(value, color.green, color.blue)
                            1 -> Color(color.red, value, color.blue)
                            else -> Color(color.red, color.green, value)
                        }
                        onSave(settings.copy(color = updated.toArgb()))
                    })
                }
            }
            val custom = settings.kind != LibraryBackgroundKind.Theme
            val darknessLabel = stringResource(R.string.library_background_darkness, (settings.darkness * 100).roundToInt())
            Text(darknessLabel)
            Slider(value = settings.darkness, valueRange = 0f..0.9f, enabled = custom && !importing,
                modifier = Modifier.semantics { contentDescription = darknessLabel },
                onValueChange = { onSave(settings.copy(darkness = it)) })
            val blurLabel = stringResource(R.string.library_background_blur, settings.blur.roundToInt())
            Text(blurLabel)
            Slider(value = settings.blur, valueRange = 0f..25f,
                modifier = Modifier.semantics { contentDescription = blurLabel },
                enabled = !importing && settings.kind in listOf(LibraryBackgroundKind.Image, LibraryBackgroundKind.Video),
                onValueChange = { onSave(settings.copy(blur = it)) })
            TextButton(enabled = !importing, onClick = onReset) { Text(stringResource(R.string.library_background_reset)) }
    }
}

@Composable
private fun LibraryBackgroundPreview(settings: LibraryBackgroundSettings, height: Dp) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) {
        Text(stringResource(R.string.library), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            LibraryBackground(settings, Modifier.matchParentSize())
            val names = listOf(R.string.library_background_demo_racing, R.string.library_background_demo_space,
                R.string.library_background_demo_adventure, R.string.library_background_demo_puzzle)
            val columns = if (maxWidth < 400.dp) 3 else 4
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
                names.take(columns).chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { name ->
                            val title = stringResource(name)
                            val item = LibraryItem("preview:$name", "", title, "", null, null, null, null, false, 0)
                            Box(Modifier.weight(1f)) {
                                LibraryIconContent(item, LibraryIconSize.Small,
                                    settings.kind != LibraryBackgroundKind.Theme)
                            }
                        }
                    }
                }
            }
        }
    }
}
