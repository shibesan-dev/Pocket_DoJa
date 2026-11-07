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

public class ThemeRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "OpenDoJa";
    private final Context context;
    private volatile int program = 0;
    private volatile String themeId = "flow";
    private int timeUniform = -1;
    private int resolutionUniform = -1;
    private int width = 1;
    private int height = 1;
    private int vertexBuffer = 0;

    public ThemeRenderer(Context context) {
        this.context = context;
    }

    public void setTheme(String id) {
        themeId = id;
        program = 0;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        program = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = h;
        GLES20.glViewport(0, 0, w, h);
        if (vertexBuffer == 0) {
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
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (program == 0) {
            String code = loadShaderCode(themeId);
            if (code != null) {
                program = compileProgram(code);
            if (program != 0) {
                timeUniform = GLES20.glGetUniformLocation(program, "iTime");
                resolutionUniform = GLES20.glGetUniformLocation(program, "iResolution");
                } else {
                    opendoja.host.OpenDoJaLog.error(ThemeRenderer.class, "onDrawFrame: compilation failed for " + themeId);
                }
            } else {
                opendoja.host.OpenDoJaLog.error(ThemeRenderer.class, "onDrawFrame: failed to load shader code for " + themeId);
            }
        }
        if (program != 0) {
            GLES20.glUseProgram(program);
            if (timeUniform >= 0) {
                float t = (float) (android.os.SystemClock.elapsedRealtime() % 16000L / 1000.0);
                GLES20.glUniform1f(timeUniform, t);
            }
            if (resolutionUniform >= 0) {
                GLES20.glUniform3f(resolutionUniform, width, height, 1f);
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer);
            int pos = GLES20.glGetAttribLocation(program, "position");
            if (pos < 0) pos = 0;
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(pos);
        }
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
            opendoja.host.OpenDoJaLog.error(ThemeRenderer.class, "Failed to load theme " + id, e);
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
            opendoja.host.OpenDoJaLog.error(ThemeRenderer.class, "Link failed (" + p + "): " + GLES20.glGetProgramInfoLog(prog));
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
            opendoja.host.OpenDoJaLog.error(ThemeRenderer.class, "Compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
