package opendoja.host.ogl;

import com.nttdocomo.ui.ogl.*;
import opendoja.host.DesktopSurface;
import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.OpenGlesRendererMode;
import opendoja.host.compat.CompatFeature;
import opendoja.host.compat.GameCompatibility;

import opendoja.compat.awt.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-owned software OpenGL ES 1.1 renderer boundary.
 */
public class OglRenderer {
    private static final int OGL_TEXTURE_UNIT_COUNT = 2;
    private static final int OGL_MAX_VERTEX_UNITS = 8;
    private static final int OGL_MAX_PALETTE_MATRICES = 32;
    private static final SharedGlObjectStore SHARED_GL_OBJECT_STORE = new SharedGlObjectStore();

    public interface Host {
        DesktopSurface surface();

        Graphics2D delegate();

        void markOpenGlesActivity();

        void flushSurfacePresentation();

        void onSoftwareSurfaceMutation();
    }

    private final Host host;
    private int oglClearColor = 0x00000000;
    private final OglState ogl = new OglState(this);
    private final ClipVector clipVectorTemp = new ClipVector();
    private final ClipVector eyeVectorTemp = new ClipVector();
    private final ClipVector normalVectorTemp = new ClipVector();
    private final OglSoftwareRenderer software = new OglSoftwareRenderer(this);
    private final OglHardwareBackend hardware = OglHardwareBackend.create(this);

    public OglRenderer(Host host) {
        this.host = Objects.requireNonNull(host, "host");
        ogl.viewportX = 0;
        ogl.viewportY = 0;
        ogl.viewportWidth = host.surface().width();
        ogl.viewportHeight = host.surface().height();
    }

    Host host() {
        return host;
    }

    OglState oglState() {
        return ogl;
    }

    int oglClearColor() {
        return oglClearColor;
    }

    ClipVector eyeVectorTemp() {
        return eyeVectorTemp;
    }

    ClipVector normalVectorTemp() {
        return normalVectorTemp;
    }

    public void close() {
        hardware.close();
    }

    public void onHostDelegateRecreated() {
        hardware.onHostDelegateRecreated();
    }

    public void onSoftwareSurfaceMutation() {
        hardware.onSoftwareSurfaceMutation();
    }

    public void onPresentedSoftwareOverlay() {
        hardware.onPresentedSoftwareOverlay(null);
    }

    public void onPresentedSoftwareOverlay(Rectangle bounds) {
        hardware.onPresentedSoftwareOverlay(bounds);
    }

    public void prepareForSoftwareMutation() {
        hardware.prepareForSoftwareMutation();
    }

    public void flushHardwarePresentation() {
        hardware.flushForPresentation();
    }

    public boolean hasBufferedHardwarePresentation() {
        return hardware.hasBufferedHardwarePresentation();
    }

    public boolean acceptsExtensionMatrixMode(int mode) {
        return false;
    }

    public void onBeginDrawing() {
    }

    public boolean usesExtensionMatrices(boolean standardModelViewConfigured, boolean standardProjectionConfigured) {
        return false;
    }

    public float[] extensionWorldMatrix() {
        return null;
    }

    public float[] extensionCameraMatrix() {
        return null;
    }

    public void loadExtensionMatrix(int mode, float[] matrix) {
        throw new IllegalArgumentException("Unsupported extension matrix mode: " + mode);
    }

    public void multiplyExtensionMatrix(int mode, float[] matrix) {
        throw new IllegalArgumentException("Unsupported extension matrix mode: " + mode);
    }

    public void pushExtensionMatrix(int mode) {
        throw new IllegalArgumentException("Unsupported extension matrix mode: " + mode);
    }

    public boolean popExtensionMatrix(int mode) {
        throw new IllegalArgumentException("Unsupported extension matrix mode: " + mode);
    }

