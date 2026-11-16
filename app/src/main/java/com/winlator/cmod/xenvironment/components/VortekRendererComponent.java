package com.winlator.cmod.xenvironment.components;

import android.content.Context;

import androidx.annotation.Keep;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * The server half of Vortek (https://github.com/brunodev85/vortek, LGPL-2.1): the guest's Vulkan client (libvulkan_vortek.so)
 * sends serialized Vulkan calls over a socket and this component runs them on the phone's own Vulkan driver. Meant for GPUs
 * with no Turnip driver (Mali); the native side is cpp/vortekrenderer.
 */
public class VortekRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private static final byte REQUEST_CODE_CREATE_CONTEXT = 1;
    private static final byte REQUEST_CODE_SEND_EXTRA_DATA = 2;
    public static final short IMAGE_CACHE_SIZE = 256;
    public static final int VK_MAX_VERSION = vkMakeVersion(1, 3, 128);
    /** Order of the present modes the native swapchain knows (xwindow_swapchain.c). */
    private static final String[] PRESENT_MODES = {"immediate", "mailbox", "fifo", "fifo_relaxed"};
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    private final Options options;

    static {
        System.loadLibrary("vortekrenderer");
    }

    public static class Options {
        public int vkMaxVersion = VK_MAX_VERSION;
        public short maxDeviceMemory = 0;
        public short imageCacheSize = IMAGE_CACHE_SIZE;
        public byte resourceMemoryType = 0;
        public byte presentMode = 2;
        public short maxFramerate = 0;
        public String[] exposedDeviceExtensions = null;
        /** The custom Vulkan driver's library, or null for the system's. */
        public String libvulkanPath = null;

        /** Reads the keys of the container's graphicsDriverConfig that Vortek uses; a missing key keeps the default. */
        public static Options fromConfig(Context context, Map<String, String> config) {
            Options options = new Options();
            if (config == null) return options;

            String extensions = config.getOrDefault("exposedDeviceExtensions", "all");
            if (!extensions.isEmpty() && !extensions.equals("all")) options.exposedDeviceExtensions = extensions.split("\\|");

            String vkMaxVersion = config.getOrDefault("vortekVkMaxVersion", "");
            if (vkMaxVersion.matches("\\d+\\.\\d+")) {
                String[] parts = vkMaxVersion.split("\\.");
                options.vkMaxVersion = vkMakeVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 128);
            }

            options.maxDeviceMemory = (short)parseInt(config.getOrDefault("maxDeviceMemory", "0"), 0);
            options.imageCacheSize = (short)parseInt(config.getOrDefault("vortekImageCacheSize", ""), IMAGE_CACHE_SIZE);
            String presentMode = config.getOrDefault("presentMode", "fifo");
            for (int i = 0; i < PRESENT_MODES.length; i++) {
                if (PRESENT_MODES[i].equalsIgnoreCase(presentMode)) options.presentMode = (byte)i;
            }

            String driver = config.getOrDefault("version", "System");
            if (!driver.isEmpty() && !driver.equalsIgnoreCase("System") && !driver.equalsIgnoreCase("auto")) {
                AdrenotoolsManager manager = new AdrenotoolsManager(context);
                String library = manager.getLibraryName(driver);
                File file = new File(manager.getDriverPath(driver), library);
                if (!library.isEmpty() && file.isFile()) options.libvulkanPath = file.getPath();
            }
            return options;
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value.trim());
            }
            catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    public VortekRendererComponent(Context context, XServer xServer, UnixSocketConfig socketConfig, Options options) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
        this.options = options;

        initVulkanWrapper(context.getApplicationInfo().nativeLibraryDir, options.libvulkanPath, context.getCacheDir().getPath());
    }

    private static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.setInitialInputBufferCapacity(8);
        connector.setInitialOutputBufferCapacity(0);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Keep
    private short getWindowWidth(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getWidth() : 0;
    }

    @Keep
    private short getWindowHeight(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getHeight() : 0;
    }

    /** The window's own buffer: the swapchain renders straight into what the X server shows. */
    @Keep
    private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) return 0;
        Drawable drawable = window.getContent();
        if (drawable == null) return 0;
        if (useHALPixelFormatBGRA8888 != (drawable.format == Drawable.HAL_PIXEL_FORMAT_BGRA_8888)) {
            System.err.println("Vortek: window " + windowId + " has pixel format " + drawable.format + ", the swapchain asked for "
                    + (useHALPixelFormatBGRA8888 ? "BGRA" : "RGBA"));
        }
        return drawable.backingAHB;
    }

    @Keep
    private void updateWindowContent(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) return;
        Drawable drawable = window.getContent();
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            drawable.updateDirect();
        }
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        if (client.getTag() != null) {
            long contextPtr = (long)client.getTag();
            destroyVkContext(contextPtr);
        }
    }

    @Override
    public void handleNewConnection(Client client) {}

    @Override
    public boolean handleRequest(Client client) throws IOException {
        XInputStream inputStream = client.getInputStream();
        if (inputStream.available() < 8) return false;
        int requestCode = inputStream.readInt();
        int requestLength = inputStream.readInt();

        if (requestCode == REQUEST_CODE_CREATE_CONTEXT) {
            long contextPtr = createVkContext(client.clientSocket.fd, options);
            if (contextPtr > 0) client.setTag(contextPtr);
            else connector.killConnection(client);
        }
        else if (requestCode > Short.MAX_VALUE && (requestCode >> 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
            int requestId = requestCode & 0xffff;
            long contextPtr = (long)client.getTag();
            boolean success = handleExtraDataRequest(contextPtr, requestId, requestLength);
            if (!success) throw new IOException("Failed to handle extra data request.");
        }

        return true;
    }

    private native long createVkContext(int clientFd, Options options);

    private native void destroyVkContext(long contextPtr);

    private native void initVulkanWrapper(String nativeLibraryDir, String libvulkanPath, String cacheDir);

    private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);
}
