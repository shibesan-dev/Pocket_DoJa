package com.docomostar.opt.ui.j3d;

public interface Graphics3D extends com.nttdocomo.opt.ui.j3d.Graphics3D {
    void setViewTrans(AffineTrans transform);

    void setViewTransArray(AffineTrans[] transforms);

    void setDirectionLight(Vector3D direction, int color);

    void setSphereTexture(Texture texture);

    void setPrimitiveTextureArray(Texture texture);

    void setPrimitiveTextureArray(Texture[] textures);

    void renderPrimitives(PrimitiveArray primitives, int attr);

    void renderPrimitives(PrimitiveArray primitives, int start, int count, int attr);
}
