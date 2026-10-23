package com.winlator.cmod.ui.library

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

internal enum class LibraryBackgroundKind { Theme, Color, Image, Video }

internal data class LibraryBackgroundSettings(
    val kind: LibraryBackgroundKind = LibraryBackgroundKind.Theme,
    val color: Int = 0xFF202128.toInt(),
    val media: String? = null,
    val darkness: Float = 0.35f,
    val blur: Float = 0f
)

internal const val LIBRARY_BACKGROUND_MAX_BYTES = 100L * 1024 * 1024

internal fun resolveLibraryBackground(settings: LibraryBackgroundSettings, themeId: String): LibraryBackgroundSettings {
    if (settings.kind != LibraryBackgroundKind.Theme) return settings
    val name = when (themeId) {
        "white" -> "skynet-pearl-aurora.mp4"
        "amoled" -> "skynet-oled-constellation.mp4"
        else -> "skynet-cosmic-gold.mp4"
    }
    return settings.copy(kind = LibraryBackgroundKind.Video, media = "asset:library-backgrounds/$name",
        darkness = 0f, blur = 0f)
}

internal fun libraryBackgroundDecodeSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Invalid background dimensions" }
    val scale = minOf(1f, 1600f / maxOf(width, height))
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

internal suspend fun copyLibraryBackground(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > LIBRARY_BACKGROUND_MAX_BYTES) throw IOException("Background exceeds 100 MB")
        output.write(buffer, 0, count)
    }
    if (total == 0L) throw IOException("Background file is empty")
}

internal object LibraryBackgroundStore {
    private fun preferences(context: Context) =
        context.getSharedPreferences("library_background", Context.MODE_PRIVATE)

    fun read(context: Context): LibraryBackgroundSettings {
        val prefs = preferences(context)
        return LibraryBackgroundSettings(
            kind = LibraryBackgroundKind.entries.firstOrNull { it.name == prefs.getString("kind", null) }
                ?: LibraryBackgroundKind.Theme,
            color = prefs.getInt("color", 0xFF202128.toInt()) or 0xFF000000.toInt(),
            media = prefs.getString("media", null),
            darkness = prefs.getFloat("darkness", 0.35f).coerceIn(0f, 0.9f),
            blur = prefs.getFloat("blur", 0f).coerceIn(0f, 25f)
        )
    }

    fun observe(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObserving(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun save(context: Context, settings: LibraryBackgroundSettings) {
        preferences(context).edit()
            .putString("kind", settings.kind.name)
            .putInt("color", settings.color)
            .putString("media", settings.media)
            .putFloat("darkness", settings.darkness.coerceIn(0f, 0.9f))
            .putFloat("blur", settings.blur.coerceIn(0f, 25f))
            .apply()
    }

    fun mediaFile(context: Context, name: String): File {
        require(name.matches(Regex("[a-f0-9-]+\\.media"))) { "Invalid background media name" }
        return File(File(context.filesDir, "library-background"), name)
    }

    /** Copy first so document-provider grants and the source file are not needed after import. */
    suspend fun import(context: Context, uri: Uri): LibraryBackgroundSettings {
        val directory = File(context.filesDir, "library-background")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create background storage")
        val destination = mediaFile(context, "${UUID.randomUUID()}.media")
        val pending = File(directory, "${destination.nameWithoutExtension}.import")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().use { output ->
                    copyLibraryBackground(input, output)
                }
            } ?: throw IOException("Cannot open the selected background")
            val kind = if (context.contentResolver.getType(uri)?.startsWith("video/") == true) {
                validateVideo(pending)
                LibraryBackgroundKind.Video
            } else {
                val drawable = decodeLibraryBackgroundImage(pending)
                drawable.callback = null
                LibraryBackgroundKind.Image
            }
            currentCoroutineContext().ensureActive()
            if (!pending.renameTo(destination)) throw IOException("Cannot finalize background import")
            return read(context).copy(kind = kind, media = destination.name)
        } catch (error: Exception) {
            if (!pending.delete() && pending.exists()) Log.w("LibraryBackground", "Cannot remove pending import")
            if (!destination.delete() && destination.exists()) Log.w("LibraryBackground", "Cannot remove failed import")
            throw error
        }
    }

    internal fun decodeLibraryBackgroundImage(file: File): Drawable =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (width, height) = libraryBackgroundDecodeSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun validateVideo(file: File) {
        val player = MediaPlayer()
        try {
            player.setDataSource(file.path)
            player.setVolume(0f, 0f)
            player.prepare()
            if (player.videoWidth <= 0 || player.videoHeight <= 0) throw IOException("No playable video track")
        } finally {
            player.release()
        }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.path)
            val frame = metadata.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64)
                ?: throw IOException("Cannot decode a video frame")
            frame.recycle()
        } finally {
            metadata.release()
        }
    }

    fun deleteUnusedMedia(context: Context, keep: String?) {
        val directory = File(context.filesDir, "library-background")
        directory.listFiles()?.filter { it.extension == "media" && it.name != keep }?.forEach {
            if (!it.delete()) Log.w("LibraryBackground", "Cannot remove unused background: ${it.name}")
        }
    }

    fun deleteMedia(context: Context, name: String?) {
        if (name != null) {
            val file = mediaFile(context, name)
            if (!file.delete() && file.exists()) Log.w("LibraryBackground", "Cannot remove previous background")
        }
    }
}

@Composable
internal fun rememberLibraryBackgroundSettings(): LibraryBackgroundSettings {
    val context = LocalContext.current.applicationContext
    var settings by remember(context) { mutableStateOf(LibraryBackgroundStore.read(context)) }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            settings = LibraryBackgroundStore.read(context)
        }
        LibraryBackgroundStore.observe(context, listener)
        settings = LibraryBackgroundStore.read(context)
        onDispose { LibraryBackgroundStore.stopObserving(context, listener) }
    }
    return settings
}
