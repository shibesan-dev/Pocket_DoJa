package opendoja.android;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders the decorative "shadertoy" background themes.
 *
 * <p>These are full-screen procedural fragment shaders, so their cost scales with shaded
 * pixels x frame rate. To keep the device cool they are rendered at half resolution into an
 * offscreen framebuffer and then upscaled to the screen by the GPU (4x fewer shaded pixels),
 * and the Home screen only drives them at ~20 fps.</p>
 */
public class ThemeRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "OpenDoJa";
    private static final int DOWNSCALE = 2;

    // In-app ring buffer of theme/GL lifecycle events (no adb logcat needed).
    private static final java.util.ArrayDeque<String> EVENTS = new java.util.ArrayDeque<>();

    private static void event(String message) {
        record(message);
        android.util.Log.i(TAG, message);
    }

    private static void error(String message) {
        record("ERROR " + message);
        android.util.Log.e(TAG, message);
    }

    private static void error(String message, Throwable throwable) {
        record("ERROR " + message + " (" + throwable + ")");
        android.util.Log.e(TAG, message, throwable);
    }

    private static void record(String message) {
        synchronized (EVENTS) {
            EVENTS.addLast(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + message);
            while (EVENTS.size() > 120) {
                EVENTS.removeFirst();
            }
        }
    }

    /** Records an external lifecycle event (e.g. Home resume/pause) into the same buffer. */
    public static void logEvent(String message) {
        record(message);
    }

    /** Snapshot of recent theme/GL lifecycle events, for the in-app diagnostics report. */
    public static String recentEvents() {
        synchronized (EVENTS) {
            StringBuilder builder = new StringBuilder();
            for (String event : EVENTS) {
                builder.append(event).append('\n');
            }
            return builder.toString();
        }
    }

    private final Context context;
    private volatile int program = 0;
    private volatile String themeId = "flow";
    private int timeUniform = -1;
    private int resolutionUniform = -1;
    private int width = 1;
    private int height = 1;
    private int vertexBuffer = 0;

    // Offscreen half-resolution target for the theme shader.
    private int fbo = 0;
    private int fboTexture = 0;
    private int fboWidth = 0;
    private int fboHeight = 0;

    // Simple textured-quad program used to upscale the FBO to the screen.
    private int blitProgram = 0;
    private int blitTextureUniform = -1;
    private volatile long drawCount = 0L;
    private volatile long surfaceCreateCount = 0L;

    public ThemeRenderer(Context context) {
        this.context = context;
    }

    public void setTheme(String id) {
        themeId = id;
        program = 0;
    }

    /**
     * Drops references to all GPU objects so the next frame rebuilds them. Must run with the
     * GL context current (onSurfaceCreated or GLSurfaceView.queueEvent).
     */
    public void resetGlState() {
        program = 0;
        vertexBuffer = 0;
        timeUniform = -1;
        resolutionUniform = -1;
        fbo = 0;
        fboTexture = 0;
        fboWidth = 0;
        fboHeight = 0;
        blitProgram = 0;
        blitTextureUniform = -1;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            resetGlState();
            surfaceCreateCount++;
            event("ThemeRenderer.onSurfaceCreated theme=" + themeId + " drawCount=" + drawCount);
        } catch (Throwable throwable) {
            error("onSurfaceCreated failed: " + throwable);
        }
    }

    /** Frames actually rendered by onDrawFrame (for the diagnostics report). */
    public long drawCount() {
        return drawCount;
    }

    public long surfaceCreateCount() {
        return surfaceCreateCount;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        try {
            width = w;
            height = h;
            GLES20.glViewport(0, 0, w, h);
            ensureVertexBuffer();
            recreateFramebuffer();
            event("ThemeRenderer.onSurfaceChanged " + w + "x" + h
                    + " fbo=" + fboWidth + "x" + fboHeight);
        } catch (Throwable throwable) {
            error("onSurfaceChanged failed: " + throwable);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            drawFrame();
        } catch (Throwable throwable) {
            // A single bad frame must NOT kill GLSurfaceView's GL thread. Previously an
            // uncaught onDrawFrame exception terminated the GL thread while the ~20 fps
            // tick kept running, which froze the background (frames stopped, tick active).
            error("onDrawFrame failed: " + throwable);
        }
    }

    private void drawFrame() {
        if (width <= 0 || height <= 0) {
            return;
        }
        drawCount++;
        if (fbo == 0 || fboWidth <= 0 || fboHeight <= 0) {
            recreateFramebuffer();
        }
        boolean offscreen = fbo != 0;
        int targetWidth = offscreen ? fboWidth : width;
        int targetHeight = offscreen ? fboHeight : height;

        // Pass 1: render the theme shader (at half resolution when the FBO is available).
        if (offscreen) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        }
        GLES20.glViewport(0, 0, targetWidth, targetHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (program == 0) {
            String code = loadShaderCode(themeId);
            if (code != null) {
                program = compileProgram(code);
                if (program != 0) {
                    timeUniform = GLES20.glGetUniformLocation(program, "iTime");
                    resolutionUniform = GLES20.glGetUniformLocation(program, "iResolution");
                    event("ThemeRenderer: program ready theme=" + themeId);
                } else {
                    error("ThemeRenderer: compilation failed for " + themeId);
                }
            } else {
                error("ThemeRenderer: failed to load shader code for " + themeId);
            }
        }
        if (program != 0) {
            ensureVertexBuffer();
            GLES20.glUseProgram(program);
            if (timeUniform >= 0) {
                float t = (float) (android.os.SystemClock.elapsedRealtime() % 16000L / 1000.0);
                GLES20.glUniform1f(timeUniform, t);
            }
            if (resolutionUniform >= 0) {
                GLES20.glUniform3f(resolutionUniform, targetWidth, targetHeight, 1f);
            }
            drawQuad(program);
        }

        // Pass 2: upscale the offscreen texture to the whole surface (GPU scaled, filtered).
        if (offscreen) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            ensureBlitProgram();
            if (blitProgram != 0) {
                ensureVertexBuffer();
                GLES20.glUseProgram(blitProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
                if (blitTextureUniform >= 0) {
                    GLES20.glUniform1i(blitTextureUniform, 0);
                }
                drawQuad(blitProgram);
            }
        }
    }

    private void drawQuad(int shaderProgram) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
        int position = GLES20.glGetAttribLocation(shaderProgram, "position");
        if (position < 0) {
            position = 0;
        }
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(position);
    }

    private void ensureVertexBuffer() {
        if (vertexBuffer != 0) {
            return;
        }
        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        vertexBuffer = buffers[0];
        float[] vertices = {
                -1f, -1f, 1f, -1f, -1f, 1f,
                1f, -1f, 1f, 1f, -1f, 1f
        };
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
        java.nio.FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(vertices.length * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(vertices).position(0);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.length * 4, fb, GLES20.GL_STATIC_DRAW);
    }

    private void recreateFramebuffer() {
        int w = Math.max(1, width / DOWNSCALE);
        int h = Math.max(1, height / DOWNSCALE);
        if (fbo != 0 && fboWidth == w && fboHeight == h) {
            return;
        }
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            fbo = 0;
        }
        if (fboTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0);
            fboTexture = 0;
        }
        fboWidth = w;
        fboHeight = h;
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        fboTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        int[] framebuffers = new int[1];
        GLES20.glGenFramebuffers(1, framebuffers, 0);
        fbo = framebuffers[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            error("ThemeRenderer: framebuffer incomplete status=" + status);
            fbo = 0;
        }
    }

    private void ensureBlitProgram() {
        if (blitProgram != 0) {
            return;
        }
        String vertex = "attribute vec2 position;\nvarying vec2 vUv;\n"
                + "void main(){ vUv = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }";
        String fragment = "precision mediump float;\nvarying vec2 vUv;\nuniform sampler2D tex;\n"
                + "void main(){ gl_FragColor = texture2D(tex, vUv); }";
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs);
            if (fs != 0) GLES20.glDeleteShader(fs);
            return;
        }
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (status[0] == 0) {
            error("ThemeRenderer: blit link failed: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return;
        }
        blitProgram = prog;
        blitTextureUniform = GLES20.glGetUniformLocation(prog, "tex");
    }

    private String loadShaderCode(String id) {
        try {
            InputStream is = context.getAssets().open("theme/" + id + ".json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            JSONObject json = new JSONObject(sb.toString());
            JSONArray rp = json.getJSONArray("renderpass");
            JSONObject pass = rp.getJSONObject(0);
            return pass.getString("code");
        } catch (Exception e) {
            error("ThemeRenderer: failed to load theme " + id, e);
            return null;
        }
    }

    private int compileProgram(String fragCode) {
        String vert = "attribute vec2 position;\nvoid main(){gl_Position=vec4(position,0.0,1.0);}";
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vert);
        if (vs == 0) return 0;
        String[] precisions = {"highp", "mediump"};
        for (String p : precisions) {
            String frag = "#version 100\nprecision " + p + " float;\nuniform " + p + " float iTime;\nuniform " + p + " vec3 iResolution;\n" + fragCode + "\nvoid main(){mainImage(gl_FragColor,gl_FragCoord.xy);}";
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, frag);
            if (fs == 0) continue;
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] status = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] != 0) {
                GLES20.glDeleteShader(vs);
                GLES20.glDeleteShader(fs);
                return prog;
            }
            error("ThemeRenderer: link failed (" + p + "): " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            GLES20.glDeleteShader(fs);
        }
        GLES20.glDeleteShader(vs);
        return 0;
    }

    private int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            error("ThemeRenderer: compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}