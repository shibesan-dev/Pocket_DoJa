package com.docomostar.ui;

/**
 * Star compatibility shim delegating to com.nttdocomo.ui.Font.
 */
public class Font extends com.nttdocomo.ui.Font {
    public Font() {
        super();
    }

    protected Font(opendoja.compat.awt.Font awtFont) {
        super(awtFont);
    }

    private static final class FontAdapter extends Font {
        private final com.nttdocomo.ui.Font delegate;

        FontAdapter(com.nttdocomo.ui.Font delegate) {
            super(delegate.getAwtFont());
            this.delegate = delegate;
        }

        @Override
        public int getAscent() {
            return delegate.getAscent();
        }

        @Override
        public int getDescent() {
            return delegate.getDescent();
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int stringWidth(String text) {
            return delegate.stringWidth(text);
        }

        @Override
        public int stringWidth(com.nttdocomo.lang.XString text) {
            return delegate.stringWidth(text);
        }

        @Override
        public int stringWidth(com.nttdocomo.lang.XString text, int offset, int length) {
            return delegate.stringWidth(text, offset, length);
        }

        @Override
        public int getBBoxWidth(String text) {
            return delegate.getBBoxWidth(text);
        }

        @Override
        public int getBBoxWidth(com.nttdocomo.lang.XString text) {
            return delegate.getBBoxWidth(text);
        }

        @Override
        public int getBBoxWidth(com.nttdocomo.lang.XString text, int offset, int length) {
            return delegate.getBBoxWidth(text, offset, length);
        }

        @Override
        public int getBBoxHeight(String text) {
            return delegate.getBBoxHeight(text);
        }

        @Override
        public int getBBoxHeight(com.nttdocomo.lang.XString text) {
            return delegate.getBBoxHeight(text);
        }

        @Override
        public int getLineBreak(String text, int offset, int length, int width) {
            return delegate.getLineBreak(text, offset, length, width);
        }

        @Override
        public int getLineBreak(com.nttdocomo.lang.XString text, int offset, int length, int width) {
            return delegate.getLineBreak(text, offset, length, width);
        }

    }

    public static Font getDefaultFont() {
        return new FontAdapter(com.nttdocomo.ui.Font.getDefaultFont());
    }

    public static void setDefaultFont(Font font) {
        com.nttdocomo.ui.Font.setDefaultFont(font);
    }

    public static Font getFont(int value) {
        return new FontAdapter(com.nttdocomo.ui.Font.getFont(value));
    }

    public static Font getFont(int faceAndStyle, int size) {
        return new FontAdapter(com.nttdocomo.ui.Font.getFont(faceAndStyle, size));
    }

    public static int[] getSupportedFontSizes() {
        return com.nttdocomo.ui.Font.getSupportedFontSizes();
    }
}
