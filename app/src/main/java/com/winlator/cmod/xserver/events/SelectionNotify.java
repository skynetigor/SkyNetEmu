package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;

/** Tells a requestor that its selection could not be converted (property None); an owner that can sends this itself with SendEvent. */
public class SelectionNotify extends Event {
    private final int timestamp;
    private final int requestor;
    private final int selection;
    private final int target;
    private final int property;

    public SelectionNotify(int timestamp, int requestor, int selection, int target, int property) {
        super(31);
        this.timestamp = timestamp;
        this.requestor = requestor;
        this.selection = selection;
        this.target = target;
        this.property = property;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(timestamp);
            outputStream.writeInt(requestor);
            outputStream.writeInt(selection);
            outputStream.writeInt(target);
            outputStream.writeInt(property);
            outputStream.writePad(8);
        }
    }
}
