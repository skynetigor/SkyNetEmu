package com.winlator.cmod;

import android.app.Application;

import com.winlator.cmod.telemetry.Telemetry;

public class SkyNetApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // The VR process runs this too and has nothing to report.
        String process = Application.getProcessName();
        if (process == null || process.equals(getPackageName())) Telemetry.init(this);
    }
}
