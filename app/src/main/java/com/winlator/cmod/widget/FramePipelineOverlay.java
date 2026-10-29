package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import com.winlator.cmod.linux.FramePipelineDiagnostics;

/** An opt-in, touch-through readout. Sampling and file IO happen on the diagnostics worker. */
public final class FramePipelineOverlay extends android.widget.TextView {
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if(!isAttachedToWindow() || !FramePipelineDiagnostics.enabled())return;
            boolean coordinated = XServerRendererView.coordinatesOverlays(getContext());
            if (coordinated && (!isShown() || getWindowVisibility() != VISIBLE)) {
                return;
            }
            String text = FramePipelineDiagnostics.overlay();
            if (!coordinated || !android.text.TextUtils.equals(getText(), text)) {
                setText(text);
            }
            postDelayed(this,1000);
        }
    };
    public FramePipelineOverlay(Context context) {
        super(context);
        setTextColor(Color.WHITE);setBackgroundColor(0xB0000000);setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP,10);setClickable(false);setFocusable(false);
        int padding=Math.round(6*getResources().getDisplayMetrics().density);setPadding(padding,padding,padding,padding);
        setText("Frame pipeline: waiting");
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(refresh);post(refresh);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (refresh == null) return;
        if (isShown()) { removeCallbacks(refresh); post(refresh); }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (refresh == null) return;
        if (visibility == VISIBLE) { removeCallbacks(refresh); post(refresh); }
        else if (XServerRendererView.coordinatesOverlays(getContext())) removeCallbacks(refresh);
    }
}
