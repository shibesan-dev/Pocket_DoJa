package com.docomostar.opt.ui.j3d;

import java.io.IOException;
import java.io.InputStream;

public class Texture extends com.nttdocomo.opt.ui.j3d.Texture {
    public Texture(byte[] data, boolean forEnv) {
        super(data, forEnv);
    }

    public Texture(InputStream inputStream, boolean forEnv) throws IOException {
        super(inputStream, forEnv);
    }
}
