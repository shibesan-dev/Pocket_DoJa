package opendoja.host.ogl;

import opendoja.compat.awt.Rectangle;
import opendoja.host.OpenDoJaLog;

/**
 * Optional hardware scene backend. The desktop build has no EGL/GLES
 * implementation, so the base class is an inert fallback; the Android app
 * provides {@code AndroidOglHardwareBackend} in the same package.
 */
class OglHardwareBackend {
    private static final String ANDROID_BACKEND =
            "opendoja.host.ogl.AndroidOglHardwareBackend";

    static OglHardwareBackend create(OglRenderer owner) {
        try {
            Class<?> backendClass = Class.forName(ANDROID_BACKEND);
            java.lang.reflect.Method factory =
                    backendClass.getDeclaredMethod("create", OglRenderer.class);
            factory.setAccessible(true);
            Object backend = factory.invoke(null, owner);
            if (backend instanceof OglHardwareBackend) {
                return (OglHardwareBackend) backend;
            }
        } catch (ClassNotFoundException ignored) {
            // Desktop build: no Android EGL/GLES backend on the classpath.
        } catch (Throwable throwable) {
            OpenDoJaLog.warn(OglHardwareBackend.class,
                    "Hardware OpenGL ES backend unavailable: "
                            + OpenDoJaLog.throwableToString(throwable));
        }
        return new OglHardwareBackend(owner);
    }

    final OglRenderer owner;

    OglHardwareBackend(OglRenderer owner) {
        this.owner = owner;
    }

    void prepareForSoftwareMutation() {
    }

    /** GL-level flush only; does not copy the framebuffer back to software. */
    void flush() {
    }

    /** Copies pending hardware pixels back for a software consumer. */
    void flushForReadback() {
        flush();
    }

    void flushForPresentation() {
        flushForReadback();
    }

    boolean draw(int mode, int first, int count, OglRenderer.OglIndexSource indexSource) {
        return false;
    }

    boolean clear(int mask) {
        return false;
    }

    void endDrawing() {
    }

    void onTextureDeleted(int textureId) {
    }

    void onPresentedSoftwareOverlay(Rectangle bounds) {
    }

    void onSoftwareSurfaceMutation() {
    }

    void onHostDelegateRecreated() {
    }

    void close() {
    }

    boolean hasBufferedHardwarePresentation() {
        return false;
    }
}