    private boolean usesHardwareRenderer() {
        // Offscreen Image surfaces do not have repaint hooks, but they still need to use the
        // same GL path as the canvas so renderImage()/syncOffscreenSurfaceForReadback() can
        // read back the finished frame before a later drawImage() composite.
        return OpenDoJaLaunchArgs.openGlesRendererMode() == OpenGlesRendererMode.HARDWARE
                || GameCompatibility.isFeatureEnabled(
                CompatFeature.OPENGLES_HARDWARE_RENDERER);
    }

public final void glClearColor(float red, float green, float blue, float alpha) {
    oglClearColor = ((clampOglChannel(alpha) & 0xFF) << 24)
            | ((clampOglChannel(red) & 0xFF) << 16)
            | ((clampOglChannel(green) & 0xFF) << 8)
            | (clampOglChannel(blue) & 0xFF);
}

public final void glClear(int mask) {
    host.markOpenGlesActivity();
    if ((mask & GraphicsOGL.GL_DEPTH_BUFFER_BIT) != 0) {
        host.surface().endDepthFrame();
    }
    if (usesHardwareRenderer() && hardware.clear(mask)) {
        return;
    }
    if ((mask & GraphicsOGL.GL_COLOR_BUFFER_BIT) == 0) {
        return;
    }
    Rectangle clip = host.delegate().getClipBounds();
    int x = clip == null ? 0 : clip.x;
    int y = clip == null ? 0 : clip.y;
    int width = clip == null ? host.surface().width() : clip.width;
    int height = clip == null ? host.surface().height() : clip.height;
    // The clear below writes the bitmap directly; pending OGL software pixels must land
    // there first or this pass would be discarded on the next GL frame fetch.
    host.surface().flushOglFrameToBitmap();
    Color old = host.delegate().getColor();
    host.delegate().setColor(new Color(oglClearColor, true));
    host.delegate().fillRect(x, y, width, height);
    host.delegate().setColor(old);
    host.flushSurfacePresentation();
    // Done after the presentation flush: the render target can start this GL pass already
    // cleared, without a full-resolution bitmap downsample.
    host.surface().onOglColorClear(x, y, width, height, oglClearColor);
}

public final void beginDrawing() {
    host.markOpenGlesActivity();
    ogl.beginDrawing();
}

public final void endDrawing() {
    host.markOpenGlesActivity();
    ogl.endDrawing();
    hardware.endDrawing();
}

public final void glEnable(int cap) {
    ogl.enableCap(cap);
    if (cap == GraphicsOGL.GL_COLOR_MATERIAL) {
        syncColorMaterial(unpackColor(ogl.color));
    }
}

public final void glDisable(int cap) {
    ogl.disableCap(cap);
}

public final void glEnableClientState(int array) {
    ogl.enableClientState(array);
}

public final void glDisableClientState(int array) {
    ogl.disableClientState(array);
}

public final void glMatrixMode(int mode) {
    switch (mode) {
        case GraphicsOGL.GL_MODELVIEW,
                GraphicsOGL.GL_PROJECTION,
                GraphicsOGL.GL_TEXTURE,
                GraphicsOGL.GL_MATRIX_PALETTE_OES -> ogl.matrixMode = mode;
        default -> {
            if (ogl.renderer.acceptsExtensionMatrixMode(mode)) {
                ogl.matrixMode = mode;
            } else {
                ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            }
        }
    }
}

public final void glLoadIdentity() {
    loadMatrix(ogl.matrixMode, OglState.identityMatrix());
}

public final void glLoadMatrixf(float[] m) {
    if (m == null || m.length < 16) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    loadMatrix(ogl.matrixMode, m.clone());
}

public final void glMultMatrixf(float[] m) {
    if (m == null || m.length < 16) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    multiplyCurrentMatrix(m.clone());
}

public final void glPushMatrix() {
    pushCurrentMatrix(ogl.matrixMode);
}

public final void glPopMatrix() {
    popCurrentMatrix(ogl.matrixMode);
}

public final void glOrthof(float left, float right, float bottom, float top, float zNear, float zFar) {
    float[] matrix = OglState.identityMatrix();
    matrix[0] = 2f / Math.max(0.0001f, right - left);
    matrix[5] = 2f / Math.max(0.0001f, top - bottom);
    matrix[10] = -2f / Math.max(0.0001f, zFar - zNear);
    matrix[12] = -((right + left) / Math.max(0.0001f, right - left));
    matrix[13] = -((top + bottom) / Math.max(0.0001f, top - bottom));
    matrix[14] = -((zFar + zNear) / Math.max(0.0001f, zFar - zNear));
    loadMatrix(ogl.matrixMode, matrix);
}

public final void glFrustumf(float left, float right, float bottom, float top, float zNear, float zFar) {
    float near = Math.max(0.0001f, zNear);
    float far = Math.max(near + 0.0001f, zFar);
    float width = Math.max(0.0001f, right - left);
    float height = Math.max(0.0001f, top - bottom);
    float depth = Math.max(0.0001f, far - near);
    float[] matrix = new float[16];
    matrix[0] = (2f * near) / width;
    matrix[5] = (2f * near) / height;
    matrix[8] = (right + left) / width;
    matrix[9] = (top + bottom) / height;
    matrix[10] = -((far + near) / depth);
    matrix[11] = -1f;
    matrix[14] = -((2f * far * near) / depth);
    multiplyCurrentMatrix(matrix);
}

public final void glDepthRangef(float zNear, float zFar) {
    ogl.depthRangeNear = Math.max(0f, Math.min(1f, zNear));
    ogl.depthRangeFar = Math.max(0f, Math.min(1f, zFar));
}

public final void glRotatef(float angle, float x, float y, float z) {
    float magnitude = (float) Math.sqrt((x * x) + (y * y) + (z * z));
    if (magnitude <= 0.000001f) {
        return;
    }
    float nx = x / magnitude;
    float ny = y / magnitude;
    float nz = z / magnitude;
    float radians = (float) Math.toRadians(angle);
    float cos = (float) Math.cos(radians);
    float sin = (float) Math.sin(radians);
    float inverseCos = 1f - cos;
    float[] matrix = OglState.identityMatrix();
    matrix[0] = cos + (nx * nx * inverseCos);
    matrix[1] = (ny * nx * inverseCos) + (nz * sin);
    matrix[2] = (nz * nx * inverseCos) - (ny * sin);
    matrix[4] = (nx * ny * inverseCos) - (nz * sin);
    matrix[5] = cos + (ny * ny * inverseCos);
    matrix[6] = (nz * ny * inverseCos) + (nx * sin);
    matrix[8] = (nx * nz * inverseCos) + (ny * sin);
    matrix[9] = (ny * nz * inverseCos) - (nx * sin);
    matrix[10] = cos + (nz * nz * inverseCos);
    multiplyCurrentMatrix(matrix);
}

public final void glScalef(float x, float y, float z) {
    float[] matrix = OglState.identityMatrix();
    matrix[0] = x;
    matrix[5] = y;
    matrix[10] = z;
    multiplyCurrentMatrix(matrix);
}

public final void glTranslatef(float x, float y, float z) {
    float[] matrix = OglState.identityMatrix();
    matrix[12] = x;
    matrix[13] = y;
    matrix[14] = z;
    multiplyCurrentMatrix(matrix);
}

public final void glAlphaFunc(int func, float ref) {
    ogl.alphaFunc = func;
    ogl.alphaRef = Math.max(0f, Math.min(1f, ref));
}

public final void glDepthMask(boolean flag) {
    ogl.depthMask = flag;
}

public final void glDepthFunc(int func) {
    ogl.depthFunc = func;
}

public final void glBlendFunc(int sfactor, int dfactor) {
    if (!isValidBlendSrcFactor(sfactor) || !isValidBlendDstFactor(dfactor)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    ogl.blendSrcFactor = sfactor;
    ogl.blendDstFactor = dfactor;
}

public final void glColor4f(float red, float green, float blue, float alpha) {
    ogl.color = ((clampOglChannel(alpha) & 0xFF) << 24)
            | ((clampOglChannel(red) & 0xFF) << 16)
            | ((clampOglChannel(green) & 0xFF) << 8)
            | (clampOglChannel(blue) & 0xFF);
    syncColorMaterial(unpackColor(ogl.color));
}

public final void glColor4ub(short red, short green, short blue, short alpha) {
    ogl.color = ((alpha & 0xFF) << 24)
            | ((red & 0xFF) << 16)
            | ((green & 0xFF) << 8)
            | (blue & 0xFF);
    syncColorMaterial(unpackColor(ogl.color));
}

public final void glLightModelf(int pname, float param) {
    glLightModelfv(pname, new float[]{param});
}

public final void glLightModelfv(int pname, float[] params) {
    if (params == null) {
        throw new NullPointerException("params");
    }
    switch (pname) {
        case GraphicsOGL.GL_LIGHT_MODEL_AMBIENT -> {
            if (params.length < 4) {
                throw new IllegalArgumentException("params");
            }
            copyColor(params, ogl.lightModelAmbient);
        }
        case GraphicsOGL.GL_LIGHT_MODEL_TWO_SIDE -> {
            if (params.length < 1) {
                throw new IllegalArgumentException("params");
            }
            ogl.lightModelTwoSide = params[0] != 0f;
        }
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}

public final void glLightf(int light, int pname, float param) {
    glLightfv(light, pname, new float[]{param});
}

public final void glLightfv(int light, int pname, float[] params) {
    if (params == null) {
        throw new NullPointerException("params");
    }
    OglLight oglLight = ogl.light(light);
    if (oglLight == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    switch (pname) {
        case GraphicsOGL.GL_AMBIENT -> {
            requireLength(params, 4);
            copyColor(params, oglLight.ambient);
        }
        case GraphicsOGL.GL_DIFFUSE -> {
            requireLength(params, 4);
            copyColor(params, oglLight.diffuse);
        }
        case GraphicsOGL.GL_SPECULAR -> {
            requireLength(params, 4);
            copyColor(params, oglLight.specular);
        }
        case GraphicsOGL.GL_POSITION -> {
            requireLength(params, 4);
            multiply(eyeVectorTemp, ogl.modelViewMatrix, params[0], params[1], params[2], params[3]);
            oglLight.position[0] = eyeVectorTemp.x;
            oglLight.position[1] = eyeVectorTemp.y;
            oglLight.position[2] = eyeVectorTemp.z;
            oglLight.position[3] = eyeVectorTemp.w;
        }
        case GraphicsOGL.GL_SPOT_DIRECTION -> {
            requireLength(params, 3);
            transformLightDirection(ogl.modelViewMatrix, params[0], params[1], params[2], oglLight.spotDirection);
        }
        case GraphicsOGL.GL_SPOT_EXPONENT -> {
            requireLength(params, 1);
            if (params[0] < 0f || params[0] > 128f) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            oglLight.spotExponent = params[0];
        }
        case GraphicsOGL.GL_SPOT_CUTOFF -> {
            requireLength(params, 1);
            if ((params[0] < 0f || params[0] > 90f) && params[0] != 180f) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            oglLight.spotCutoff = params[0];
        }
        case GraphicsOGL.GL_CONSTANT_ATTENUATION -> {
            requireLength(params, 1);
            if (params[0] < 0f) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            oglLight.constantAttenuation = params[0];
        }
        case GraphicsOGL.GL_LINEAR_ATTENUATION -> {
            requireLength(params, 1);
            if (params[0] < 0f) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            oglLight.linearAttenuation = params[0];
        }
        case GraphicsOGL.GL_QUADRATIC_ATTENUATION -> {
            requireLength(params, 1);
            if (params[0] < 0f) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            oglLight.quadraticAttenuation = params[0];
        }
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}

public final void glMaterialf(int face, int pname, float param) {
    glMaterialfv(face, pname, new float[]{param});
}

public final void glMaterialfv(int face, int pname, float[] params) {
    if (params == null) {
        throw new NullPointerException("params");
    }
    if (face != GraphicsOGL.GL_FRONT_AND_BACK) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    switch (pname) {
        case GraphicsOGL.GL_AMBIENT -> {
            requireLength(params, 4);
            if (!ogl.colorMaterialTracksAmbientAndDiffuse()) {
                copyColor(params, ogl.materialAmbient);
            }
        }
        case GraphicsOGL.GL_DIFFUSE -> {
            requireLength(params, 4);
            if (!ogl.colorMaterialTracksAmbientAndDiffuse()) {
                copyColor(params, ogl.materialDiffuse);
            }
        }
        case GraphicsOGL.GL_AMBIENT_AND_DIFFUSE -> {
            requireLength(params, 4);
            if (!ogl.colorMaterialTracksAmbientAndDiffuse()) {
                copyColor(params, ogl.materialAmbient);
                copyColor(params, ogl.materialDiffuse);
            }
        }
        case GraphicsOGL.GL_SPECULAR -> {
            requireLength(params, 4);
            copyColor(params, ogl.materialSpecular);
        }
        case GraphicsOGL.GL_EMISSION -> {
            requireLength(params, 4);
            copyColor(params, ogl.materialEmission);
        }
        case GraphicsOGL.GL_SHININESS -> {
            requireLength(params, 1);
            if (params[0] < 0f || params[0] > 128f) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            ogl.materialShininess = params[0];
        }
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}

public final void glNormal3f(float nx, float ny, float nz) {
    ogl.currentNormal[0] = nx;
    ogl.currentNormal[1] = ny;
    ogl.currentNormal[2] = nz;
}

public final void glTexEnvi(int target, int pname, int param) {
    if (target != GraphicsOGL.GL_TEXTURE_ENV) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    OglTextureUnit unit = ogl.activeTextureUnit();
    switch (pname) {
        case GraphicsOGL.GL_TEXTURE_ENV_MODE -> unit.textureEnvMode = param;
        case GraphicsOGL.GL_COMBINE_RGB -> unit.combineRgb = param;
        case GraphicsOGL.GL_COMBINE_ALPHA -> unit.combineAlpha = param;
        case GraphicsOGL.GL_SRC0_RGB -> unit.srcRgb[0] = param;
        case GraphicsOGL.GL_SRC1_RGB -> unit.srcRgb[1] = param;
        case GraphicsOGL.GL_SRC2_RGB -> unit.srcRgb[2] = param;
        case GraphicsOGL.GL_SRC0_ALPHA -> unit.srcAlpha[0] = param;
        case GraphicsOGL.GL_SRC1_ALPHA -> unit.srcAlpha[1] = param;
        case GraphicsOGL.GL_SRC2_ALPHA -> unit.srcAlpha[2] = param;
        case GraphicsOGL.GL_OPERAND0_RGB -> unit.operandRgb[0] = param;
        case GraphicsOGL.GL_OPERAND1_RGB -> unit.operandRgb[1] = param;
        case GraphicsOGL.GL_OPERAND2_RGB -> unit.operandRgb[2] = param;
        case GraphicsOGL.GL_OPERAND0_ALPHA -> unit.operandAlpha[0] = param;
        case GraphicsOGL.GL_OPERAND1_ALPHA -> unit.operandAlpha[1] = param;
        case GraphicsOGL.GL_OPERAND2_ALPHA -> unit.operandAlpha[2] = param;
        case GraphicsOGL.GL_RGB_SCALE -> unit.rgbScale = sanitizeTextureScale(param);
        case GraphicsOGL.GL_ALPHA_SCALE -> unit.alphaScale = sanitizeTextureScale(param);
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}

public final void glTexEnviv(int target, int pname, int[] params) {
    if (params == null) {
        throw new NullPointerException("params");
    }
    if (params.length < 1) {
        throw new IllegalArgumentException("params");
    }
    glTexEnvi(target, pname, params[0]);
}

public final void glTexEnvf(int target, int pname, float param) {
    if (target != GraphicsOGL.GL_TEXTURE_ENV) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    if (pname == GraphicsOGL.GL_RGB_SCALE) {
        ogl.activeTextureUnit().rgbScale = sanitizeTextureScale(Math.round(param));
        return;
    }
    if (pname == GraphicsOGL.GL_ALPHA_SCALE) {
        ogl.activeTextureUnit().alphaScale = sanitizeTextureScale(Math.round(param));
        return;
    }
    glTexEnvi(target, pname, Math.round(param));
}

public final void glTexEnvfv(int target, int pname, float[] params) {
    if (target != GraphicsOGL.GL_TEXTURE_ENV) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    if (params == null) {
        throw new NullPointerException("params");
    }
    OglTextureUnit unit = ogl.activeTextureUnit();
    if (pname == GraphicsOGL.GL_TEXTURE_ENV_COLOR) {
        if (params.length < 4) {
            throw new IllegalArgumentException("params");
        }
        unit.textureEnvColor = packColor(params[0], params[1], params[2], params[3]);
        return;
    }
    if (params.length < 1) {
        throw new IllegalArgumentException("params");
    }
    if (pname == GraphicsOGL.GL_RGB_SCALE) {
        unit.rgbScale = sanitizeTextureScale(Math.round(params[0]));
        return;
    }
    if (pname == GraphicsOGL.GL_ALPHA_SCALE) {
        unit.alphaScale = sanitizeTextureScale(Math.round(params[0]));
        return;
    }
    glTexEnvi(target, pname, Math.round(params[0]));
}

public final void glShadeModel(int mode) {
    ogl.shadeModel = mode;
}

public final void glActiveTexture(int texture) {
    if (texture < GraphicsOGL.GL_TEXTURE0 || texture >= GraphicsOGL.GL_TEXTURE0 + OGL_TEXTURE_UNIT_COUNT) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    ogl.activeTexture = texture;
}

public final void glClientActiveTexture(int texture) {
    if (texture < GraphicsOGL.GL_TEXTURE0 || texture >= GraphicsOGL.GL_TEXTURE0 + OGL_TEXTURE_UNIT_COUNT) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    ogl.clientActiveTexture = texture;
}

public final void glMultiTexCoord4f(int target, float s, float t, float r, float q) {
    int index = target - GraphicsOGL.GL_TEXTURE0;
    if (index < 0 || index >= OGL_TEXTURE_UNIT_COUNT) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    OglTextureUnit unit = ogl.textureUnits[index];
    unit.currentS = s;
    unit.currentT = t;
    unit.currentR = r;
    unit.currentQ = q;
}

public final void glPixelStorei(int pname, int param) {
    if (pname == GraphicsOGL.GL_UNPACK_ALIGNMENT) {
        ogl.unpackAlignment = Math.max(1, param);
    }
}

public final void glHint(int target, int mode) {
}

public final void glFrontFace(int mode) {
    ogl.frontFace = mode;
}

public final void glCullFace(int mode) {
    ogl.cullFace = mode;
}

public final void glViewport(int x, int y, int width, int height) {
    if (width < 0 || height < 0) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    ogl.viewportX = x;
    ogl.viewportY = y;
    ogl.viewportWidth = width;
    ogl.viewportHeight = height;
}

public final void glTexParameterf(int target, int pname, float param) {
    OglTexture texture = ogl.boundTexture();
    if (texture == null || target != GraphicsOGL.GL_TEXTURE_2D) {
        return;
    }
    texture.setParameter(pname, Math.round(param));
}

public final void glTexParameteri(int target, int pname, int param) {
    glTexParameterf(target, pname, param);
}

public final void glBindTexture(int target, int texture) {
    if (target != GraphicsOGL.GL_TEXTURE_2D) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    ogl.activeTextureUnit().boundTextureId = texture;
}

public final void glGenTextures(int n, int[] textures) {
    if (textures == null) {
        throw new NullPointerException("textures");
    }
    int count = Math.min(n, textures.length);
    for (int i = 0; i < count; i++) {
        textures[i] = SHARED_GL_OBJECT_STORE.createTextureId();
    }
}

public final void glGenTextures(int[] textures) {
    glGenTextures(textures == null ? 0 : textures.length, textures);
}

public final void glDeleteTextures(int n, int[] textures) {
    if (textures == null) {
        throw new NullPointerException("textures");
    }
    int count = Math.min(n, textures.length);
    for (int i = 0; i < count; i++) {
        int textureId = textures[i];
        ogl.textures.remove(textureId);
        hardware.onTextureDeleted(textureId);
        for (OglTextureUnit unit : ogl.textureUnits) {
            if (unit.boundTextureId == textureId) {
                unit.boundTextureId = 0;
            }
        }
    }
}

public final void glDeleteTextures(int[] textures) {
    glDeleteTextures(textures == null ? 0 : textures.length, textures);
}

public final void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, int imageSize, DirectBuffer pixels) {
    if (target != GraphicsOGL.GL_TEXTURE_2D || level != 0 || border != 0) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    OglTexture texture = ogl.boundTexture();
    if (texture == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_OPERATION;
        return;
    }
    if (!(pixels instanceof com.nttdocomo.ui.ogl.ByteBuffer byteBuffer)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    byte[] raw = new byte[Math.max(0, imageSize)];
    int offset = DirectBufferFactory.getSegmentOffset(byteBuffer);
    byteBuffer.get(offset, raw, 0, raw.length);
    if (!OglTexture.supportsCompressedInternalFormat(internalformat)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    if (!texture.loadCompressed(internalformat, width, height, raw)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    }
}

public final void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border,
                                   com.nttdocomo.ui.ogl.ByteBuffer pixels) {
    if (pixels == null) {
        throw new NullPointerException("pixels");
    }
    glCompressedTexImage2D(target, level, internalformat, width, height, border,
            DirectBufferFactory.getSegmentLength(pixels), pixels);
}

public final void glTexImage2D(int target, int level, int internalformat, int width, int height, int border,
                         int format, int type, DirectBuffer pixels) {
    if (target != GraphicsOGL.GL_TEXTURE_2D || level != 0 || border != 0 || width <= 0 || height <= 0) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    OglTexture texture = ogl.boundTexture();
    if (texture == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_OPERATION;
        return;
    }
    if (!texture.loadUncompressed(width, height, format, type, pixels)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    }
}

public final void glGenBuffers(int[] buffers) {
    if (buffers == null) {
        throw new NullPointerException("buffers");
    }
    for (int i = 0; i < buffers.length; i++) {
        buffers[i] = SHARED_GL_OBJECT_STORE.createBufferId();
    }
}

public final void glDeleteBuffers(int[] buffers) {
    if (buffers == null) {
        throw new NullPointerException("buffers");
    }
    for (int bufferId : buffers) {
        ogl.buffers.remove(bufferId);
        if (ogl.boundArrayBufferId == bufferId) {
            ogl.boundArrayBufferId = 0;
        }
        if (ogl.boundElementArrayBufferId == bufferId) {
            ogl.boundElementArrayBufferId = 0;
        }
    }
}

public final void glBindBuffer(int target, int buffer) {
    switch (target) {
        case GraphicsOGL.GL_ARRAY_BUFFER -> {
            if (buffer != 0) {
                ogl.buffers.computeIfAbsent(buffer, ignored -> new OglBufferObject());
            }
            ogl.boundArrayBufferId = buffer;
        }
        case GraphicsOGL.GL_ELEMENT_ARRAY_BUFFER -> {
            if (buffer != 0) {
                ogl.buffers.computeIfAbsent(buffer, ignored -> new OglBufferObject());
            }
            ogl.boundElementArrayBufferId = buffer;
        }
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}

public final void glBufferData(int target, DirectBuffer data, int usage) {
    OglBufferObject buffer = ogl.boundBuffer(target);
    if (buffer == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_OPERATION;
        return;
    }
    if (data == null) {
        throw new NullPointerException("data");
    }
    byte[] bytes = copyBufferBytes(data);
    buffer.replace(bytes, usage);
}

public final void glBufferSubData(int target, int offset, DirectBuffer data) {
    OglBufferObject buffer = ogl.boundBuffer(target);
    if (buffer == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_OPERATION;
        return;
    }
    if (offset < 0) {
        throw new ArrayIndexOutOfBoundsException("offset");
    }
    if (data == null) {
        throw new NullPointerException("data");
    }
    byte[] bytes = copyBufferBytes(data);
    if (!buffer.update(offset, bytes)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    }
}

public final void glVertexPointer(int size, int type, int stride, DirectBuffer pointer) {
    ogl.vertexPointer = OglPointer.direct(size, type, stride, pointer);
}

public final void glVertexPointer(int size, int type, int stride, int pointer) {
    ogl.vertexPointer = OglPointer.bufferObject(size, type, stride, requireArrayBuffer(), pointer);
}

public final void glTexCoordPointer(int size, int type, int stride, DirectBuffer pointer) {
    ogl.clientTextureUnit().texCoordPointer = OglPointer.direct(size, type, stride, pointer);
}

public final void glTexCoordPointer(int size, int type, int stride, int pointer) {
    ogl.clientTextureUnit().texCoordPointer = OglPointer.bufferObject(size, type, stride, requireArrayBuffer(), pointer);
}

public final void glNormalPointer(int type, int stride, DirectBuffer pointer) {
    ogl.normalPointer = OglPointer.direct(3, type, stride, pointer);
}

public final void glNormalPointer(int type, int stride, int pointer) {
    ogl.normalPointer = OglPointer.bufferObject(3, type, stride, requireArrayBuffer(), pointer);
}

public final void glColorPointer(int size, int type, int stride, DirectBuffer pointer) {
    ogl.colorPointer = OglPointer.direct(size, type, stride, pointer);
}

public final void glColorPointer(int size, int type, int stride, int pointer) {
    ogl.colorPointer = OglPointer.bufferObject(size, type, stride, requireArrayBuffer(), pointer);
}

public final void glCurrentPaletteMatrixOES(int matrixpaletteindex) {
    if (matrixpaletteindex < 0 || matrixpaletteindex >= OGL_MAX_PALETTE_MATRICES) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    ogl.currentPaletteMatrix = matrixpaletteindex;
}

public final void glLoadPaletteFromModelViewMatrixOES() {
    ogl.paletteMatrices[ogl.currentPaletteMatrix] = ogl.modelViewMatrix.clone();
    ogl.markPaletteMatrixChanged(ogl.currentPaletteMatrix);
}

public final void glMatrixIndexPointerOES(int size, int type, int stride, DirectBuffer pointer) {
    ogl.matrixIndexPointer = OglPointer.direct(size, type, stride, pointer);
}

public final void glMatrixIndexPointerOES(int size, int type, int stride, int pointer) {
    ogl.matrixIndexPointer = OglPointer.bufferObject(size, type, stride, requireArrayBuffer(), pointer);
}

public final void glWeightPointerOES(int size, int type, int stride, DirectBuffer pointer) {
    ogl.weightPointer = OglPointer.direct(size, type, stride, pointer);
}

public final void glWeightPointerOES(int size, int type, int stride, int pointer) {
    ogl.weightPointer = OglPointer.bufferObject(size, type, stride, requireArrayBuffer(), pointer);
}

public final void glDrawArrays(int mode, int first, int count) {
    host.markOpenGlesActivity();
    drawOgl(mode, first, count, null);
}

public final void glDrawElements(int mode, int count, int type, DirectBuffer indices) {
    host.markOpenGlesActivity();
    if (type != GraphicsOGL.GL_UNSIGNED_SHORT) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    if (!(indices instanceof ShortBuffer)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return;
    }
    drawOgl(mode, 0, count, OglIndexSource.direct((ShortBuffer) indices));
}

public final void glDrawElements(int mode, int count, int type, int indices) {
    host.markOpenGlesActivity();
    if (type != GraphicsOGL.GL_UNSIGNED_SHORT) {
        ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
        return;
    }
    if (indices < 0) {
        throw new ArrayIndexOutOfBoundsException("indices");
    }
    OglBufferObject buffer = ogl.boundElementArrayBuffer();
    if (buffer == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_OPERATION;
        return;
    }
    drawOgl(mode, 0, count, OglIndexSource.bufferObject(buffer, indices));
}

public final void glDrawElements(int mode, int count, DirectBuffer indices) {
    glDrawElements(mode, count, GraphicsOGL.GL_UNSIGNED_SHORT, indices);
}

public final void glFlush() {
    hardware.flush();
}

public final int glGetError() {
    int error = ogl.lastError;
    ogl.lastError = GraphicsOGL.GL_NO_ERROR;
    return error;
}

public final void glGetIntegerv(int pname, int[] params) {
    if (params == null) {
        throw new NullPointerException("params");
    }
    if (params.length < 1) {
        throw new IllegalArgumentException("params");
    }
    switch (pname) {
        case GraphicsOGL.GL_MAX_LIGHTS -> params[0] = 8;
        case GraphicsOGL.GL_MAX_TEXTURE_SIZE -> params[0] = 2048;
        case GraphicsOGL.GL_MAX_TEXTURE_UNITS -> params[0] = OGL_TEXTURE_UNIT_COUNT;
        case GraphicsOGL.GL_ACTIVE_TEXTURE -> params[0] = ogl.activeTexture;
        case GraphicsOGL.GL_CLIENT_ACTIVE_TEXTURE -> params[0] = ogl.clientActiveTexture;
        case GraphicsOGL.GL_MAX_PALETTE_MATRICES_OES -> params[0] = OGL_MAX_PALETTE_MATRICES;
        case GraphicsOGL.GL_MAX_VERTEX_UNITS_OES -> params[0] = OGL_MAX_VERTEX_UNITS;
        case GraphicsOGL.GL_MAX_VIEWPORT_DIMS -> {
            params[0] = 2048;
            if (params.length > 1) {
                params[1] = 2048;
            }
        }
        case 0xD56 -> params[0] = 1;
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}

public final void glGetFloatv(int pname, float[] params) {
    if (params == null) {
        throw new NullPointerException("params");
    }
    switch (pname) {
        case GraphicsOGL.GL_MODELVIEW_MATRIX -> copyFloatState(ogl.modelViewMatrix, params, 16);
        case GraphicsOGL.GL_PROJECTION_MATRIX -> copyFloatState(ogl.projectionMatrix, params, 16);
        case GraphicsOGL.GL_TEXTURE_MATRIX -> copyFloatState(ogl.activeTextureUnit().textureMatrix, params, 16);
        case GraphicsOGL.GL_VIEWPORT -> {
            requireLength(params, 4);
            params[0] = ogl.viewportX;
            params[1] = ogl.viewportY;
            params[2] = ogl.viewportWidth;
            params[3] = ogl.viewportHeight;
        }
        case GraphicsOGL.GL_DEPTH_RANGE -> {
            requireLength(params, 2);
            params[0] = ogl.depthRangeNear;
            params[1] = ogl.depthRangeFar;
        }
        default -> ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
    }
}
private static int clampOglChannel(float value) {
    if (Float.isNaN(value) || value <= 0.0f) {
        return 0;
    }
    if (value >= 1.0f) {
        return 255;
    }
    // Math.round() routes through a JNI Float.floatToRawIntBits call on this ART
    // build; the value is already clamped, so a direct rounded cast is equivalent.
    return (int) (value * 255.0f + 0.5f);
}

private static int packColor(float red, float green, float blue, float alpha) {
    return ((clampOglChannel(alpha) & 0xFF) << 24)
            | ((clampOglChannel(red) & 0xFF) << 16)
            | ((clampOglChannel(green) & 0xFF) << 8)
            | (clampOglChannel(blue) & 0xFF);
}

private static void requireLength(float[] params, int minimumLength) {
    if (params.length < minimumLength) {
        throw new IllegalArgumentException("params");
    }
}

private static void copyColor(float[] source, float[] destination) {
    destination[0] = source[0];
    destination[1] = source[1];
    destination[2] = source[2];
    destination[3] = source[3];
}

private static void copyFloatState(float[] source, float[] destination, int requiredLength) {
    requireLength(destination, requiredLength);
    System.arraycopy(source, 0, destination, 0, requiredLength);
}

private void syncColorMaterial(float[] color) {
    if (!ogl.colorMaterialTracksAmbientAndDiffuse()) {
        return;
    }
    copyColor(color, ogl.materialAmbient);
    copyColor(color, ogl.materialDiffuse);
}

private void transformLightDirection(float[] matrix, float x, float y, float z, float[] destination) {
    if (matrix == null) {
        destination[0] = x;
        destination[1] = y;
        destination[2] = z;
        return;
    }
    destination[0] = (matrix[0] * x) + (matrix[4] * y) + (matrix[8] * z);
    destination[1] = (matrix[1] * x) + (matrix[5] * y) + (matrix[9] * z);
    destination[2] = (matrix[2] * x) + (matrix[6] * y) + (matrix[10] * z);
}

private int sanitizeTextureScale(int scale) {
    if (scale == 1 || scale == 2 || scale == 4) {
        return scale;
    }
    ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    return 1;
}
private void loadMatrix(int mode, float[] matrix) {
    switch (mode) {
        case GraphicsOGL.GL_MODELVIEW -> {
            ogl.modelViewMatrix = matrix;
            ogl.standardModelViewConfigured = true;
            ogl.markModelViewMatrixChanged();
        }
        case GraphicsOGL.GL_PROJECTION -> {
            ogl.projectionMatrix = matrix;
            ogl.standardProjectionConfigured = true;
        }
        case GraphicsOGL.GL_TEXTURE -> ogl.activeTextureUnit().setTextureMatrix(matrix);
        case GraphicsOGL.GL_MATRIX_PALETTE_OES -> {
                ogl.paletteMatrices[ogl.currentPaletteMatrix] = matrix;
                ogl.markPaletteMatrixChanged(ogl.currentPaletteMatrix);
        }
        default -> {
            if (ogl.renderer.acceptsExtensionMatrixMode(mode)) {
                ogl.renderer.loadExtensionMatrix(mode, matrix);
            } else {
                ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            }
        }
    }
}

private void multiplyCurrentMatrix(float[] matrix) {
    switch (ogl.matrixMode) {
        case GraphicsOGL.GL_MODELVIEW -> {
            ogl.modelViewMatrix = multiplyMatrices(ogl.modelViewMatrix, matrix);
            ogl.standardModelViewConfigured = true;
            ogl.markModelViewMatrixChanged();
        }
        case GraphicsOGL.GL_PROJECTION -> {
            ogl.projectionMatrix = multiplyMatrices(ogl.projectionMatrix, matrix);
            ogl.standardProjectionConfigured = true;
        }
        case GraphicsOGL.GL_TEXTURE ->
                ogl.activeTextureUnit().setTextureMatrix(
                        multiplyMatrices(ogl.activeTextureUnit().textureMatrix, matrix));
        case GraphicsOGL.GL_MATRIX_PALETTE_OES -> {
                ogl.paletteMatrices[ogl.currentPaletteMatrix] = multiplyMatrices(
                        ogl.paletteMatrices[ogl.currentPaletteMatrix],
                        matrix);
                ogl.markPaletteMatrixChanged(ogl.currentPaletteMatrix);
        }
        default -> {
            if (ogl.renderer.acceptsExtensionMatrixMode(ogl.matrixMode)) {
                ogl.renderer.multiplyExtensionMatrix(ogl.matrixMode, matrix);
            } else {
                ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            }
        }
    }
}

private static float[] multiplyMatrices(float[] left, float[] right) {
    float[] product = new float[16];
    for (int column = 0; column < 4; column++) {
        int columnOffset = column * 4;
        for (int row = 0; row < 4; row++) {
            product[columnOffset + row] =
                    left[row] * right[columnOffset]
                            + left[row + 4] * right[columnOffset + 1]
                            + left[row + 8] * right[columnOffset + 2]
                            + left[row + 12] * right[columnOffset + 3];
        }
    }
    return product;
}

private void pushCurrentMatrix(int mode) {
    switch (mode) {
        case GraphicsOGL.GL_MODELVIEW -> ogl.modelViewStack.push(ogl.modelViewMatrix.clone());
        case GraphicsOGL.GL_PROJECTION -> ogl.projectionStack.push(ogl.projectionMatrix.clone());
        case GraphicsOGL.GL_TEXTURE -> ogl.activeTextureUnit().textureStack.push(ogl.activeTextureUnit().textureMatrix.clone());
        case GraphicsOGL.GL_MATRIX_PALETTE_OES ->
                ogl.paletteMatrixStacks[ogl.currentPaletteMatrix].push(ogl.paletteMatrices[ogl.currentPaletteMatrix].clone());
        default -> {
            if (ogl.renderer.acceptsExtensionMatrixMode(mode)) {
                ogl.renderer.pushExtensionMatrix(mode);
            } else {
                ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            }
        }
    }
}

private void popCurrentMatrix(int mode) {
    switch (mode) {
        case GraphicsOGL.GL_MODELVIEW -> {
            if (ogl.modelViewStack.isEmpty()) {
                ogl.lastError = GraphicsOGL.GL_STACK_UNDERFLOW;
                return;
            }
            ogl.modelViewMatrix = ogl.modelViewStack.pop();
            ogl.standardModelViewConfigured = true;
            ogl.markModelViewMatrixChanged();
        }
        case GraphicsOGL.GL_PROJECTION -> {
            if (ogl.projectionStack.isEmpty()) {
                ogl.lastError = GraphicsOGL.GL_STACK_UNDERFLOW;
                return;
            }
            ogl.projectionMatrix = ogl.projectionStack.pop();
            ogl.standardProjectionConfigured = true;
        }
        case GraphicsOGL.GL_TEXTURE -> {
            OglTextureUnit unit = ogl.activeTextureUnit();
            if (unit.textureStack.isEmpty()) {
                ogl.lastError = GraphicsOGL.GL_STACK_UNDERFLOW;
                return;
            }
            unit.setTextureMatrix(unit.textureStack.pop());
        }
        case GraphicsOGL.GL_MATRIX_PALETTE_OES -> {
            if (ogl.paletteMatrixStacks[ogl.currentPaletteMatrix].isEmpty()) {
                ogl.lastError = GraphicsOGL.GL_STACK_UNDERFLOW;
                return;
            }
            ogl.paletteMatrices[ogl.currentPaletteMatrix] = ogl.paletteMatrixStacks[ogl.currentPaletteMatrix].pop();
            ogl.markPaletteMatrixChanged(ogl.currentPaletteMatrix);
        }
        default -> {
            if (ogl.renderer.acceptsExtensionMatrixMode(mode)) {
                if (!ogl.renderer.popExtensionMatrix(mode)) {
                    ogl.lastError = GraphicsOGL.GL_STACK_UNDERFLOW;
                }
            } else {
                ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            }
        }
    }
}

private OglBufferObject requireArrayBuffer() {
    OglBufferObject buffer = ogl.boundArrayBuffer();
    if (buffer == null) {
        ogl.lastError = GraphicsOGL.GL_INVALID_OPERATION;
    }
    return buffer;
}

private static byte[] copyBufferBytes(DirectBuffer data) {
    if (data instanceof ByteBuffer byteBuffer) {
        int offset = DirectBufferFactory.getSegmentOffset(byteBuffer);
        int length = DirectBufferFactory.getSegmentLength(byteBuffer);
        byte[] raw = new byte[length];
        byteBuffer.get(offset, raw, 0, raw.length);
        return raw;
    }
    if (data instanceof ShortBuffer shortBuffer) {
        int offset = DirectBufferFactory.getSegmentOffset(shortBuffer);
        int length = DirectBufferFactory.getSegmentLength(shortBuffer);
        short[] raw = new short[length];
        shortBuffer.get(offset, raw, 0, raw.length);
        byte[] bytes = new byte[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int value = raw[i] & 0xFFFF;
            int byteOffset = i * 2;
            bytes[byteOffset] = (byte) value;
            bytes[byteOffset + 1] = (byte) (value >>> 8);
        }
        return bytes;
    }
    if (data instanceof FloatBuffer floatBuffer) {
        int offset = DirectBufferFactory.getSegmentOffset(floatBuffer);
        int length = DirectBufferFactory.getSegmentLength(floatBuffer);
        float[] raw = new float[length];
        floatBuffer.get(offset, raw, 0, raw.length);
        byte[] bytes = new byte[raw.length * 4];
        for (int i = 0; i < raw.length; i++) {
            int value = Float.floatToIntBits(raw[i]);
            int byteOffset = i * 4;
            bytes[byteOffset] = (byte) value;
            bytes[byteOffset + 1] = (byte) (value >>> 8);
            bytes[byteOffset + 2] = (byte) (value >>> 16);
            bytes[byteOffset + 3] = (byte) (value >>> 24);
        }
        return bytes;
    }
    if (data instanceof IntBuffer intBuffer) {
        int offset = DirectBufferFactory.getSegmentOffset(intBuffer);
        int length = DirectBufferFactory.getSegmentLength(intBuffer);
        int[] raw = new int[length];
        intBuffer.get(offset, raw, 0, raw.length);
        byte[] bytes = new byte[raw.length * 4];
        for (int i = 0; i < raw.length; i++) {
            int value = raw[i];
            int byteOffset = i * 4;
            bytes[byteOffset] = (byte) value;
            bytes[byteOffset + 1] = (byte) (value >>> 8);
            bytes[byteOffset + 2] = (byte) (value >>> 16);
            bytes[byteOffset + 3] = (byte) (value >>> 24);
        }
        return bytes;
    }
    throw new IllegalArgumentException("data");
}

private void drawOgl(int mode, int first, int count, OglIndexSource indexSource) {
    if (ogl.vertexPointer == null || count <= 0) {
        return;
    }
    if (usesHardwareRenderer() && hardware.draw(mode, first, count, indexSource)) {
        return;
    }
    hardware.flushForReadback();
    host.onSoftwareSurfaceMutation();
    SceneTrace.onDraw(ogl, mode, first, count, indexSource);
    software.draw(mode, first, count, indexSource);
    SceneTrace.endDraw();
}

/**
 * Temporary passive render diagnostics. Never reads GL state through getters,
 * so the shared GL error flag is not disturbed.
 */
private static final class SceneTrace {
    static final boolean ENABLED = traceEnabled();
    static final long WINDOW_NANOS = 1_000_000_000L;
    static int draws;
    static int dynamicDraws;
    static int staticDraws;
    static long staticTriangles;
    static long dynamicTriangles;
    static long dynamicIndices;
    static long windowStartNanos;
    static long lastSummaryNanos;
    static boolean insideDynamicDraw;
    static boolean rasterActive;
    static long rasterLastNanos;
    static int rasterTriangles;
    static int rasterClipped;
    static int rasterCulled;
    static int rasterDegenerate;
    static int rasterEmpty;
    static long rasterDepthFailed;
    static long rasterAlphaFailed;
    static long rasterPixelsWritten;

    private static boolean traceEnabled() {
        try {
            return Boolean.getBoolean("opendoja.ogl.trace");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void onDraw(OglState state, int mode, int first, int count, OglIndexSource indexSource) {
        if (!ENABLED) {
            return;
        }
        try {
            OglPointer vertexPointer = state.vertexPointer;
            if (vertexPointer == null || vertexPointer.pointer() == null) {
                return;
            }
            boolean dynamic = vertexPointer.pointer() instanceof HostDirectBuffers.HostShortBuffer shortBuffer
                    && shortBuffer.isDynamic();
            draws++;
            long now = System.nanoTime();
            if (windowStartNanos == 0L) {
                windowStartNanos = now;
                lastSummaryNanos = now;
            }
            int triangles = switch (mode) {
                case GraphicsOGL.GL_TRIANGLES -> count / 3;
                case GraphicsOGL.GL_TRIANGLE_STRIP -> Math.max(0, count - 2);
                default -> 0;
            };
            if (dynamic) {
                dynamicDraws++;
                dynamicTriangles += triangles;
                dynamicIndices += count;
                insideDynamicDraw = true;
                if (now - lastSummaryNanos >= 500_000_000L) {
                    lastSummaryNanos = now;
                    logDynamicDraw(state, mode, first, count, indexSource, vertexPointer);
                }
                if (count >= 500 && now - rasterLastNanos >= 1_000_000_000L) {
                    rasterLastNanos = now;
                    rasterTriangles = 0;
                    rasterClipped = 0;
                    rasterCulled = 0;
                    rasterDegenerate = 0;
                    rasterEmpty = 0;
                    rasterDepthFailed = 0;
                    rasterAlphaFailed = 0;
                    rasterPixelsWritten = 0;
                    rasterActive = true;
                }
            } else {
                staticDraws++;
                staticTriangles += triangles;
            }
            if (now - windowStartNanos >= WINDOW_NANOS) {
                System.out.println("OGLTRACE summary draws=" + draws
                        + " dynamicDraws=" + dynamicDraws + " dynamicTris=" + dynamicTriangles
                        + " dynamicIndices=" + dynamicIndices
                        + " staticDraws=" + staticDraws + " staticTris=" + staticTriangles);
                draws = 0;
                dynamicDraws = 0;
                staticDraws = 0;
                staticTriangles = 0;
                dynamicTriangles = 0;
                dynamicIndices = 0;
                windowStartNanos = now;
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect rendering.
        }
    }

    private static void logDynamicDraw(OglState state, int mode, int first, int count,
                                       OglIndexSource indexSource, OglPointer vertexPointer) {
        HostDirectBuffers.HostShortBuffer vertexBuffer =
                (HostDirectBuffers.HostShortBuffer) vertexPointer.pointer();
        short[] values = vertexBuffer.values();
        int offset = DirectBufferFactory.getSegmentOffset(vertexBuffer);
        int available = Math.min(DirectBufferFactory.getSegmentLength(vertexBuffer), values.length - offset);
        StringBuilder builder = new StringBuilder(320);
        builder.append("OGLTRACE dynamic mode=").append(mode)
                .append(" first=").append(first)
                .append(" count=").append(count)
                .append(" indexLen=").append(indexSource == null ? -1 : indexSource.elementCount())
                .append(" vtxBytes=").append(available)
                .append(" vtxSegOff=").append(offset)
                .append(" verts=").append(available / 3);
        builder.append(" v0=");
        appendVertex(builder, values, offset, 0, available);
        builder.append(" v1=");
        appendVertex(builder, values, offset, 1, available);
        builder.append(" v2=");
        appendVertex(builder, values, offset, 2, available);
        builder.append(" min=");
        appendBounds(builder, values, offset, available, false);
        builder.append(" max=");
        appendBounds(builder, values, offset, available, true);
        OglTexture texture = state.boundTexture();
        builder.append(" tex=").append(state.activeTextureUnit().boundTextureId);
        if (texture != null) {
            builder.append(' ').append(texture.width).append('x').append(texture.height)
                    .append(" fmt=").append(texture.baseFormat());
            appendTextureAlphaStats(builder, texture);
        }
        builder.append(" color=").append(Integer.toHexString(state.color));
        builder.append(" env=").append(state.activeTextureUnit().textureEnvMode)
                .append(" texOn=").append(state.textureEnabled())
                .append(" depthTest=").append(state.depthTestEnabled)
                .append(" depthMask=").append(state.depthMask)
                .append(" depthFunc=").append(state.depthFunc)
                .append(" depthRange=").append(state.depthRangeNear).append(',').append(state.depthRangeFar)
                .append(" alphaTest=").append(state.alphaTestEnabled)
                .append(" alphaFunc=").append(state.alphaFunc)
                .append(" alphaRef=").append(state.alphaRef)
                .append(" blend=").append(state.blendCapEnabled)
                .append(" blendFunc=").append(state.blendSrcFactor).append(',').append(state.blendDstFactor)
                .append(" cull=").append(state.cullFaceEnabled).append(' ').append(state.cullFace)
                .append(" frontFace=").append(state.frontFace)
                .append(" light=").append(state.lightingCapEnabled)
                .append(" colorArray=").append(state.colorArrayEnabled)
                .append(" normalArray=").append(state.normalArrayEnabled)
                .append(" texCoordArray=").append(state.clientTextureUnit().texCoordArrayEnabled)
                .append(" shade=").append(state.shadeModel);
        float[] mv = state.modelViewMatrix;
        if (mv != null) {
            builder.append(" mvT=").append(mv[12]).append(',').append(mv[13]).append(',').append(mv[14]);
        }
        float[] projection = state.projectionMatrix;
        if (projection != null) {
            float m10 = projection[10];
            float m14 = projection[14];
            if (Math.abs(m10 - 1f) > 0.00001f) {
                builder.append(" near=").append(m14 / (m10 - 1f))
                        .append(" far=").append(m14 / (m10 + 1f));
            }
        }
        System.out.println(builder.toString());
    }

    private static void appendTextureAlphaStats(StringBuilder builder, OglTexture texture) {
        int[] pixels = texture.pixels;
        if (pixels.length == 0) {
            return;
        }
        int min = 255;
        int max = 0;
        int zero = 0;
        int opaque = 0;
        for (int pixel : pixels) {
            int alpha = (pixel >>> 24) & 0xFF;
            min = Math.min(min, alpha);
            max = Math.max(max, alpha);
            if (alpha == 0) {
                zero++;
            } else if (alpha == 255) {
                opaque++;
            }
        }
        builder.append(" aMin=").append(min).append(" aMax=").append(max)
                .append(" a0=").append(zero).append(" a255=").append(opaque)
                .append('/').append(pixels.length)
                .append(" fil=").append(texture.minFilter).append(',').append(texture.magFilter)
                .append(" wrap=").append(texture.wrapS).append(',').append(texture.wrapT);
    }

    private static void appendVertex(StringBuilder builder, short[] values, int offset, int vertex, int available) {
        int base = offset + (vertex * 3);
        if (base + 2 >= offset + available) {
            builder.append("oob");
            return;
        }
        builder.append('(').append(values[base]).append(',')
                .append(values[base + 1]).append(',')
                .append(values[base + 2]).append(')');
    }

    private static void appendBounds(StringBuilder builder, short[] values, int offset, int available,
                                     boolean maximum) {
        int vertices = Math.min(available / 3, 512);
        int x = maximum ? Short.MIN_VALUE : Short.MAX_VALUE;
        int y = x;
        int z = x;
        for (int i = 0; i < vertices; i++) {
            int base = offset + (i * 3);
            int vx = values[base];
            int vy = values[base + 1];
            int vz = values[base + 2];
            x = maximum ? Math.max(x, vx) : Math.min(x, vx);
            y = maximum ? Math.max(y, vy) : Math.min(y, vy);
            z = maximum ? Math.max(z, vz) : Math.min(z, vz);
        }
        builder.append('(').append(x).append(',').append(y).append(',').append(z).append(')');
    }

    static void endDraw() {
        insideDynamicDraw = false;
        if (!rasterActive) {
            return;
        }
        rasterActive = false;
        System.out.println("OGLTRACE raster tris=" + rasterTriangles
                + " clipped=" + rasterClipped
                + " culled=" + rasterCulled
                + " degenerate=" + rasterDegenerate
                + " empty=" + rasterEmpty
                + " depthFailPx=" + rasterDepthFailed
                + " alphaFailPx=" + rasterAlphaFailed
                + " writtenPx=" + rasterPixelsWritten);
    }

    private SceneTrace() {
    }
}

    // Shared transform and buffer-decoding helpers used by both backends.
void transformVertex(ClipVector target, ClipVector eyeTarget, float x, float y, float z, int vertexIndex, boolean useExtensionMatrices) {
    if (ogl.usesMatrixPalette()) {
        float eyeX = 0f;
        float eyeY = 0f;
        float eyeZ = 0f;
        float eyeW = 0f;
        int unitCount = Math.min(
                Math.min(ogl.matrixIndexPointer.size(), ogl.weightPointer.size()),
                OGL_MAX_VERTEX_UNITS);
        boolean weighted = false;
        for (int unit = 0; unit < unitCount; unit++) {
            int matrixIndex = readUnsignedByteComponent(ogl.matrixIndexPointer, vertexIndex, unit);
            if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                return;
            }
            if (matrixIndex < 0 || matrixIndex >= OGL_MAX_PALETTE_MATRICES) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return;
            }
            float weight = readFloatComponent(ogl.weightPointer, vertexIndex, unit);
            if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                return;
            }
            if (absFloat(weight) < 0.000001f) {
                continue;
            }
            multiply(clipVectorTemp, ogl.paletteMatrices[matrixIndex], x, y, z, 1f);
            eyeX += clipVectorTemp.x * weight;
            eyeY += clipVectorTemp.y * weight;
            eyeZ += clipVectorTemp.z * weight;
            eyeW += clipVectorTemp.w * weight;
            weighted = true;
        }
        if (weighted) {
            eyeTarget.set(eyeX, eyeY, eyeZ, eyeW);
            multiply(target, ogl.projectionMatrix, eyeX, eyeY, eyeZ, eyeW);
            return;
        }
    }
    if (useExtensionMatrices) {
        multiply(clipVectorTemp, ogl.renderer.extensionWorldMatrix(), x, y, z, 1f);
        eyeTarget.set(clipVectorTemp.x, clipVectorTemp.y, clipVectorTemp.z, clipVectorTemp.w);
        multiply(target, ogl.renderer.extensionCameraMatrix(), clipVectorTemp.x, clipVectorTemp.y, clipVectorTemp.z, clipVectorTemp.w);
        return;
    }
    multiply(eyeTarget, ogl.modelViewMatrix, x, y, z, 1f);
    multiply(target, ogl.projectionMatrix, eyeTarget.x, eyeTarget.y, eyeTarget.z, eyeTarget.w);
}

void transformNormal(ClipVector target, float nx, float ny, float nz, int vertexIndex) {
    if (ogl.usesMatrixPalette()) {
        float eyeNx = 0f;
        float eyeNy = 0f;
        float eyeNz = 0f;
        int unitCount = Math.min(
                Math.min(ogl.matrixIndexPointer.size(), ogl.weightPointer.size()),
                OGL_MAX_VERTEX_UNITS);
        boolean weighted = false;
        for (int unit = 0; unit < unitCount; unit++) {
            int matrixIndex = readUnsignedByteComponent(ogl.matrixIndexPointer, vertexIndex, unit);
            if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                return;
            }
            float weight = readFloatComponent(ogl.weightPointer, vertexIndex, unit);
            if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                return;
            }
            if (absFloat(weight) < 0.000001f) {
                continue;
            }
            transformNormalByMatrix(
                    ogl.normalMatrixForPalette(matrixIndex),
                    ogl.normalMatrixValidForPalette(matrixIndex),
                    nx,
                    ny,
                    nz,
                    clipVectorTemp);
            eyeNx += clipVectorTemp.x * weight;
            eyeNy += clipVectorTemp.y * weight;
            eyeNz += clipVectorTemp.z * weight;
            weighted = true;
        }
        if (weighted) {
            target.set(eyeNx, eyeNy, eyeNz, 0f);
            return;
        }
    }
    transformNormalByMatrix(
            ogl.normalMatrixForModelView(),
            ogl.normalMatrixValidForModelView(),
            nx,
            ny,
            nz,
            target);
}

private void transformNormalByMatrix(float[] normalMatrix, boolean valid, float nx, float ny, float nz, ClipVector target) {
    if (!valid) {
        target.set(nx, ny, nz, 0f);
        return;
    }
    target.set(
            (normalMatrix[0] * nx) + (normalMatrix[3] * ny) + (normalMatrix[6] * nz),
            (normalMatrix[1] * nx) + (normalMatrix[4] * ny) + (normalMatrix[7] * nz),
            (normalMatrix[2] * nx) + (normalMatrix[5] * ny) + (normalMatrix[8] * nz),
            0f
    );
    if (ogl.normalizeEnabled || ogl.rescaleNormalEnabled) {
        float length = vectorLength(target.x, target.y, target.z);
        if (length > 0.000001f) {
            float inverseLength = 1f / length;
            target.x *= inverseLength;
            target.y *= inverseLength;
            target.z *= inverseLength;
        }
    }
}

void resolveEyeNormal(ClipVector target, int vertexIndex, boolean backFace) {
    float nx;
    float ny;
    float nz;
    if (!ogl.normalArrayEnabled || ogl.normalPointer == null) {
        nx = ogl.currentNormal[0];
        ny = ogl.currentNormal[1];
        nz = ogl.currentNormal[2];
    } else {
        nx = readNormalComponent(ogl.normalPointer, vertexIndex, 0);
        if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
            target.set(0f, 0f, 1f, 0f);
            return;
        }
        ny = readNormalComponent(ogl.normalPointer, vertexIndex, 1);
        if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
            target.set(0f, 0f, 1f, 0f);
            return;
        }
        nz = readNormalComponent(ogl.normalPointer, vertexIndex, 2);
        if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
            target.set(0f, 0f, 1f, 0f);
            return;
        }
    }
    transformNormal(target, nx, ny, nz, vertexIndex);
    float length = vectorLength(target.x, target.y, target.z);
    if (length <= 0.000001f) {
        target.set(0f, 0f, 0f, 0f);
        return;
    }
    float inverseLength = 1f / length;
    target.x *= inverseLength;
    target.y *= inverseLength;
    target.z *= inverseLength;
    if (backFace) {
        target.x = -target.x;
        target.y = -target.y;
        target.z = -target.z;
    }
}

int computeLightingColor(ClipVector eyePosition, float normalX, float normalY, float normalZ,
                                 float ambientRed, float ambientGreen, float ambientBlue,
                                 float diffuseRed, float diffuseGreen, float diffuseBlue, float diffuseAlpha,
                                 float specularRed, float specularGreen, float specularBlue,
                                 float emissionRed, float emissionGreen, float emissionBlue,
                                 float shininess) {
    float red = emissionRed + (ambientRed * ogl.lightModelAmbient[0]);
    float green = emissionGreen + (ambientGreen * ogl.lightModelAmbient[1]);
    float blue = emissionBlue + (ambientBlue * ogl.lightModelAmbient[2]);
    for (int i = 0; i < ogl.lights.length; i++) {
        OglLight light = ogl.lights[i];
        if (!ogl.lightEnabled[i]) {
            continue;
        }
        float lightX;
        float lightY;
        float lightZ;
        float attenuation;
        float lightDistance = 0f;
        if (absFloat(light.position[3]) < 0.000001f) {
            float length = vectorLength(light.position[0], light.position[1], light.position[2]);
            if (length <= 0.000001f) {
                lightX = 0f;
                lightY = 0f;
                lightZ = 0f;
            } else {
                float inverseLength = 1f / length;
                lightX = light.position[0] * inverseLength;
                lightY = light.position[1] * inverseLength;
                lightZ = light.position[2] * inverseLength;
            }
            attenuation = 1f;
        } else {
            float lx = light.position[0] - eyePosition.x;
            float ly = light.position[1] - eyePosition.y;
            float lz = light.position[2] - eyePosition.z;
            lightDistance = vectorLength(lx, ly, lz);
            if (lightDistance > 0.000001f) {
                float inverseDistance = 1f / lightDistance;
                lightX = lx * inverseDistance;
                lightY = ly * inverseDistance;
                lightZ = lz * inverseDistance;
            } else {
                lightX = 0f;
                lightY = 0f;
                lightZ = 0f;
            }
            attenuation = 1f / Math.max(0.000001f, light.constantAttenuation
                    + (light.linearAttenuation * lightDistance)
                    + (light.quadraticAttenuation * lightDistance * lightDistance));
        }
        float spotlight = 1f;
        if (light.spotCutoff != 180f) {
            float spotLength = vectorLength(light.spotDirection[0], light.spotDirection[1], light.spotDirection[2]);
            float spotX = 0f;
            float spotY = 0f;
            float spotZ = 0f;
            if (spotLength > 0.000001f) {
                float inverseSpotLength = 1f / spotLength;
                spotX = light.spotDirection[0] * inverseSpotLength;
                spotY = light.spotDirection[1] * inverseSpotLength;
                spotZ = light.spotDirection[2] * inverseSpotLength;
            }
            float spotCos = dot(-lightX, -lightY, -lightZ, spotX, spotY, spotZ);
            float cutoffCos = (float) Math.cos(Math.toRadians(light.spotCutoff));
            if (spotCos < cutoffCos) {
                spotlight = 0f;
            } else {
                spotlight = (float) Math.pow(Math.max(0f, spotCos), light.spotExponent);
            }
        }
        float scale = attenuation * spotlight;
        if (scale <= 0f) {
            continue;
        }
        red += ambientRed * light.ambient[0] * scale;
        green += ambientGreen * light.ambient[1] * scale;
        blue += ambientBlue * light.ambient[2] * scale;

        float diffuseFactor = Math.max(0f, dot(normalX, normalY, normalZ, lightX, lightY, lightZ));
        red += diffuseRed * light.diffuse[0] * diffuseFactor * scale;
        green += diffuseGreen * light.diffuse[1] * diffuseFactor * scale;
        blue += diffuseBlue * light.diffuse[2] * diffuseFactor * scale;

        if (diffuseFactor > 0f && shininess > 0f
                && (specularRed != 0f || specularGreen != 0f || specularBlue != 0f)
                && (light.specular[0] != 0f || light.specular[1] != 0f || light.specular[2] != 0f)) {
            float halfX = lightX;
            float halfY = lightY;
            float halfZ = lightZ + 1f;
            float halfLength = vectorLength(halfX, halfY, halfZ);
            if (halfLength > 0.000001f) {
                float inverseHalfLength = 1f / halfLength;
                halfX *= inverseHalfLength;
                halfY *= inverseHalfLength;
                halfZ *= inverseHalfLength;
            }
            float specularFactor = (float) Math.pow(Math.max(0f,
                    dot(normalX, normalY, normalZ, halfX, halfY, halfZ)), shininess);
            red += specularRed * light.specular[0] * specularFactor * scale;
            green += specularGreen * light.specular[1] * specularFactor * scale;
            blue += specularBlue * light.specular[2] * specularFactor * scale;
        }
    }
    return packColor(red, green, blue, diffuseAlpha);
}

float readFloatComponent(OglPointer pointer, int vertexIndex, int componentIndex) {
    int byteOffset = pointer.componentByteOffset(vertexIndex, componentIndex);
    if (pointer.bufferObject() != null) {
        return readFloat(pointer.bufferObject().data(), byteOffset);
    }
    if (pointer.pointer() instanceof FloatBuffer floatBuffer) {
        if ((byteOffset & 3) != 0) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        int elementIndex = DirectBufferFactory.getSegmentOffset(floatBuffer) + (byteOffset / 4);
        if (elementIndex < 0 || elementIndex >= DirectBufferFactory.getSegmentOffset(floatBuffer)
                + DirectBufferFactory.getSegmentLength(floatBuffer)) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        return DirectBufferFactory.getFloat(floatBuffer, elementIndex);
    }
    if (pointer.pointer() instanceof ByteBuffer byteBuffer) {
        byte[] data = DirectBufferFactory.peekByteArray(byteBuffer);
        if (data != null) {
            int segmentOffset = DirectBufferFactory.getSegmentOffset(byteBuffer);
            int baseIndex = segmentOffset + byteOffset;
            if (baseIndex < 0 || baseIndex + 4 > segmentOffset + DirectBufferFactory.getSegmentLength(byteBuffer)) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return 0f;
            }
            return readFloat(data, baseIndex);
        }
        byte[] raw = new byte[4];
        int baseIndex = DirectBufferFactory.getSegmentOffset(byteBuffer) + byteOffset;
        if (baseIndex < 0 || baseIndex + raw.length > DirectBufferFactory.getSegmentOffset(byteBuffer)
                + DirectBufferFactory.getSegmentLength(byteBuffer)) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        byteBuffer.get(baseIndex, raw, 0, raw.length);
        return readFloat(raw, 0);
    }
    ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    return 0f;
}

/**
 * Reads one component from a vertex-array pointer regardless of its declared GL type.
 * Mobile titles commonly store geometry as GL_SHORT (integer world units scaled by the
 * model-view matrix) while colors stay GL_FLOAT. The software rasterizer previously
 * accepted only GL_FLOAT vertices, which silently dropped every draw in those titles.
 */
float readArrayComponent(OglPointer pointer, int vertexIndex, int componentIndex) {
    int byteOffset = pointer.componentByteOffset(vertexIndex, componentIndex);
    if (pointer.bufferObject() != null) {
        return readBufferComponent(pointer, byteOffset);
    }
    return switch (pointer.type()) {
        case GraphicsOGL.GL_FLOAT -> readFloatComponent(pointer, vertexIndex, componentIndex);
        case GraphicsOGL.GL_SHORT -> readDirectShortComponent(pointer, byteOffset);
        case GraphicsOGL.GL_BYTE -> readDirectByteComponent(pointer, byteOffset);
        case GraphicsOGL.GL_UNSIGNED_BYTE -> readUnsignedByteComponent(pointer, vertexIndex, componentIndex);
        default -> {
            ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            yield 0f;
        }
    };
}

float readNormalComponent(OglPointer pointer, int vertexIndex, int componentIndex) {
    int byteOffset = pointer.componentByteOffset(vertexIndex, componentIndex);
    if (pointer.bufferObject() != null) {
        return readBufferComponent(pointer, byteOffset);
    }
    return switch (pointer.type()) {
        case GraphicsOGL.GL_FLOAT -> readFloatComponent(pointer, vertexIndex, componentIndex);
        case GraphicsOGL.GL_BYTE -> readDirectByteComponent(pointer, byteOffset);
        case GraphicsOGL.GL_SHORT -> readDirectShortComponent(pointer, byteOffset);
        default -> {
            ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            yield 0f;
        }
    };
}

/**
 * Reads a component from a buffer object. GL_FLOAT buffers are decoded into a
 * cached float view once per upload, avoiding a Float.intBitsToFloat JNI call
 * for every vertex component on every draw.
 */
private float readBufferComponent(OglPointer pointer, int byteOffset) {
    OglBufferObject buffer = pointer.bufferObject();
    byte[] data = buffer.data();
    if (pointer.type() == GraphicsOGL.GL_FLOAT
            && byteOffset >= 0 && (byteOffset & 3) == 0 && byteOffset + 3 < data.length) {
        float[] view = buffer.floatView();
        int index = byteOffset >>> 2;
        if (index < view.length) {
            return view[index];
        }
    }
    return readTypedFloat(pointer.type(), data, byteOffset);
}

int readUnsignedByteComponent(OglPointer pointer, int vertexIndex, int componentIndex) {
    int byteOffset = pointer.componentByteOffset(vertexIndex, componentIndex);
    if (pointer.bufferObject() != null) {
        return readUnsignedByte(pointer.bufferObject().data(), byteOffset);
    }
    if (pointer.pointer() instanceof ByteBuffer byteBuffer) {
        byte[] data = DirectBufferFactory.peekByteArray(byteBuffer);
        if (data != null) {
            int segmentOffset = DirectBufferFactory.getSegmentOffset(byteBuffer);
            int baseIndex = segmentOffset + byteOffset;
            if (baseIndex < 0 || baseIndex >= segmentOffset + DirectBufferFactory.getSegmentLength(byteBuffer)) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return 0;
            }
            return data[baseIndex] & 0xFF;
        }
        int baseIndex = DirectBufferFactory.getSegmentOffset(byteBuffer) + byteOffset;
        if (baseIndex < 0 || baseIndex >= DirectBufferFactory.getSegmentOffset(byteBuffer)
                + DirectBufferFactory.getSegmentLength(byteBuffer)) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0;
        }
        byte[] raw = new byte[1];
        byteBuffer.get(baseIndex, raw, 0, 1);
        return raw[0] & 0xFF;
    }
    ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    return 0;
}

private float readDirectByteComponent(OglPointer pointer, int byteOffset) {
    if (!(pointer.pointer() instanceof ByteBuffer byteBuffer)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return 0f;
    }
    byte[] data = DirectBufferFactory.peekByteArray(byteBuffer);
    if (data != null) {
        int segmentOffset = DirectBufferFactory.getSegmentOffset(byteBuffer);
        int baseIndex = segmentOffset + byteOffset;
        if (baseIndex < 0 || baseIndex >= segmentOffset + DirectBufferFactory.getSegmentLength(byteBuffer)) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        return data[baseIndex];
    }
    int baseIndex = DirectBufferFactory.getSegmentOffset(byteBuffer) + byteOffset;
    if (baseIndex < 0 || baseIndex >= DirectBufferFactory.getSegmentOffset(byteBuffer)
            + DirectBufferFactory.getSegmentLength(byteBuffer)) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return 0f;
    }
    byte[] raw = new byte[1];
    byteBuffer.get(baseIndex, raw, 0, 1);
    return raw[0];
}

