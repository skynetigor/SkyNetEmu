package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class GrabRequests {
    private enum Status {SUCCESS, ALREADY_GRABBED, INVALID_TIME, NOT_VIEWABLE, FROZEN}

    public static void grabPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        if (client.xServer.isRelativeMouseMovement()) {
            client.skipRequest();
            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                outputStream.writeByte((byte)Status.ALREADY_GRABBED.ordinal());
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writePad(24);
            }
            return;
        }

        boolean ownerEvents = client.getRequestData() == 1;
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Bitmask eventMask = new Bitmask(inputStream.readShort());
        inputStream.skip(14);

        Status status;
        if (client.xServer.grabManager.getWindow() != null && client.xServer.grabManager.getClient() != client) {
            status = Status.ALREADY_GRABBED;
        }
        else if (window.getMapState() != Window.MapState.VIEWABLE) {
            status = Status.NOT_VIEWABLE;
        }
        else {
            status = Status.SUCCESS;
            client.xServer.grabManager.activatePointerGrab(window, ownerEvents, eventMask, client);
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)status.ordinal());
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    public static void ungrabPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        inputStream.skip(4);
        client.xServer.grabManager.deactivatePointerGrab();
    }

    // A window manager grabs buttons and keys on the windows it manages (click to focus, shortcuts).

    public static void grabButton(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean ownerEvents = client.getRequestData() == 1;
        int windowId = inputStream.readInt();
        Bitmask eventMask = new Bitmask(inputStream.readShort() & 0xffff);
        boolean pointerSync = inputStream.readByte() == 0;
        inputStream.skip(1 + 4 + 4);   // keyboard mode, confine-to, cursor
        int button = inputStream.readByte() & 0xff;
        inputStream.skip(1);
        int modifiers = inputStream.readShort() & 0xffff;
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        client.xServer.grabManager.addButtonGrab(window, client, ownerEvents, eventMask, pointerSync, button, modifiers);
    }

    public static void ungrabButton(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int button = client.getRequestData() & 0xff;
        int windowId = inputStream.readInt();
        int modifiers = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        client.xServer.grabManager.removeButtonGrab(window, button, modifiers);
    }

    public static void grabKey(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean ownerEvents = client.getRequestData() == 1;
        int windowId = inputStream.readInt();
        int modifiers = inputStream.readShort() & 0xffff;
        int key = inputStream.readByte() & 0xff;
        inputStream.skip(1 + 1 + 3);   // pointer mode, keyboard mode, pad
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        client.xServer.grabManager.addKeyGrab(window, client, ownerEvents, key, modifiers);
    }

    public static void ungrabKey(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int key = client.getRequestData() & 0xff;
        int windowId = inputStream.readInt();
        int modifiers = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        client.xServer.grabManager.removeKeyGrab(window, key, modifiers);
    }

    /** AllowEvents: the mode is the request's data byte; 0, 1, 2 and 6, 7 end a pointer freeze (2 replays the press). */
    public static void allowEvents(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int mode = client.getRequestData() & 0xff;
        inputStream.skip(4);   // time
        if (mode <= 2 || mode >= 6) client.xServer.inputDeviceManager.allowPointerEvents(mode == 2 ? 2 : 0);
    }
}
