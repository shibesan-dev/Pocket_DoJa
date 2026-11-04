package com.docomostar.ui;
/**
 * Star compatibility shim delegating to com.nttdocomo.ui.Image.
 * Static methods are re-exposed because Java does not inherit them; Star
 * titles call them as com.docomostar.ui.Image.createImage(...).
 */
public abstract class Image extends com.nttdocomo.ui.Image {
    public static Image createImage(int width, int height) {
        return (Image) com.nttdocomo.ui.Image.createImage(width, height);
    }

    public static Image createImage(int width, int height, int[] pixels, int offset) {
        return (Image) com.nttdocomo.ui.Image.createImage(width, height, pixels, offset);
    }

    @Override
    public com.docomostar.ui.Graphics getGraphics() {
        return (com.docomostar.ui.Graphics) super.getGraphics();
    }
}
