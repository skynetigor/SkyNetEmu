package com.winlator.cmod;

import android.app.Application;

import com.winlator.cmod.telemetry.Telemetry;

public class SkyNetApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        com.winlator.cmod.linux.LinuxRuntimeCatalog.init(this);
        // The VR process runs this too and has nothing to report.
        String process = Application.getProcessName();
        if (process == null || process.equals(getPackageName())) {
            Telemetry.init(this);
            // The registry (game profiles, defaults) is asked of the network at every start; the saved copies are for when it cannot be reached.
            com.winlator.cmod.contents.RegistryCache.refreshInBackground(this);
        }
    }
}
