package com.winlator.cmod.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun LibraryBackground(selection: LibraryBackgroundSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = resolveLibraryBackground(selection, WinlatorThemeManager.currentTheme().id)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(modifier.clipToBounds().background(if (settings.kind == LibraryBackgroundKind.Color) Color(settings.color)
        else MaterialTheme.colorScheme.background)) {
        if (settings.kind == LibraryBackgroundKind.Image || settings.kind == LibraryBackgroundKind.Video) {
            key(settings.media, settings.kind) {
                val image by produceState<Drawable?>(null) {
                    if (settings.kind == LibraryBackgroundKind.Image) {
                        try {
                            value = withContext(Dispatchers.IO) {
                                val file = LibraryBackgroundStore.mediaFile(context, requireNotNull(settings.media))
                                LibraryBackgroundStore.decodeLibraryBackgroundImage(file)
                            }
                        } catch (error: java.io.IOException) {
                            reportBackgroundError(context, error)
                        } catch (error: IllegalArgumentException) {
                            reportBackgroundError(context, error)
                        }
                    }
                }
                AndroidView(
                    factory = { BackgroundMediaView(it, settings) },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.update(image, settings.blur, active) },
                    onRelease = { it.release() }
                )
            }
        }
        if (settings.kind != LibraryBackgroundKind.Theme) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.darkness)))
        }
    }
}

private fun reportBackgroundError(context: Context, error: Throwable) {
    Log.e("LibraryBackground", "Unable to display library background", error)
    Toast.makeText(context, R.string.library_background_load_error, Toast.LENGTH_LONG).show()
}

