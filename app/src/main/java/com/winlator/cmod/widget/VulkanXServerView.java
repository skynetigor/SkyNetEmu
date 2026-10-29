package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dalvik.annotation.optimization.FastNative;

@SuppressLint("ViewConstructor")
public class VulkanXServerView extends XServerRendererView implements SurfaceHolder.Callback,
        WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {

    private long nativeHandle = 0;
    private long rendererSampleAt;
    private boolean importFailureReported;
    private boolean rendererFailureReported;
    private final Runnable rendererHealthCheck = new Runnable() {
        @Override public void run() {
            if (!initComplete) return;
            queueEvent(() -> {
                synchronized (lock) {
                    if (!initComplete || nativeHandle == 0) return;
                    if (!rendererFailureReported && nativeRendererFailed(nativeHandle)) {
                        rendererFailureReported = true;
                        String details = nativeRendererDiagnostics(nativeHandle);
                        Log.e("VulkanXServerView", "Renderer stopped: " + details);
                        com.winlator.cmod.telemetry.Telemetry.event("skynet.error", "render", "failure",
                                "error.type", "renderer_stalled",
                                "error.message", "Renderer stopped after a persistent stall or Vulkan error",
                                "skynet.renderer.diagnostics", details);
                        if (initializationError != null) post(() -> initializationError.accept(
                                "The renderer stopped after a persistent GPU stall or Vulkan error. "
                                + "No in-flight buffers were discarded. Close this session before trying again."));
                        return;
                    }
                }
                removeCallbacks(this);
                postDelayed(this, 1000);
            });
        }
    };
    private final Object lock = new Object();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private final Set<Integer> compositeRedirectedWindows = ConcurrentHashMap.newKeySet();

    private boolean fullscreen = false;
    private volatile boolean gameWindowZoom = false;
    private volatile int[] gameZoomRect;
    private volatile Runnable onGameZoomChanged;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private boolean cursorVisible = false;
    private boolean forceCursor;
    private String driverPath = null;
    private ExecutorService initExecutor = null;
    private boolean requireDmaBufImport;
    private boolean requireSelectedDriver;
    private java.util.function.Consumer<String> initializationError;

    public void requireDmaBufImport(java.util.function.Consumer<String> onError) {
        requireDmaBufImport = true;
        initializationError = onError;
    }

    public void requireSelectedDriver(java.util.function.Consumer<String> onError) {
        requireSelectedDriver = true;
        initializationError = onError;
    }

    private volatile boolean initComplete = false;
    private volatile boolean inPipMode = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private boolean validationEnabled = false;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private static volatile boolean gpuImageChecked = false;

    private int     pendingPresentMode    = 2;
    private int     pendingStretchMode    = 0;
    private int     pendingFilterMode     = 0;
    private int     pendingPostFXMode     = 0;
    private float   pendingSharpness      = 0.5f;
    private boolean pendingSwapRB         = false;

    private WinlatorHUD hudRef = null;
    private FrameRating classicHudRef = null;
    private volatile int fpsWindowId = -1;
    private volatile int sessionWindowId = -1;
    private volatile int fpsLimit = 0;

    private static volatile boolean nativeLibLoaded = false;

    public static synchronized void loadNativeLibrary() {
        if (!nativeLibLoaded) {
            System.loadLibrary("vulkan_renderer");
            nativeLibLoaded = true;
        }
    }

    public VulkanXServerView(Context context, XServer xServer) {
        super(context, xServer);
        setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);

        rootCursorDrawable = createRootCursorDrawable();
        xServer.setXServerView(this);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface,
                                   int screenWidth, int screenHeight,
                                   String driverPath, String libraryName, String nativeLibDir,
                                   boolean validationEnabled, boolean requireDmaBufImport, boolean requireSelectedDriver, boolean pipelineEnabled);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, Surface surface);
    private native int[] nativeGetSwapchainSize(long handle);

    @FastNative private native void nativeUpdateWindowContent(long handle, long id,
        ByteBuffer pixels, short width, short height, short stride, int x, int y);
    @FastNative private native boolean nativeUpdateWindowContentDmaBuf(long handle, long id, long key, int fd,
        int w, int h, int stride, int offset);
    private native String nativeRendererDiagnostics(long handle);
    private native boolean nativeRendererFailed(long handle);
    private native String nativePipelineDiagnostics(long handle);
    private native void nativeSetPipelineTarget(long handle, long id);
    public String pipelineDiagnostics() {
        synchronized(lock) {return nativeHandle!=0?nativePipelineDiagnostics(nativeHandle):"pipe_available=0";}
    }
    @FastNative private native void nativeUpdateWindowContentAHB(long handle, long id,
        long ahbPtr, short width, short height, int x, int y);
    @FastNative private native void nativeSetTransformAndScissor(long handle,
        float ox, float oy, float sx, float sy,
        boolean hasScissor, int scX, int scY, int scW, int scH);
    @FastNative private native void nativeSetPointerPos(long handle, short x, short y);
    @FastNative private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeSetScreenSize(long handle, int width, int height);
    @FastNative private native void nativeUpdateCursorImage(long handle,
        ByteBuffer pixels, short width, short height, short stride, short hotX, short hotY);
    @FastNative private native void nativeRemoveWindow(long handle, long id);
    @FastNative private native void nativeReleaseDmaBuf(long handle, long key);
    @FastNative private native void nativeSetVerboseLog(long handle, boolean v);
    @FastNative private native void nativeSetSharpness(long handle, float sharpness);

    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetStretchMode(long handle, int mode);
    private native void nativeSetPostFXMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native void nativeSetFrameCoordination(long handle, int cap);
    private native int[] nativeGetSupportedPresentModes(long handle);

    private native void nativeInitRootWindow(long handle, long rootId, long contentId, int width, int height);
    private native void nativeCreateWindow(long handle, long id, long parentId, long contentId,
                                           int x, int y, int width, int height);
    private native void nativeDestroyWindow(long handle, long id);
    private native void nativeMapWindow(long handle, long id);
    private native void nativeUnmapWindow(long handle, long id);
    private native void nativeReparentWindow(long handle, long id, long newParentId);
    private native void nativeUpdateWindowGeometry(long handle, long id, long contentId,
                                                   int x, int y, int width, int height);
    private native void nativeSyncChildOrder(long handle, long parentId, long[] orderedChildIds, int count);
    private native void nativeSetWindowViewable(long handle, long id, boolean viewable);

    private static long did(Drawable d) {
        return (long) System.identityHashCode(d);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        onSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        onSurfaceDestroyed();
    }

    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) { GPUImage.checkIsSupported(); gpuImageChecked = true; }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        initExecutor = Executors.newSingleThreadExecutor();

        final boolean isColdInit = (nativeHandle == 0);

        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                    } else {

                        nativeSetPresentMode(nativeHandle, pendingPresentMode);
                        nativeSetFrameCoordination(nativeHandle, isFrameCoordinationEnabled() ? fpsLimit : 0);
                        nativeSetFilterMode(nativeHandle, pendingFilterMode);
                        nativeSetSwapRB(nativeHandle, pendingSwapRB);
                        nativeSetStretchMode(nativeHandle, pendingStretchMode);
                        nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                        nativeSetSharpness(nativeHandle, pendingSharpness);
                        updateTransform();
                        sendCursorToNative(lastCursor);
                        initComplete = true;
                        post(rendererHealthCheck);
                        return;
                    }
                }

                try {
                    nativeHandle = nativeInit(
                            surface,
                            xServer.screenInfo.width, xServer.screenInfo.height,
                            driverPath, driverLibraryName, nativeLibDir, validationEnabled, requireDmaBufImport, requireSelectedDriver, com.winlator.cmod.linux.FramePipelineDiagnostics.enabled());
                } catch (IllegalStateException error) {
                    Log.e("VulkanXServerView", "Renderer initialization failed", error);
                    if (initializationError != null) post(() -> initializationError.accept(error.getMessage()));
                    return;
                }

                if (nativeHandle != 0) {
                    com.winlator.cmod.linux.FramePipelineDiagnostics.renderer(this::pipelineDiagnostics);
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFrameCoordination(nativeHandle, isFrameCoordinationEnabled() ? fpsLimit : 0);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetSwapRB(nativeHandle, pendingSwapRB);
                    nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                    nativeSetSharpness(nativeHandle, pendingSharpness);
                    updateTransform();
                    sendCursorToNative(lastCursor);

                    if (isColdInit) {
                        Window root = xServer.windowManager.rootWindow;
                        nativeInitRootWindow(nativeHandle, root.id, did(root.getContent()),
                            xServer.screenInfo.width, xServer.screenInfo.height);
                    }
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                    sampleRendererDiagnostics(true);
                }
            }
            initComplete = true;
            post(rendererHealthCheck);
        });
    }

    @Override
    public void onScreenResized() {
        final int w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetScreenSize(nativeHandle, w, h);
            viewTransformation.update(surfaceWidth, surfaceHeight, w, h);
            updateTransform();
        }
        // Whether the game's window fills the screen depends on the screen's size.
        if (gameWindowZoom) queueEvent(this::refreshGameZoom);
    }

    public void onSurfaceChanged(int width, int height) {
        if (inPipMode) return;
        surfaceWidth = width; surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        synchronized (lock) {
            if (nativeHandle != 0) { nativeResize(nativeHandle, width, height); updateTransform(); }
        }
    }

    public void onSurfaceDestroyed() {
        removeCallbacks(rendererHealthCheck);
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) nativeDetachSurface(nativeHandle);
        }
    }

    @Override
    public void onDestroy() {
        forceCleanup();
    }

    public void forceCleanup() {
        removeCallbacks(rendererHealthCheck);
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        compositeRedirectedWindows.clear();
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
    }

    public void queueEvent(Runnable r) { eventExecutor.execute(r); }
    @Override
    public void onPause()  {}
    @Override
    public void onResume() {}

    @Override
    public void setGameWindowZoom(boolean on) {
        gameWindowZoom = on;
        queueEvent(this::refreshGameZoom);
    }

    @Override
    public int[] getGameZoomRect() { return gameWindowZoom ? gameZoomRect : null; }

    @Override
    public void setOnGameZoomChanged(Runnable listener) { onGameZoomChanged = listener; }

    /**
     * The window to scale up: the biggest mapped window directly under the root that is not a menu or tooltip (override-redirect)
     * and does not cover the X screen already. A full-screen window under the game (the Steam client) is passed over; when the
     * only windows are full-screen ones, or none, the screen is shown as it is.
     */
    private int[] pickGameWindow() {
        final int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;
        Window[] children;
        try {
            // The list is changed by the X server's thread: copy it in one step instead of walking it.
            children = xServer.windowManager.rootWindow.getChildren().toArray(new Window[0]);
        } catch (RuntimeException e) {
            return gameZoomRect;
        }
        Window best = null;
        for (Window child : children) {
            if (child == null || !child.isMappedTopLevel() || child.attributes.isOverrideRedirect()) continue;
            if (child.getWidth() < 100 || child.getHeight() < 100) continue;
            // The Steam client's own windows (its update and login windows) are not the game: they stay as they are.
            String name = child.getName();
            if (name.equals("Steam") || name.startsWith("Steam - ") || name.startsWith("Steam Big Picture")) continue;
            if (child.getWidth() >= screenW && child.getHeight() >= screenH) continue;
            if (best == null || (long) child.getWidth() * child.getHeight() >= (long) best.getWidth() * best.getHeight()) best = child;
        }
        if (best == null) return null;
        return new int[]{best.getX(), best.getY(), best.getWidth(), best.getHeight()};
    }

    /** Picks the window again after the windows changed (on the render thread) and redraws if it, or its place, is another. */
    private void refreshGameZoom() {
        if (!gameWindowZoom && gameZoomRect == null) return;
        int[] rect = gameWindowZoom ? pickGameWindow() : null;
        synchronized (lock) {
            if (java.util.Arrays.equals(rect, gameZoomRect)) return;
            gameZoomRect = rect;
            updateTransform();
        }
        Runnable listener = onGameZoomChanged;
        if (listener != null) listener.run();
    }

    private void updateTransform() {
        if (nativeHandle == 0) return;
        final int[] zoomRect = gameWindowZoom ? gameZoomRect : null;
        if (zoomRect != null && surfaceWidth > 0 && surfaceHeight > 0) {
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height);
            // The window's rectangle is mapped onto the view, aspect kept, and everything else is cut off by the scissor.
            final float screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;
            final float s = Math.min((float) surfaceWidth / zoomRect[2], (float) surfaceHeight / zoomRect[3]);
            final float destW = zoomRect[2] * s, destH = zoomRect[3] * s;
            final float x0 = (surfaceWidth - destW) * 0.5f, y0 = (surfaceHeight - destH) * 0.5f;
            final float sx = s * screenW / surfaceWidth, sy = s * screenH / surfaceHeight;
            final float ox = x0 * screenW / surfaceWidth - zoomRect[0] * sx;
            final float oy = y0 * screenH / surfaceHeight - zoomRect[1] * sy;
            nativeSetTransformAndScissor(nativeHandle, ox, oy, sx, sy, true,
                (int) x0, (int) y0, (int) Math.ceil(destW), (int) Math.ceil(destH));
            return;
        }
        final float zoom = magnifierZoom;
        final float ptrX = xServer.pointer.getX();
        final float ptrY = xServer.pointer.getY();

        final float ox, oy, sx, sy;
        if (fullscreen) {
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height);
            if (zoom != 1.0f) {
                ox = viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom);
                oy = viewTransformation.sceneOffsetY + ptrY * viewTransformation.sceneScaleY * (1f - zoom);
                sx = viewTransformation.sceneScaleX * zoom;
                sy = viewTransformation.sceneScaleY * zoom;
            } else {
                ox = 0f; oy = 0f; sx = 1f; sy = 1f;
            }
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(xServer.screenInfo.height / 2);
                py = Math.max(0, Math.min(ptrY - halfH / 2.0f, halfH));
            }
            ox = viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom);
            oy = viewTransformation.sceneOffsetY - py + ptrY * viewTransformation.sceneScaleY * (1f - zoom);
            sx = viewTransformation.sceneScaleX * zoom;
            sy = viewTransformation.sceneScaleY * zoom;
        }

        final boolean needScissor = !(fullscreen && zoom == 1.0f);
        nativeSetTransformAndScissor(nativeHandle, ox, oy, sx, sy,
            needScissor,
            viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
            viewTransformation.viewWidth,   viewTransformation.viewHeight);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        if (forceCursor) cursor = null;
        Drawable cd; short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage; hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY;
        } else { cd = rootCursorDrawable; }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null && cd.backingAHB != 0) {
            synchronized (cd.renderLock) {
                ByteBuffer pixels = cd.lockBuffer(cd.backingAHB);
                if (pixels != null) {
                    try {
                        nativeUpdateCursorImage(nativeHandle, pixels, cd.width, cd.height,
                            cd.getStride(), hotX, hotY);
                    } finally {
                        cd.unlockBuffer(cd.backingAHB);
                    }
                }
            }
        }
    }

    private void checkViewable(Window window) {
        if (unviewableWMClasses == null || window == xServer.windowManager.rootWindow) return;
        boolean viewable = true;
        String wc = window.getClassName();
        for (String cls : unviewableWMClasses) {
            if (wc.contains(cls)) {
                if (window.attributes.isEnabled()) window.disableAllDescendants();
                viewable = false;
                break;
            }
        }
        final long id = window.id;
        final boolean v = viewable;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeSetWindowViewable(nativeHandle, id, v);
            }
        });
    }

    @Override
    public void onCreateWindow(Window window, Window parent) {
        final long id = window.id;
        final long parentId = parent != null ? parent.id : 0;
        final long contentId = did(window.getContent());
        final int x = window.getX(), y = window.getY(), w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeCreateWindow(nativeHandle, id, parentId, contentId, x, y, w, h);
            }
        });
        checkViewable(window);
    }

    @Override
    public void onDestroyWindow(Window window) {
        if (gameWindowZoom) queueEvent(this::refreshGameZoom);
        final long id = window.id;
        compositeRedirectedWindows.remove(window.id);
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeDestroyWindow(nativeHandle, id);
            }
        });
    }

    @Override
    public void onMapWindow(Window window) {
        if (gameWindowZoom) queueEvent(this::refreshGameZoom);
        final long id = window.id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeMapWindow(nativeHandle, id);
            }
        });
        checkViewable(window);
    }

    @Override
    public void onUnmapWindow(Window window) {
        if (gameWindowZoom) queueEvent(this::refreshGameZoom);
        final long id = window.id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeUnmapWindow(nativeHandle, id);
            }
        });
    }

    @Override
    public void onReparentWindow(Window window, Window newParent, short x, short y) {
        final long id = window.id;
        final long newParentId = newParent != null ? newParent.id : 0;
        final long contentId = did(window.getContent());
        final int width = window.getWidth(), height = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeReparentWindow(nativeHandle, id, newParentId);
                    nativeUpdateWindowGeometry(nativeHandle, id, contentId, x, y, width, height);
                }
            }
        });
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        if (parent == null) return;
        List<Window> children = parent.getChildren();
        final long parentId = parent.id;
        final long[] orderedIds = new long[children.size()];
        for (int i = 0; i < orderedIds.length; i++) orderedIds[i] = children.get(i).id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeSyncChildOrder(nativeHandle, parentId, orderedIds, orderedIds.length);
            }
        });
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        if (gameWindowZoom) queueEvent(this::refreshGameZoom);
        final long id = window.id;
        final long contentId = did(window.getContent());
        final int x = window.getX(), y = window.getY(), w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeUpdateWindowGeometry(nativeHandle, id, contentId, x, y, w, h);
            }
        });
    }

    @Override
    public void onModifyWindowProperty(Window window, Property property) {

        checkViewable(window);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastCursor = window.attributes.getCursor(); sendCursorToNative(lastCursor); }
            }
        }
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        onUpdateWindowContentDirect(window, drawable, (short) 0, (short) 0);
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            if (window.id==sessionWindowId && com.winlator.cmod.linux.FramePipelineDiagnostics.enabled())
                nativeSetPipelineTarget(nativeHandle,did(window.getContent()));
            final int rx = window.getRootX() + xOff, ry = window.getRootY() + yOff;
            synchronized (pixmap.renderLock) {
                if (pixmap.dmaBufFd >= 0) {
                    boolean accepted = nativeUpdateWindowContentDmaBuf(nativeHandle, did(window.getContent()), pixmap.dmaBufKey,
                        pixmap.dmaBufFd, pixmap.width, pixmap.height, pixmap.dmaBufStride, pixmap.dmaBufOffset);
                    if (!accepted) {
                        if (!importFailureReported) {
                            importFailureReported = true;
                            String details = nativeRendererDiagnostics(nativeHandle);
                            Log.e("VulkanXServerView", "Linux frame import failed: " + details);
                            try {
                                com.winlator.cmod.telemetry.LinuxDiagnostics.rendererImportFailed(details);
                            } catch (Exception e) {
                                Log.w("VulkanXServerView", "Cannot report frame import failure", e);
                            }
                            if (requireDmaBufImport && initializationError != null) {
                                final String failure = details.contains("vk_result=-1000069000")
                                        ? "The renderer ran out of descriptor pool space while importing a frame."
                                        : details.contains("vk_result=-1 ") || details.contains("vk_result=-2 ")
                                        ? "The renderer ran out of memory while importing a frame."
                                        : "The renderer could not import the game's DMA-BUF frame.";
                                post(() -> initializationError.accept(failure));
                            }
                        }
                        return;
                    }
                }
                else if (pixmap.backingAHB != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(window.getContent()),
                        pixmap.backingAHB, pixmap.width, pixmap.height, rx, ry);
                }
                else return;
                sampleRendererDiagnostics(false);
            }
        }
        // Incoming Present requests are not proof that the renderer accepted their buffers.
        if (window.id == sessionWindowId) com.winlator.cmod.telemetry.GameSessionRecorder.onFrame();
        if (window.id == fpsWindowId) {
            if (hudRef != null) hudRef.onFrame();
            if (classicHudRef != null) classicHudRef.update();
        }
    }

    /** At most one small snapshot every five seconds, and only for opted-in telemetry. Caller holds lock. */
    private void sampleRendererDiagnostics(boolean force) {
        if (nativeHandle == 0 || !com.winlator.cmod.telemetry.Telemetry.isEnabled()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - rendererSampleAt < 5000) return;
        rendererSampleAt = now;
        try { com.winlator.cmod.telemetry.LinuxDiagnostics.rendererSnapshot(nativeRendererDiagnostics(nativeHandle)); }
        catch (Exception ignored) {}
    }

    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {

    }

    public void nativeRemoveDirectContent(int windowId, int pixmapId) {
        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null || pixmap.drawable == null) return;
        if (pixmap.drawable.dmaBufStride > 0) {
            synchronized (lock) {
                if (nativeHandle != 0) nativeReleaseDmaBuf(nativeHandle, pixmap.drawable.dmaBufKey);
            }
            return;
        }
        final long contentId = did(pixmap.drawable);
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, contentId);
            }
        });
    }

    @Override
    public void nativeSetCompositeRedirected(int windowId, boolean redirected) {
        if (xServer.windowManager.getWindow(windowId) == null) return;
        if (redirected) compositeRedirectedWindows.add(windowId);
        else compositeRedirectedWindows.remove(windowId);
    }

    private static boolean hasAncestor(Window window, Window ancestor) {
        Window current = window;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private static Window getWindowSibling(Window window, Window other) {
        Window current = window;
        while (current != null) {
            if (current.getParent() == other.getParent()) return current;
            current = current.getParent();
        }
        return null;
    }

    @Override
    public void nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY) {
        Window srcWindow = xServer.windowManager.getWindow(srcDrawableId);
        if (srcWindow == null) return;

        Window dstWindow = xServer.windowManager.getWindow(dstDrawableId);
        if (dstWindow == null) return;

        if (!hasAncestor(srcWindow, dstWindow)) {
            Window sibling = getWindowSibling(srcWindow, dstWindow);
            if (sibling == null) return;

            int posX = dstWindow.getX() + dstX;
            int posY = dstWindow.getY() + dstY;
            boolean positionChanged = sibling.getX() != posX || sibling.getY() != posY;

            if (positionChanged) {
                sibling.setX((short)posX);
                sibling.setY((short)posY);
                onUpdateWindowGeometry(sibling, false);
            }

            Window parent = sibling.getParent();
            if (parent == null || parent != dstWindow.getParent()) return;

            List<Window> children = parent.getChildren();
            int siblingIndex = children.indexOf(sibling);
            int dstIndex = children.indexOf(dstWindow);
            if (siblingIndex < 0 || dstIndex < 0) return;

            if (siblingIndex <= dstIndex) {
                parent.moveChildAbove(sibling, dstWindow);
                onChangeWindowZOrder(Window.StackMode.ABOVE, sibling, dstWindow);
            }
        }

    }

    @Override
    public void onUpdateWindowContent(Window window) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            Drawable drawable = window.getContent();
            if (drawable == null || !window.attributes.isMapped()) return;

            final int rx = window.getRootX(), ry = window.getRootY();
            synchronized (drawable.renderLock) {
                if (drawable.backingAHB != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(drawable),
                        drawable.backingAHB, drawable.width, drawable.height, rx, ry);
                    sampleRendererDiagnostics(false);
                }
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) { lastCursor = cursor; sendCursorToNative(lastCursor); }
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) { nativeSetCursorVisible(nativeHandle, visible); if (visible) sendCursorToNative(lastCursor); }
        }
    }

    @Override public void setForceCursor(boolean force) {
        synchronized (lock) {
            forceCursor = force;
            sendCursorToNative(lastCursor);
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
    }

    public void setValidationEnabled(boolean enabled, String nativeLibDir) {
        this.validationEnabled = enabled;
        this.nativeLibDir = nativeLibDir;
    }

    public void setVerboseLog(boolean v) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetVerboseLog(nativeHandle, v); }
    }

    public void dumpRendererInfo() {
        synchronized (lock) { if (nativeHandle != 0) nativeDumpRendererInfo(nativeHandle); }
    }

    public void setStretchMode(int mode) {
        pendingStretchMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetStretchMode(nativeHandle, mode); }
    }

    public void setPostFXMode(int mode) {
        pendingPostFXMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPostFXMode(nativeHandle, mode); }
    }

    public void setSharpness(float s) {
        pendingSharpness = s;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSharpness(nativeHandle, s); }
    }

    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
    }

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled); }
    }

    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode); }
    }

    public int[] getSupportedPresentModes() {
        synchronized (lock) {
            if (nativeHandle != 0) return nativeGetSupportedPresentModes(nativeHandle);
        }
        return new int[0];
    }

    @Override
    public boolean isFullscreen() { return fullscreen; }
    @Override
    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        synchronized (lock) { updateTransform(); }
    }
    @Override
    public void setScreenOffsetYRelativeToCursor(boolean b) {
        screenOffsetYRelativeToCursor = b;
        synchronized (lock) { updateTransform(); }
    }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    @Override
    public void setMagnifierZoom(float zoom) {
        magnifierZoom = zoom;
        synchronized (lock) { updateTransform(); }
    }
    @Override
    public float getMagnifierZoom() { return magnifierZoom; }
    @Override
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }

    public void setFpsWindowId(int id) { fpsWindowId = id; }
    @Override
    public void setSessionWindowId(int id) { sessionWindowId = id; com.winlator.cmod.linux.FramePipelineDiagnostics.targetWindow(id); }
    public void setFrameRating(Object fr) {
        if (fr instanceof WinlatorHUD) hudRef = (WinlatorHUD) fr;
        else if (fr instanceof FrameRating) classicHudRef = (FrameRating) fr;
    }
    public int getFpsLimit() {
        return fpsLimit;
    }
    public void setFpsLimit(int limit) {
        this.fpsLimit = Math.max(0, Math.min(1000, limit));
        updateDisplayFps(fpsLimit);
        synchronized (lock) {
            if (nativeHandle != 0)
                nativeSetFrameCoordination(nativeHandle, isFrameCoordinationEnabled() ? fpsLimit : 0);
        }
    }
    @Override public void setFrameCoordination(boolean enabled) {
        super.setFrameCoordination(enabled);
        setFpsLimit(fpsLimit);
    }
    public void setPipMode(boolean pip) { inPipMode = pip; }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public void requestRender() {}
}
