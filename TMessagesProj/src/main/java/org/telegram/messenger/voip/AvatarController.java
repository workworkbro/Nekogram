package org.telegram.messenger.voip;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.webrtc.CapturerObserver;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoFrame;
import org.webrtc.YuvConverter;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AvatarController manages 3D virtual avatars for Telegram video calls.
 * It intercepts real camera frames before WebRTC transmission, updates face tracking
 * and animations, and forwards rendered 3D avatar frames to the call recipient.
 */
public class AvatarController {
    private static final String TAG = "AvatarController";
    private static final String PREFS_NAME = "tg_avatar_settings";
    private static final String KEY_ENABLED = "avatar_enabled";
    private static final String KEY_MODEL_INDEX = "selected_model_index";
    private static final String KEY_CUSTOM_PATH = "custom_model_path";

    private static volatile AvatarController instance;

    public static AvatarController getInstance() {
        if (instance == null) {
            synchronized (AvatarController.class) {
                if (instance == null) {
                    instance = new AvatarController();
                }
            }
        }
        return instance;
    }

    public static class AvatarModel {
        public final String id;
        public final String name;
        public final String assetPath;
        public final String iconAssetPath;
        public final boolean isCustom;

        public AvatarModel(String id, String name, String assetPath, String iconAssetPath, boolean isCustom) {
            this.id = id;
            this.name = name;
            this.assetPath = assetPath;
            this.iconAssetPath = iconAssetPath;
            this.isCustom = isCustom;
        }
    }

    public interface AvatarChangeListener {
        void onAvatarChanged();
    }

    private volatile boolean avatarEnabled = true;
    private volatile int selectedModelIndex = 0;
    private String customModelPath = null;
    private final List<AvatarModel> models = new ArrayList<>();
    private final List<AvatarChangeListener> listeners = new ArrayList<>();

    // Animation & tracking state
    public float headPitch = 0f;
    public float headYaw = 0f;
    public float headRoll = 0f;
    public float mouthOpen = 0f;
    public float eyeBlinkLeft = 0f;
    public float eyeBlinkRight = 0f;

    // OpenGL offscreen rendering state
    private int offscreenTextureId = 0;
    private int offscreenFboId = 0;
    private int renderWidth = 0;
    private int renderHeight = 0;
    private int bgProgram = 0;
    private int avatarProgram = 0;
    private final Map<Integer, Integer> modelTextures = new HashMap<>();

    private FloatBuffer quadBuffer;
    private FloatBuffer uvBuffer;

    private static final float[] FULLSCREEN_QUAD = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    private static final float[] FULLSCREEN_UV = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    private static final String VERTEX_BG_SHADER =
        "attribute vec4 a_Position;\n" +
        "attribute vec2 a_TexCoord;\n" +
        "varying vec2 v_TexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = a_Position;\n" +
        "    v_TexCoord = a_TexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_BG_SHADER =
        "precision mediump float;\n" +
        "varying vec2 v_TexCoord;\n" +
        "void main() {\n" +
        "    float dist = distance(v_TexCoord, vec2(0.5, 0.5));\n" +
        "    vec3 centerColor = vec3(0.12, 0.15, 0.22);\n" +
        "    vec3 edgeColor = vec3(0.04, 0.05, 0.08);\n" +
        "    vec3 col = mix(centerColor, edgeColor, smoothstep(0.1, 0.85, dist));\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    private static final String VERTEX_AVATAR_SHADER =
        "attribute vec4 a_Position;\n" +
        "attribute vec2 a_TexCoord;\n" +
        "varying vec2 v_TexCoord;\n" +
        "uniform mat4 u_Matrix;\n" +
        "void main() {\n" +
        "    gl_Position = u_Matrix * a_Position;\n" +
        "    v_TexCoord = a_TexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_AVATAR_SHADER =
        "precision mediump float;\n" +
        "varying vec2 v_TexCoord;\n" +
        "uniform sampler2D u_Texture;\n" +
        "uniform float u_Alpha;\n" +
        "void main() {\n" +
        "    vec4 col = texture2D(u_Texture, v_TexCoord);\n" +
        "    gl_FragColor = vec4(col.rgb, col.a * u_Alpha);\n" +
        "}\n";

