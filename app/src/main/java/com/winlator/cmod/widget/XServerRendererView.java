package com.winlator.cmod.widget;

import android.content.Context;
import android.view.SurfaceView;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

public abstract class XServerRendererView extends SurfaceView {

    public final ViewTransformation viewTransformation = new ViewTransformation();
    protected final XServer xServer;
    private final com.winlator.cmod.perf.DisplayFrameRate displayFrameRate;
    private volatile boolean frameCoordination;

    public void setFrameCoordination(boolean enabled) { frameCoordination = enabled; }
    public final boolean isFrameCoordinationEnabled() { return frameCoordination; }

    public static boolean coordinatesOverlays(Context context) {
        XServerRendererView renderer = findRenderer(context);
        return renderer != null && renderer.isFrameCoordinationEnabled();
    }

    public static int overlayUpdateIntervalMs(Context context, int normalIntervalMs) {
        XServerRendererView renderer = findRenderer(context);
        int cap = renderer != null && renderer.isFrameCoordinationEnabled() ? renderer.getFpsLimit() : 0;
        return cap > 0 ? Math.max(normalIntervalMs, (1000 + cap - 1) / cap) : normalIntervalMs;
    }

    private static XServerRendererView findRenderer(Context context) {
        while (context instanceof android.content.ContextWrapper && !(context instanceof com.winlator.cmod.XServerDisplayActivity))
            context = ((android.content.ContextWrapper) context).getBaseContext();
        return context instanceof com.winlator.cmod.XServerDisplayActivity
                ? ((com.winlator.cmod.XServerDisplayActivity) context).getXServerView() : null;
    }

    protected XServerRendererView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        android.content.Context owner = context;
        while (owner instanceof android.content.ContextWrapper && !(owner instanceof android.app.Activity))
            owner = ((android.content.ContextWrapper) owner).getBaseContext();
        displayFrameRate = owner instanceof android.app.Activity
                ? new com.winlator.cmod.perf.DisplayFrameRate(this, (android.app.Activity) owner) : null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (displayFrameRate != null) displayFrameRate.attach();
    }

    @Override protected void onDetachedFromWindow() {
        if (displayFrameRate != null) displayFrameRate.detach();
        super.onDetachedFromWindow();
    }

    protected final void updateDisplayFps(int cap) {
        if (displayFrameRate != null) displayFrameRate.setCap(cap);
    }

    public final void setDisplayRateActive(boolean active) {
        if (displayFrameRate != null) displayFrameRate.setActive(active);
    }

    /** The X screen changed size (a program picked another resolution): its size is the new one in xServer.screenInfo. */
    public void onScreenResized() {}

    /**
     * Draw the game's window (a window smaller than the X screen) scaled up to the view, keeping its proportions, instead of the
     * whole screen: the upscalers then work on the game's own resolution. Only the Vulkan renderer does it.
     */
    public void setGameWindowZoom(boolean on) {}

    /** The game window being scaled up, as x, y, width, height on the X screen; null when the whole screen is shown. */
    public int[] getGameZoomRect() { return null; }

    /** Called (from a render thread) when the window being scaled up, or its place, changed: touches map onto it. */
    public void setOnGameZoomChanged(Runnable listener) {}

    public abstract void onPause();
    public abstract void onResume();
    public abstract void onDestroy();

    public abstract void toggleFullscreen();
    public abstract boolean isFullscreen();

    public abstract void setCursorVisible(boolean visible);
    /** Draw the app pointer instead of a game cursor that may be blank or hidden. */
    public abstract void setForceCursor(boolean force);
    public abstract void setScreenOffsetYRelativeToCursor(boolean relative);
    public abstract void setMagnifierZoom(float zoom);
    public abstract float getMagnifierZoom();
    public abstract void setUnviewableWMClasses(String... classes);

    public abstract void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage);
    public abstract void nativeRemoveDirectContent(int windowId, int pixmapId);
    public abstract void nativeSetCompositeRedirected(int windowId, boolean redirected);
    public abstract void nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY);

    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable);
    public abstract void onUpdateWindowContentDirect(Window window, Drawable drawable, short xOff, short yOff);
    public abstract void onPointerMove(short x, short y);
    public abstract void requestRender();
    public abstract void queueEvent(Runnable action);
    public abstract void onSurfaceChanged(int width, int height);
    public abstract void forceCleanup();
    public abstract void setFpsWindowId(int windowId);
    /** The game's render window, for the telemetry recorder (independent of any HUD). */
    public abstract void setSessionWindowId(int windowId);
    public abstract void setPipMode(boolean pipMode);
    public abstract void setFrameRating(Object frameRating);

    public abstract void setFpsLimit(int fps);
    public abstract int getFpsLimit();
}
