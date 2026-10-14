package com.winlator.cmod.xserver;

import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.events.ButtonPress;
import com.winlator.cmod.xserver.events.ButtonRelease;
import com.winlator.cmod.xserver.events.EnterNotify;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.FocusEvent;
import com.winlator.cmod.xserver.events.KeyPress;
import com.winlator.cmod.xserver.events.KeyRelease;
import com.winlator.cmod.xserver.events.LeaveNotify;
import com.winlator.cmod.xserver.events.MappingNotify;
import com.winlator.cmod.xserver.events.MotionNotify;
import com.winlator.cmod.xserver.events.PointerWindowEvent;
import com.winlator.cmod.xserver.extensions.XInput2Extension;
import java.util.ArrayList;

public class InputDeviceManager implements Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private Window pointWindow;
    private final XServer xServer;
    /** A synchronous passive grab holds the pointer until the grabbing client says what to do with the press (AllowEvents). */
    private boolean pointerFrozen;
    private Pointer.Button frozenButton;
    private boolean frozenReleased;
    /** The client a pressed key was given to by a key grab, so that its release goes there too. */
    private int freezeSerial;
    private final java.util.HashMap<Integer, XClient> keyGrabClients = new java.util.HashMap<>();

    public InputDeviceManager(XServer xServer) {
        this.xServer = xServer;
        pointWindow = xServer.windowManager.rootWindow;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.keyboard.addOnKeyboardListener(this);
        xServer.windowManager.setOnFocusChangeListener(this::onFocusChanged);
    }

    /** Toolkits such as SDL wait for FocusIn / EnterNotify before they take input; Wine does not need them. */
    private boolean sendsFocusAndCrossing() {
        return xi2() != null;
    }

    // The details of a focus event (FocusIn / FocusOut and XI2's focus events): where the focus came from or goes to, seen from the window.
    private static final byte FOCUS_ANCESTOR = 0, FOCUS_VIRTUAL = 1, FOCUS_INFERIOR = 2, FOCUS_NONLINEAR = 3, FOCUS_NONLINEAR_VIRTUAL = 4;

    private static boolean isStrictAncestor(Window ancestor, Window window) {
        for (Window w = window == null ? null : window.getParent(); w != null; w = w.getParent()) if (w == ancestor) return true;
        return false;
    }

    private void sendFocusEvent(boolean in, Window window, byte detail) {
        if (window == null || window == xServer.windowManager.rootWindow || !window.attributes.isEnabled()) return;
        if (com.winlator.cmod.xserver.XClientRequestHandler.keyTrace) {
            android.util.Log.d("XKeys", "focus " + (in ? "in" : "out") + " window=" + window.id + " detail=" + detail);
        }
        window.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(in, detail, window, FocusEvent.MODE_NORMAL));
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onFocus(in, window, detail);
    }

    /**
     * A desktop's toolkits care about where the focus moved: GTK puts the focus on a hidden child of its window, and the window itself
     * must hear "focus moved into me" (Virtual / Inferior), not "I lost it" (Nonlinear), or it takes itself for unfocused and ignores typing.
     * The events are the ones the X protocol defines for a focus change between two windows of the tree.
     */
    private void onFocusChangedDesktop(Window oldFocus, Window newFocus) {
        Window root = xServer.windowManager.rootWindow;
        if (oldFocus == null) oldFocus = root;
        if (newFocus == null) newFocus = root;
        if (oldFocus == newFocus) return;
        if (isStrictAncestor(oldFocus, newFocus)) {
            sendFocusEvent(false, oldFocus, FOCUS_INFERIOR);
            java.util.ArrayList<Window> between = new java.util.ArrayList<>();
            for (Window w = newFocus.getParent(); w != null && w != oldFocus; w = w.getParent()) between.add(0, w);
            for (Window w : between) sendFocusEvent(true, w, FOCUS_VIRTUAL);
            sendFocusEvent(true, newFocus, FOCUS_ANCESTOR);
        }
        else if (isStrictAncestor(newFocus, oldFocus)) {
            sendFocusEvent(false, oldFocus, FOCUS_ANCESTOR);
            for (Window w = oldFocus.getParent(); w != null && w != newFocus; w = w.getParent()) sendFocusEvent(false, w, FOCUS_VIRTUAL);
            sendFocusEvent(true, newFocus, FOCUS_INFERIOR);
        }
        else {
            Window common = null;
            for (Window w = oldFocus.getParent(); w != null && common == null; w = w.getParent()) if (isStrictAncestor(w, newFocus)) common = w;
            sendFocusEvent(false, oldFocus, FOCUS_NONLINEAR);
            for (Window w = oldFocus.getParent(); w != null && w != common; w = w.getParent()) sendFocusEvent(false, w, FOCUS_NONLINEAR_VIRTUAL);
            java.util.ArrayList<Window> down = new java.util.ArrayList<>();
            for (Window w = newFocus.getParent(); w != null && w != common; w = w.getParent()) down.add(0, w);
            for (Window w : down) sendFocusEvent(true, w, FOCUS_NONLINEAR_VIRTUAL);
            sendFocusEvent(true, newFocus, FOCUS_NONLINEAR);
        }
        if (newFocus != root && newFocus.attributes.isEnabled()) newFocus.sendEvent(Event.KEYMAP_STATE, new com.winlator.cmod.xserver.events.KeymapNotify());
    }

    private void onFocusChanged(Window oldFocus, Window newFocus) {
        if (!sendsFocusAndCrossing()) return;
        if (xServer.desktopSession) {
            onFocusChangedDesktop(oldFocus, newFocus);
            return;
        }
        if (com.winlator.cmod.xserver.XClientRequestHandler.trace) {
            android.util.Log.d("XTrace", "focus " + (oldFocus != null ? oldFocus.id : 0) + " -> " + (newFocus != null ? newFocus.id : 0)
                    + " pointWindow=" + (pointWindow != null ? pointWindow.id : 0));
        }
        XInput2Extension xi2 = xi2();
        if (oldFocus == xServer.windowManager.rootWindow && pointWindow != null && pointWindow != xServer.windowManager.rootWindow
                && pointWindow.attributes.isEnabled()) {
            // Moving the focus from PointerRoot: the window under the pointer is told it loses it (detail Pointer), as a real server does.
            pointWindow.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(false, (byte)5, pointWindow, FocusEvent.MODE_NORMAL));
        }
        if (oldFocus != null && oldFocus != xServer.windowManager.rootWindow && oldFocus.attributes.isEnabled()) {
            oldFocus.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(false, FocusEvent.DETAIL_NONLINEAR, oldFocus, FocusEvent.MODE_NORMAL));
            xi2.onFocus(false, oldFocus);
        }
        if (newFocus != null && newFocus != xServer.windowManager.rootWindow && newFocus.attributes.isEnabled()) {
            sendFocusIn(newFocus);
            repeatFocusIn(newFocus);
        }
    }

    private java.util.concurrent.ScheduledExecutorService focusExecutor;

    /**
     * The focus is handed to a window the moment it is mapped, which is a moment too early: a program maps its
     * window and asks for focus events in whichever order its toolkit likes, and SDL's ask lands second, so the
     * one announcement it needed went to nobody. Measured on the device: without this, an SDL program never sees
     * it gain the focus, and SDL drops every key. So it is said again over the next seconds, and a repeat does no
     * harm to a program that heard the first one.
     */
    private void repeatFocusIn(final Window window) {
        if (focusExecutor == null) {
            focusExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "FocusRepeat");
                thread.setDaemon(true);
                return thread;
            });
        }
        for (int delay : new int[]{200, 600, 1500, 4000}) {
            focusExecutor.schedule(() -> {
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                    // Only while it still holds the focus: a later window of its own must not be talked over.
                    if (xServer.windowManager.getFocusedWindow() != window || !window.attributes.isEnabled()) return;
                    sendFocusIn(window);
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private void sendFocusIn(Window window) {
        if (com.winlator.cmod.xserver.XClientRequestHandler.keyTrace) android.util.Log.d("XKeys", "core FocusIn to " + window.id);
        window.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(true, FocusEvent.DETAIL_NONLINEAR, window, FocusEvent.MODE_NORMAL));
        window.sendEvent(Event.KEYMAP_STATE, new com.winlator.cmod.xserver.events.KeymapNotify());
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onFocus(true, window);
    }

    @Override
    public void onMapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUnmapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        updatePointWindow();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        updatePointWindow();
    }

    @Override
    public void onCreateResource(XResource resource) {
        updatePointWindow();
    }

    @Override
    public void onFreeResource(XResource resource) {
        updatePointWindow();
    }

    private void updatePointWindow() {
        Window pointWindow = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
        Window previous = this.pointWindow;
        this.pointWindow = pointWindow != null ? pointWindow : xServer.windowManager.rootWindow;
        if (previous != null && previous != this.pointWindow && sendsFocusAndCrossing()) {
            sendEnterLeaveNotify(previous, this.pointWindow, PointerWindowEvent.Mode.NORMAL);
        }
    }

    public Window getPointWindow() {
        return pointWindow;
    }

    private void sendEvent(Window window, int eventId, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventId, event, xServer.grabManager.getClient());
            }
            else if (eventListener.isInterestedIn(eventId)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventId, event);
        }
    }

    private void sendEvent(Window window, Bitmask eventMask, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventMask, event, eventListener.client);
            }
            else if (eventListener.isInterestedIn(eventMask)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventMask, event);
        }
    }

    public void sendEnterLeaveNotify(Window windowA, Window windowB, PointerWindowEvent.Mode mode) {
        if (windowA == windowB) return;
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        short[] localPointA = windowA.rootPointToLocal(x, y);
        short[] localPointB = windowB.rootPointToLocal(x, y);

        boolean sameScreenAndFocus = windowB.isAncestorOf(xServer.windowManager.getFocusedWindow());
        PointerWindowEvent.Detail detailA = PointerWindowEvent.Detail.NONLINEAR;
        PointerWindowEvent.Detail detailB = PointerWindowEvent.Detail.NONLINEAR;

        // X11: leaving a window for its inferior sends Leave(detail Inferior) to the outer one and Enter(detail Ancestor)
        // to the inner one. The Wine-oriented code below has them the other way round; toolkits (SDL) ignore an Enter
        // with detail Inferior, so Linux sessions use the correct pairing.
        boolean correct = sendsFocusAndCrossing();
        if (windowA.isAncestorOf(windowB)) {
            detailA = correct ? PointerWindowEvent.Detail.INFERIOR : PointerWindowEvent.Detail.ANCESTOR;
            detailB = correct ? PointerWindowEvent.Detail.ANCESTOR : PointerWindowEvent.Detail.INFERIOR;
        }
        else if (windowB.isAncestorOf(windowA)) {
            detailB = correct ? PointerWindowEvent.Detail.INFERIOR : PointerWindowEvent.Detail.ANCESTOR;
            detailA = correct ? PointerWindowEvent.Detail.ANCESTOR : PointerWindowEvent.Detail.INFERIOR;
        }

        Bitmask keyButMask = getKeyButMask();
        sendEvent(windowA, Event.LEAVE_WINDOW, new LeaveNotify(detailA, xServer.windowManager.rootWindow, windowA, null, x, y, localPointA[0], localPointA[1], keyButMask, mode, sameScreenAndFocus));
        sendEvent(windowB, Event.ENTER_WINDOW, new EnterNotify(detailB, xServer.windowManager.rootWindow, windowB, null, x, y, localPointB[0], localPointB[1], keyButMask, mode, sameScreenAndFocus));
        if (sendsFocusAndCrossing()) windowB.sendEvent(Event.KEYMAP_STATE, new com.winlator.cmod.xserver.events.KeymapNotify());
        XInput2Extension xi2 = xi2();
        if (xi2 != null) {
            xi2.onCrossing(false, windowA, detailA.ordinal(), mode.ordinal());
            xi2.onCrossing(true, windowB, detailB.ordinal(), mode.ordinal());
        }
    }

    private XInput2Extension xi2() {
        return xServer.getExtension(XInput2Extension.MAJOR_OPCODE);
    }

    @Override
    public void onPointerButtonPress(Pointer.Button button) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onButton(button, true);
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            int wheelDelta = button == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (button == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0);
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, true), 0, 0, wheelDelta);
        }
        else {
            corePress(button, true);
        }
    }

    /**
     * The core ButtonPress. With {@code allowPassive} a window manager's passive grab on the way down to the pointer takes it first
     * (a desktop only); a press handed back with ReplayPointer comes here again without that.
     */
    private void corePress(Pointer.Button button, boolean allowPassive) {
        if (allowPassive && xServer.desktopSession && xServer.grabManager.getWindow() == null) {
            GrabManager.PassiveGrab grab = xServer.grabManager.findButtonGrab(pointWindow, button.code(), getKeyButMask().getBits() & 0xff);
            if (grab != null && grab.window.attributes.isEnabled()) {
                xServer.grabManager.activatePassiveGrab(grab);
                if (grab.pointerSync) {
                    pointerFrozen = true;
                    frozenButton = button;
                    frozenReleased = false;
                    scheduleThaw(++freezeSerial);
                }
                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();
                short[] localPoint = grab.window.rootPointToLocal(x, y);
                Window child = grab.window.isAncestorOf(pointWindow) ? pointWindow : null;
                Bitmask state = getKeyButMask();
                state.unset(button.flag());
                grab.client.sendEvent(new ButtonPress(button.code(), xServer.windowManager.rootWindow, grab.window, child, x, y, localPoint[0], localPoint[1], state));
                return;
            }
        }
            Window grabWindow = xServer.grabManager.getWindow();
            if (grabWindow == null) {
                grabWindow = pointWindow.getAncestorWithEventId(Event.BUTTON_PRESS);
                if (grabWindow != null) xServer.grabManager.activatePointerGrab(grabWindow);
            }

            if (grabWindow != null && grabWindow.attributes.isEnabled()) {
                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();
                short[] localPoint = grabWindow.rootPointToLocal(x, y);

                Window child = grabWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                // The state of a ButtonPress is the keys and buttons held before it (not the event-selection mask used above).
                Bitmask state = getKeyButMask();
                state.unset(button.flag());
                grabWindow.sendEvent(Event.BUTTON_PRESS, new ButtonPress(button.code(), xServer.windowManager.rootWindow, grabWindow, child, x, y, localPoint[0], localPoint[1], state));
            }
    }

    @Override
    public void onPointerButtonRelease(Pointer.Button button) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onButton(button, false);
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, false), 0, 0, 0);
        }
        else {
            if (pointerFrozen) {
                frozenReleased = true;
                return;
            }
            coreRelease(button);
        }
    }

    private void coreRelease(Pointer.Button button) {
            // A ButtonRelease goes where its ButtonPress went, and is chosen by the window's interest in ButtonRelease.
            // It used to be looked up, and delivered, by the pointer-motion mask: a program that reads motion some
            // other way (BeamNG takes it from XInput2) selects no motion at all, and then the press arrived while the
            // release was dropped. A menu acts on the release, so every click did nothing.
            Window grabWindow = xServer.grabManager.getWindow();
            Window window = grabWindow == null || xServer.grabManager.isOwnerEvents()
                    ? pointWindow.getAncestorWithEventId(Event.BUTTON_RELEASE) : null;

            if (grabWindow != null || window != null) {
                Window eventWindow = window != null ? window : grabWindow;

                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();
                short[] localPoint = eventWindow.rootPointToLocal(x, y);

                Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                // ... and for a ButtonRelease the buttons held before it, which still include this one.
                Bitmask state = getKeyButMask();
                state.set(button.flag());
                ButtonRelease buttonRelease = new ButtonRelease(button.code(), xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], state);
                sendEvent(window, Event.BUTTON_RELEASE, buttonRelease);
            }

            if (xServer.pointer.getButtonMask().isEmpty() && xServer.grabManager.isReleaseWithButtons()) {
                xServer.grabManager.deactivatePointerGrab();
            }
    }

    /**
     * AllowEvents for the pointer: ends a freeze. ReplayPointer (2) takes the press back from the grab and delivers it as if the grab were
     * not there, which is how a window manager that grabbed a click to focus the window hands it on to the window.
     */
    /** A window manager that never answers must not leave the pointer frozen for good: after two seconds the press is replayed. */
    private void scheduleThaw(final int serial) {
        if (focusExecutor == null) {
            focusExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "FocusRepeat");
                thread.setDaemon(true);
                return thread;
            });
        }
        focusExecutor.schedule(() -> {
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                if (pointerFrozen && freezeSerial == serial) allowPointerEvents(2);
            }
        }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void allowPointerEvents(int mode) {
        if (!pointerFrozen) return;
        Pointer.Button button = frozenButton;
        boolean released = frozenReleased;
        pointerFrozen = false;
        frozenButton = null;
        frozenReleased = false;
        if (mode == 2) {
            xServer.grabManager.deactivatePointerGrab();
            if (button != null) corePress(button, false);
        }
        if (released && button != null) coreRelease(button);
    }

    @Override
    public void onPointerMove(short x, short y) {
        updatePointWindow();
        if (pointerFrozen) return;
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onMotion(x, y);
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = xServer.grabManager.getWindow();
        Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

        if (grabWindow != null || window != null) {
            Window eventWindow = window != null ? window : grabWindow;
            short[] localPoint = eventWindow.rootPointToLocal(x, y);

            Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
            sendEvent(window, eventMask, new MotionNotify(false, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], getKeyButMask()));
        }
    }

    @Override
    public void onKeyPress(byte keycode, int keysym) {
        if (com.winlator.cmod.xserver.XClientRequestHandler.keyTrace) {
            Window focus = xServer.windowManager.getFocusedWindow();
            android.util.Log.d("XKeys", "key press " + (keycode & 0xff) + " focus=" + (focus != null ? focus.id : 0) + " point=" + (pointWindow != null ? pointWindow.id : 0));
        }
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onKey(keycode, true);
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        if (xServer.desktopSession) {
            // A window manager's key grab (a shortcut) takes the key before the focused window sees it.
            GrabManager.PassiveGrab grab = xServer.grabManager.findKeyGrab(focusedWindow, keycode & 0xff,
                    xServer.keyboard.getModifiersMask().getBits() & 0xff);
            if (grab != null && grab.window.attributes.isEnabled()) {
                keyGrabClients.put(keycode & 0xff, grab.client);
                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();
                short[] localPoint = grab.window.rootPointToLocal(x, y);
                grab.client.sendEvent(new KeyPress(keycode, xServer.windowManager.rootWindow, grab.window, null, x, y, localPoint[0], localPoint[1], getKeyButMask()));
                return;
            }
        }

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_PRESS, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_PRESS)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);

        if (keysym != 0 && !xServer.keyboard.hasKeysym(keycode, keysym)) {
            xServer.keyboard.setKeysyms(keycode, keysym, keysym);
            eventWindow.sendEvent(new MappingNotify(MappingNotify.Request.KEYBOARD, keycode, 1));
        }

        eventWindow.sendEvent(Event.KEY_PRESS, new KeyPress(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    @Override
    public void onKeyRelease(byte keycode) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onKey(keycode, false);
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        XClient grabbed = keyGrabClients.remove(keycode & 0xff);
        if (grabbed != null) {
            short x = xServer.pointer.getX();
            short y = xServer.pointer.getY();
            grabbed.sendEvent(new KeyRelease(keycode, xServer.windowManager.rootWindow, focusedWindow, null, x, y, x, y, getKeyButMask()));
            return;
        }

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_RELEASE, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_RELEASE)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);

        eventWindow.sendEvent(Event.KEY_RELEASE, new KeyRelease(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    private Bitmask createPointerEventMask() {
        Bitmask eventMask = new Bitmask();
        eventMask.set(Event.POINTER_MOTION);

        Bitmask buttonMask = xServer.pointer.getButtonMask();
        if (!buttonMask.isEmpty()) {
            eventMask.set(Event.BUTTON_MOTION);

            if (buttonMask.isSet(Pointer.Button.BUTTON_LEFT.flag())) {
                eventMask.set(Event.BUTTON1_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_MIDDLE.flag())) {
                eventMask.set(Event.BUTTON2_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_RIGHT.flag())) {
                eventMask.set(Event.BUTTON3_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_UP.flag())) {
                eventMask.set(Event.BUTTON4_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_DOWN.flag())) {
                eventMask.set(Event.BUTTON5_MOTION);
            }
        }
        return eventMask;
    }

    public Bitmask getKeyButMask() {
        Bitmask keyButMask = new Bitmask();
        keyButMask.join(xServer.pointer.getButtonMask());
        keyButMask.join(xServer.keyboard.getModifiersMask());
        return keyButMask;
    }
}
