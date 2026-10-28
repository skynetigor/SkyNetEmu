package com.winlator.cmod.perf;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.util.ArrayList;

public final class DisplayFrameRate implements SurfaceHolder.Callback, DisplayManager.DisplayListener {
    private static final String TAG = "DisplayFrameRate";
    private final SurfaceView view;
    private final Activity activity;
    private final DisplayManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float originalRate;
    private int originalMode;
    private int cap;
    private boolean active = true;
    private boolean attached;
    private boolean policyCaptured;
    private Surface lastSurface;
    private int lastCap = -1;
    private float lastObserved;

    public DisplayFrameRate(SurfaceView view, Activity activity) {
        this.view = view;
        this.activity = activity;
        manager = activity.getSystemService(DisplayManager.class);
        view.getHolder().addCallback(this);
    }

    public void attach() {
        if (attached) return;
        attached = true;
        if (!policyCaptured) {
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            originalRate = params.preferredRefreshRate;
            originalMode = params.preferredDisplayModeId;
            policyCaptured = true;
        }
        if (manager != null) manager.registerDisplayListener(this, handler);
        apply();
    }

    public void detach() {
        if (!attached) return;
        boolean wasActive = active;
        active = false;
        apply();
        active = wasActive;
        if (manager != null) manager.unregisterDisplayListener(this);
        attached = false;
    }

    public void setCap(int value) {
        handler.post(() -> { cap = value; apply(); });
    }

    public void setActive(boolean value) {
        handler.post(() -> { active = value; apply(); });
    }

    private void apply() {
        if (!attached) return;
        Display display = view.getDisplay();
        if (display == null) return;
        Display.Mode current = display.getMode();
        ArrayList<Float> available = new ArrayList<>();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight())
                available.add(mode.getRefreshRate());
        }
        float[] rates = new float[available.size()];
        for (int i = 0; i < rates.length; i++) rates[i] = available.get(i);
        int contentCap = active ? cap : 0;
        float selected = contentCap > 0 ? RefreshRatePolicy.choose(rates, contentCap) : 0;
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        float requested = active ? (contentCap > 0 ? selected : originalRate) : 0;
        int requestedMode = active && contentCap == 0 ? originalMode : 0;
        if (params.preferredRefreshRate != requested || params.preferredDisplayModeId != requestedMode) {
            params.preferredRefreshRate = requested;
            params.preferredDisplayModeId = requestedMode;
            activity.getWindow().setAttributes(params);
        }
        Surface surface = view.getHolder().getSurface();
        float observed = display.getRefreshRate();
        if (observed != lastObserved) {
            lastObserved = observed;
            Log.d(TAG, "display=" + display.getDisplayId() + " cap=" + contentCap
                    + " requestedHz=" + requested + " observedHz=" + observed);
        }
        if (surface != lastSurface || contentCap != lastCap) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface.isValid()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        surface.setFrameRate(contentCap, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                    else surface.setFrameRate(contentCap, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Log.w(TAG, "Surface frame-rate request failed: cap=" + contentCap, e);
                    return;
                }
            }
            lastSurface = surface.isValid() ? surface : null;
            lastCap = surface.isValid() ? contentCap : -1;
            Log.d(TAG, "backend=" + view.getClass().getSimpleName() + " cap=" + contentCap
                    + " requestedHz=" + requested + " observedHz=" + display.getRefreshRate());
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { lastSurface = null; apply(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { apply(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { lastSurface = null; lastCap = -1; }
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}
    @Override public void onDisplayChanged(int id) {
        Display display = view.getDisplay();
        if (display != null && display.getDisplayId() == id) apply();
    }
}
