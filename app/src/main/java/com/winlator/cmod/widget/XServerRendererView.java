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

    protected XServerRendererView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
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
    /** Draws the pointers of the windows again (after {@link com.winlator.cmod.xserver.Cursor#setShowHidden}). */
    public void refreshCursors() {}
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
