package com.docomostar.ui;

/**
 * Star compatibility shim. In the real Star SDK {@code com.docomostar.ui.Frame}
 * is the base frame class and {@code com.docomostar.ui.Canvas} extends it.
 * OpenDoJa's real implementation is {@code com.nttdocomo.ui.Canvas}. This shim
 * extends {@code com.nttdocomo.ui.Canvas} so a Star frame is simultaneously a
 * {@code com.docomostar.ui.Frame}, a {@code com.nttdocomo.ui.Canvas} and a
 * {@code com.nttdocomo.ui.Frame}, and {@code com.docomostar.ui.Canvas} extends
 * it so a Star canvas subclass is also a {@code com.docomostar.ui.Frame}.
 * Without this, Star titles fail with {@code VerifyError}: a canvas subclass
 * is not assignable to {@code com.docomostar.ui.Frame}.
 */
public abstract class Frame extends com.nttdocomo.ui.Canvas {
    @Override
    public com.docomostar.ui.Graphics getGraphics() {
        return (com.docomostar.ui.Graphics) super.getGraphics();
    }
}