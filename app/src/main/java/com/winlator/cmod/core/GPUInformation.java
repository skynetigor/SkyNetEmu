package com.winlator.cmod.core;

import android.content.Context;

public abstract class GPUInformation {

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }
    // The JNI probe uses shared Vulkan handles; serialize probes from UI and launch workers.
    public synchronized native static String getVulkanVersion(String driverName, Context context);
    public synchronized native static int getVendorID(String driverName, Context context);
    public synchronized native static String getRenderer(String driverName, Context context);
    public synchronized native static String[] enumerateExtensions(String driverName, Context context);

    static {
        System.loadLibrary("winlator");
    }
}
