package com.docomostar.opt.ui;

import opendoja.host.DesktopSurface;

/**
 * Star compatibility view of the DoJa 5.1 optional graphics surface.
 */
public class Graphics2 extends com.nttdocomo.opt.ui.Graphics2 {
    /**
     * Applications cannot create this object directly.
     */
    protected Graphics2() {
        super();
    }

    /**
     * Creates a graphics surface for the specified desktop surface.
     *
     * @param surface the backing surface
     */
    public Graphics2(DesktopSurface surface) {
        super(surface);
    }
}
