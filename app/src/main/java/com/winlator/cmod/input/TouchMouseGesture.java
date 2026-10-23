package com.winlator.cmod.input;

/** One captured guest contact; coordinates have already passed through the display transform. */
public final class TouchMouseGesture {
    public interface Sink {
        void move(int x, int y);
        void button(boolean pressed);
    }
    private final Sink sink;
    private int primary = -1;
    private boolean fresh = true, captured;

    public TouchMouseGesture(Sink sink) { this.sink = sink; }

    public void begin() {
        cancel();
        fresh = false;
        captured = false;
    }

    public void down(int id, float x, float y, boolean eligible) {
        if (fresh || captured || !eligible) return;
        primary = id;
        captured = true;
        sink.move((int) x, (int) y);
        sink.button(true);
    }

    public void move(int id, float x, float y) {
        if (id == primary && !fresh) sink.move((int) x, (int) y);
    }

    public void up(int id, float x, float y) {
        if (id != primary || fresh) return;
        sink.move((int) x, (int) y);
        sink.button(false);
        primary = -1;
    }

    public void cancel() {
        if (primary != -1) sink.button(false);
        primary = -1;
        fresh = true;
    }

    public int primary() { return primary; }
}
