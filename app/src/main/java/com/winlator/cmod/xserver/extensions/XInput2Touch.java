package com.winlator.cmod.xserver.extensions;

import com.winlator.cmod.xserver.*;
import com.winlator.cmod.xserver.errors.*;
import java.util.*;

/** Direct-touch state, independent of the pointer and its button/gesture recognizer. */
public final class XInput2Touch implements AutoCloseable {
    public static final int DEVICE = 6, CAPACITY = 32;
    private final XInput2Extension extension;
    private final XServer server;
    private final Map<Integer, Contact> contacts = new LinkedHashMap<>();
    private final List<Grab> grabs = new ArrayList<>();
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private int nextId = 1;
    private volatile boolean enabled = true, closed;
    // Existing core events use wall-clock milliseconds. Offset monotonic Android samples once per session.
    private final long clockOffset = System.currentTimeMillis() - android.os.SystemClock.uptimeMillis();
    private long lastTime;
    private volatile double lastX, lastY;
    double axisValue(int axis) { return axis == 0 ? lastX : lastY; }

    private static final class Job {
        final int updateId;
        final Runnable action;
        Job(int updateId, Runnable action) { this.updateId = updateId; this.action = action; }
    }
    static final class Grab {
        final XClient client;
        final Window window;
        final int device, modifiers;
        final byte[] mask;
        final boolean ownerEvents;
        Grab(XClient c, Window w, int d, int m, byte[] mask, boolean ownerEvents) {
            client = c; window = w; device = d; modifiers = m; this.mask = mask.clone(); this.ownerEvents = ownerEvents;
        }
    }
    private static final class Listener {
        final XClient client;
        final Window window;
        final int device;
        final boolean grab;
        boolean ownership;
        boolean begun, ended, endSent, accepted;
        Window grabWindow;
        int child;
        Listener(XClient c, Window w, int d, boolean g, byte[] mask) {
            client = c; window = w; grabWindow = w; device = d; grab = g; ownership = bit(mask, 21);
        }
    }
    private static final class Contact {
        final int id, androidId;
        final List<Listener> listeners = new ArrayList<>();
        double x, y, beginX, beginY;
        int time, beginTime;
        boolean physicalEnd;
        Window target;
        Contact(int id, int androidId) { this.id = id; this.androidId = androidId; }
        Listener owner(int device) { for (Listener l : listeners) if (l.device == device && !l.ended) return l; return null; }
        boolean finished() { for (Listener l : listeners) if (!l.ended) return false; return true; }
    }
    XInput2Touch(XInput2Extension extension, XServer server) {
        this.extension = extension; this.server = server;
        Thread worker = new Thread(() -> {
            while (true) {
                Job job;
                synchronized (jobs) {
                    try { while (jobs.isEmpty() && !closed) jobs.wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    if (closed) return;
                    job = jobs.removeFirst();
                }
                job.action.run();
            }
        }, "X11-touch-input");
        worker.setDaemon(true); worker.start();
    }
    private void post(int updateId, Runnable action) {
        synchronized (jobs) {
            if (closed) return;
            // Replace only an adjacent update. Never cross a lifecycle/ownership/mode barrier.
            if (updateId >= 0) {
                Iterator<Job> previous = jobs.descendingIterator();
                while (previous.hasNext()) {
                    Job job = previous.next();
                    if (job.updateId < 0) break;
                    if (job.updateId == updateId) { previous.remove(); break; }
                }
            }
            if (jobs.size() >= 2048) jobs.removeIf(j -> j.updateId >= 0);
            if (jobs.size() >= 4096) {
                // Explicitly cancel on overload rather than retain unbounded gestures or stranded contacts.
                jobs.clear(); jobs.add(new Job(-1, this::cancelNow));
                android.util.Log.w("XInput2Touch", "Input overflow: cancelled pending gestures");
            }
            jobs.addLast(new Job(updateId, action)); jobs.notifyAll();
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) {
        enabled = value;
        post(-1, () -> { cancelNow(); extension.touchHierarchy(value); });
    }
    public void sample(int type, int androidId, double x, double y, long uptime) {
        post(type == 19 ? androidId : -1, () -> process(type, androidId, x, y, uptime));
    }
    public void cancel() { post(-1, this::cancelNow); }
    private synchronized void cancelNow() {
        for (Contact c : contacts.values()) {
            c.time = timestamp(android.os.SystemClock.uptimeMillis());
            for (Listener l : c.listeners) end(c, l);
            if (!c.physicalEnd) raw(c, 24);
        }
        contacts.clear();
    }
    private int timestamp(long uptime) {
        lastTime = Math.max(lastTime, clockOffset + uptime);
        return (int)lastTime;
    }
    private void process(int type, int androidId, double x, double y, long uptime) {
        // Same lock order as requests and resource cleanup. Output is only enqueued here, never written to a socket.
        try (XLock ignored = server.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window target = type == 18 && enabled ? server.windowManager.findPointWindow((short)x, (short)y) : null;
            synchronized (this) {
                Contact c = null;
                for (Contact active : contacts.values()) if (active.androidId == androidId && !active.physicalEnd) { c = active; break; }
                if (type == 18) {
                    if (c != null) {
                        c.time = timestamp(uptime);
                        for (Listener l : c.listeners) end(c, l);
                        raw(c, 24); contacts.remove(c.id);
                    }
                    if (!enabled || x < 0 || y < 0 || x >= server.screenInfo.width || y >= server.screenInfo.height) return;
                    if (contacts.values().stream().filter(active -> !active.physicalEnd).count() >= CAPACITY) return;
                    c = new Contact(nextId++, androidId);
                    if (nextId == 0) nextId = 1;
                    c.x = c.beginX = x; c.y = c.beginY = y; c.time = c.beginTime = timestamp(uptime);
                    c.target = target;
                    collect(c, target);
                    contacts.put(c.id, c);
                    for (Listener l : c.listeners) if (l == c.owner(l.device) || l.ownership) begin(c, l);
                    ownership(c, c.owner(2)); ownership(c, c.owner(DEVICE));
                } else {
                    if (c == null || c.physicalEnd) return;
                    c.x = x; c.y = y; c.time = timestamp(uptime);
                    if (type == 20) c.physicalEnd = true;
                    for (Listener l : c.listeners) if (l.begun && !l.ended) {
                        Listener owner = c.owner(l.device);
                        if (type == 20 && owner != null && owner.grab && !owner.accepted) {
                            if (l == owner) { send(c, l, 20, 0); l.endSent = true; } // keep ownership pending after physical lift
                            else send(c, l, 19, 1 << 16);
                        } else if (type == 20) end(c, l);
                        else send(c, l, 19, 0);
                    }
                }
                lastX = c.x; lastY = c.y;
                raw(c, type + 4);
                if (c.physicalEnd && c.finished()) contacts.remove(c.id);
            }
        }
    }
    private void collect(Contact c, Window target) {
        List<Window> path = new ArrayList<>();
        for (Window w = target; w != null; w = w.getParent()) path.add(w);
        // Both master and source streams are independently selectable in XI2.
        for (int device : new int[]{2, DEVICE}) {
            for (int i = path.size() - 1; i >= 0; i--) for (Grab g : grabs) {
                if (g.window == path.get(i) && matches(g.device, device) && extension.touchCapable(g.client)
                        && (g.modifiers == (1 << 31) || g.modifiers == server.keyboard.getModifiersMask().getBits())) {
                    Window eventWindow = g.window;
                    if (g.ownerEvents) {
                        for (Window w : path) {
                            boolean own = false;
                            for (XInput2Extension.Selection s : extension.touchSelections())
                                if (s.client == g.client && s.window == w && matches(s.deviceId, device) && bit(s.mask, 18)) own = true;
                            if (own) { eventWindow = w; break; }
                        }
                    }
                    Listener listener = new Listener(g.client, eventWindow, device, true, g.mask);
                    listener.grabWindow = g.window;
                    boolean duplicate = false;
                    for (Listener old : c.listeners) if (old.client == listener.client && old.device == device && old.grabWindow == g.window) duplicate = true;
                    if (!duplicate) c.listeners.add(listener);
                }
            }
            for (Window w : path) {
                boolean selected = false;
                for (XInput2Extension.Selection s : extension.touchSelections()) {
                    if (s.window == w && matches(s.deviceId, device) && bit(s.mask, 18) && extension.touchCapable(s.client)) {
                        boolean duplicate = false;
                        for (Listener l : c.listeners) if (l.client == s.client && l.window == w && l.device == device) {
                            duplicate = true;
                            if (!l.grab) l.ownership |= bit(s.mask, 21); // union of per-device and wildcard selections
                        }
                        if (!duplicate) c.listeners.add(new Listener(s.client, w, device, false, s.mask));
                        selected = true;
                    }
                }
                if (selected) break;
            }
        }
        for (Listener listener : c.listeners) {
            int index = path.indexOf(listener.window);
            if (index > 0) listener.child = path.get(index - 1).id;
        }
    }
    static boolean matches(int selected, int actual) { return selected == 0 || selected == actual || (selected == 1 && actual == 2); }
    static boolean bit(byte[] mask, int type) { return type / 8 < mask.length && (mask[type / 8] & (1 << (type & 7))) != 0; }
    private void begin(Contact c, Listener l) {
        if (l.begun) return;
        l.begun = true;
        sendAt(c, l, 18, 0, c.beginX, c.beginY, c.beginTime);
        // A listener without TouchOwnership starts receiving only when it becomes owner.
        // Retain the original down point, followed by the latest coalesced position.
        if (c.x != c.beginX || c.y != c.beginY)
            send(c, l, 19, c.physicalEnd ? 1 << 16 : 0);
    }
    private void end(Contact c, Listener l) {
        if (l.ended) return;
        if (l.begun && !l.endSent) { send(c, l, 20, 0); l.endSent = true; }
        l.ended = true;
    }
    private void ownership(Contact c, Listener l) {
        if (l == null) return;
        begin(c, l);
        if (l.ownership) send(c, l, 21, 0);
        if (c.physicalEnd) { if (!l.endSent) { send(c, l, 20, 0); l.endSent = true; } if (!l.grab || l.accepted) l.ended = true; }
    }
    private void send(Contact c, Listener l, int type, int flags) {
        sendAt(c, l, type, flags, c.x, c.y, c.time);
    }
    private void sendAt(Contact c, Listener l, int type, int flags, double x, double y, int time) {
        short[] origin = l.window.rootPointToLocal((short)0, (short)0);
        l.client.sendEvent(new XITouchEvent(type, l.device, c.id, time, server.windowManager.rootWindow.id,
                l.window.id, l.child, x, y, x + origin[0], y + origin[1], flags,
                server.keyboard.getModifiersMask().getBits()));
    }
    private void raw(Contact c, int type) {
        Set<String> sent = new HashSet<>();
        for (XInput2Extension.Selection s : extension.touchSelections()) {
            if (s.window != server.windowManager.rootWindow || !bit(s.mask, type) || !extension.touchCapable(s.client)) continue;
            for (int device : new int[]{2, DEVICE}) if (matches(s.deviceId, device) && sent.add(s.client.resourceIDBase + ":" + device))
                s.client.sendEvent(new XITouchEvent(type, device, c.id, c.time, 0, 0, 0, c.x, c.y, 0, 0, 0, 0));
        }
    }
    synchronized List<Integer> grab(XClient client, Window window, int device, byte[] mask, int[] modifiers, boolean ownerEvents) {
        List<Integer> failed = new ArrayList<>();
        for (int modifier : modifiers) {
            boolean conflict = false;
            for (Grab g : grabs) if (g.client != client && g.window == window && overlaps(g.device, device)
                    && (g.modifiers == modifier || g.modifiers == (1 << 31) || modifier == (1 << 31))) conflict = true;
            if (conflict) { failed.add(modifier); continue; }
            grabs.removeIf(g -> g.client == client && g.window == window && g.device == device && g.modifiers == modifier);
            grabs.add(new Grab(client, window, device, modifier, mask, ownerEvents));
        }
        return failed;
    }
    static boolean overlaps(int a, int b) { return a == b || a == 0 || b == 0 || (a == 1 && b == 2) || (a == 2 && b == 1); }
    synchronized void ungrab(XClient client, Window window, int device, int[] modifiers) {
        for (int m : modifiers) grabs.removeIf(g -> g.client == client && g.window == window && g.device == device
                && (m == (1 << 31) || m == g.modifiers));
    }
    synchronized void allow(XClient client, int device, int id, int window, boolean accept) throws XRequestError {
        Contact found = null; Listener listener = null;
        for (Contact c : contacts.values()) if (c.id == id) {
            found = c;
            for (Listener l : c.listeners) if (!l.ended && l.client == client && l.device == device && l.grabWindow.id == window) listener = l;
        }
        if (found == null) throw new BadValue(id);
        if (listener == null) throw new BadAccess();
        Contact c = found; Listener l = listener;
        if (accept) {
            l.accepted = true;
            // Potential owners may accept in advance. Only listeners below them lose the sequence.
            boolean below = false;
            for (Listener other : c.listeners) {
                if (other == l) below = true;
                else if (below && other.device == device) end(c, other);
            }
            if (c.physicalEnd && c.owner(device) == l) l.ended = true;
        } else {
            boolean wasOwner = l == c.owner(device);
            end(c, l);
            if (wasOwner) ownership(c, c.owner(device));

        }
        if (c.physicalEnd && c.finished()) contacts.remove(c.id);
    }
    synchronized void remove(XClient client, Window window) {
        grabs.removeIf(g -> g.client == client || g.window == window);
        Iterator<Contact> it = contacts.values().iterator();
        while (it.hasNext()) {
            Contact c = it.next();
            if (window != null && c.target == window) {
                for (Listener l : c.listeners) end(c, l);
                // Device/raw lifetime continues until the physical finger lifts.
                if (c.physicalEnd) it.remove();
                continue;
            }
            Listener master = c.owner(2), source = c.owner(DEVICE);
            for (Listener l : c.listeners) if (l.client == client || l.window == window || l.grabWindow == window) {
                if (l.client == client) l.ended = true; else end(c, l);
            }
            if (master != c.owner(2)) ownership(c, c.owner(2));
            if (source != c.owner(DEVICE)) ownership(c, c.owner(DEVICE));
            if (c.physicalEnd && c.finished()) it.remove();
        }
    }
    @Override public void close() { enabled = false; cancelNow(); synchronized (jobs) { closed = true; jobs.clear(); jobs.notifyAll(); } }
}
