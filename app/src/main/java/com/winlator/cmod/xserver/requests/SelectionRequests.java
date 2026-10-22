package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadAtom;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.SelectionNotify;
import com.winlator.cmod.xserver.events.SelectionRequest;

import java.io.IOException;

public abstract class SelectionRequests {
    public static void setSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int atom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window owner = client.xServer.windowManager.getWindow(windowId);
        if (owner == null) throw new BadWindow(windowId);
        if (!Atom.isValid(atom)) throw new BadAtom(atom);

        client.xServer.selectionManager.setSelection(atom, owner, client, timestamp);
    }

    public static void getSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int atom = inputStream.readInt();
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        Window owner = client.xServer.selectionManager.getSelection(atom).owner;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(owner != null ? owner.id : 0);
            outputStream.writePad(20);
        }
    }

    /**
     * ConvertSelection: the owner is asked (SelectionRequest) to put the selection, as the target, into the property of the
     * requestor, and to say so with a SelectionNotify; with no owner the requestor is told at once that there is nothing (property None).
     * This is what a paste is.
     */
    public static void convertSelection(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int requestorId = inputStream.readInt();
        int selectionAtom = inputStream.readInt();
        int target = inputStream.readInt();
        int property = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window requestor = client.xServer.windowManager.getWindow(requestorId);
        if (requestor == null) throw new BadWindow(requestorId);
        if (!Atom.isValid(selectionAtom)) throw new BadAtom(selectionAtom);

        com.winlator.cmod.xserver.SelectionManager.Selection selection = client.xServer.selectionManager.getSelection(selectionAtom);
        if (selection.owner != null && selection.getClient() != null) {
            selection.getClient().sendEvent(new SelectionRequest(timestamp, selection.owner.id, requestorId, selectionAtom, target, property));
        }
        else client.sendEvent(new SelectionNotify(timestamp, requestorId, selectionAtom, target, 0));
    }
}
