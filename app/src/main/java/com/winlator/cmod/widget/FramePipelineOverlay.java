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
            setText(FramePipelineDiagnostics.overlay());postDelayed(this,1000);
        }
    };
    public FramePipelineOverlay(Context context) {
        super(context);
        setTextColor(Color.WHITE);setBackgroundColor(0xB0000000);setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP,10);setClickable(false);setFocusable(false);
        int padding=Math.round(6*getResources().getDisplayMetrics().density);setPadding(padding,padding,padding,padding);
        setText("Frame pipeline: waiting");
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(refresh);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);super.onDetachedFromWindow();}
}
