package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.XLock;

import java.io.IOException;

/**
 * XTEST: a client makes the server act as if a key or a button had been pressed or the pointer moved. An on-screen keyboard
 * types with it (FakeInput); the version and cursor-comparison requests get their usual answers.
 */
public class XTestExtension implements Extension {
    public static final byte MAJOR_OPCODE = -109;
    private static final byte FIRST_EVENT_ID = 0;
    private static final byte FIRST_ERROR_ID = 0;

    private static final int GET_VERSION = 0;
    private static final int COMPARE_CURSOR = 1;
    private static final int FAKE_INPUT = 2;

    private static final int KEY_PRESS = 2;
    private static final int KEY_RELEASE = 3;
    private static final int BUTTON_PRESS = 4;
    private static final int BUTTON_RELEASE = 5;
    private static final int MOTION_NOTIFY = 6;

    private final XServer xServer;

    public XTestExtension(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public String getName() {
        return "XTEST";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR_ID;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT_ID;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int minor = client.getRequestData() & 0xff;
        switch (minor) {
            case GET_VERSION:
                inputStream.skip(4);   // major version, pad, minor version
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)2);               // server major
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeShort((short)2);             // server minor
                    outputStream.writePad(22);
                }
                break;
            case COMPARE_CURSOR:
                inputStream.skip(8);   // window, cursor
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);               // not the same
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writePad(24);
                }
                break;
            case FAKE_INPUT: {
                int type = inputStream.readByte() & 0x7f;
                int detail = inputStream.readByte() & 0xff;
                inputStream.skip(2);   // pad
                inputStream.skip(4);   // delay
                inputStream.skip(4);   // root window
                inputStream.skip(8);   // pad
                short x = inputStream.readShort();
                short y = inputStream.readShort();
                inputStream.skip(7);   // pad
                inputStream.skip(1);   // device id
                fakeInput(type, detail, x, y);
                break;
            }
            default:
                // GrabControl and anything else without an answer.
                client.skipRequest();
        }
    }

    private void fakeInput(int type, int detail, short x, short y) {
        if (com.winlator.cmod.xserver.XClientRequestHandler.keyTrace) {
            com.winlator.cmod.xserver.Window focused = xServer.windowManager.getFocusedWindow();
            android.util.Log.d("XKeys", "XTEST type=" + type + " detail=" + detail + " focus=" + (focused != null ? focused.id : 0)
                    + " point=" + xServer.inputDeviceManager.getPointWindow().id);
        }
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            switch (type) {
                case KEY_PRESS:
                    xServer.keyboard.setKeyPress((byte) detail, 0);
                    break;
                case KEY_RELEASE:
                    xServer.keyboard.setKeyRelease((byte) detail);
                    break;
                case BUTTON_PRESS:
                case BUTTON_RELEASE: {
                    Pointer.Button[] buttons = Pointer.Button.values();
                    if (detail >= 1 && detail <= buttons.length) xServer.pointer.setButton(buttons[detail - 1], type == BUTTON_PRESS);
                    break;
                }
                case MOTION_NOTIFY:
                    // The detail says whether the position is relative to where the pointer is (1) or on the screen (0).
                    if (detail == 1) xServer.pointer.setPosition(xServer.pointer.getX() + x, xServer.pointer.getY() + y);
                    else xServer.pointer.setPosition(x, y);
                    break;
                default:
                    break;
            }
        }
    }
}