private float readDirectShortComponent(OglPointer pointer, int byteOffset) {
    if (pointer.pointer() instanceof ShortBuffer shortBuffer) {
        if ((byteOffset & 1) != 0) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        int elementIndex = DirectBufferFactory.getSegmentOffset(shortBuffer) + (byteOffset / 2);
        if (elementIndex < 0 || elementIndex >= DirectBufferFactory.getSegmentOffset(shortBuffer)
                + DirectBufferFactory.getSegmentLength(shortBuffer)) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        return DirectBufferFactory.getShort(shortBuffer, elementIndex);
    }
    if (pointer.pointer() instanceof ByteBuffer byteBuffer) {
        byte[] data = DirectBufferFactory.peekByteArray(byteBuffer);
        if (data != null) {
            int segmentOffset = DirectBufferFactory.getSegmentOffset(byteBuffer);
            int baseIndex = segmentOffset + byteOffset;
            if (baseIndex < 0 || baseIndex + 1 >= segmentOffset + DirectBufferFactory.getSegmentLength(byteBuffer)) {
                ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
                return 0f;
            }
            return readSignedShort(data, baseIndex);
        }
        int baseIndex = DirectBufferFactory.getSegmentOffset(byteBuffer) + byteOffset;
        if (baseIndex < 0 || baseIndex + 1 >= DirectBufferFactory.getSegmentOffset(byteBuffer)
                + DirectBufferFactory.getSegmentLength(byteBuffer)) {
            ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
            return 0f;
        }
        byte[] raw = new byte[2];
        byteBuffer.get(baseIndex, raw, 0, raw.length);
        return readSignedShort(raw, 0);
    }
    ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
    return 0f;
}

