package com.winlator.cmod.xserver.extensions;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.events.Event;
import java.io.IOException;

/** XI 2.2 wire events. Coordinates remain fractional; fingers never carry pointer-emulation flags. */
public final class XITouchEvent extends Event {
    public final int type, device, detail, time, root, window, child, flags, modifiers;
    public final double x, y, localX, localY;
    public XITouchEvent(int type, int device, int detail, int time, int root, int window, int child,
                        double x, double y, double localX, double localY, int flags, int modifiers) {
        super((byte)35);
        this.type = type; this.device = device; this.detail = detail; this.time = time;
        this.root = root; this.window = window; this.child = child;
        this.x = x; this.y = y; this.localX = localX; this.localY = localY;
        this.flags = flags; this.modifiers = modifiers;
    }
    @Override public void send(short sequence, XOutputStream out) throws IOException {
        boolean raw = type >= 22 && type <= 24;
        boolean ownership = type == 21;
        try (XStreamLock ignored = out.lock()) {
            out.writeByte((byte)35); out.writeByte(XInput2Extension.MAJOR_OPCODE);
            out.writeShort(sequence);
            out.writeInt(raw ? 9 : ownership ? 4 : 17);
            out.writeShort((short)type); out.writeShort((short)device);
            out.writeInt(time); out.writeInt(detail);
            if (raw) {
                out.writeShort((short)XInput2Touch.DEVICE); out.writeShort((short)1);
                out.writeInt(0); out.writeInt(0);
                out.writeInt(3); // absolute X and Y
                out.writeFP3232(x); out.writeFP3232(y);
                out.writeFP3232(x); out.writeFP3232(y);
                return;
            }
            out.writeInt(root); out.writeInt(window); out.writeInt(child);
            if (ownership) {
                out.writeShort((short)XInput2Touch.DEVICE); out.writeShort((short)0);
                out.writeInt(flags); out.writePad(8);
                return;
            }
            out.writeInt((int)Math.round(x * 65536)); out.writeInt((int)Math.round(y * 65536));
            out.writeInt((int)Math.round(localX * 65536)); out.writeInt((int)Math.round(localY * 65536));
            out.writeShort((short)0); out.writeShort((short)1); // no buttons, one valuator-mask word
            out.writeShort((short)XInput2Touch.DEVICE); out.writeShort((short)0);
            out.writeInt(flags);
            out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(modifiers);
            out.writeInt(0); // group
            out.writeInt(3); out.writeFP3232(x); out.writeFP3232(y);
        }
    }
}
