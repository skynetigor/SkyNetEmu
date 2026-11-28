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
    private int fixedSourceRate;
    private boolean active = true;
    private boolean attached;
    private boolean policyCaptured;
    private Surface lastSurface;
    private int lastCap = -1;
    private int lastCompatibility = -1;
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

    /** Hold each generated frame for a fixed number of display refreshes. Does not drop the panel rate. */
    public void setFixedSourceRate(int value) {
        handler.post(() -> {
            if (fixedSourceRate == value) return;
            fixedSourceRate = value;
            lastCap = -1;
            apply();
        });
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
        int surfaceRate = active && fixedSourceRate > 0 ? fixedSourceRate : contentCap;
        int compatibility = active && fixedSourceRate > 0
                ? Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                : Surface.FRAME_RATE_COMPATIBILITY_DEFAULT;
        float selected = contentCap > 0 ? RefreshRatePolicy.choose(rates, contentCap) : 0;
        float panelRate = 0f;
        for (float rate : rates) if (rate > panelRate) panelRate = rate;
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        float requested;
        int requestedMode;
        if (!active) {
            requested = 0f;
            requestedMode = 0;
        } else if (fixedSourceRate > 0) {
            requested = panelRate > 1f ? panelRate : originalRate;
            requestedMode = 0;
        } else if (contentCap > 0) {
            requested = selected;
            requestedMode = 0;
        } else {
            requested = originalRate;
            requestedMode = originalMode;
        }
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
        if (surface != lastSurface || surfaceRate != lastCap || compatibility != lastCompatibility) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface.isValid()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        int strategy = fixedSourceRate > 0
                                ? Surface.CHANGE_FRAME_RATE_ALWAYS
                                : Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS;
                        surface.setFrameRate(surfaceRate, compatibility, strategy);
                    } else surface.setFrameRate(surfaceRate, compatibility);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Log.w(TAG, "Surface frame-rate request failed: cap=" + surfaceRate, e);
                    return;
                }
            }
            lastSurface = surface.isValid() ? surface : null;
            lastCap = surface.isValid() ? surfaceRate : -1;
            lastCompatibility = surface.isValid() ? compatibility : -1;
            Log.d(TAG, "backend=" + view.getClass().getSimpleName() + " cap=" + surfaceRate
                    + " fixed=" + (compatibility == Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    + " requestedHz=" + requested + " observedHz=" + display.getRefreshRate());
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { lastSurface = null; apply(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { apply(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { lastSurface = null; lastCap = -1; lastCompatibility = -1; }
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}
    @Override public void onDisplayChanged(int id) {
        Display display = view.getDisplay();
        if (display != null && display.getDisplayId() == id) apply();
    }
}
