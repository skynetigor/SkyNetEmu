package com.winlator.cmod.ui;

import android.view.KeyEvent;

/** Shared, side-effect-free rules for responsive library and UI controller input. */
public final class UiNavigationPolicy {
    private UiNavigationPolicy() {}

    public static boolean usesLibraryRail(float widthDp) {
        return widthDp >= 600;
    }

    public static int libraryColumns(float widthDp, float cellDp) {
        return Math.max(1, (int) ((widthDp - 24 + 10) / (cellDp + 10)));
    }

    public static int confirmOrBack(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) return KeyEvent.KEYCODE_DPAD_CENTER;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) return KeyEvent.KEYCODE_BACK;
        return keyCode;
    }

    public static int stickDirection(float x, float y) {
        if (Math.abs(x) > Math.abs(y) && x < -.5f) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (Math.abs(x) > Math.abs(y) && x > .5f) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if (y < -.5f) return KeyEvent.KEYCODE_DPAD_UP;
        if (y > .5f) return KeyEvent.KEYCODE_DPAD_DOWN;
        return 0;
    }

    /** -1 means leave the grid; horizontal edges never wrap into another row. */
    public static int gridTarget(int index, int count, int columns, int keyCode) {
        return gridTarget(index, count, columns, keyCode, false);
    }

    public static int gridTarget(int index, int count, int columns, int keyCode, boolean hasLeftRail) {
        if (index < 0 || index >= count || columns < 1) throw new IllegalArgumentException("Invalid grid position");
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return index % columns == 0 ? (hasLeftRail ? -1 : index) : index - 1;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return index % columns == columns - 1 || index == count - 1 ? index : index + 1;
            case KeyEvent.KEYCODE_DPAD_UP:
                return index < columns ? -1 : index - columns;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return index + columns >= count ? -1 : index + columns;
            default:
                return -1;
        }
    }
}
