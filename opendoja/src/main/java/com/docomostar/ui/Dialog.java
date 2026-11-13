package com.docomostar.ui;

/**
 * Star SDK dialogs live in com.docomostar.ui, while OpenDoJa implements the
 * class in com.nttdocomo.ui. Kingdom Hearts coded constructs this type when
 * its quit confirmation opens; without it the game thread died with
 * NoClassDefFoundError. This is just a package alias, nothing more.
 */
public final class Dialog extends com.nttdocomo.ui.Dialog {
    public Dialog(int type, String title) {
        super(type, title);
    }
}