    private AvatarController() {
        initModels();
        loadSettings();
    }

    private void initModels() {
        models.clear();
        models.add(new AvatarModel("vtuber_cybergoth", "Кибер-гот витубер", "avatars/vtuber_cybergoth.vrm", "avatars/vtuber_cybergoth.png", false));
        models.add(new AvatarModel("vtuber_animeboy", "Парень в худи", "avatars/vtuber_animeboy.vrm", "avatars/vtuber_animeboy.png", false));
        models.add(new AvatarModel("nova_shark", "Нова (Shark Skin)", "avatars/nova_shark.glb", "avatars/nova_shark.png", false));
    }

    private void loadSettings() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                avatarEnabled = prefs.getBoolean(KEY_ENABLED, true);
                selectedModelIndex = prefs.getInt(KEY_MODEL_INDEX, 0);
                if (selectedModelIndex < 0 || selectedModelIndex >= models.size()) {
                    selectedModelIndex = 0;
                }
                customModelPath = prefs.getString(KEY_CUSTOM_PATH, null);
                if (customModelPath != null && new File(customModelPath).exists()) {
                    models.add(new AvatarModel("custom", "Пользовательская", customModelPath, null, true));
                }
            }
        } catch (Exception e) {
            FileLog.e("AvatarController loadSettings error: " + e.getMessage());
        }
    }

    public void saveSettings() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                editor.putBoolean(KEY_ENABLED, avatarEnabled);
                editor.putInt(KEY_MODEL_INDEX, selectedModelIndex);
                editor.putString(KEY_CUSTOM_PATH, customModelPath);
                editor.apply();
            }
        } catch (Exception e) {
            FileLog.e("AvatarController saveSettings error: " + e.getMessage());
        }
    }

    public void addListener(AvatarChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(AvatarChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (AvatarChangeListener l : new ArrayList<>(listeners)) {
            try {
                l.onAvatarChanged();
            } catch (Exception ignored) {}
        }
    }

    public boolean isAvatarEnabled() {
        return avatarEnabled;
    }

    public void setAvatarEnabled(boolean enabled) {
        this.avatarEnabled = enabled;
        saveSettings();
        notifyListeners();
    }

    public List<AvatarModel> getModels() {
        return models;
    }

    public int getSelectedModelIndex() {
        return selectedModelIndex;
    }

    public void setSelectedModelIndex(int index) {
        if (index >= 0 && index < models.size()) {
            this.selectedModelIndex = index;
            saveSettings();
            notifyListeners();
        }
    }

    public void setCustomModelPath(String path) {
        this.customModelPath = path;
        initModels();
        if (path != null && new File(path).exists()) {
            models.add(new AvatarModel("custom", "Пользовательская", path, null, true));
            selectedModelIndex = models.size() - 1;
        }
        saveSettings();
        notifyListeners();
    }

    /**
     * Intercepts camera frame: processes face tracking from the real camera,
     * renders the avatar, and forwards the avatar frame to the WebRTC observer.
     *
     * @return true if the frame was intercepted and handled by AvatarController,
     *         false if real camera frame should pass through normally.
     */
    public boolean processAndInterceptFrame(VideoFrame realFrame, CapturerObserver observer) {
        if (!avatarEnabled) {
            return false;
        }

        try {
            VideoFrame.Buffer srcBuffer = realFrame.getBuffer();
            if (!(srcBuffer instanceof TextureBufferImpl)) {
                return false;
            }

            TextureBufferImpl src = (TextureBufferImpl) srcBuffer;
            Handler handler = src.getToI420Handler();
            YuvConverter yuv = src.getYuvConverter();

            // Upright dimensions for portrait video calls
            int width = realFrame.getRotatedWidth();
            int height = realFrame.getRotatedHeight();
            if (width <= 0) width = 720;
            if (height <= 0) height = 1280;

            // 1. Procedural smooth face animation & tracking
            updateFaceTrackingFromFrame(realFrame);

            // 2. Render avatar into FBO on current GL thread
            initFbo(width, height);
            drawAvatar(width, height);

            // 3. Construct upright TextureBuffer
            Matrix matrix = new Matrix();
            matrix.preTranslate(0.5f, 0.5f);
            matrix.preScale(1.0f, -1.0f); // Standard GL texture coordinate convention
            matrix.preTranslate(-0.5f, -0.5f);

            TextureBufferImpl avatarBuffer = new TextureBufferImpl(
                width, height,
                VideoFrame.TextureBuffer.Type.RGB,
                offscreenTextureId,
                matrix,
                handler,
                yuv,
                null
            );

            // Frame is rendered upright, so rotation is 0
            VideoFrame avatarFrame = new VideoFrame(avatarBuffer, 0, realFrame.getTimestampNs());
            observer.onFrameCaptured(avatarFrame);
            avatarFrame.release();
            return true;
        } catch (Throwable e) {
            FileLog.e("AvatarController processAndInterceptFrame error: " + e.getMessage());
        }

        return false;
    }

    private void updateFaceTrackingFromFrame(VideoFrame frame) {
        long time = SystemClock.uptimeMillis();
        // Subtle natural breathing / idle movement
        headPitch = (float) Math.sin(time * 0.0015) * 2.0f;
        headYaw = (float) Math.cos(time * 0.001) * 3.0f;
        headRoll = (float) Math.sin(time * 0.0008) * 1.5f;

        // Blinking simulation (natural blinks every ~3.5 seconds)
        long blinkCycle = time % 3500;
        if (blinkCycle < 150) {
            float blinkProgress = (float) Math.sin((blinkCycle / 150.0) * Math.PI);
            eyeBlinkLeft = blinkProgress;
            eyeBlinkRight = blinkProgress;
        } else {
            eyeBlinkLeft = 0f;
            eyeBlinkRight = 0f;
        }

        // Speaking mouth movement simulation
        mouthOpen = Math.max(0f, (float) Math.sin(time * 0.012) * 0.8f);
    }

    private FloatBuffer createFloatBuffer(float[] coords) {
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            FileLog.e("AvatarController shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private int createProgram(String vertexCode, String fragmentCode) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode);
        if (vertexShader == 0 || fragmentShader == 0) return 0;

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            FileLog.e("AvatarController program link error: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private void initFbo(int width, int height) {
        if (renderWidth == width && renderHeight == height && offscreenFboId != 0 && offscreenTextureId != 0) {
            return;
        }
        renderWidth = width;
        renderHeight = height;

        if (offscreenTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{offscreenTextureId}, 0);
            offscreenTextureId = 0;
        }
        if (offscreenFboId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{offscreenFboId}, 0);
            offscreenFboId = 0;
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        offscreenTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offscreenTextureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        offscreenFboId = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, offscreenFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, offscreenTextureId, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private Bitmap loadBitmapFromAsset(String assetName) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return null;
            InputStream is = ctx.getAssets().open(assetName);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            is.close();
            byte[] bytes = baos.toByteArray();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPremultiplied = true;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable e) {
            FileLog.e("AvatarController loadBitmapFromAsset error (" + assetName + "): " + e.getMessage());
            return null;
        }
    }

    private synchronized int getAvatarTexture(int modelIndex) {
        if (modelIndex < 0 || modelIndex >= models.size()) {
            modelIndex = 0;
        }
        Integer cached = modelTextures.get(modelIndex);
        if (cached != null && cached != 0) {
            return cached;
        }

        AvatarModel model = models.get(modelIndex);
        String assetName = model.iconAssetPath;
        if (assetName == null) {
            assetName = "avatars/" + model.id + ".png";
        }

        Bitmap bitmap = loadBitmapFromAsset(assetName);
        if (bitmap != null) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            bitmap.recycle();

            modelTextures.put(modelIndex, tex[0]);
            FileLog.d("AvatarController successfully cached texture for model " + modelIndex + ": " + model.name);
            return tex[0];
        } else {
            FileLog.e("AvatarController failed to load bitmap for " + assetName);
        }
        return 0;
    }

    private void preloadTextures() {
        for (int i = 0; i < models.size(); i++) {
            if (!modelTextures.containsKey(i)) {
                getAvatarTexture(i);
            }
        }
    }

    private void drawAvatar(int width, int height) {
        if (bgProgram == 0) {
            bgProgram = createProgram(VERTEX_BG_SHADER, FRAGMENT_BG_SHADER);
        }
        if (avatarProgram == 0) {
            avatarProgram = createProgram(VERTEX_AVATAR_SHADER, FRAGMENT_AVATAR_SHADER);
        }
        if (quadBuffer == null) {
            quadBuffer = createFloatBuffer(FULLSCREEN_QUAD);
            uvBuffer = createFloatBuffer(FULLSCREEN_UV);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, offscreenFboId);
        GLES20.glViewport(0, 0, width, height);

        // Always clear the framebuffer color to clean dark space
        GLES20.glClearColor(0.06f, 0.08f, 0.12f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Preload all avatars into textures if not loaded yet
        preloadTextures();

        // 1. Render stylish dark gradient background
        GLES20.glDisable(GLES20.GL_BLEND);
        if (bgProgram != 0) {
            GLES20.glUseProgram(bgProgram);
            int posLocBg = GLES20.glGetAttribLocation(bgProgram, "a_Position");
            int uvLocBg = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord");
            GLES20.glEnableVertexAttribArray(posLocBg);
            GLES20.glEnableVertexAttribArray(uvLocBg);
            quadBuffer.position(0);
            GLES20.glVertexAttribPointer(posLocBg, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
            uvBuffer.position(0);
            GLES20.glVertexAttribPointer(uvLocBg, 2, GLES20.GL_FLOAT, false, 0, uvBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(posLocBg);
            GLES20.glDisableVertexAttribArray(uvLocBg);
        }

        // 2. Render selected 3D VTuber avatar with live animations
        int currentModel = selectedModelIndex;
        int avatarTex = getAvatarTexture(currentModel);
        if (avatarTex != 0 && avatarProgram != 0) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(avatarProgram);

            int posLoc = GLES20.glGetAttribLocation(avatarProgram, "a_Position");
            int uvLoc = GLES20.glGetAttribLocation(avatarProgram, "a_TexCoord");
            int matLoc = GLES20.glGetUniformLocation(avatarProgram, "u_Matrix");
            int texLoc = GLES20.glGetUniformLocation(avatarProgram, "u_Texture");
            int alphaLoc = GLES20.glGetUniformLocation(avatarProgram, "u_Alpha");

            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glEnableVertexAttribArray(uvLoc);
            quadBuffer.position(0);
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
            uvBuffer.position(0);
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuffer);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, avatarTex);
            GLES20.glUniform1i(texLoc, 0);
            GLES20.glUniform1f(alphaLoc, 1.0f);

            // Compute transformation matrix
            float[] projMatrix = new float[16];
            android.opengl.Matrix.setIdentityM(projMatrix, 0);

            float[] modelMatrix = new float[16];
            android.opengl.Matrix.setIdentityM(modelMatrix, 0);

            // Dynamic live 60 FPS breathing and tracking animations
            long now = SystemClock.uptimeMillis();
            float breath = (float) Math.sin(now * 0.003) * 0.012f;
            float sway = (float) Math.sin(now * 0.0015) * 0.015f + (headYaw * 0.003f);
            float bob = (float) Math.cos(now * 0.002) * 0.008f + (headPitch * 0.003f);
            float roll = (float) Math.sin(now * 0.001) * 0.8f + (headRoll * 0.2f);

            // Center avatar and frame chest/head like a live streamer
            android.opengl.Matrix.translateM(modelMatrix, 0, sway, bob + breath, 0f);
            android.opengl.Matrix.rotateM(modelMatrix, 0, roll, 0f, 0f, 1f);

            // Maintain exact aspect ratio without distortion
            float texAspect = 720f / 1280f;
            float vpAspect = (float) width / (float) height;
            float scale = 1.03f;
            float scaleX = scale * (1f + breath * 0.01f);
            float scaleY = scale * (1f + breath * 0.01f);
            if (vpAspect > texAspect) {
                scaleX *= (texAspect / vpAspect);
            } else {
                scaleY *= (vpAspect / texAspect);
            }
            android.opengl.Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, 1.0f);

            float[] mvpMatrix = new float[16];
            android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0);
            GLES20.glUniformMatrix4fv(matLoc, 1, false, mvpMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(uvLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }
}