private float readTypedFloat(int type, byte[] data, int byteOffset) {
    return switch (type) {
        case GraphicsOGL.GL_FLOAT -> readFloat(data, byteOffset);
        case GraphicsOGL.GL_BYTE -> readSignedByte(data, byteOffset);
        case GraphicsOGL.GL_SHORT -> readSignedShort(data, byteOffset);
        default -> {
            ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
            yield 0f;
        }
    };
}

private static int readUnsignedShort(byte[] data, int byteOffset) {
    if (byteOffset < 0 || byteOffset + 1 >= data.length) {
        return -1;
    }
    return (data[byteOffset] & 0xFF) | ((data[byteOffset + 1] & 0xFF) << 8);
}

private float readSignedByte(byte[] data, int byteOffset) {
    if (byteOffset < 0 || byteOffset >= data.length) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return 0f;
    }
    return data[byteOffset];
}

private float readSignedShort(byte[] data, int byteOffset) {
    if (byteOffset < 0 || byteOffset + 1 >= data.length) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return 0f;
    }
    return (short) ((data[byteOffset] & 0xFF) | ((data[byteOffset + 1] & 0xFF) << 8));
}

private int readUnsignedByte(byte[] data, int byteOffset) {
    if (byteOffset < 0 || byteOffset >= data.length) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return 0;
    }
    return data[byteOffset] & 0xFF;
}

private float readFloat(byte[] data, int byteOffset) {
    if (byteOffset < 0 || byteOffset + 3 >= data.length) {
        ogl.lastError = GraphicsOGL.GL_INVALID_VALUE;
        return 0f;
    }
    int bits = (data[byteOffset] & 0xFF)
            | ((data[byteOffset + 1] & 0xFF) << 8)
            | ((data[byteOffset + 2] & 0xFF) << 16)
            | ((data[byteOffset + 3] & 0xFF) << 24);
    return Float.intBitsToFloat(bits);
}

void drawRasterTriangle(RasterVertex v0, RasterVertex v1, RasterVertex v2, int[] pixels, float[] depthBuffer,
                                Rectangle clip, int width, int height, RasterVertex[] clipInput,
                                RasterVertex[] clipScratch, RasterVertex project0, RasterVertex project1,
                                RasterVertex project2) {
    if (isFullyInsideClipVolume(v0) && isFullyInsideClipVolume(v1) && isFullyInsideClipVolume(v2)) {
        rasterizeProjectedTriangle(projectClipVertex(project0, v0),
                projectClipVertex(project1, v1),
                projectClipVertex(project2, v2),
                pixels, depthBuffer, clip, width, height);
        return;
    }
    RasterVertex[] input = clipInput;
    RasterVertex[] scratch = clipScratch;
    input[0].copyFrom(v0);
    input[1].copyFrom(v1);
    input[2].copyFrom(v2);
    int count = 3;
    for (int plane = 0; plane < 6 && count > 0; plane++) {
        count = clipPolygonAgainstPlane(input, count, scratch, plane);
        RasterVertex[] swap = input;
        input = scratch;
        scratch = swap;
    }
    if (count < 3) {
        if (SceneTrace.rasterActive) {
            SceneTrace.rasterClipped++;
        }
        return;
    }
    RasterVertex first = input[0];
    for (int i = 1; i + 1 < count; i++) {
        rasterizeProjectedTriangle(projectClipVertex(project0, first),
                projectClipVertex(project1, input[i]),
                projectClipVertex(project2, input[i + 1]),
                pixels,
                depthBuffer,
                clip,
                width,
                height);
    }
}

private static boolean isFullyInsideClipVolume(RasterVertex vertex) {
    return vertex.clipX + vertex.clipW >= 0f
            && vertex.clipW - vertex.clipX >= 0f
            && vertex.clipY + vertex.clipW >= 0f
            && vertex.clipW - vertex.clipY >= 0f
            && vertex.clipZ + vertex.clipW >= 0f
            && vertex.clipW - vertex.clipZ >= 0f;
}

private int clipPolygonAgainstPlane(RasterVertex[] input, int inputCount, RasterVertex[] output, int plane) {    int outputCount = 0;
    RasterVertex previous = input[inputCount - 1];
    float previousDistance = clipDistance(previous, plane);
    boolean previousInside = previousDistance >= 0f;
    for (int i = 0; i < inputCount; i++) {
        RasterVertex current = input[i];
        float currentDistance = clipDistance(current, plane);
        boolean currentInside = currentDistance >= 0f;
        if (currentInside != previousInside) {
            float t = previousDistance / (previousDistance - currentDistance);
            output[outputCount++].interpolateFrom(previous, current, t);
        }
        if (currentInside) {
            output[outputCount++].copyFrom(current);
        }
        previous = current;
        previousDistance = currentDistance;
        previousInside = currentInside;
    }
    return outputCount;
}

private float clipDistance(RasterVertex vertex, int plane) {
    return switch (plane) {
        case 0 -> vertex.clipX + vertex.clipW;
        case 1 -> vertex.clipW - vertex.clipX;
        case 2 -> vertex.clipY + vertex.clipW;
        case 3 -> vertex.clipW - vertex.clipY;
        case 4 -> vertex.clipZ + vertex.clipW;
        case 5 -> vertex.clipW - vertex.clipZ;
        default -> 0f;
    };
}

private RasterVertex projectClipVertex(RasterVertex projected, RasterVertex clipVertex) {
    float w = absFloat(clipVertex.clipW) < 0.000001f
            ? (clipVertex.clipW < 0f ? -0.000001f : 0.000001f)
            : clipVertex.clipW;
    float ndcX = clipVertex.clipX / w;
    float ndcY = clipVertex.clipY / w;
    float ndcZ = clipVertex.clipZ / w;
    float windowDepth = ogl.depthRangeNear + (((ndcZ + 1f) * 0.5f) * (ogl.depthRangeFar - ogl.depthRangeNear));
    float depth = 1f - windowDepth;
    float reciprocalW = 1f / Math.max(0.000001f, absFloat(w));
    projected.set(
            clipVertex.clipX,
            clipVertex.clipY,
            clipVertex.clipZ,
            clipVertex.clipW,
            rasterViewportX(ndcX),
            rasterViewportY(ndcY),
            depth,
            reciprocalW,
            clipVertex.u0,
            clipVertex.v0,
            clipVertex.q0,
            clipVertex.u1,
            clipVertex.v1,
            clipVertex.q1,
            clipVertex.color,
            clipVertex.backColor
    );
    return projected;
}

static RasterVertex[] createRasterVertexArray(int length) {
    RasterVertex[] vertices = new RasterVertex[length];
    for (int i = 0; i < vertices.length; i++) {
        vertices[i] = new RasterVertex();
    }
    return vertices;
}

float viewportX(float ndcX) {
    return ogl.viewportX + (((ndcX + 1f) * 0.5f) * ogl.viewportWidth);
}

float viewportY(float ndcY) {
    float bottomLeftY = ogl.viewportY + (((ndcY + 1f) * 0.5f) * ogl.viewportHeight);
    return host.surface().height() - bottomLeftY;
}

float rasterViewportX(float ndcX) {
    return viewportX(ndcX) * host.surface().oglRenderScaleX();
}

float rasterViewportY(float ndcY) {
    float bottomLeftY = ogl.viewportY + (((ndcY + 1f) * 0.5f) * ogl.viewportHeight);
    return host.surface().oglRenderHeight() - (bottomLeftY * host.surface().oglRenderScaleY());
}

