package com.winlator.cmod.xserver;

public class Cursor extends XResource {
    public final int hotSpotX;
    public final int hotSpotY;
    public final Drawable cursorImage;
    public final Drawable sourceImage;
    public final Drawable maskImage;
    private boolean visible = true;
    /** A program hides the pointer by giving its window an empty cursor; this draws that window's pointer anyway (the sidebar's "Show hidden cursor"). */
    private static volatile boolean showHidden = false;

    public static void setShowHidden(boolean show) {
        showHidden = show;
    }

    public Cursor(int id, int hotSpotX, int hotSpotY, Drawable cursorImage, Drawable sourceImage, Drawable maskImage) {
        super(id);
        this.hotSpotX = hotSpotX;
        this.hotSpotY = hotSpotY;
        this.cursorImage = cursorImage;
        this.sourceImage = sourceImage;
        this.maskImage = maskImage;
    }

    public boolean isVisible() {
        return visible || showHidden;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
