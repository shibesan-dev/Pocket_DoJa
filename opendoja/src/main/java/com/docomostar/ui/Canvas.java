package com.docomostar.ui;

/**
 * Star compatibility shim. In the real Star SDK {@code com.docomostar.ui.Canvas}
 * extends {@code com.docomostar.ui.Frame}; OpenDoJa's real implementation is
 * {@code com.nttdocomo.ui.Canvas}. This shim extends
 * {@code com.docomostar.ui.Frame} (which itself delegates to
 * {@code com.nttdocomo.ui.Canvas}) so that a Star canvas subclass is both a
 * {@code com.docomostar.ui.Canvas} and a {@code com.docomostar.ui.Frame},
 * matching the Star hierarchy under single inheritance. Without it, Star
 * titles fail with {@code VerifyError}: a canvas subclass is not assignable to
 * {@code com.docomostar.ui.Frame}.
 */
public abstract class Canvas extends com.docomostar.ui.Frame {
    @Override
    public void paint(com.nttdocomo.ui.Graphics g) {
        paint((com.docomostar.ui.Graphics) g);
    }

    public abstract void paint(com.docomostar.ui.Graphics g);

    @Override
    public com.docomostar.ui.Graphics getGraphics() {
        return (com.docomostar.ui.Graphics) super.getGraphics();
    }
}