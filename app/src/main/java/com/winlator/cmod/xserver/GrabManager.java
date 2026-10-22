package com.winlator.cmod.xserver;

import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.PointerWindowEvent;

public class GrabManager implements WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    /** The modifiers value of a grab that matches whatever modifiers are held. */
    public static final int ANY_MODIFIER = 0x8000;
    private Window window;
    private boolean ownerEvents;
    private boolean releaseWithButtons;
    private EventListener eventListener;
    private final XServer xServer;

    public GrabManager(XServer xServer) {
        this.xServer = xServer;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
    }

    /**
     * A button or key a client (a window manager) grabs on a window for good: when it is pressed with the modifiers held, over the
     * window or under it, the press goes to the grabbing client first. With a synchronous pointer mode the pointer then stays frozen
     * until the client says what to do with the press (AllowEvents), for example to hand it on to the window that was clicked.
     */
    public static final class PassiveGrab {
        public final Window window;
        public final XClient client;
        public final boolean ownerEvents;
        public final Bitmask eventMask;
        public final boolean pointerSync;
        /** A button (1..5) or a key code; 0 for any. */
        public final int code;
        public final int modifiers;

        PassiveGrab(Window window, XClient client, boolean ownerEvents, Bitmask eventMask, boolean pointerSync, int code, int modifiers) {
            this.window = window;
            this.client = client;
            this.ownerEvents = ownerEvents;
            this.eventMask = eventMask;
            this.pointerSync = pointerSync;
            this.code = code;
            this.modifiers = modifiers;
        }

        boolean matches(int pressed, int held) {
            return (code == 0 || code == pressed) && (modifiers == ANY_MODIFIER || modifiers == held);
        }
    }

    private final java.util.List<PassiveGrab> buttonGrabs = new java.util.ArrayList<>();
    private final java.util.List<PassiveGrab> keyGrabs = new java.util.ArrayList<>();

    public synchronized void addButtonGrab(Window window, XClient client, boolean ownerEvents, Bitmask eventMask, boolean pointerSync, int button, int modifiers) {
        removeGrab(buttonGrabs, window, button, modifiers);
        buttonGrabs.add(new PassiveGrab(window, client, ownerEvents, eventMask, pointerSync, button, modifiers));
    }

    public synchronized void removeButtonGrab(Window window, int button, int modifiers) {
        removeGrab(buttonGrabs, window, button, modifiers);
    }

    public synchronized void addKeyGrab(Window window, XClient client, boolean ownerEvents, int key, int modifiers) {
        removeGrab(keyGrabs, window, key, modifiers);
        keyGrabs.add(new PassiveGrab(window, client, ownerEvents, new Bitmask(), false, key, modifiers));
    }

    public synchronized void removeKeyGrab(Window window, int key, int modifiers) {
        removeGrab(keyGrabs, window, key, modifiers);
    }

    /** UngrabButton / UngrabKey name AnyButton / AnyKey (0) and AnyModifier as wildcards for what to release. */
    private static void removeGrab(java.util.List<PassiveGrab> grabs, Window window, int code, int modifiers) {
        for (int i = grabs.size() - 1; i >= 0; i--) {
            PassiveGrab grab = grabs.get(i);
            if (grab.window == window && (code == 0 || grab.code == code) && (modifiers == ANY_MODIFIER || grab.modifiers == modifiers)) grabs.remove(i);
        }
    }

    /** The grab on the window, or the first one above it, that a press of the button with these modifiers falls under: the one nearest the root wins. */
    public synchronized PassiveGrab findButtonGrab(Window under, int button, int held) {
        return find(buttonGrabs, under, button, held);
    }

    public synchronized PassiveGrab findKeyGrab(Window under, int key, int held) {
        return find(keyGrabs, under, key, held);
    }

    private static PassiveGrab find(java.util.List<PassiveGrab> grabs, Window under, int code, int held) {
        if (grabs.isEmpty() || under == null) return null;
        java.util.ArrayList<Window> chain = new java.util.ArrayList<>();
        for (Window window = under; window != null; window = window.getParent()) chain.add(0, window);
        for (Window window : chain) {
            for (PassiveGrab grab : grabs) {
                if (grab.window == window && grab.matches(code, held)) return grab;
            }
        }
        return null;
    }

    public synchronized void removeGrabsOf(XClient client) {
        buttonGrabs.removeIf(grab -> grab.client == client);
        keyGrabs.removeIf(grab -> grab.client == client);
    }

    @Override
    public synchronized void onFreeResource(XResource resource) {
        buttonGrabs.removeIf(grab -> grab.window == resource);
        keyGrabs.removeIf(grab -> grab.window == resource);
    }

    /** A passive grab fires: it becomes the active pointer grab until every button is up. */
    public void activatePassiveGrab(PassiveGrab grab) {
        activatePointerGrab(grab.window, new EventListener(grab.client, grab.eventMask), grab.ownerEvents, true);
    }

    @Override
    public void onUnmapWindow(Window window) {
        if (window != null && window.getMapState() != Window.MapState.VIEWABLE) {
            deactivatePointerGrab();
        }
    }

    public Window getWindow() {
        return window;
    }

    public boolean isOwnerEvents() {
        return ownerEvents;
    }

    public boolean isReleaseWithButtons() {
        return releaseWithButtons;
    }

    public EventListener getEventListener() {
        return eventListener;
    }

    public XClient getClient() {
        return eventListener != null ? eventListener.client : null;
    }

    public void deactivatePointerGrab() {
        if (window != null) {
            xServer.inputDeviceManager.sendEnterLeaveNotify(window, xServer.inputDeviceManager.getPointWindow(), PointerWindowEvent.Mode.UNGRAB);
            window = null;
            eventListener = null;
        }
    }

    private void activatePointerGrab(Window window, EventListener eventListener, boolean ownerEvents, boolean releaseWithButtons) {
        if (this.window == null) {
            xServer.inputDeviceManager.sendEnterLeaveNotify(xServer.inputDeviceManager.getPointWindow(), window, PointerWindowEvent.Mode.GRAB);
        }
        this.window = window;
        this.releaseWithButtons = releaseWithButtons;
        this.ownerEvents = ownerEvents;
        this.eventListener = eventListener;
    }

    public void activatePointerGrab(Window window, boolean ownerEvents, Bitmask eventMask, XClient client) {
        activatePointerGrab(window, new EventListener(client, eventMask), ownerEvents, false);
    }

    public void activatePointerGrab(Window window) {
        EventListener eventListener = window.getButtonPressListener();
        activatePointerGrab(window, eventListener, eventListener.isInterestedIn(Event.OWNER_GRAB_BUTTON), true);
    }
}
