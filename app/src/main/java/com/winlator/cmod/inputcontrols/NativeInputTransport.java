package com.winlator.cmod.inputcontrols;

import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class NativeInputTransport {
    private static volatile boolean enabled;

    private NativeInputTransport() {}

    public static synchronized void start(String directory, boolean legacy) throws IOException {
        if (enabled) throw new IOException("A controller transport session is already running");
        if (legacy) {
            Log.i("SkyInput", "protocol=legacy selected for session compatibility");
            return;
        }
        System.loadLibrary("winlator");
        int error = nativeStart(directory);
        if (error != 0) throw new IOException("Cannot start controller transport: errno=" + error);
        enabled = true;
    }

    public static boolean isEnabled() { return enabled; }

    public static synchronized void stop() {
        if (enabled) {
            nativeStop();
            enabled = false;
        }
    }

    static void activate(int slot) throws IOException {
        int error = nativeActivate(slot);
        if (error != 0) throw new IOException("Cannot activate controller slot: errno=" + error);
    }

    static int publish(int slot, ByteBuffer buffer) throws IOException {
        int result = nativePublish(slot, buffer, buffer.position(), buffer.remaining());
        if (result < 0) throw new IOException("Controller publication failed: errno=" + -result);
        buffer.position(buffer.position() + result);
        return result;
    }

    static native void disconnect(int slot);
    private static native int nativeStart(String directory);
    private static native void nativeStop();
    private static native int nativeActivate(int slot);
    private static native int nativePublish(int slot, ByteBuffer data, int offset, int bytes);
}