private void rasterizeProjectedTriangle(RasterVertex v0, RasterVertex v1, RasterVertex v2, int[] pixels, float[] depthBuffer,
                                        Rectangle clip, int width, int height) {
    boolean traceRaster = SceneTrace.rasterActive;
    if (traceRaster) {
        SceneTrace.rasterTriangles++;
    }
    // Compute the signed area once and reuse it for culling and two-sided
    // lighting; isCulled()/isFrontFacing() used to recompute the same edge
    // function before the rasterizer ran.
    float area = edge(v0.x, v0.y, v1.x, v1.y, v2.x, v2.y);
    if (absFloat(area) < 0.0001f) {
        if (traceRaster) {
            SceneTrace.rasterDegenerate++;
        }
        return;
    }
    boolean frontFacing = ogl.frontFace == GraphicsOGL.GL_CCW ? area > 0f : area <= 0f;
    if (ogl.cullFaceEnabled) {
        boolean culled = switch (ogl.cullFace) {
            case GraphicsOGL.GL_FRONT -> frontFacing;
            case GraphicsOGL.GL_FRONT_AND_BACK -> true;
            default -> !frontFacing;
        };
        if (culled) {
            if (traceRaster) {
                SceneTrace.rasterCulled++;
            }
            return;
        }
    }
    boolean useBackColor = ogl.lightModelTwoSide && !frontFacing;
    int flatColor = 0;
    boolean flatColorKnown = false;
    if (ogl.shadeModel == GraphicsOGL.GL_FLAT) {
        flatColor = useBackColor ? v2.backColor : v2.color;
        flatColorKnown = true;
    } else if (v0.color == v1.color && v1.color == v2.color
            && v0.backColor == v1.backColor && v1.backColor == v2.backColor) {
        flatColor = useBackColor ? v0.backColor : v0.color;
        flatColorKnown = true;
    }
    int minX = clamp((int) Math.floor(Math.min(v0.x, Math.min(v1.x, v2.x))), 0, width - 1);
    int maxX = clamp((int) Math.ceil(Math.max(v0.x, Math.max(v1.x, v2.x))), 0, width - 1);
    int minY = clamp((int) Math.floor(Math.min(v0.y, Math.min(v1.y, v2.y))), 0, height - 1);
    int maxY = clamp((int) Math.ceil(Math.max(v0.y, Math.max(v1.y, v2.y))), 0, height - 1);
    if (clip != null) {
        minX = Math.max(minX, clip.x);
        minY = Math.max(minY, clip.y);
        maxX = Math.min(maxX, clip.x + clip.width - 1);
        maxY = Math.min(maxY, clip.y + clip.height - 1);
    }
    if (minX > maxX || minY > maxY) {
        if (traceRaster) {
            SceneTrace.rasterEmpty++;
        }
        return;
    }
    float inverseArea = 1f / area;
    float reciprocalW0 = v0.reciprocalW;
    float reciprocalW1 = v1.reciprocalW;
    float reciprocalW2 = v2.reciprocalW;
    boolean depthEnabled = ogl.depthEnabled();
    int depthFunc = ogl.depthFunc;
    boolean alphaTestEnabled = ogl.alphaTestEnabled;
    int alphaFunc = ogl.alphaFunc;
    float alphaRef = ogl.alphaRef;
    int alphaTestMode = alphaTestMode(alphaTestEnabled, alphaFunc, alphaRef);
    boolean blendEnabled = ogl.blendCapEnabled;
    int blendMode = blendMode(blendEnabled, ogl.blendSrcFactor, ogl.blendDstFactor);
    boolean depthWriteEnabled = ogl.depthMask && depthEnabled;
    OglTextureUnit unit0 = ogl.textureUnit(0);
    OglTextureUnit unit1 = ogl.textureUnit(1);
    OglTexture texture0 = ogl.textureEnabled(0) ? ogl.boundTexture(0) : null;
    OglTexture texture1 = ogl.textureEnabled(1) ? ogl.boundTexture(1) : null;
    boolean texturing0 = texture0 != null;
    boolean texturing1 = texture1 != null;
    // Most geometry uses the default q=1 texture coordinates. In that case the
    // interpolated q is exactly 1, so skip the per-pixel q interpolation and
    // division entirely.
    boolean constantQ0 = texturing0 && v0.q0 == 1f && v1.q0 == 1f && v2.q0 == 1f;
    boolean constantQ1 = texturing1 && v0.q1 == 1f && v1.q1 == 1f && v2.q1 == 1f;
    int envFastMode0 = texturing0 ? textureEnvFastMode(unit0) : ENV_FAST_NONE;
    int envFastMode1 = texturing1 ? textureEnvFastMode(unit1) : ENV_FAST_NONE;
    int envRgbScale0 = unit0.rgbScale;
    int envRgbScale1 = unit1.rgbScale;
    int envAlphaScale0 = unit0.alphaScale;
    int envAlphaScale1 = unit1.alphaScale;
    int baseFormat0 = texturing0 ? texture0.baseFormat() : 0;
    int baseFormat1 = texturing1 ? texture1.baseFormat() : 0;
    float startX = minX + 0.5f;
    float startY = minY + 0.5f;
    float edge0Row = edge(v1.x, v1.y, v2.x, v2.y, startX, startY);
    float edge1Row = edge(v2.x, v2.y, v0.x, v0.y, startX, startY);
    float edge2Row = edge(v0.x, v0.y, v1.x, v1.y, startX, startY);
    float edge0StepX = v2.y - v1.y;
    float edge1StepX = v0.y - v2.y;
    float edge2StepX = v1.y - v0.y;
    float edge0StepY = v1.x - v2.x;
    float edge1StepY = v2.x - v0.x;
    float edge2StepY = v0.x - v1.x;
    boolean wroteAny = false;
    for (int y = minY; y <= maxY; y++) {
        float edge0Value = edge0Row;
        float edge1Value = edge1Row;
        float edge2Value = edge2Row;
        for (int x = minX; x <= maxX; x++) {
            float w0 = edge0Value * inverseArea;
            float w1 = edge1Value * inverseArea;
            float w2 = edge2Value * inverseArea;
            edge0Value += edge0StepX;
            edge1Value += edge1StepX;
            edge2Value += edge2StepX;
            if (w0 < 0f || w1 < 0f || w2 < 0f) {
                continue;
            }
            float depth = (w0 * v0.depth) + (w1 * v1.depth) + (w2 * v2.depth);
            int offset = (y * width) + x;
            if (depthEnabled) {
                float existingDepth = depthBuffer[offset];
                boolean depthPass = switch (depthFunc) {
                    case GraphicsOGL.GL_LESS -> depth > existingDepth + 0.00001f;
                    case GraphicsOGL.GL_LEQUAL -> depth >= existingDepth - 0.00001f;
                    case GraphicsOGL.GL_EQUAL -> absFloat(depth - existingDepth) <= 0.00001f;
                    case GraphicsOGL.GL_GREATER -> depth < existingDepth - 0.00001f;
                    case GraphicsOGL.GL_GEQUAL -> depth <= existingDepth + 0.00001f;
                    case GraphicsOGL.GL_NEVER -> false;
                    default -> true;
                };
                if (!depthPass) {
                    if (traceRaster) {
                        SceneTrace.rasterDepthFailed++;
                    }
                    continue;
                }
            }
            float perspective0 = w0 * reciprocalW0;
            float perspective1 = w1 * reciprocalW1;
            float perspective2 = w2 * reciprocalW2;
            float denominator = perspective0 + perspective1 + perspective2;
            if (denominator < 0.000001f) {
                denominator = 0.000001f;
            }
            float inverseDenominator = 1f / denominator;
            float weight0 = perspective0 * inverseDenominator;
            float weight1 = perspective1 * inverseDenominator;
            float weight2 = perspective2 * inverseDenominator;
            int primaryColor = flatColorKnown ? flatColor
                    : interpolateColor(v0, v1, v2, weight0, weight1, weight2, useBackColor);
            int fragmentColor = primaryColor;
            if (texturing0) {
                float u = (weight0 * v0.u0) + (weight1 * v1.u0) + (weight2 * v2.u0);
                float v = (weight0 * v0.v0) + (weight1 * v1.v0) + (weight2 * v2.v0);
                if (!constantQ0) {
                    float q = (weight0 * v0.q0) + (weight1 * v1.q0) + (weight2 * v2.q0);
                    if (q != 0f && q != 1f) {
                        u /= q;
                        v /= q;
                    }
                }
                int sampled = texture0.sample(u, v);
                fragmentColor = switch (envFastMode0) {
                    case ENV_FAST_MODULATE ->
                            applyModulateTextureFunction(fragmentColor, sampled, baseFormat0);
                    case ENV_FAST_SCALED_MODULATE ->
                            applyScaledModulateTextureFunction(fragmentColor, sampled, baseFormat0, envRgbScale0, envAlphaScale0);
                    default ->
                            applyTextureEnvironment(fragmentColor, primaryColor, sampled, texture0, unit0);
                };
            }
            if (texturing1) {
                float u = (weight0 * v0.u1) + (weight1 * v1.u1) + (weight2 * v2.u1);
                float v = (weight0 * v0.v1) + (weight1 * v1.v1) + (weight2 * v2.v1);
                if (!constantQ1) {
                    float q = (weight0 * v0.q1) + (weight1 * v1.q1) + (weight2 * v2.q1);
                    if (q != 0f && q != 1f) {
                        u /= q;
                        v /= q;
                    }
                }
                int sampled = texture1.sample(u, v);
                fragmentColor = switch (envFastMode1) {
                    case ENV_FAST_MODULATE ->
                            applyModulateTextureFunction(fragmentColor, sampled, baseFormat1);
                    case ENV_FAST_SCALED_MODULATE ->
                            applyScaledModulateTextureFunction(fragmentColor, sampled, baseFormat1, envRgbScale1, envAlphaScale1);
                    default ->
                            applyTextureEnvironment(fragmentColor, primaryColor, sampled, texture1, unit1);
                };
            }
            if (alphaTestMode != ALPHA_TEST_NONE) {
                int alphaByte = fragmentColor >>> 24;
                boolean alphaPass = switch (alphaTestMode) {
                    case ALPHA_TEST_ALWAYS -> true;
                    case ALPHA_TEST_NEVER -> false;
                    case ALPHA_TEST_NOT_ZERO -> alphaByte != 0;
                    case ALPHA_TEST_ZERO -> alphaByte == 0;
                    default -> passesAlphaTestAlpha(alphaByte, alphaFunc, alphaRef);
                };
                if (!alphaPass) {
                    if (traceRaster) {
                        SceneTrace.rasterAlphaFailed++;
                    }
                    continue;
                }
            }
            int blended = switch (blendMode) {
                case BLEND_NONE -> fragmentColor;
                case BLEND_SRC_ALPHA_ONE -> blendSourceAlphaOne(fragmentColor, pixels[offset]);
                case BLEND_SRC_ALPHA_INV_SRC_ALPHA -> blendSourceAlpha(fragmentColor, pixels[offset]);
                case BLEND_ONE_INV_SRC_ALPHA -> blendOneOneMinusSourceAlpha(fragmentColor, pixels[offset]);
                default -> blend(fragmentColor, pixels[offset], ogl.blendSrcFactor, ogl.blendDstFactor);
            };
            pixels[offset] = blended;
            wroteAny = true;
            if (traceRaster) {
                SceneTrace.rasterPixelsWritten++;
            }
            if (depthWriteEnabled) {
                depthBuffer[offset] = depth;
            }
        }
        edge0Row += edge0StepY;
        edge1Row += edge1StepY;
        edge2Row += edge2StepY;
    }
    if (wroteAny) {
        host.surface().expandOglDirtyRect(minX, minY, maxX, maxY);
    }
}

void drawLineLoop(int first, int primitiveCount, OglIndexSource elementIndices, Rectangle clip, int width, int height,
                          RasterVertex firstVertex, RasterVertex previousVertex, RasterVertex currentVertex,
                          ClipVector clip0, ClipVector clip1) {
    if (primitiveCount < 2) {
        return;
    }
    if (!software.populateRasterVertex(firstVertex, clip0, software.resolveVertexIndex(first, elementIndices, 0))) {
        return;
    }
    previousVertex.copyFrom(firstVertex);
    Color old = host.delegate().getColor();
    Shape oldClip = host.delegate().getClip();
    host.delegate().setColor(new Color(firstVertex.color, true));
    if (clip != null) {
        host.delegate().setClip(clip);
    }
    try {
        for (int i = 1; i < primitiveCount; i++) {
            if (!software.populateRasterVertex(currentVertex, clip1, software.resolveVertexIndex(first, elementIndices, i))) {
                continue;
            }
            host.delegate().drawLine(clamp(Math.round(previousVertex.x), 0, width - 1),
                    clamp(Math.round(previousVertex.y), 0, height - 1),
                    clamp(Math.round(currentVertex.x), 0, width - 1),
                    clamp(Math.round(currentVertex.y), 0, height - 1));
            RasterVertex swap = previousVertex;
            previousVertex = currentVertex;
            currentVertex = swap;
        }
        host.delegate().drawLine(clamp(Math.round(previousVertex.x), 0, width - 1),
                clamp(Math.round(previousVertex.y), 0, height - 1),
                clamp(Math.round(firstVertex.x), 0, width - 1),
                clamp(Math.round(firstVertex.y), 0, height - 1));
    } finally {
        host.delegate().setColor(old);
        host.delegate().setClip(oldClip);
    }
}

private boolean passesDepth(float incoming, float existing) {
    if (!ogl.depthEnabled()) {
        return true;
    }
    return switch (ogl.depthFunc) {
        case GraphicsOGL.GL_LESS -> incoming > existing + 0.00001f;
        case GraphicsOGL.GL_LEQUAL -> incoming >= existing - 0.00001f;
        case GraphicsOGL.GL_EQUAL -> absFloat(incoming - existing) <= 0.00001f;
        case GraphicsOGL.GL_GREATER -> incoming < existing - 0.00001f;
        case GraphicsOGL.GL_GEQUAL -> incoming <= existing + 0.00001f;
        case GraphicsOGL.GL_ALWAYS -> true;
        case GraphicsOGL.GL_NEVER -> false;
        default -> incoming > existing + 0.00001f;
    };
}

private static boolean passesAlphaTestAlpha(int alphaByte, int alphaFunc, float alphaRef) {
    float alpha = alphaByte / 255f;
    switch (alphaFunc) {
        case GraphicsOGL.GL_NEVER:
            return false;
        case GraphicsOGL.GL_LESS:
            return alpha < alphaRef;
        case GraphicsOGL.GL_LEQUAL:
            return alpha <= alphaRef;
        case GraphicsOGL.GL_GREATER:
            return alpha > alphaRef;
        case GraphicsOGL.GL_GEQUAL:
            return alpha >= alphaRef;
        case GraphicsOGL.GL_EQUAL: {
            float difference = alpha - alphaRef;
            if (difference < 0f) {
                difference = -difference;
            }
            return difference <= 0.0001f;
        }
        case GraphicsOGL.GL_NOTEQUAL: {
            float difference = alpha - alphaRef;
            if (difference < 0f) {
                difference = -difference;
            }
            return difference > 0.0001f;
        }
        default:
            return true;
    }
}

private static final int ALPHA_TEST_NONE = 0;
private static final int ALPHA_TEST_GENERIC = 1;
private static final int ALPHA_TEST_NOT_ZERO = 2;
private static final int ALPHA_TEST_ZERO = 3;
private static final int ALPHA_TEST_ALWAYS = 4;
private static final int ALPHA_TEST_NEVER = 5;

/**
 * Collapses the alpha-test state into an integer mode once per triangle. The
 * overwhelmingly common case is GL_NOTEQUAL with a zero reference, which is
 * exactly an integer alpha != 0 test and avoids the Math.abs() JNI call that
 * used to dominate the per-pixel path.
 */
private static int alphaTestMode(boolean enabled, int alphaFunc, float alphaRef) {
    if (!enabled) {
        return ALPHA_TEST_NONE;
    }
    if (alphaFunc == GraphicsOGL.GL_ALWAYS) {
        return ALPHA_TEST_ALWAYS;
    }
    if (alphaFunc == GraphicsOGL.GL_NEVER) {
        return ALPHA_TEST_NEVER;
    }
    if (alphaRef == 0f) {
        return switch (alphaFunc) {
            case GraphicsOGL.GL_NOTEQUAL, GraphicsOGL.GL_GREATER -> ALPHA_TEST_NOT_ZERO;
            case GraphicsOGL.GL_EQUAL, GraphicsOGL.GL_LEQUAL -> ALPHA_TEST_ZERO;
            case GraphicsOGL.GL_GEQUAL -> ALPHA_TEST_ALWAYS;
            case GraphicsOGL.GL_LESS -> ALPHA_TEST_NEVER;
            default -> ALPHA_TEST_GENERIC;
        };
    }
    return ALPHA_TEST_GENERIC;
}

private static final int BLEND_NONE = 0;
private static final int BLEND_SRC_ALPHA_ONE = 1;
private static final int BLEND_SRC_ALPHA_INV_SRC_ALPHA = 2;
private static final int BLEND_ONE_INV_SRC_ALPHA = 3;
private static final int BLEND_GENERIC = 4;

private static int blendMode(boolean enabled, int srcFactor, int dstFactor) {
    if (!enabled) {
        return BLEND_NONE;
    }
    if (srcFactor == GraphicsOGL.GL_SRC_ALPHA && dstFactor == GraphicsOGL.GL_ONE) {
        return BLEND_SRC_ALPHA_ONE;
    }
    if (srcFactor == GraphicsOGL.GL_SRC_ALPHA && dstFactor == GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA) {
        return BLEND_SRC_ALPHA_INV_SRC_ALPHA;
    }
    if (srcFactor == GraphicsOGL.GL_ONE && dstFactor == GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA) {
        return BLEND_ONE_INV_SRC_ALPHA;
    }
    return BLEND_GENERIC;
}

private static void multiply(ClipVector out, float[] matrix, float x, float y, float z, float w) {
    if (matrix == null) {
        out.set(x, y, z, w);
        return;
    }
    out.set(
            matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
            matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
    );
}

private int interpolateColor(RasterVertex v0, RasterVertex v1, RasterVertex v2,
                             float weight0, float weight1, float weight2, boolean useBackColor) {
    int c0 = useBackColor ? v0.backColor : v0.color;
    int c1 = useBackColor ? v1.backColor : v1.color;
    int c2 = useBackColor ? v2.backColor : v2.color;
    if (ogl.shadeModel == GraphicsOGL.GL_FLAT) {
        return c2;
    }
    if (c0 == c1 && c1 == c2) {
        return c0;
    }
    int alpha = roundedChannel((weight0 * ((c0 >>> 24) & 0xFF))
            + (weight1 * ((c1 >>> 24) & 0xFF))
            + (weight2 * ((c2 >>> 24) & 0xFF)));
    int red = roundedChannel((weight0 * ((c0 >>> 16) & 0xFF))
            + (weight1 * ((c1 >>> 16) & 0xFF))
            + (weight2 * ((c2 >>> 16) & 0xFF)));
    int green = roundedChannel((weight0 * ((c0 >>> 8) & 0xFF))
            + (weight1 * ((c1 >>> 8) & 0xFF))
            + (weight2 * ((c2 >>> 8) & 0xFF)));
    int blue = roundedChannel((weight0 * (c0 & 0xFF))
            + (weight1 * (c1 & 0xFF))
            + (weight2 * (c2 & 0xFF)));
    return (alpha << 24) | (red << 16) | (green << 8) | blue;
}

private static int roundedChannel(float value) {
    if (value <= 0f) {
        return 0;
    }
    if (value >= 255f) {
        return 255;
    }
    // Math.round() becomes a JNI Float.floatToRawIntBits call here; a rounded cast
    // is equivalent for this clamped, non-negative range.
    return (int) (value + 0.5f);
}

private int applyTextureEnvironment(int previousColor, int primaryColor, int sampledColor,
                                    OglTexture texture, OglTextureUnit unit) {
    if (texture == null) {
        return previousColor;
    }
    int baseFormat = texture.baseFormat();
    if (unit.textureEnvMode == GraphicsOGL.GL_MODULATE) {
        return applyModulateTextureFunction(previousColor, sampledColor, baseFormat);
    }
    if (unit.textureEnvMode == GraphicsOGL.GL_COMBINE) {
        return applyCombineTextureEnvironment(previousColor, primaryColor, sampledColor, baseFormat, unit);
    }
    float pr = packedComponent(previousColor, 0);
    float pg = packedComponent(previousColor, 1);
    float pb = packedComponent(previousColor, 2);
    float pa = packedComponent(previousColor, 3);
    float tr = textureComponent(sampledColor, 0, baseFormat);
    float tg = textureComponent(sampledColor, 1, baseFormat);
    float tb = textureComponent(sampledColor, 2, baseFormat);
    float ta = textureComponent(sampledColor, 3, baseFormat);
    float er = packedComponent(unit.textureEnvColor, 0);
    float eg = packedComponent(unit.textureEnvColor, 1);
    float eb = packedComponent(unit.textureEnvColor, 2);
    return switch (unit.textureEnvMode) {
        case GraphicsOGL.GL_REPLACE ->
                applyReplaceTextureFunction(pr, pg, pb, pa, tr, tg, tb, ta, baseFormat);
        case GraphicsOGL.GL_DECAL ->
                applyDecalTextureFunction(pr, pg, pb, pa, tr, tg, tb, ta, baseFormat);
        case GraphicsOGL.GL_BLEND ->
                applyBlendTextureFunction(pr, pg, pb, pa, tr, tg, tb, ta, er, eg, eb, baseFormat);
        case GraphicsOGL.GL_ADD ->
                applyAddTextureFunction(pr, pg, pb, pa, tr, tg, tb, ta, baseFormat);
        default -> previousColor;
    };
}

private int applyReplaceTextureFunction(float pr, float pg, float pb, float pa,
                                        float tr, float tg, float tb, float ta, int baseFormat) {
    return switch (baseFormat) {
        case GraphicsOGL.GL_ALPHA -> packColor(pr, pg, pb, ta);
        case GraphicsOGL.GL_LUMINANCE -> packColor(tr, tg, tb, pa);
        case GraphicsOGL.GL_LUMINANCE_ALPHA,
                GraphicsOGL.GL_RGBA -> packColor(tr, tg, tb, ta);
        case GraphicsOGL.GL_RGB -> packColor(tr, tg, tb, pa);
        default -> packColor(tr, tg, tb, ta);
    };
}

private int applyModulateTextureFunction(int primaryColor, int sampledColor, int baseFormat) {
    int primaryAlpha = (primaryColor >>> 24) & 0xFF;
    int primaryRed = (primaryColor >>> 16) & 0xFF;
    int primaryGreen = (primaryColor >>> 8) & 0xFF;
    int primaryBlue = primaryColor & 0xFF;
    int sampledAlpha = (sampledColor >>> 24) & 0xFF;
    int sampledRed = (sampledColor >>> 16) & 0xFF;
    int sampledGreen = (sampledColor >>> 8) & 0xFF;
    int sampledBlue = sampledColor & 0xFF;
    return switch (baseFormat) {
        case GraphicsOGL.GL_ALPHA ->
                (modulateColorChannel(primaryAlpha, sampledAlpha) << 24) | (primaryColor & 0x00FFFFFF);
        case GraphicsOGL.GL_LUMINANCE,
                GraphicsOGL.GL_RGB ->
                (primaryAlpha << 24)
                        | (modulateColorChannel(primaryRed, sampledRed) << 16)
                        | (modulateColorChannel(primaryGreen, sampledGreen) << 8)
                        | modulateColorChannel(primaryBlue, sampledBlue);
        case GraphicsOGL.GL_LUMINANCE_ALPHA,
                GraphicsOGL.GL_RGBA ->
                (modulateColorChannel(primaryAlpha, sampledAlpha) << 24)
                        | (modulateColorChannel(primaryRed, sampledRed) << 16)
                        | (modulateColorChannel(primaryGreen, sampledGreen) << 8)
                        | modulateColorChannel(primaryBlue, sampledBlue);
        default ->
                (modulateColorChannel(primaryAlpha, sampledAlpha) << 24)
                        | (modulateColorChannel(primaryRed, sampledRed) << 16)
                        | (modulateColorChannel(primaryGreen, sampledGreen) << 8)
                        | modulateColorChannel(primaryBlue, sampledBlue);
    };
}

