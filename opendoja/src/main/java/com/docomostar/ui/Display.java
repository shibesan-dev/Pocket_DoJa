package com.docomostar.ui;

/**
 * Star compatibility shim delegating to com.nttdocomo.ui.Display.
 */
public abstract class Display extends com.nttdocomo.ui.Display {
    public static com.docomostar.ui.Frame getCurrent() {
        return (com.docomostar.ui.Frame) com.nttdocomo.ui.Display.getCurrent();
    }

    public static int getWidth() {
        return com.nttdocomo.ui.Display.getWidth();
    }

    public static int getHeight() {
        return com.nttdocomo.ui.Display.getHeight();
    }

    public static boolean isColor() {
        return com.nttdocomo.ui.Display.isColor();
    }

    public static int numColors() {
        return com.nttdocomo.ui.Display.numColors();
    }

    public static void setCurrent(com.docomostar.ui.Frame frame) {
        com.nttdocomo.ui.Display.setCurrent(frame);
    }
}