private class BackgroundMediaView(context: Context, private val settings: LibraryBackgroundSettings) :
    FrameLayout(context), TextureView.SurfaceTextureListener {
    private val image = BlurredBackgroundImage(context)
    private val texture = TextureView(context)
    private val blurredVideo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var rendered = false
    private var active = false
    private var released = false
    private var blur = 0f
    private var lastFrame = 0L
    private val playbackTimeout = Runnable {
        if (!released && active) {
            reportBackgroundError(context, java.io.IOException("Video did not render its first frame"))
            releasePlayer()
        }
    }
    private var legacyBlur: SoftwareBackgroundBlur? = null
    private var frame: Bitmap? = null

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        clipChildren = true
        if (settings.kind == LibraryBackgroundKind.Video) {
            addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(blurredVideo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            texture.surfaceTextureListener = this
        } else {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun update(drawable: Drawable?, radius: Float, running: Boolean) {
        if (image.drawable !== drawable) image.setImageDrawable(drawable)
        blur = radius * resources.displayMetrics.density
        image.blurPixels = blur
        if (Build.VERSION.SDK_INT >= 31) {
            val effect = if (blur > 0f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
            image.setRenderEffect(effect)
            texture.setRenderEffect(effect)
        } else if (blur == 0f) {
            blurredVideo.visibility = View.GONE
        }
        active = running
        (image.drawable as? AnimatedImageDrawable)?.let {
            it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            if (active) it.start() else it.stop()
        }
        syncPlayback()
    }

    private fun syncPlayback() {
        removeCallbacks(playbackTimeout)
        if (!prepared) return
        player?.let {
            if (active) {
                if (!it.isPlaying) it.start()
                if (!rendered) postDelayed(playbackTimeout, 10000)
            } else if (it.isPlaying) it.pause()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        surface = Surface(surfaceTexture)
        val video = MediaPlayer()
        player = video
        video.setSurface(surface)
        video.setVolume(0f, 0f)
        video.isLooping = true
        video.setOnPreparedListener {
            prepared = true
            cropVideo()
            syncPlayback()
        }
        video.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                rendered = true
                removeCallbacks(playbackTimeout)
            }
            false
        }
        video.setOnVideoSizeChangedListener { _, _, _ -> cropVideo() }
        video.setOnErrorListener { _, what, extra ->
            reportBackgroundError(context, java.io.IOException("Video playback failed ($what/$extra)"))
            releasePlayer()
            true
        }
        try {
            val media = requireNotNull(settings.media)
            if (media.startsWith("asset:")) {
                context.assets.openFd(media.removePrefix("asset:")).use {
                    video.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            } else {
                video.setDataSource(LibraryBackgroundStore.mediaFile(context, media).path)
            }
            video.prepareAsync()
        } catch (error: java.io.IOException) {
            reportBackgroundError(context, error)
            releasePlayer()
        } catch (error: IllegalArgumentException) {
            reportBackgroundError(context, error)
            releasePlayer()
        }
    }

    private fun cropVideo() {
        val video = player ?: return
        if (!prepared || width == 0 || height == 0 || video.videoWidth == 0 || video.videoHeight == 0) return
        val scale = maxOf(width.toFloat() / video.videoWidth, height.toFloat() / video.videoHeight)
        texture.setTransform(Matrix().apply {
            setScale(video.videoWidth * scale / width, video.videoHeight * scale / height,
                width / 2f, height / 2f)
        })
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = cropVideo()

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        rendered = true
        removeCallbacks(playbackTimeout)
        if (Build.VERSION.SDK_INT >= 31 || blur <= 0f || !active || width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastFrame < 33) return
        lastFrame = now
        val scale = minOf(1f, 256f / maxOf(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (frame?.width != w || frame?.height != h) frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        texture.getBitmap(requireNotNull(frame))?.let { bitmap ->
            val processor = legacyBlur ?: SoftwareBackgroundBlur(context).also { legacyBlur = it }
            blurredVideo.setImageBitmap(processor.apply(bitmap, blur * scale))
            blurredVideo.visibility = View.VISIBLE
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    private fun releasePlayer() {
        removeCallbacks(playbackTimeout)
        prepared = false
        rendered = false
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    fun release() {
        released = true
        (image.drawable as? AnimatedImageDrawable)?.stop()
        image.setImageDrawable(null)
        image.release()
        releasePlayer()
        legacyBlur?.close()
        legacyBlur = null
        blurredVideo.setImageDrawable(null)
        frame = null
    }
}

private class BlurredBackgroundImage(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    var blurPixels = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    private var frame: Bitmap? = null
    private var processor: SoftwareBackgroundBlur? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    init { scaleType = ScaleType.CENTER_CROP }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 31 || blurPixels <= 0f || width == 0 || height == 0 || drawable == null) {
            super.onDraw(canvas)
            return
        }
        val scale = minOf(1f, 256f / maxOf(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (frame?.width != w || frame?.height != h) frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bitmap = requireNotNull(frame)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val target = Canvas(bitmap)
        target.scale(w.toFloat() / width, h.toFloat() / height)
        super.onDraw(target)
        val blur = processor ?: SoftwareBackgroundBlur(context).also { processor = it }
        canvas.drawBitmap(blur.apply(bitmap, blurPixels * scale), null, Rect(0, 0, width, height), paint)
    }

    fun release() { processor?.close(); processor = null; frame = null }
}

/** API 28–30 fallback: blur a bounded, downsampled frame instead of allocating full-screen frames. */
@Suppress("DEPRECATION")
private class SoftwareBackgroundBlur(context: Context) {
    private val scriptContext = RenderScript.create(context.applicationContext)
    private val script = ScriptIntrinsicBlur.create(scriptContext, Element.U8_4(scriptContext))
    private var input: Allocation? = null
    private var output: Allocation? = null
    private var result: Bitmap? = null

    fun apply(bitmap: Bitmap, radius: Float): Bitmap {
        if (result?.width != bitmap.width || result?.height != bitmap.height) {
            input?.destroy()
            output?.destroy()
            result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            input = Allocation.createFromBitmap(scriptContext, bitmap)
            output = Allocation.createFromBitmap(scriptContext, requireNotNull(result))
        }
        input?.copyFrom(bitmap)
        script.setRadius(radius.coerceIn(0.1f, 25f))
        script.setInput(input)
        script.forEach(output)
        output?.copyTo(requireNotNull(result))
        return requireNotNull(result)
    }

    fun close() {
        input?.destroy()
        output?.destroy()
        script.destroy()
        scriptContext.destroy()
    }
}
