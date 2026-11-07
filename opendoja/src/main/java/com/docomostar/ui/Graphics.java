package com.docomostar.ui;
/**
 * Star compatibility shim delegating to com.nttdocomo.ui.Graphics.
 * Static methods are re-exposed because Java does not inherit them; Star
 * titles call them as com.docomostar.ui.Graphics.getColorOfRGB(...).
 */
public abstract class Graphics extends com.nttdocomo.ui.Graphics implements com.docomostar.opt.ui.j3d.Graphics3D {
    protected Graphics() {
        super();
    }

    protected Graphics(opendoja.host.DesktopSurface surface) {
        super(surface);
    }

    public void drawImage(com.docomostar.ui.Image image, int x, int y) {
        super.drawImage(image, x, y);
    }

    public void drawImage(com.docomostar.ui.Image image, int x, int y, int sx, int sy, int width, int height) {
        super.drawImage(image, x, y, sx, sy, width, height);
    }

    public void drawImage(com.docomostar.ui.Image image, int[] matrix, int sx, int sy, int width, int height) {
        super.drawImage(image, matrix, sx, sy, width, height);
    }

    public void drawImage(com.docomostar.ui.Image image, int[] matrix) {
        super.drawImage(image, matrix);
    }

    public void drawScaledImage(com.docomostar.ui.Image image, int dx, int dy, int dw, int dh, int sx, int sy, int sw, int sh) {
        super.drawScaledImage(image, dx, dy, dw, dh, sx, sy, sw, sh);
    }

    public void setFont(com.docomostar.ui.Font font) {
        super.setFont(font);
    }

    public static int getColorOfName(int name) {
        return com.nttdocomo.ui.Graphics.getColorOfName(name);
    }

    public static int getColorOfRGB(int red, int green, int blue) {
        return com.nttdocomo.ui.Graphics.getColorOfRGB(red, green, blue);
    }

    public static int getColorOfRGB(int red, int green, int blue, int alpha) {
        return com.nttdocomo.ui.Graphics.getColorOfRGB(red, green, blue, alpha);
    }

    public void setViewTrans(com.docomostar.opt.ui.j3d.AffineTrans transform) {
        super.setViewTrans(transform);
    }

    public void setViewTransArray(com.docomostar.opt.ui.j3d.AffineTrans[] transforms) {
        if (transforms == null) {
            super.setViewTransArray(null);
            return;
        }
        com.nttdocomo.opt.ui.j3d.AffineTrans[] converted = new com.nttdocomo.opt.ui.j3d.AffineTrans[transforms.length];
        System.arraycopy(transforms, 0, converted, 0, transforms.length);
        super.setViewTransArray(converted);
    }

    public void setDirectionLight(com.docomostar.opt.ui.j3d.Vector3D direction, int color) {
        super.setDirectionLight(direction, color);
    }

    public void setSphereTexture(com.docomostar.opt.ui.j3d.Texture texture) {
        super.setSphereTexture(texture);
    }

    public void setPrimitiveTextureArray(com.docomostar.opt.ui.j3d.Texture texture) {
        super.setPrimitiveTextureArray(texture);
    }

    public void setPrimitiveTextureArray(com.docomostar.opt.ui.j3d.Texture[] textures) {
        if (textures == null) {
            super.setPrimitiveTextureArray((com.nttdocomo.opt.ui.j3d.Texture[]) null);
            return;
        }
        com.nttdocomo.opt.ui.j3d.Texture[] converted = new com.nttdocomo.opt.ui.j3d.Texture[textures.length];
        System.arraycopy(textures, 0, converted, 0, textures.length);
        super.setPrimitiveTextureArray(converted);
    }

    public void renderPrimitives(com.docomostar.opt.ui.j3d.PrimitiveArray primitives, int attr) {
        super.renderPrimitives(primitives, attr);
    }

    public void renderPrimitives(com.docomostar.opt.ui.j3d.PrimitiveArray primitives, int start, int count, int attr) {
        super.renderPrimitives(primitives, start, count, attr);
    }
}