private int applyDecalTextureFunction(float pr, float pg, float pb, float pa,
                                      float tr, float tg, float tb, float ta, int baseFormat) {
    return switch (baseFormat) {
        case GraphicsOGL.GL_RGB -> packColor(tr, tg, tb, pa);
        case GraphicsOGL.GL_RGBA -> packColor(
                (pr * (1f - ta)) + (tr * ta),
                (pg * (1f - ta)) + (tg * ta),
                (pb * (1f - ta)) + (tb * ta),
                pa);
        default -> packColor(pr, pg, pb, pa);
    };
}

private int applyBlendTextureFunction(float pr, float pg, float pb, float pa,
                                      float tr, float tg, float tb, float ta,
                                      float er, float eg, float eb, int baseFormat) {
    return switch (baseFormat) {
        case GraphicsOGL.GL_ALPHA -> packColor(pr, pg, pb, pa * ta);
        case GraphicsOGL.GL_LUMINANCE,
                GraphicsOGL.GL_RGB -> packColor(
                (pr * (1f - tr)) + (er * tr),
                (pg * (1f - tg)) + (eg * tg),
                (pb * (1f - tb)) + (eb * tb),
                pa);
        case GraphicsOGL.GL_LUMINANCE_ALPHA,
                GraphicsOGL.GL_RGBA -> packColor(
                (pr * (1f - tr)) + (er * tr),
                (pg * (1f - tg)) + (eg * tg),
                (pb * (1f - tb)) + (eb * tb),
                pa * ta);
        default -> packColor(
                (pr * (1f - tr)) + (er * tr),
                (pg * (1f - tg)) + (eg * tg),
                (pb * (1f - tb)) + (eb * tb),
                pa * ta);
    };
}

private int applyAddTextureFunction(float pr, float pg, float pb, float pa,
                                    float tr, float tg, float tb, float ta, int baseFormat) {
    return switch (baseFormat) {
        case GraphicsOGL.GL_ALPHA -> packColor(pr, pg, pb, pa * ta);
        case GraphicsOGL.GL_LUMINANCE,
                GraphicsOGL.GL_RGB -> packColor(pr + tr, pg + tg, pb + tb, pa);
        case GraphicsOGL.GL_LUMINANCE_ALPHA,
                GraphicsOGL.GL_RGBA -> packColor(pr + tr, pg + tg, pb + tb, pa * ta);
        default -> packColor(pr + tr, pg + tg, pb + tb, pa * ta);
    };
}

private static final int ENV_FAST_NONE = 0;
private static final int ENV_FAST_MODULATE = 1;
private static final int ENV_FAST_SCALED_MODULATE = 2;

/**
 * Classifies the per-unit texture environment once per triangle so the inner
 * raster loop does not have to re-inspect the combine state for every pixel.
 */
private static int textureEnvFastMode(OglTextureUnit unit) {
    if (unit.textureEnvMode == GraphicsOGL.GL_MODULATE) {
        return ENV_FAST_MODULATE;
    }
    if (unit.textureEnvMode != GraphicsOGL.GL_COMBINE || !isModulateCombineConfig(unit)) {
        return ENV_FAST_NONE;
    }
    if (unit.rgbScale == 1 && unit.alphaScale == 1) {
        return ENV_FAST_MODULATE;
    }
    if ((unit.rgbScale == 2 || unit.rgbScale == 4) && unit.alphaScale == 1) {
        return ENV_FAST_SCALED_MODULATE;
    }
    return ENV_FAST_NONE;
}

