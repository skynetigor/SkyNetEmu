package com.winlator.cmod.core;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.Locale;

/**
 * Which kind of Vulkan driver the device's GPU can use: Turnip (Adreno), or the phone's own system driver for every GPU without a
 * Turnip build (Mali and the others). The device decides; the settings show the type and do not
 * let it be changed.
 */
public enum GpuDriverType {
    TURNIP("Turnip"),
    SYSTEM("System");

    public final String label;

    GpuDriverType(String label) {
        this.label = label;
    }

    /** A file named like this in Winlator/ on shared storage forces a type ("turnip" or "system"), to try one on a device of the other kind. */
    private static final String OVERRIDE_FILE = "Winlator/gpu-driver-type";

    private static volatile GpuDriverType cached;

    public static GpuDriverType forDevice(Context context) {
        GpuDriverType forced = override();
        if (forced != null) return forced;
        GpuDriverType type = cached;
        if (type == null) {
            type = fromDeviceFiles();
            if (type == null) {
                // An unreadable renderer keeps the Turnip behavior this app always had.
                String renderer = "";
                try {
                    renderer = GPUInformation.getRenderer(null, context);
                }
                catch (Throwable ignored) {
                }
                renderer = renderer == null ? "" : renderer.trim().toLowerCase(Locale.ROOT);
                if (renderer.isEmpty() || renderer.contains("unknown")) return TURNIP;
                type = fromRenderer(renderer);
            }
            cached = type;
        }
        return type;
    }

    /** For a renderer name already in hand (the preset lists filter by it); a forced type applies here too. */
    public static GpuDriverType forRenderer(String renderer) {
        GpuDriverType forced = override();
        if (forced != null) return forced;
        String name = renderer == null ? "" : renderer.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.contains("unknown")) return TURNIP;
        return fromRenderer(name);
    }

    static GpuDriverType fromRenderer(String renderer) {
        return renderer.toLowerCase(Locale.ROOT).contains("adreno") ? TURNIP : SYSTEM;
    }

    /** The GPU's kernel interface says it without starting Vulkan: kgsl is Adreno's, /dev/mali0 is Arm's; null when neither is there. */
    private static GpuDriverType fromDeviceFiles() {
        if (new File("/sys/class/kgsl/kgsl-3d0").exists() || new File("/vendor/lib64/hw/vulkan.adreno.so").exists()) return TURNIP;
        if (new File("/dev/mali0").exists()) return SYSTEM;
        return null;
    }

    private static GpuDriverType override() {
        try {
            File file = new File(Environment.getExternalStorageDirectory(), OVERRIDE_FILE);
            if (!file.isFile()) return null;
            String text = FileUtils.readString(file);
            String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            if (value.equals("system")) return SYSTEM;
            if (value.equals("turnip")) return TURNIP;
        }
        catch (Throwable ignored) {
        }
        return null;
    }
}
