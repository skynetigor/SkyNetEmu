package com.winlator.cmod.renderer;

import androidx.annotation.Keep;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import java.nio.ByteBuffer;

public class GPUImage {
    public long hardwareBufferPtr;
    public int format;
    private ByteBuffer virtualData;
    private short stride;
    private int width, height;
    private static boolean supported = false;

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }
    
    public GPUImage(int socketFd) {
        hardwareBufferPtr = nativeHardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr == 0)
            System.err.println("Error: Failed to create hardware buffer");
    }

    public short getStride() {
        return stride;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    @Keep
    private void setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void releaseImportedBuffer() {
        if (hardwareBufferPtr != 0) {
            releaseHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    public static boolean isSupported() {
        return supported;
    }

    public static void checkIsSupported() {
        final short size = 8;
        GPUImage gpuImage = new GPUImage(size, size);
        supported = gpuImage.hardwareBufferPtr != 0 && gpuImage.virtualData != null;
        if (gpuImage.hardwareBufferPtr != 0) {
            gpuImage.destroyHardwareBuffer(gpuImage.hardwareBufferPtr);
            gpuImage.hardwareBufferPtr = 0;
            gpuImage.virtualData = null;
        }
    }

    private native long nativeHardwareBufferFromSocket(int fd);
    
    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native void releaseHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);
}
    