private int applyCombineTextureEnvironment(int previousColor, int primaryColor, int sampledColor,
                                           int baseFormat, OglTextureUnit unit) {
    if (isModulateCombine(unit)) {
        return applyModulateTextureFunction(previousColor, sampledColor, baseFormat);
    }
    float r = combineRgbComponent(0, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float g = combineRgbComponent(1, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float b = combineRgbComponent(2, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float alpha0 = textureAlphaArgument(0, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float alpha1 = textureAlphaArgument(1, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float alpha2 = textureAlphaArgument(2, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float a = switch (unit.combineAlpha) {
        case GraphicsOGL.GL_REPLACE -> alpha0;
        case GraphicsOGL.GL_MODULATE -> alpha0 * alpha1;
        case GraphicsOGL.GL_ADD -> alpha0 + alpha1;
        case GraphicsOGL.GL_ADD_SIGNED -> alpha0 + alpha1 - 0.5f;
        case GraphicsOGL.GL_INTERPOLATE -> (alpha0 * alpha2) + (alpha1 * (1f - alpha2));
        case GraphicsOGL.GL_SUBTRACT -> alpha0 - alpha1;
        default -> textureComponent(sampledColor, 3, baseFormat);
    };
    return packColor(clampUnit(r * unit.rgbScale), clampUnit(g * unit.rgbScale),
            clampUnit(b * unit.rgbScale), clampUnit(a * unit.alphaScale));
}

private static boolean isModulateCombine(OglTextureUnit unit) {
    // Equivalent to the default GL_COMBINE setup: MODULATE(TEXTURE, PREVIOUS)
    // with unit-scale 1. This is by far the most common multi-texture state and
    // avoiding the generic argument evaluator removes dozens of calls per pixel.
    return unit.rgbScale == 1
            && unit.alphaScale == 1
            && isModulateCombineConfig(unit);
}

private static boolean isModulateCombineConfig(OglTextureUnit unit) {
    return unit.combineRgb == GraphicsOGL.GL_MODULATE
            && unit.combineAlpha == GraphicsOGL.GL_MODULATE
            && unit.srcRgb[0] == GraphicsOGL.GL_TEXTURE
            && unit.srcRgb[1] == GraphicsOGL.GL_PREVIOUS
            && unit.operandRgb[0] == GraphicsOGL.GL_SRC_COLOR
            && unit.operandRgb[1] == GraphicsOGL.GL_SRC_COLOR
            && unit.srcAlpha[0] == GraphicsOGL.GL_TEXTURE
            && unit.srcAlpha[1] == GraphicsOGL.GL_PREVIOUS
            && unit.operandAlpha[0] == GraphicsOGL.GL_SRC_ALPHA
            && unit.operandAlpha[1] == GraphicsOGL.GL_SRC_ALPHA;
}

/**
 * Integer fast path for MODULATE(TEXTURE, PREVIOUS) with a 1, 2 or 4 RGB
 * unit-scale. KH coded uses rgbScale=2 for its 3D geometry, which previously
 * fell through to the generic float combine evaluator for every pixel.
 */
private static int applyScaledModulateTextureFunction(int primaryColor, int sampledColor,
                                                      int baseFormat, int rgbScale, int alphaScale) {
    int primaryAlpha = (primaryColor >>> 24) & 0xFF;
    int primaryRed = (primaryColor >>> 16) & 0xFF;
    int primaryGreen = (primaryColor >>> 8) & 0xFF;
    int primaryBlue = primaryColor & 0xFF;
    int sampledAlpha = (sampledColor >>> 24) & 0xFF;
    int sampledRed = (sampledColor >>> 16) & 0xFF;
    int sampledGreen = (sampledColor >>> 8) & 0xFF;
    int sampledBlue = sampledColor & 0xFF;
    return switch (baseFormat) {
        case GraphicsOGL.GL_ALPHA ->
                (modulateScaledChannel(primaryAlpha, sampledAlpha, alphaScale) << 24) | (primaryColor & 0x00FFFFFF);
        case GraphicsOGL.GL_LUMINANCE ->
                (primaryAlpha << 24)
                        | (modulateScaledChannel(primaryRed, sampledRed, rgbScale) << 16)
                        | (modulateScaledChannel(primaryGreen, sampledRed, rgbScale) << 8)
                        | modulateScaledChannel(primaryBlue, sampledRed, rgbScale);
        case GraphicsOGL.GL_LUMINANCE_ALPHA ->
                (modulateScaledChannel(primaryAlpha, sampledAlpha, alphaScale) << 24)
                        | (modulateScaledChannel(primaryRed, sampledRed, rgbScale) << 16)
                        | (modulateScaledChannel(primaryGreen, sampledRed, rgbScale) << 8)
                        | modulateScaledChannel(primaryBlue, sampledRed, rgbScale);
        case GraphicsOGL.GL_RGB ->
                (primaryAlpha << 24)
                        | (modulateScaledChannel(primaryRed, sampledRed, rgbScale) << 16)
                        | (modulateScaledChannel(primaryGreen, sampledGreen, rgbScale) << 8)
                        | modulateScaledChannel(primaryBlue, sampledBlue, rgbScale);
        default ->
                (modulateScaledChannel(primaryAlpha, sampledAlpha, alphaScale) << 24)
                        | (modulateScaledChannel(primaryRed, sampledRed, rgbScale) << 16)
                        | (modulateScaledChannel(primaryGreen, sampledGreen, rgbScale) << 8)
                        | modulateScaledChannel(primaryBlue, sampledBlue, rgbScale);
    };
}

private static int modulateScaledChannel(int primary, int sampled, int scale) {
    int product = primary * sampled * scale;
    if (product >= 65_025) {
        return 255;
    }
    // Exact integer division by 255 without emitting a hardware divide:
    // for 0 <= product < 65025, (product + 127) / 255 == (product + 128 + ((product + 127) >> 8)) >> 8.
    int numerator = product + 127;
    return (numerator + 1 + (numerator >> 8)) >> 8;
}

private float combineRgbComponent(int channel, int previousColor, int primaryColor,
                                  int sampledColor, int baseFormat, OglTextureUnit unit) {
    float arg0 = textureRgbArgument(0, channel, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float arg1 = textureRgbArgument(1, channel, previousColor, primaryColor, sampledColor, baseFormat, unit);
    float arg2 = textureRgbArgument(2, channel, previousColor, primaryColor, sampledColor, baseFormat, unit);
    return switch (unit.combineRgb) {
        case GraphicsOGL.GL_REPLACE -> arg0;
        case GraphicsOGL.GL_MODULATE -> arg0 * arg1;
        case GraphicsOGL.GL_ADD -> arg0 + arg1;
        case GraphicsOGL.GL_ADD_SIGNED -> arg0 + arg1 - 0.5f;
        case GraphicsOGL.GL_INTERPOLATE -> (arg0 * arg2) + (arg1 * (1f - arg2));
        case GraphicsOGL.GL_SUBTRACT -> arg0 - arg1;
        default -> textureComponent(sampledColor, channel, baseFormat);
    };
}

private float textureRgbArgument(int index, int channel, int previousColor, int primaryColor,
                                 int sampledColor, int baseFormat, OglTextureUnit unit) {
    int source = unit.srcRgb[index];
    int operand = unit.operandRgb[index];
    float component = textureSourceComponent(source, channel, previousColor, primaryColor, sampledColor, baseFormat, unit);
    return switch (operand) {
        case GraphicsOGL.GL_SRC_ALPHA ->
                textureSourceComponent(source, 3, previousColor, primaryColor, sampledColor, baseFormat, unit);
        case GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA ->
                1f - textureSourceComponent(source, 3, previousColor, primaryColor, sampledColor, baseFormat, unit);
        case GraphicsOGL.GL_ONE_MINUS_SRC_COLOR -> 1f - component;
        default -> component;
    };
}

private float textureAlphaArgument(int index, int previousColor, int primaryColor,
                                   int sampledColor, int baseFormat, OglTextureUnit unit) {
    float alpha = textureSourceComponent(unit.srcAlpha[index], 3, previousColor, primaryColor,
            sampledColor, baseFormat, unit);
    return unit.operandAlpha[index] == GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA ? 1f - alpha : alpha;
}

private float textureSourceComponent(int source, int channel, int previousColor, int primaryColor,
                                     int sampledColor, int baseFormat, OglTextureUnit unit) {
    return switch (source) {
        case GraphicsOGL.GL_TEXTURE -> textureComponent(sampledColor, channel, baseFormat);
        case GraphicsOGL.GL_CONSTANT -> packedComponent(unit.textureEnvColor, channel);
        case GraphicsOGL.GL_PRIMARY_COLOR -> packedComponent(primaryColor, channel);
        case GraphicsOGL.GL_PREVIOUS -> packedComponent(previousColor, channel);
        default -> packedComponent(previousColor, channel);
    };
}

private static float textureComponent(int packed, int channel, int baseFormat) {
    return switch (baseFormat) {
        case GraphicsOGL.GL_ALPHA -> channel == 3 ? packedComponent(packed, 3) : 0f;
        case GraphicsOGL.GL_LUMINANCE -> channel == 3 ? 1f : packedComponent(packed, 0);
        case GraphicsOGL.GL_LUMINANCE_ALPHA ->
                channel == 3 ? packedComponent(packed, 3) : packedComponent(packed, 0);
        case GraphicsOGL.GL_RGB -> channel == 3 ? 1f : packedComponent(packed, channel);
        default -> packedComponent(packed, channel);
    };
}

static float packedComponent(int packed, int channel) {
    int shift = switch (channel) {
        case 0 -> 16;
        case 1 -> 8;
        case 2 -> 0;
        default -> 24;
    };
    return ((packed >>> shift) & 0xFF) / 255f;
}

float[] unpackColor(int packed) {
    return new float[]{
            ((packed >>> 16) & 0xFF) / 255f,
            ((packed >>> 8) & 0xFF) / 255f,
            (packed & 0xFF) / 255f,
            ((packed >>> 24) & 0xFF) / 255f
    };
}

private float vectorLength(float x, float y, float z) {
    return (float) Math.sqrt((x * x) + (y * y) + (z * z));
}

private float dot(float ax, float ay, float az, float bx, float by, float bz) {
    return (ax * bx) + (ay * by) + (az * bz);
}

private float clampUnit(float value) {
    return Math.max(0f, Math.min(1f, value));
}

private static int blend(int source, int destination, int srcFactor, int dstFactor) {
    if (srcFactor == GraphicsOGL.GL_ONE && dstFactor == GraphicsOGL.GL_ZERO) {
        return source;
    }
    if (srcFactor == GraphicsOGL.GL_SRC_ALPHA && dstFactor == GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA) {
        return blendSourceAlpha(source, destination);
    }
    if (srcFactor == GraphicsOGL.GL_ONE && dstFactor == GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA) {
        return blendOneOneMinusSourceAlpha(source, destination);
    }
    float sourceR = ((source >>> 16) & 0xFF) / 255f;
    float sourceG = ((source >>> 8) & 0xFF) / 255f;
    float sourceB = (source & 0xFF) / 255f;
    float sourceA = ((source >>> 24) & 0xFF) / 255f;
    float destinationR = ((destination >>> 16) & 0xFF) / 255f;
    float destinationG = ((destination >>> 8) & 0xFF) / 255f;
    float destinationB = (destination & 0xFF) / 255f;
    float destinationA = ((destination >>> 24) & 0xFF) / 255f;
    float srcBlendR = blendFactorComponent(srcFactor, 0, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float srcBlendG = blendFactorComponent(srcFactor, 1, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float srcBlendB = blendFactorComponent(srcFactor, 2, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float srcBlendA = blendFactorComponent(srcFactor, 3, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float dstBlendR = blendFactorComponent(dstFactor, 0, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float dstBlendG = blendFactorComponent(dstFactor, 1, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float dstBlendB = blendFactorComponent(dstFactor, 2, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    float dstBlendA = blendFactorComponent(dstFactor, 3, sourceR, sourceG, sourceB, sourceA,
            destinationR, destinationG, destinationB, destinationA);
    return (roundedClampedByte(((sourceA * srcBlendA) + (destinationA * dstBlendA)) * 255f) << 24)
            | (roundedClampedByte(((sourceR * srcBlendR) + (destinationR * dstBlendR)) * 255f) << 16)
            | (roundedClampedByte(((sourceG * srcBlendG) + (destinationG * dstBlendG)) * 255f) << 8)
            | roundedClampedByte(((sourceB * srcBlendB) + (destinationB * dstBlendB)) * 255f);
}

private static int roundedClampedByte(float value) {
    if (value <= 0f) {
        return 0;
    }
    if (value >= 255f) {
        return 255;
    }
    // Math.round() routes through Float.floatToRawIntBits on this ART build;
    // a rounded cast is equivalent for this clamped, non-negative range.
    return (int) (value + 0.5f);
}

private static float blendFactorComponent(int factor, int channel,
                                          float sourceR, float sourceG, float sourceB, float sourceA,
                                          float destinationR, float destinationG, float destinationB,
                                          float destinationA) {
    return switch (factor) {
        case GraphicsOGL.GL_ZERO -> 0f;
        case GraphicsOGL.GL_ONE -> 1f;
        case GraphicsOGL.GL_SRC_COLOR -> channelComponent(channel, sourceR, sourceG, sourceB, sourceA);
        case GraphicsOGL.GL_ONE_MINUS_SRC_COLOR ->
                1f - channelComponent(channel, sourceR, sourceG, sourceB, sourceA);
        case GraphicsOGL.GL_DST_COLOR ->
                channelComponent(channel, destinationR, destinationG, destinationB, destinationA);
        case GraphicsOGL.GL_ONE_MINUS_DST_COLOR ->
                1f - channelComponent(channel, destinationR, destinationG, destinationB, destinationA);
        case GraphicsOGL.GL_SRC_ALPHA -> sourceA;
        case GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA -> 1f - sourceA;
        case GraphicsOGL.GL_DST_ALPHA -> destinationA;
        case GraphicsOGL.GL_ONE_MINUS_DST_ALPHA -> 1f - destinationA;
        case GraphicsOGL.GL_SRC_ALPHA_SATURATE ->
                channel == 3 ? 1f : Math.min(sourceA, 1f - destinationA);
        default -> 1f;
    };
}

private static float channelComponent(int channel, float red, float green, float blue, float alpha) {
    return switch (channel) {
        case 0 -> red;
        case 1 -> green;
        case 2 -> blue;
        default -> alpha;
    };
}

private static int blendSourceAlpha(int source, int destination) {
    int sourceAlpha = (source >>> 24) & 0xFF;
    if (sourceAlpha <= 0) {
        return destination;
    }
    if (sourceAlpha >= 255) {
        return source;
    }
    int inverseAlpha = 255 - sourceAlpha;
    int destinationAlpha = (destination >>> 24) & 0xFF;
    return (blendChannel(sourceAlpha, sourceAlpha, destinationAlpha, inverseAlpha) << 24)
            | (blendChannel((source >>> 16) & 0xFF, sourceAlpha, (destination >>> 16) & 0xFF, inverseAlpha) << 16)
            | (blendChannel((source >>> 8) & 0xFF, sourceAlpha, (destination >>> 8) & 0xFF, inverseAlpha) << 8)
            | blendChannel(source & 0xFF, sourceAlpha, destination & 0xFF, inverseAlpha);
}

/**
 * Integer fast path for GL_SRC_ALPHA / GL_ONE (additive alpha blending),
 * which is the blend mode used by the Gundam 3D materials.
 */
private static int blendSourceAlphaOne(int source, int destination) {
    int sourceAlpha = (source >>> 24) & 0xFF;
    if (sourceAlpha == 0) {
        return destination;
    }
    return (blendChannel(sourceAlpha, sourceAlpha, (destination >>> 24) & 0xFF, 255) << 24)
            | (blendChannel((source >>> 16) & 0xFF, sourceAlpha, (destination >>> 16) & 0xFF, 255) << 16)
            | (blendChannel((source >>> 8) & 0xFF, sourceAlpha, (destination >>> 8) & 0xFF, 255) << 8)
            | blendChannel(source & 0xFF, sourceAlpha, destination & 0xFF, 255);
}

private static int blendOneOneMinusSourceAlpha(int source, int destination) {
    int sourceAlpha = (source >>> 24) & 0xFF;
    if (sourceAlpha >= 255) {
        return source;
    }
    int inverseAlpha = 255 - sourceAlpha;
    int destinationAlpha = (destination >>> 24) & 0xFF;
    return (clamp(((sourceAlpha * 255) + (destinationAlpha * inverseAlpha) + 127) / 255, 0, 255) << 24)
            | (clamp(((source >>> 16) & 0xFF) + (((destination >>> 16) & 0xFF) * inverseAlpha + 127) / 255, 0, 255) << 16)
            | (clamp(((source >>> 8) & 0xFF) + (((destination >>> 8) & 0xFF) * inverseAlpha + 127) / 255, 0, 255) << 8)
            | clamp((source & 0xFF) + (((destination & 0xFF) * inverseAlpha) + 127) / 255, 0, 255);
}

private static int blendChannel(int sourceValue, int sourceFactor, int destinationValue, int destinationFactor) {
    return clamp(((sourceValue * sourceFactor) + (destinationValue * destinationFactor) + 127) / 255, 0, 255);
}

private static boolean isValidBlendSrcFactor(int factor) {
    return switch (factor) {
        case GraphicsOGL.GL_ZERO,
                GraphicsOGL.GL_ONE,
                GraphicsOGL.GL_SRC_COLOR,
                GraphicsOGL.GL_ONE_MINUS_SRC_COLOR,
                GraphicsOGL.GL_DST_COLOR,
                GraphicsOGL.GL_ONE_MINUS_DST_COLOR,
                GraphicsOGL.GL_SRC_ALPHA,
                GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA,
                GraphicsOGL.GL_DST_ALPHA,
                GraphicsOGL.GL_ONE_MINUS_DST_ALPHA,
                GraphicsOGL.GL_SRC_ALPHA_SATURATE -> true;
        default -> false;
    };
}

private static boolean isValidBlendDstFactor(int factor) {
    return switch (factor) {
        case GraphicsOGL.GL_ZERO,
                GraphicsOGL.GL_ONE,
                GraphicsOGL.GL_SRC_COLOR,
                GraphicsOGL.GL_ONE_MINUS_SRC_COLOR,
                GraphicsOGL.GL_SRC_ALPHA,
                GraphicsOGL.GL_ONE_MINUS_SRC_ALPHA,
                GraphicsOGL.GL_DST_ALPHA,
                GraphicsOGL.GL_ONE_MINUS_DST_ALPHA -> true;
        default -> false;
    };
}

private static float edge(float ax, float ay, float bx, float by, float px, float py) {
    return ((px - ax) * (by - ay)) - ((py - ay) * (bx - ax));
}

private static int modulateColorChannel(int primary, int sampled) {
    return ((primary * sampled) + 127) / 255;
}

private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
}

/**
 * Branching absolute value. ART routes {@link Math#abs(float)} through
 * Float.floatToRawIntBits on this target, which showed up as a hot JNI call in
 * vertex projection. The sign test is exact for every value used here.
 */
static float absFloat(float value) {
    return value < 0f ? -value : value;
}

private static final class SharedGlObjectStore {
    private final AtomicInteger nextTextureId = new AtomicInteger(1);
    private final AtomicInteger nextBufferId = new AtomicInteger(1);
    private final Map<Integer, OglTexture> textures = new ConcurrentHashMap<>();
    private final Map<Integer, OglBufferObject> buffers = new ConcurrentHashMap<>();

    int createTextureId() {
        int id = nextTextureId.getAndIncrement();
        textures.put(id, new OglTexture());
        return id;
    }

    int createBufferId() {
        int id = nextBufferId.getAndIncrement();
        buffers.put(id, new OglBufferObject());
        return id;
    }
}

static final class RasterVertex {
    float clipX;
    float clipY;
    float clipZ;
    float clipW;
    float x;
    float y;
    float depth;
    float reciprocalW;
    float u0;
    float v0;
    float q0 = 1f;
    float u1;
    float v1;
    float q1 = 1f;
    int color;
    int backColor;

    void set(float clipX, float clipY, float clipZ, float clipW,
             float x, float y, float depth, float reciprocalW,
             float u0, float v0, float q0, float u1, float v1, float q1,
             int color, int backColor) {
        this.clipX = clipX;
        this.clipY = clipY;
        this.clipZ = clipZ;
        this.clipW = clipW;
        this.x = x;
        this.y = y;
        this.depth = depth;
        this.reciprocalW = reciprocalW;
        this.u0 = u0;
        this.v0 = v0;
        this.q0 = q0;
        this.u1 = u1;
        this.v1 = v1;
        this.q1 = q1;
        this.color = color;
        this.backColor = backColor;
    }

    void copyFrom(RasterVertex other) {
        clipX = other.clipX;
        clipY = other.clipY;
        clipZ = other.clipZ;
        clipW = other.clipW;
        x = other.x;
        y = other.y;
        depth = other.depth;
        reciprocalW = other.reciprocalW;
        u0 = other.u0;
        v0 = other.v0;
        q0 = other.q0;
        u1 = other.u1;
        v1 = other.v1;
        q1 = other.q1;
        color = other.color;
        backColor = other.backColor;
    }

    RasterVertex copyOf(RasterVertex other) {
        copyFrom(other);
        return this;
    }

    void interpolateFrom(RasterVertex from, RasterVertex to, float t) {
        set(
                lerp(from.clipX, to.clipX, t),
                lerp(from.clipY, to.clipY, t),
                lerp(from.clipZ, to.clipZ, t),
                lerp(from.clipW, to.clipW, t),
                0f,
                0f,
                0f,
                0f,
                lerp(from.u0, to.u0, t),
                lerp(from.v0, to.v0, t),
                lerp(from.q0, to.q0, t),
                lerp(from.u1, to.u1, t),
                lerp(from.v1, to.v1, t),
                lerp(from.q1, to.q1, t),
                lerpColor(from.color, to.color, t),
                lerpColor(from.backColor, to.backColor, t)
        );
    }

    private static float lerp(float from, float to, float t) {
        return from + ((to - from) * t);
    }

    private static int lerpColor(int from, int to, float t) {
        int fromA = (from >>> 24) & 0xFF;
        int fromR = (from >>> 16) & 0xFF;
        int fromG = (from >>> 8) & 0xFF;
        int fromB = from & 0xFF;
        int toA = (to >>> 24) & 0xFF;
        int toR = (to >>> 16) & 0xFF;
        int toG = (to >>> 8) & 0xFF;
        int toB = to & 0xFF;
        int alpha = roundedChannel(fromA + ((toA - fromA) * t));
        int red = roundedChannel(fromR + ((toR - fromR) * t));
        int green = roundedChannel(fromG + ((toG - fromG) * t));
        int blue = roundedChannel(fromB + ((toB - fromB) * t));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}

static final class ClipVector {
    float x;
    float y;
    float z;
    float w;

    void set(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }
}

record OglPointer(int size, int type, int stride, DirectBuffer pointer,
                          OglBufferObject bufferObject, int byteOffset) {
    static OglPointer direct(int size, int type, int stride, DirectBuffer pointer) {
        return new OglPointer(size, type, stride, pointer, null, 0);
    }

    static OglPointer bufferObject(int size, int type, int stride, OglBufferObject bufferObject, int byteOffset) {
        if (bufferObject == null) {
            return null;
        }
        return new OglPointer(size, type, stride, null, bufferObject, byteOffset);
    }

    int componentByteOffset(int vertexIndex, int componentIndex) {
        int componentSize = bytesPerComponent(type);
        if (componentSize <= 0) {
            return -1;
        }
        int byteStride = stride > 0 ? stride : Math.max(1, size) * componentSize;
        return byteOffset + (vertexIndex * byteStride) + (componentIndex * componentSize);
    }

    private static int bytesPerComponent(int type) {
        return switch (type) {
            case GraphicsOGL.GL_UNSIGNED_BYTE,
                    GraphicsOGL.GL_BYTE -> 1;
            case GraphicsOGL.GL_SHORT,
                    GraphicsOGL.GL_UNSIGNED_SHORT -> 2;
            case GraphicsOGL.GL_FLOAT -> 4;
            default -> -1;
        };
    }
}

static final class OglBufferObject {
    private byte[] data = new byte[0];
    private int usage = GraphicsOGL.GL_STATIC_DRAW;
    private float[] floatView;
    private short[] shortView;

    byte[] data() {
        return data;
    }

    /** Cached little-endian float decoding of the buffer contents. */
    float[] floatView() {
        byte[] bytes = data;
        float[] view = floatView;
        if (view != null && view.length * 4 == bytes.length) {
            return view;
        }
        int count = bytes.length / 4;
        view = new float[count];
        for (int i = 0; i < count; i++) {
            int offset = i * 4;
            int bits = (bytes[offset] & 0xFF)
                    | ((bytes[offset + 1] & 0xFF) << 8)
                    | ((bytes[offset + 2] & 0xFF) << 16)
                    | ((bytes[offset + 3] & 0xFF) << 24);
            view[i] = Float.intBitsToFloat(bits);
        }
        floatView = view;
        return view;
    }

    /** Cached little-endian short decoding, used for element index buffers. */
    short[] shortView() {
        byte[] bytes = data;
        short[] view = shortView;
        if (view != null && view.length * 2 == bytes.length) {
            return view;
        }
        int count = bytes.length / 2;
        view = new short[count];
        for (int i = 0; i < count; i++) {
            int offset = i * 2;
            view[i] = (short) ((bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8));
        }
        shortView = view;
        return view;
    }

    void replace(byte[] data, int usage) {
        this.data = data == null ? new byte[0] : data.clone();
        this.usage = usage;
        this.floatView = null;
        this.shortView = null;
    }

    boolean update(int offset, byte[] updateBytes) {
        if (offset < 0 || updateBytes == null) {
            return false;
        }
        int requiredLength = offset + updateBytes.length;
        if (requiredLength > data.length) {
            data = Arrays.copyOf(data, requiredLength);
        }
        System.arraycopy(updateBytes, 0, data, offset, updateBytes.length);
        floatView = null;
        shortView = null;
        return true;
    }
}

record OglIndexSource(ShortBuffer pointer, OglBufferObject bufferObject, int byteOffset,
                      short[] directIndices, int directOffset) {
    static OglIndexSource direct(ShortBuffer pointer) {
        return new OglIndexSource(pointer, null, 0,
                DirectBufferFactory.peekShortArray(pointer),
                DirectBufferFactory.getSegmentOffset(pointer));
    }

    static OglIndexSource bufferObject(OglBufferObject bufferObject, int byteOffset) {
        return new OglIndexSource(null, bufferObject, byteOffset, null, 0);
    }

    int elementCount() {
        if (pointer != null) {
            return DirectBufferFactory.getSegmentLength(pointer);
        }
        if (bufferObject == null || byteOffset < 0 || byteOffset > bufferObject.data().length) {
            return 0;
        }
        return (bufferObject.data().length - byteOffset) / 2;
    }

    int indexAt(int primitiveIndex) {
        if (directIndices != null) {
            return directIndices[directOffset + primitiveIndex] & 0xFFFF;
        }
        if (bufferObject == null) {
            return 0;
        }
        if ((byteOffset & 1) == 0) {
            short[] view = bufferObject.shortView();
            int index = (byteOffset >>> 1) + primitiveIndex;
            if (index >= 0 && index < view.length) {
                return view[index] & 0xFFFF;
            }
        }
        return readUnsignedShort(bufferObject.data(), byteOffset + (primitiveIndex * 2));
    }
}

static final class OglTexture {
    int width;
    int height;
    int[] pixels = new int[0];
    int baseFormat = GraphicsOGL.GL_RGBA;
    int minFilter = GraphicsOGL.GL_NEAREST;
    int magFilter = GraphicsOGL.GL_NEAREST;
    int wrapS = GraphicsOGL.GL_REPEAT;
    int wrapT = GraphicsOGL.GL_REPEAT;
    boolean linearFilter;
    int uploadRevision;

    void setParameter(int pname, int value) {
        switch (pname) {
            case GraphicsOGL.GL_TEXTURE_MIN_FILTER -> {
                minFilter = value;
                updateLinearFilter();
            }
            case GraphicsOGL.GL_TEXTURE_MAG_FILTER -> {
                magFilter = value;
                updateLinearFilter();
            }
            case GraphicsOGL.GL_TEXTURE_WRAP_S -> wrapS = value;
            case GraphicsOGL.GL_TEXTURE_WRAP_T -> wrapT = value;
            default -> {
            }
        }
    }

    private void updateLinearFilter() {
        linearFilter = minFilter == GraphicsOGL.GL_LINEAR || magFilter == GraphicsOGL.GL_LINEAR;
    }

    static boolean supportsCompressedInternalFormat(int internalFormat) {
        return CompressedPaletteFormatInfo.forInternalFormat(internalFormat) != null;
    }

    boolean loadCompressed(int internalFormat, int width, int height, byte[] raw) {
        CompressedPaletteFormatInfo format = CompressedPaletteFormatInfo.forInternalFormat(internalFormat);
        if (format == null) {
            return false;
        }
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.baseFormat = format.baseFormat();
        int pixelCount = this.width * this.height;
        int paletteBytes = format.paletteEntries() * format.paletteEntrySize();
        int indexBytes = format.indexByteCount(pixelCount);
        if (raw.length < paletteBytes + indexBytes) {
            return false;
        }
        int[] palette = new int[format.paletteEntries()];
        for (int i = 0; i < palette.length; i++) {
            int entryOffset = i * format.paletteEntrySize();
            palette[i] = decodePaletteEntry(format, raw, entryOffset);
        }
        this.pixels = new int[pixelCount];
        if (format.bitsPerIndex() == 8) {
            for (int i = 0; i < this.pixels.length; i++) {
                this.pixels[i] = palette[raw[paletteBytes + i] & 0xFF];
            }
        } else {
            for (int i = 0; i < this.pixels.length; i += 2) {
                int packed = raw[paletteBytes + (i >> 1)] & 0xFF;
                this.pixels[i] = palette[(packed >>> 4) & 0x0F];
                if (i + 1 < this.pixels.length) {
                    this.pixels[i + 1] = palette[packed & 0x0F];
                }
            }
        }
        uploadRevision++;
        return true;
    }

    boolean loadUncompressed(int width, int height, int format, int type, DirectBuffer source) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.baseFormat = format;
        int pixelCount = this.width * this.height;
        int[] decoded = new int[pixelCount];
        if (source instanceof ShortBuffer shortBuffer) {
            int offset = DirectBufferFactory.getSegmentOffset(shortBuffer);
            if (offset < 0 || offset + pixelCount > shortBuffer.length()) {
                return false;
            }
            for (int i = 0; i < pixelCount; i++) {
                int value = DirectBufferFactory.getShort(shortBuffer, offset + i) & 0xFFFF;
                decoded[i] = switch (type) {
                    case GraphicsOGL.GL_UNSIGNED_SHORT_4_4_4_4 ->
                            decodeRgba4444(value);
                    case GraphicsOGL.GL_UNSIGNED_SHORT_5_5_5_1 ->
                            decodeRgb5a1(value);
                    case GraphicsOGL.GL_UNSIGNED_SHORT_5_6_5 ->
                            decodeRgb565(value);
                    default -> 0;
                };
                if (decoded[i] == 0 && type != GraphicsOGL.GL_UNSIGNED_SHORT_4_4_4_4
                        && type != GraphicsOGL.GL_UNSIGNED_SHORT_5_5_5_1
                        && type != GraphicsOGL.GL_UNSIGNED_SHORT_5_6_5) {
                    return false;
                }
            }
            this.pixels = decoded;
            uploadRevision++;
            return true;
        }
        if (source instanceof com.nttdocomo.ui.ogl.ByteBuffer byteBuffer) {
            int bytesPerPixel = bytesPerPixel(format, type);
            if (bytesPerPixel <= 0) {
                return false;
            }
            int totalBytes = pixelCount * bytesPerPixel;
            int offset = DirectBufferFactory.getSegmentOffset(byteBuffer);
            if (offset < 0 || offset + totalBytes > byteBuffer.length()) {
                return false;
            }
            byte[] raw = new byte[totalBytes];
            byteBuffer.get(offset, raw, 0, raw.length);
            for (int i = 0; i < pixelCount; i++) {
                decoded[i] = decodeBytePixel(raw, i * bytesPerPixel, format, type);
            }
            this.pixels = decoded;
            uploadRevision++;
            return true;
        }
        return false;
    }

    int uploadRevision() {
        return uploadRevision;
    }

    int sample(float u, float v) {
        if (pixels.length == 0 || width <= 0 || height <= 0) {
            return 0xFFFFFFFF;
        }
        // Both REPEAT and CLAMP_TO_EDGE leave in-range coordinates unchanged,
        // so skip wrapping in the common case. The helper only handles the
        // out-of-range branch; this avoids re-testing [0, 1) inside the call.
        if (u < 0f || u >= 1f) {
            u = wrapOutOfRangeCoordinate(u, wrapS);
        }
        if (v < 0f || v >= 1f) {
            v = wrapOutOfRangeCoordinate(v, wrapT);
        }
        if (linearFilter) {
            return bilinearSample(u, v);
        }
        int x = (int) (u * (width - 1) + 0.5f);
        int y = (int) (v * (height - 1) + 0.5f);
        return pixels[(y * width) + x];
    }

    int baseFormat() {
        return baseFormat;
    }

    private int bilinearSample(float u, float v) {
        float x = u * (width - 1);
        float y = v * (height - 1);
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = x0 + 1 < width ? x0 + 1 : x0;
        int y1 = y0 + 1 < height ? y0 + 1 : y0;
        // wrapOutOfRangeCoordinate() already guarantees [0, 1], so both the
        // integer cell and the fractional weight stay in range. Math.round()
        // compiles to a JNI call to Float.floatToRawIntBits on this ART build
        // and dominated texture sampling.
        int tx = (int) ((x - x0) * 256f + 0.5f);
        int ty = (int) ((y - y0) * 256f + 0.5f);
        int invTx = 256 - tx;
        int invTy = 256 - ty;
        int w00 = invTx * invTy;
        int w10 = tx * invTy;
        int w01 = invTx * ty;
        int w11 = tx * ty;
        int c00 = pixels[(y0 * width) + x0];
        int c10 = pixels[(y0 * width) + x1];
        int c01 = pixels[(y1 * width) + x0];
        int c11 = pixels[(y1 * width) + x1];
        int alpha = bilinearChannel(c00, c10, c01, c11, 24, w00, w10, w01, w11);
        int red = bilinearChannel(c00, c10, c01, c11, 16, w00, w10, w01, w11);
        int green = bilinearChannel(c00, c10, c01, c11, 8, w00, w10, w01, w11);
        int blue = bilinearChannel(c00, c10, c01, c11, 0, w00, w10, w01, w11);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int bilinearChannel(int c00, int c10, int c01, int c11, int shift,
                                       int w00, int w10, int w01, int w11) {
        int value = (((c00 >>> shift) & 0xFF) * w00
                + ((c10 >>> shift) & 0xFF) * w10
                + ((c01 >>> shift) & 0xFF) * w01
                + ((c11 >>> shift) & 0xFF) * w11
                + 32768) >> 16;
        return value > 255 ? 255 : value;
    }

    private static float wrapOutOfRangeCoordinate(float value, int wrapMode) {
        if (wrapMode == GraphicsOGL.GL_CLAMP_TO_EDGE) {
            if (value < 0f) {
                return 0f;
            }
            return value > 1f ? 1f : value;
        }
        // Truncation matches floor() for the normal, modest texture-coordinate
        // range and avoids a Math.floor() JNI call. Keep the original path for
        // huge or non-finite values, where the cast would saturate.
        if (value >= 0f && value < 16_777_216f) {
            return value - (int) value;
        }
        if (value > -16_777_216f && value < 0f) {
            float wrapped = value - (int) value;
            return wrapped < 0f ? wrapped + 1f : wrapped;
        }
        float wrapped = value - (float) Math.floor(value);
        return wrapped < 0f ? wrapped + 1f : wrapped;
    }

    private static int decodeRgb5a1(int value) {
        int alpha = (value & 0x1) != 0 ? 0xFF : 0x00;
        int red = ((value >>> 11) & 0x1F) * 255 / 31;
        int green = ((value >>> 6) & 0x1F) * 255 / 31;
        int blue = ((value >>> 1) & 0x1F) * 255 / 31;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int decodeRgba4444(int value) {
        int red = ((value >>> 12) & 0x0F) * 17;
        int green = ((value >>> 8) & 0x0F) * 17;
        int blue = ((value >>> 4) & 0x0F) * 17;
        int alpha = (value & 0x0F) * 17;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int decodeRgb565(int value) {
        int red = ((value >>> 11) & 0x1F) * 255 / 31;
        int green = ((value >>> 5) & 0x3F) * 255 / 63;
        int blue = (value & 0x1F) * 255 / 31;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int bytesPerPixel(int format, int type) {
        if (type != GraphicsOGL.GL_UNSIGNED_BYTE) {
            return -1;
        }
        return switch (format) {
            case GraphicsOGL.GL_ALPHA,
                    GraphicsOGL.GL_LUMINANCE -> 1;
            case GraphicsOGL.GL_LUMINANCE_ALPHA -> 2;
            case GraphicsOGL.GL_RGB -> 3;
            case GraphicsOGL.GL_RGBA -> 4;
            default -> -1;
        };
    }

    private static int decodeBytePixel(byte[] raw, int offset, int format, int type) {
        if (type != GraphicsOGL.GL_UNSIGNED_BYTE) {
            return 0;
        }
        return switch (format) {
            case GraphicsOGL.GL_ALPHA ->
                    ((raw[offset] & 0xFF) << 24) | 0x00FFFFFF;
            case GraphicsOGL.GL_LUMINANCE -> {
                int l = raw[offset] & 0xFF;
                yield 0xFF000000 | (l << 16) | (l << 8) | l;
            }
            case GraphicsOGL.GL_LUMINANCE_ALPHA -> {
                int l = raw[offset] & 0xFF;
                int a = raw[offset + 1] & 0xFF;
                yield (a << 24) | (l << 16) | (l << 8) | l;
            }
            case GraphicsOGL.GL_RGB -> {
                int r = raw[offset] & 0xFF;
                int g = raw[offset + 1] & 0xFF;
                int b = raw[offset + 2] & 0xFF;
                yield 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            case GraphicsOGL.GL_RGBA -> {
                int r = raw[offset] & 0xFF;
                int g = raw[offset + 1] & 0xFF;
                int b = raw[offset + 2] & 0xFF;
                int a = raw[offset + 3] & 0xFF;
                yield (a << 24) | (r << 16) | (g << 8) | b;
            }
            default -> 0;
        };
    }

    private static int decodePaletteEntry(CompressedPaletteFormatInfo format, byte[] raw, int offset) {
        return switch (format.paletteEntryType()) {
            case GraphicsOGL.GL_UNSIGNED_BYTE ->
                    decodeBytePixel(raw, offset, format.baseFormat(), GraphicsOGL.GL_UNSIGNED_BYTE);
            case GraphicsOGL.GL_UNSIGNED_SHORT_5_6_5 ->
                    decodeRgb565(readPackedShort(raw, offset));
            case GraphicsOGL.GL_UNSIGNED_SHORT_4_4_4_4 ->
                    decodeRgba4444(readPackedShort(raw, offset));
            case GraphicsOGL.GL_UNSIGNED_SHORT_5_5_5_1 ->
                    decodeRgb5a1(readPackedShort(raw, offset));
            default -> 0;
        };
    }

    private static int readPackedShort(byte[] raw, int offset) {
        return (raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8);
    }
}

private record CompressedPaletteFormatInfo(int internalFormat, int baseFormat, int paletteEntryType,
                                           int paletteEntries, int paletteEntrySize) {
    private static final CompressedPaletteFormatInfo[] VALUES = {
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE4_RGB8_OES,
                    GraphicsOGL.GL_RGB,
                    GraphicsOGL.GL_UNSIGNED_BYTE,
                    16,
                    3),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE4_RGBA8_OES,
                    GraphicsOGL.GL_RGBA,
                    GraphicsOGL.GL_UNSIGNED_BYTE,
                    16,
                    4),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE4_R5_G6_B5_OES,
                    GraphicsOGL.GL_RGB,
                    GraphicsOGL.GL_UNSIGNED_SHORT_5_6_5,
                    16,
                    2),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE4_RGBA4_OES,
                    GraphicsOGL.GL_RGBA,
                    GraphicsOGL.GL_UNSIGNED_SHORT_4_4_4_4,
                    16,
                    2),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE4_RGB5_A1_OES,
                    GraphicsOGL.GL_RGBA,
                    GraphicsOGL.GL_UNSIGNED_SHORT_5_5_5_1,
                    16,
                    2),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE8_RGB8_OES,
                    GraphicsOGL.GL_RGB,
                    GraphicsOGL.GL_UNSIGNED_BYTE,
                    256,
                    3),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE8_RGBA8_OES,
                    GraphicsOGL.GL_RGBA,
                    GraphicsOGL.GL_UNSIGNED_BYTE,
                    256,
                    4),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE8_R5_G6_B5_OES,
                    GraphicsOGL.GL_RGB,
                    GraphicsOGL.GL_UNSIGNED_SHORT_5_6_5,
                    256,
                    2),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE8_RGBA4_OES,
                    GraphicsOGL.GL_RGBA,
                    GraphicsOGL.GL_UNSIGNED_SHORT_4_4_4_4,
                    256,
                    2),
            new CompressedPaletteFormatInfo(GraphicsOGL.GL_PALETTE8_RGB5_A1_OES,
                    GraphicsOGL.GL_RGBA,
                    GraphicsOGL.GL_UNSIGNED_SHORT_5_5_5_1,
                    256,
                    2)
    };

    static CompressedPaletteFormatInfo forInternalFormat(int internalFormat) {
        for (CompressedPaletteFormatInfo value : VALUES) {
            if (value.internalFormat == internalFormat) {
                return value;
            }
        }
        return null;
    }

    int bitsPerIndex() {
        return paletteEntries == 16 ? 4 : 8;
    }

    int indexByteCount(int pixelCount) {
        return (pixelCount * bitsPerIndex() + 7) / 8;
    }
}

static final class OglLight {
    final float[] ambient = {0f, 0f, 0f, 1f};
    final float[] diffuse;
    final float[] specular;
    final float[] position = {0f, 0f, 1f, 0f};
    final float[] spotDirection = {0f, 0f, -1f};
    float spotExponent;
    float spotCutoff = 180f;
    float constantAttenuation = 1f;
    float linearAttenuation;
    float quadraticAttenuation;

    private OglLight(int index) {
        diffuse = index == 0 ? new float[]{1f, 1f, 1f, 1f} : new float[]{0f, 0f, 0f, 1f};
        specular = index == 0 ? new float[]{1f, 1f, 1f, 1f} : new float[]{0f, 0f, 0f, 1f};
    }
}

    /**
     * Per-texture-unit state, mirroring the GLES 1.x fixed-function pipeline.
     */
    static final class OglTextureUnit {
        boolean texture2DEnabled;
        boolean texCoordArrayEnabled;
        int boundTextureId;
        int textureEnvMode = GraphicsOGL.GL_MODULATE;
        int combineRgb = GraphicsOGL.GL_MODULATE;
        int combineAlpha = GraphicsOGL.GL_MODULATE;
        final int[] srcRgb = {
                GraphicsOGL.GL_TEXTURE,
                GraphicsOGL.GL_PREVIOUS,
                GraphicsOGL.GL_CONSTANT
        };
        final int[] srcAlpha = {
                GraphicsOGL.GL_TEXTURE,
                GraphicsOGL.GL_PREVIOUS,
                GraphicsOGL.GL_CONSTANT
        };
        final int[] operandRgb = {
                GraphicsOGL.GL_SRC_COLOR,
                GraphicsOGL.GL_SRC_COLOR,
                GraphicsOGL.GL_SRC_ALPHA
        };
        final int[] operandAlpha = {
                GraphicsOGL.GL_SRC_ALPHA,
                GraphicsOGL.GL_SRC_ALPHA,
                GraphicsOGL.GL_SRC_ALPHA
        };
        int textureEnvColor;
        int rgbScale = 1;
        int alphaScale = 1;
        float[] textureMatrix = OglState.identityMatrix();
        boolean textureMatrixIdentity = true;
        final Deque<float[]> textureStack = new ArrayDeque<>();
        OglPointer texCoordPointer;
        float currentS;
        float currentT;
        float currentR;
        float currentQ = 1f;

        void setTextureMatrix(float[] matrix) {
            textureMatrix = matrix;
            textureMatrixIdentity = isIdentityMatrix(matrix);
        }

        private static boolean isIdentityMatrix(float[] matrix) {
            if (matrix == null) {
                return true;
            }
            for (int i = 0; i < 16; i++) {
                float expected = (i % 5) == 0 ? 1f : 0f;
                if (Math.abs(matrix[i] - expected) > 0.000001f) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Shared GLES state machine and object lifetime tracked across backends.
     */
static final class OglState {
    final OglRenderer renderer;
    final Map<Integer, OglTexture> textures;
    final Map<Integer, OglBufferObject> buffers;
    final boolean[] lightEnabled = new boolean[8];
    boolean blendCapEnabled;
    boolean depthTestEnabled;
    boolean lightingCapEnabled;
    boolean colorMaterialEnabled;
    boolean matrixPaletteEnabled;
    boolean normalizeEnabled;
    boolean rescaleNormalEnabled;
    boolean cullFaceEnabled;
    boolean alphaTestEnabled;
    boolean colorArrayEnabled;
    boolean normalArrayEnabled;
    boolean matrixIndexArrayEnabled;
    boolean weightArrayEnabled;
    int lastError = GraphicsOGL.GL_NO_ERROR;
    int matrixMode = GraphicsOGL.GL_MODELVIEW;
    int shadeModel = GraphicsOGL.GL_SMOOTH;
    int activeTexture = GraphicsOGL.GL_TEXTURE0;
    int clientActiveTexture = GraphicsOGL.GL_TEXTURE0;
    final OglTextureUnit[] textureUnits = createTextureUnits();
    int alphaFunc = GraphicsOGL.GL_ALWAYS;
    float alphaRef;
    boolean depthMask = true;
    int depthFunc = GraphicsOGL.GL_LESS;
    float depthRangeNear;
    float depthRangeFar = 1f;
    int blendSrcFactor = GraphicsOGL.GL_ONE;
    int blendDstFactor = GraphicsOGL.GL_ZERO;
    int frontFace = GraphicsOGL.GL_CCW;
    int cullFace = GraphicsOGL.GL_BACK;
    int viewportX;
    int viewportY;
    int viewportWidth = 1;
    int viewportHeight = 1;
    int unpackAlignment = 1;
    int boundArrayBufferId;
    int boundElementArrayBufferId;
    int color = 0xFFFFFFFF;
    final float[] currentNormal = {0f, 0f, 1f};
    final float[] materialAmbient = {0.2f, 0.2f, 0.2f, 1f};
    final float[] materialDiffuse = {0.8f, 0.8f, 0.8f, 1f};
    final float[] materialSpecular = {0f, 0f, 0f, 1f};
    final float[] materialEmission = {0f, 0f, 0f, 1f};
    float materialShininess;
    final float[] lightModelAmbient = {0.2f, 0.2f, 0.2f, 1f};
    boolean lightModelTwoSide;
    final OglLight[] lights = createLights();
    float[] modelViewMatrix = identityMatrix();
    float[] projectionMatrix = identityMatrix();
    final float[] modelViewNormalMatrix = new float[9];
    boolean modelViewNormalMatrixDirty = true;
    boolean modelViewNormalMatrixValid;
    final float[][] paletteMatrices = new float[OGL_MAX_PALETTE_MATRICES][];
    final float[][] paletteNormalMatrices = new float[OGL_MAX_PALETTE_MATRICES][9];
    final boolean[] paletteNormalMatrixDirty = new boolean[OGL_MAX_PALETTE_MATRICES];
    final boolean[] paletteNormalMatrixValid = new boolean[OGL_MAX_PALETTE_MATRICES];
    boolean standardModelViewConfigured;
    boolean standardProjectionConfigured;
    int currentPaletteMatrix;
    final Deque<float[]> modelViewStack = new ArrayDeque<>();
    final Deque<float[]> projectionStack = new ArrayDeque<>();
    final Deque<float[]>[] paletteMatrixStacks = createPaletteMatrixStacks();
    OglPointer vertexPointer;
    OglPointer normalPointer;
    OglPointer colorPointer;
    OglPointer matrixIndexPointer;
    OglPointer weightPointer;

    private OglState(OglRenderer renderer) {
        this.renderer = renderer;
        this.textures = SHARED_GL_OBJECT_STORE.textures;
        this.buffers = SHARED_GL_OBJECT_STORE.buffers;
        for (int i = 0; i < paletteMatrices.length; i++) {
            paletteMatrices[i] = identityMatrix();
            paletteNormalMatrixDirty[i] = true;
        }
    }

    void enableCap(int cap) {
        switch (cap) {
            case GraphicsOGL.GL_TEXTURE_2D -> activeTextureUnit().texture2DEnabled = true;
            case GraphicsOGL.GL_BLEND -> blendCapEnabled = true;
            case GraphicsOGL.GL_DEPTH_TEST -> depthTestEnabled = true;
            case GraphicsOGL.GL_LIGHTING -> lightingCapEnabled = true;
            case GraphicsOGL.GL_COLOR_MATERIAL -> colorMaterialEnabled = true;
            case GraphicsOGL.GL_MATRIX_PALETTE_OES -> matrixPaletteEnabled = true;
            case GraphicsOGL.GL_NORMALIZE -> normalizeEnabled = true;
            case GraphicsOGL.GL_RESCALE_NORMAL -> rescaleNormalEnabled = true;
            case GraphicsOGL.GL_CULL_FACE -> cullFaceEnabled = true;
            case GraphicsOGL.GL_ALPHA_TEST -> alphaTestEnabled = true;
            default -> {
                int lightIndex = cap - GraphicsOGL.GL_LIGHT0;
                if (lightIndex >= 0 && lightIndex < lightEnabled.length) {
                    lightEnabled[lightIndex] = true;
                }
            }
        }
    }

    void disableCap(int cap) {
        switch (cap) {
            case GraphicsOGL.GL_TEXTURE_2D -> activeTextureUnit().texture2DEnabled = false;
            case GraphicsOGL.GL_BLEND -> blendCapEnabled = false;
            case GraphicsOGL.GL_DEPTH_TEST -> depthTestEnabled = false;
            case GraphicsOGL.GL_LIGHTING -> lightingCapEnabled = false;
            case GraphicsOGL.GL_COLOR_MATERIAL -> colorMaterialEnabled = false;
            case GraphicsOGL.GL_MATRIX_PALETTE_OES -> matrixPaletteEnabled = false;
            case GraphicsOGL.GL_NORMALIZE -> normalizeEnabled = false;
            case GraphicsOGL.GL_RESCALE_NORMAL -> rescaleNormalEnabled = false;
            case GraphicsOGL.GL_CULL_FACE -> cullFaceEnabled = false;
            case GraphicsOGL.GL_ALPHA_TEST -> alphaTestEnabled = false;
            default -> {
                int lightIndex = cap - GraphicsOGL.GL_LIGHT0;
                if (lightIndex >= 0 && lightIndex < lightEnabled.length) {
                    lightEnabled[lightIndex] = false;
                }
            }
        }
    }

    void enableClientState(int array) {
        switch (array) {
            case GraphicsOGL.GL_TEXTURE_COORD_ARRAY -> clientTextureUnit().texCoordArrayEnabled = true;
            case GraphicsOGL.GL_COLOR_ARRAY -> colorArrayEnabled = true;
            case GraphicsOGL.GL_NORMAL_ARRAY -> normalArrayEnabled = true;
            case GraphicsOGL.GL_MATRIX_INDEX_ARRAY_OES -> matrixIndexArrayEnabled = true;
            case GraphicsOGL.GL_WEIGHT_ARRAY_OES -> weightArrayEnabled = true;
            default -> {
            }
        }
    }

    void disableClientState(int array) {
        switch (array) {
            case GraphicsOGL.GL_TEXTURE_COORD_ARRAY -> clientTextureUnit().texCoordArrayEnabled = false;
            case GraphicsOGL.GL_COLOR_ARRAY -> colorArrayEnabled = false;
            case GraphicsOGL.GL_NORMAL_ARRAY -> normalArrayEnabled = false;
            case GraphicsOGL.GL_MATRIX_INDEX_ARRAY_OES -> matrixIndexArrayEnabled = false;
            case GraphicsOGL.GL_WEIGHT_ARRAY_OES -> weightArrayEnabled = false;
            default -> {
            }
        }
    }

    void beginDrawing() {
        standardModelViewConfigured = false;
        standardProjectionConfigured = false;
        renderer.onBeginDrawing();
    }

    void endDrawing() {
    }

    int activeTextureIndex() {
        return clamp(activeTexture - GraphicsOGL.GL_TEXTURE0, 0, textureUnits.length - 1);
    }

    int clientTextureIndex() {
        return clamp(clientActiveTexture - GraphicsOGL.GL_TEXTURE0, 0, textureUnits.length - 1);
    }

    OglTextureUnit activeTextureUnit() {
        return textureUnits[activeTextureIndex()];
    }

    OglTextureUnit clientTextureUnit() {
        return textureUnits[clientTextureIndex()];
    }

    OglTextureUnit textureUnit(int index) {
        return index >= 0 && index < textureUnits.length ? textureUnits[index] : null;
    }

    boolean textureEnabled() {
        return textureEnabled(activeTextureIndex());
    }

    boolean textureEnabled(int index) {
        OglTextureUnit unit = textureUnit(index);
        return unit != null && unit.texture2DEnabled && boundTexture(index) != null;
    }

    boolean blendEnabled() {
        return blendCapEnabled;
    }

    boolean depthEnabled() {
        return depthTestEnabled;
    }

    boolean lightingEnabled() {
        return lightingCapEnabled;
    }

    boolean colorMaterialTracksAmbientAndDiffuse() {
        return colorMaterialEnabled;
    }

    boolean usesExtensionMatrices() {
        return renderer.usesExtensionMatrices(standardModelViewConfigured, standardProjectionConfigured);
    }

    boolean usesMatrixPalette() {
        return matrixPaletteEnabled
                && matrixIndexArrayEnabled
                && weightArrayEnabled
                && matrixIndexPointer != null
                && weightPointer != null;
    }

    OglTexture boundTexture() {
        return boundTexture(activeTextureIndex());
    }

    OglTexture boundTexture(int index) {
        OglTextureUnit unit = textureUnit(index);
        return unit == null || unit.boundTextureId == 0 ? null : textures.get(unit.boundTextureId);
    }

    OglBufferObject boundArrayBuffer() {
        return boundArrayBufferId == 0 ? null : buffers.get(boundArrayBufferId);
    }

    OglBufferObject boundElementArrayBuffer() {
        return boundElementArrayBufferId == 0 ? null : buffers.get(boundElementArrayBufferId);
    }

    OglBufferObject boundBuffer(int target) {
        return switch (target) {
            case GraphicsOGL.GL_ARRAY_BUFFER -> boundArrayBuffer();
            case GraphicsOGL.GL_ELEMENT_ARRAY_BUFFER -> boundElementArrayBuffer();
            default -> null;
        };
    }

    int sampleBoundTexture(float u, float v) {
        OglTexture texture = boundTexture();
        return texture == null ? 0xFFFFFFFF : texture.sample(u, v);
    }

    OglLight light(int lightEnum) {
        int index = lightEnum - GraphicsOGL.GL_LIGHT0;
        return index < 0 || index >= lights.length ? null : lights[index];
    }

    void markModelViewMatrixChanged() {
        modelViewNormalMatrixDirty = true;
    }

    void markPaletteMatrixChanged(int matrixIndex) {
        if (matrixIndex >= 0 && matrixIndex < paletteNormalMatrixDirty.length) {
            paletteNormalMatrixDirty[matrixIndex] = true;
        }
    }

    float[] normalMatrixForModelView() {
        ensureModelViewNormalMatrix();
        return modelViewNormalMatrix;
    }

    boolean normalMatrixValidForModelView() {
        ensureModelViewNormalMatrix();
        return modelViewNormalMatrixValid;
    }

    float[] normalMatrixForPalette(int matrixIndex) {
        ensurePaletteNormalMatrix(matrixIndex);
        return paletteNormalMatrices[matrixIndex];
    }

    boolean normalMatrixValidForPalette(int matrixIndex) {
        ensurePaletteNormalMatrix(matrixIndex);
        return paletteNormalMatrixValid[matrixIndex];
    }

    private void ensureModelViewNormalMatrix() {
        if (!modelViewNormalMatrixDirty) {
            return;
        }
        modelViewNormalMatrixValid = computeNormalMatrix(modelViewMatrix, modelViewNormalMatrix);
        modelViewNormalMatrixDirty = false;
    }

    private void ensurePaletteNormalMatrix(int matrixIndex) {
        if (!paletteNormalMatrixDirty[matrixIndex]) {
            return;
        }
        paletteNormalMatrixValid[matrixIndex] = computeNormalMatrix(
                paletteMatrices[matrixIndex],
                paletteNormalMatrices[matrixIndex]);
        paletteNormalMatrixDirty[matrixIndex] = false;
    }

    private static OglTextureUnit[] createTextureUnits() {
        OglTextureUnit[] units = new OglTextureUnit[OGL_TEXTURE_UNIT_COUNT];
        for (int i = 0; i < units.length; i++) {
            units[i] = new OglTextureUnit();
        }
        return units;
    }

    @SuppressWarnings("unchecked")
    private static Deque<float[]>[] createPaletteMatrixStacks() {
        Deque<float[]>[] stacks = (Deque<float[]>[]) new Deque<?>[OGL_MAX_PALETTE_MATRICES];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = new ArrayDeque<>();
        }
        return stacks;
    }

    private static OglLight[] createLights() {
        OglLight[] lights = new OglLight[8];
        for (int i = 0; i < lights.length; i++) {
            lights[i] = new OglLight(i);
        }
        return lights;
    }

    static float[] identityMatrix() {
        return new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
    }

    private static boolean computeNormalMatrix(float[] matrix, float[] target) {
        if (matrix == null) {
            return false;
        }
        float a00 = matrix[0];
        float a01 = matrix[4];
        float a02 = matrix[8];
        float a10 = matrix[1];
        float a11 = matrix[5];
        float a12 = matrix[9];
        float a20 = matrix[2];
        float a21 = matrix[6];
        float a22 = matrix[10];
        float c00 = (a11 * a22) - (a12 * a21);
        float c01 = (a02 * a21) - (a01 * a22);
        float c02 = (a01 * a12) - (a02 * a11);
        float c10 = (a12 * a20) - (a10 * a22);
        float c11 = (a00 * a22) - (a02 * a20);
        float c12 = (a02 * a10) - (a00 * a12);
        float c20 = (a10 * a21) - (a11 * a20);
        float c21 = (a01 * a20) - (a00 * a21);
        float c22 = (a00 * a11) - (a01 * a10);
        float determinant = (a00 * c00) + (a01 * c10) + (a02 * c20);
        if (Math.abs(determinant) < 0.000001f) {
            return false;
        }
        float reciprocalDeterminant = 1f / determinant;
        target[0] = c00 * reciprocalDeterminant;
        target[1] = c01 * reciprocalDeterminant;
        target[2] = c02 * reciprocalDeterminant;
        target[3] = c10 * reciprocalDeterminant;
        target[4] = c11 * reciprocalDeterminant;
        target[5] = c12 * reciprocalDeterminant;
        target[6] = c20 * reciprocalDeterminant;
        target[7] = c21 * reciprocalDeterminant;
        target[8] = c22 * reciprocalDeterminant;
        return true;
    }
}
}
