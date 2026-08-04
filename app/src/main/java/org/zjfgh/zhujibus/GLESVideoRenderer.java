package org.zjfgh.zhujibus;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OpenGL ES 视频渲染器（CameraX 架构）
 * ⭐ 双 EGL surface 输出：previewSurface（TextureView 预览）+ encoderSurface（MediaCodec 录制）
 * ⭐ 共享同一个 EGL Context，每帧渲染两次（preview 始终，encoder 仅录制时）
 * ⭐ 性能优化：预分配 FloatBuffer + texSubImage2D 替代每帧 texImage2D
 */
public class GLESVideoRenderer {
    private static final String TAG = "GLESVideoRenderer";

    // EGL 相关
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig = null;
    private EGLSurface eglPreviewSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface eglEncoderSurface = EGL14.EGL_NO_SURFACE;

    // 纹理相关
    private int cameraTextureId = -1;
    private int mapTexture2DId = -1;
    private int overlayTexture2DId = -1;
    private SurfaceTexture cameraSurfaceTexture;
    private long lastPresentationTimeNs = 0L;
    private final Object bitmapLock = new Object();

    // ⭐ 纹理初始化跟踪（首次 texImage2D，后续 texSubImage2D）
    private boolean mapTextureInitialized = false;
    private boolean overlayTextureInitialized = false;
    private int mapTextureWidth = 0;
    private int mapTextureHeight = 0;
    private int overlayTextureWidth = 0;
    private int overlayTextureHeight = 0;

    // 渲染线程
    private RenderThread renderThread;
    private volatile boolean isRunning = false;
    private final Lock frameLock = new ReentrantLock();
    private final Condition frameAvailable = frameLock.newCondition();
    private boolean framePending = false;

    private CountDownLatch glInitLatch = new CountDownLatch(1);
    private volatile boolean glResourcesInitialized = false;

    // 画面尺寸
    private int videoWidth = 1920;
    private int videoHeight = 1080;
    // ⭐ 相机输出分辨率（CameraX 实际请求），用于宽高比校正
    private int camBufferWidth = 0;
    private int camBufferHeight = 0;
    private volatile boolean cameraVerticesDirty = true;
    private final float[] cameraTexMatrix = new float[16];
    private int mapWidth = 300;
    private int mapHeight = 300;
    private int mapPositionX = 1620;
    private int mapPositionY = 20;
    private float mapCornerRadius = 24f;

    // 信息面板叠加尺寸和位置
    private int overlayWidth = 1920;
    private int overlayHeight = 100;
    private int overlayPositionX = 0;
    private int overlayPositionY = 0;

    private boolean isMapEnabled = false;
    private boolean isOverlayEnabled = false;

    // ⭐ 预览 Surface（来自 TextureView）
    private Surface previewSurface;

    // ⭐ Encoder Surface 动态管理（主线程设置请求，渲染线程处理）
    private volatile Surface pendingEncoderSurface = null;
    private volatile boolean encoderSurfaceReleaseRequested = false;
    private volatile boolean hasEncoderSurface = false;

    // ⭐ 预分配顶点 FloatBuffer（避免每帧分配）
    private FloatBuffer texCoordBufferCamera;
    private FloatBuffer texCoordBufferMap;
    private FloatBuffer mapVertexBuffer;
    private FloatBuffer overlayVertexBuffer;
    private FloatBuffer cameraVertexBuffer;  // ⭐ 相机顶点（宽高比校正 center-crop）

    // ⭐ 最新 Bitmap（主线程设置，渲染线程读取）
    private volatile android.graphics.Bitmap pendingMapBitmap;
    private volatile android.graphics.Bitmap pendingOverlayBitmap;

    // ===== Shader 程序 =====
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = aTextureCoord.xy;\n" +
            "}\n";

    // ⭐ OES 专用 vertex shader：应用 SurfaceTexture.getTransformMatrix()，修正相机画面方向/翻转
    // aTextureCoord 只传 .xy（2 分量），在此补成 vec4(s,t,0,1) 以正确参与 mat4 变换
    private static final String VERTEX_SHADER_OES =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * vec4(aTextureCoord.xy, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTextureCoord);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTextureCoord);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_2D_ROUNDED =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec2 uSize;\n" +
            "uniform float uRadius;\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(uTexture, vTextureCoord);\n" +
            "    if (uRadius > 0.0) {\n" +
            "        vec2 p = vTextureCoord * uSize;\n" +
            "        vec2 corner = vec2(p.x < uRadius ? p.x : uSize.x - p.x, p.y < uRadius ? p.y : uSize.y - p.y);\n" +
            "        if (corner.x < uRadius && corner.y < uRadius) {\n" +
            "            float dist = distance(corner, vec2(uRadius, uRadius));\n" +
            "            color.a *= 1.0 - smoothstep(uRadius - 2.0, uRadius, dist);\n" +
            "        }\n" +
            "    }\n" +
            "    gl_FragColor = color;\n" +
            "}\n";

    private int shaderProgramOES = -1;
    private int shaderProgram2D = -1;
    private int shaderProgramRounded2D = -1;
    private int positionHandleOES, texCoordHandleOES, textureHandleOES, texMatrixHandleOES;
    private int positionHandle2D, texCoordHandle2D, textureHandle2D;
    private int positionHandleRounded2D, texCoordHandleRounded2D, textureHandleRounded2D;
    private int sizeHandleRounded2D, radiusHandleRounded2D;

    // 纹理坐标（标准）
    private static final float[] TEXTURE_COORDS_NORMAL = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    // ⭐ 相机画面方向校正（绕纹理中心，作用于 OES 采样坐标）
    // 实测 SurfaceTexture.getTransformMatrix() 在本机会引入额外镜像/旋转，故不采用，
    // 改为按以下常量显式构造纹理矩阵。ROT_DEG 正值=图像视觉 CCW。
    // 9:16 portrait buffer 用 90（CCW90）转横屏；方向不对改 270，镜像则切对应 flip。
    private static final int CAMERA_ROT_DEG = 0;       // 0/90/180/270，正值=图像 CCW
    private static final boolean CAMERA_FLIP_H = false; // 水平镜像
    private static final boolean CAMERA_FLIP_V = false; // 垂直镜像

    /**
     * 初始化 EGL 环境
     * @param previewSurface 预览 Surface（来自 TextureView 的 SurfaceTexture）
     */
    public boolean initEGL(Surface previewSurface) {
        if (previewSurface == null || !previewSurface.isValid()) {
            Log.e(TAG, "previewSurface 无效");
            return false;
        }
        this.previewSurface = previewSurface;

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "获取 EGL Display 失败");
            return false;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "初始化 EGL 失败");
            return false;
        }

        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            Log.e(TAG, "选择 EGL 配置失败");
            return false;
        }
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "创建 EGL Context 失败");
            return false;
        }

        // 创建预览 EGL Surface
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglPreviewSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, previewSurface, surfaceAttribs, 0);
        if (eglPreviewSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "创建预览 EGL Surface 失败");
            return false;
        }

        Log.d(TAG, "EGL 初始化成功（previewSurface）");
        return true;
    }

    /**
     * 请求设置 Encoder Surface（录制开始时调用）
     * 渲染线程会在下一帧创建对应的 EGL Surface
     */
    public void setEncoderSurface(Surface encoderSurface) {
        if (encoderSurface == null || !encoderSurface.isValid()) {
            Log.e(TAG, "encoderSurface 无效");
            return;
        }
        this.pendingEncoderSurface = encoderSurface;
        this.encoderSurfaceReleaseRequested = false;
    }

    /**
     * 请求清除 Encoder Surface（录制结束时调用）
     */
    public void clearEncoderSurface() {
        this.encoderSurfaceReleaseRequested = true;
        this.pendingEncoderSurface = null;
    }

    /**
     * 渲染线程处理 Encoder Surface 请求（每帧调用）
     */
    private void handleEncoderSurfaceRequests() {
        if (pendingEncoderSurface != null) {
            Surface req = pendingEncoderSurface;
            pendingEncoderSurface = null;
            // 销毁旧的 encoder surface
            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface);
                eglEncoderSurface = EGL14.EGL_NO_SURFACE;
            }
            // 创建新的 encoder surface
            if (req.isValid()) {
                int[] surfaceAttribs = {EGL14.EGL_NONE};
                eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, req, surfaceAttribs, 0);
                if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                    hasEncoderSurface = true;
                    Log.d(TAG, "Encoder EGL Surface 创建成功");
                } else {
                    Log.e(TAG, "Encoder EGL Surface 创建失败");
                    hasEncoderSurface = false;
                }
            }
        }
        if (encoderSurfaceReleaseRequested) {
            encoderSurfaceReleaseRequested = false;
            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                // 切换到 preview surface 再销毁 encoder，避免销毁 current surface
                if (eglPreviewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, eglPreviewSurface, eglPreviewSurface, eglContext);
                }
                EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface);
                eglEncoderSurface = EGL14.EGL_NO_SURFACE;
            }
            hasEncoderSurface = false;
            Log.d(TAG, "Encoder EGL Surface 已销毁");
        }
    }

    public boolean initGLResources() {
        texCoordBufferCamera = createFloatBuffer(TEXTURE_COORDS_NORMAL);
        texCoordBufferMap = createFloatBuffer(TEXTURE_COORDS_NORMAL);
        // ⭐ 预分配顶点缓冲（避免每帧分配）
        mapVertexBuffer = createFloatBuffer(new float[8]);
        overlayVertexBuffer = createFloatBuffer(new float[8]);
        cameraVertexBuffer = createFloatBuffer(new float[8]);
        updateCameraVertices(); // 初始化相机顶点（camBuffer 未知时退化为全屏）
        buildCameraTexMatrix(); // 构造相机方向校正矩阵（旋转+翻转）

        // 摄像头 OES 纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        cameraTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 地图 2D 纹理
        GLES20.glGenTextures(1, textures, 0);
        mapTexture2DId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mapTexture2DId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 信息面板 2D 纹理
        GLES20.glGenTextures(1, textures, 0);
        overlayTexture2DId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture2DId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Shader 程序
        shaderProgramOES = createShaderProgram(VERTEX_SHADER_OES, FRAGMENT_SHADER_OES);
        if (shaderProgramOES < 0) return false;
        positionHandleOES = GLES20.glGetAttribLocation(shaderProgramOES, "aPosition");
        texCoordHandleOES = GLES20.glGetAttribLocation(shaderProgramOES, "aTextureCoord");
        textureHandleOES = GLES20.glGetUniformLocation(shaderProgramOES, "uTexture");
        texMatrixHandleOES = GLES20.glGetUniformLocation(shaderProgramOES, "uTexMatrix");

        shaderProgram2D = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
        if (shaderProgram2D < 0) return false;
        positionHandle2D = GLES20.glGetAttribLocation(shaderProgram2D, "aPosition");
        texCoordHandle2D = GLES20.glGetAttribLocation(shaderProgram2D, "aTextureCoord");
        textureHandle2D = GLES20.glGetUniformLocation(shaderProgram2D, "uTexture");

        shaderProgramRounded2D = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D_ROUNDED);
        if (shaderProgramRounded2D < 0) return false;
        positionHandleRounded2D = GLES20.glGetAttribLocation(shaderProgramRounded2D, "aPosition");
        texCoordHandleRounded2D = GLES20.glGetAttribLocation(shaderProgramRounded2D, "aTextureCoord");
        textureHandleRounded2D = GLES20.glGetUniformLocation(shaderProgramRounded2D, "uTexture");
        sizeHandleRounded2D = GLES20.glGetUniformLocation(shaderProgramRounded2D, "uSize");
        radiusHandleRounded2D = GLES20.glGetUniformLocation(shaderProgramRounded2D, "uRadius");

        Log.d(TAG, "GL 资源初始化成功: cameraTex=" + cameraTextureId + ", map2DTex=" + mapTexture2DId);
        return true;
    }

    private int createShaderProgram(String vertexSource, String fragmentSource) {
        int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShader, vertexSource);
        GLES20.glCompileShader(vertexShader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "编译顶点 Shader 失败: " + GLES20.glGetShaderInfoLog(vertexShader));
            GLES20.glDeleteShader(vertexShader);
            return -1;
        }

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, fragmentSource);
        GLES20.glCompileShader(fragmentShader);

        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "编译片段 Shader 失败: " + GLES20.glGetShaderInfoLog(fragmentShader));
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return -1;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "链接 Shader 程序失败: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            GLES20.glDeleteProgram(program);
            return -1;
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    public SurfaceTexture getCameraSurfaceTexture() {
        try {
            glInitLatch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "等待 GL 初始化被中断");
            return null;
        }
        if (!glResourcesInitialized) {
            Log.e(TAG, "GL 资源未初始化");
            return null;
        }
        return cameraSurfaceTexture;
    }

    public boolean waitForGLInit() {
        try {
            glInitLatch.await();
            return glResourcesInitialized;
        } catch (InterruptedException e) {
            Log.e(TAG, "等待 GL 初始化被中断");
            return false;
        }
    }

    public void startRendering() {
        if (renderThread != null && isRunning) return;
        isRunning = true;
        renderThread = new RenderThread();
        renderThread.start();
        Log.d(TAG, "渲染线程已启动");
    }

    public void stopRendering() {
        isRunning = false;
        if (renderThread != null) {
            frameLock.lock();
            try {
                framePending = true;
                frameAvailable.signal();
            } finally {
                frameLock.unlock();
            }
            try {
                renderThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "渲染线程 join 失败", e);
            }
            renderThread = null;
        }
        Log.d(TAG, "渲染线程已停止");
    }

    public void onFrameAvailable() {
        frameLock.lock();
        try {
            framePending = true;
            frameAvailable.signal();
        } finally {
            frameLock.unlock();
        }
    }

    /**
     * 渲染线程
     */
    private class RenderThread extends Thread {
        @Override
        public void run() {
            // 绑定 EGL Context 到 preview surface
            if (!EGL14.eglMakeCurrent(eglDisplay, eglPreviewSurface, eglPreviewSurface, eglContext)) {
                Log.e(TAG, "渲染线程绑定 EGL Context 到 preview surface 失败");
                glInitLatch.countDown();
                return;
            }

            if (!initGLResources()) {
                Log.e(TAG, "渲染线程 GL 资源初始化失败");
                glInitLatch.countDown();
                return;
            }
            glResourcesInitialized = true;
            Log.d(TAG, "GL 资源初始化完成: cameraTex=" + cameraTextureId);

            // 创建摄像头 SurfaceTexture（必须在 EGL Context 所在线程）
            cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
            cameraSurfaceTexture.setOnFrameAvailableListener(st -> GLESVideoRenderer.this.onFrameAvailable());
            Log.d(TAG, "camera SurfaceTexture 创建成功: texId=" + cameraTextureId);

            glInitLatch.countDown();
            Log.d(TAG, "渲染线程进入渲染循环");

            while (isRunning) {
                frameLock.lock();
                try {
                    while (isRunning && !framePending) {
                        frameAvailable.await();
                    }
                    framePending = false;
                } catch (InterruptedException e) {
                    break;
                } finally {
                    frameLock.unlock();
                }

                if (!isRunning) break;

                try {
                    renderFrameInternal();
                } catch (Exception e) {
                    Log.e(TAG, "渲染出错", e);
                }
            }
            Log.d(TAG, "渲染线程退出");
        }

        private void renderFrameInternal() {
            long frameStartNs = System.nanoTime();
            long presentationTimeNs = frameStartNs;
            if (cameraSurfaceTexture != null) {
                cameraSurfaceTexture.updateTexImage();
                long cameraTimestampNs = cameraSurfaceTexture.getTimestamp();
                if (cameraTimestampNs > 0) {
                    presentationTimeNs = cameraTimestampNs;
                }
            }
            if (presentationTimeNs <= lastPresentationTimeNs) {
                presentationTimeNs = lastPresentationTimeNs + 1_000_000L;
            }
            lastPresentationTimeNs = presentationTimeNs;

            // 处理 encoder surface 请求
            handleEncoderSurfaceRequests();

            // 取出最新的 bitmap
            android.graphics.Bitmap mapBmp = null;
            android.graphics.Bitmap overlayBmp = null;
            synchronized (bitmapLock) {
                mapBmp = pendingMapBitmap;
                pendingMapBitmap = null;
                overlayBmp = pendingOverlayBitmap;
                pendingOverlayBitmap = null;
            }

            // 更新地图纹理（首次 texImage2D，后续 texSubImage2D）
            if (isMapEnabled && mapBmp != null && !mapBmp.isRecycled()) {
                try {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mapTexture2DId);
                    if (!mapTextureInitialized
                            || mapTextureWidth != mapBmp.getWidth()
                            || mapTextureHeight != mapBmp.getHeight()) {
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mapBmp, 0);
                        mapTextureInitialized = true;
                        mapTextureWidth = mapBmp.getWidth();
                        mapTextureHeight = mapBmp.getHeight();
                    } else {
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, mapBmp);
                    }
                } finally {
                    if (!mapBmp.isRecycled()) mapBmp.recycle();
                }
            }

            // 更新信息面板纹理
            if (isOverlayEnabled && overlayBmp != null && !overlayBmp.isRecycled()) {
                try {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture2DId);
                    if (!overlayTextureInitialized
                            || overlayTextureWidth != overlayBmp.getWidth()
                            || overlayTextureHeight != overlayBmp.getHeight()) {
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBmp, 0);
                        overlayTextureInitialized = true;
                        overlayTextureWidth = overlayBmp.getWidth();
                        overlayTextureHeight = overlayBmp.getHeight();
                    } else {
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlayBmp);
                    }
                } finally {
                    if (!overlayBmp.isRecycled()) overlayBmp.recycle();
                }
            }

            // ===== 渲染到 preview surface（始终）=====
            if (eglPreviewSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglPreviewSurface, eglPreviewSurface, eglContext);
                renderScene(videoWidth, videoHeight);
                EGL14.eglSwapBuffers(eglDisplay, eglPreviewSurface);
            }

            // ===== 渲染到 encoder surface（仅录制时）=====
            if (hasEncoderSurface && eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext);
                renderScene(videoWidth, videoHeight);
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglEncoderSurface, presentationTimeNs);
                EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface);
            }
        }

        /**
         * 渲染完整场景到当前绑定的 EGL surface
         */
        private void renderScene(int width, int height) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            // 摄像头画面（OES，应用显式旋转/翻转矩阵 + 宽高比校正 center-crop）
            if (cameraVerticesDirty) {
                updateCameraVertices();
                cameraVerticesDirty = false;
            }
            drawTextureOES(cameraTextureId, cameraVertexBuffer, texCoordBufferCamera);

            // 信息面板叠加
            if (isOverlayEnabled && overlayTexture2DId >= 0 && overlayTextureInitialized) {
                float oLeft = (float) overlayPositionX / width * 2.0f - 1.0f;
                float oRight = (float) (overlayPositionX + overlayWidth) / width * 2.0f - 1.0f;
                float oTop = 1.0f - (float) overlayPositionY / height * 2.0f;
                float oBottom = 1.0f - (float) (overlayPositionY + overlayHeight) / height * 2.0f;
                float[] overlayVertices = {
                        oLeft, oBottom,
                        oRight, oBottom,
                        oLeft, oTop,
                        oRight, oTop
                };
                updateFloatBuffer(overlayVertexBuffer, overlayVertices);
                drawTexture2D(overlayTexture2DId, overlayVertexBuffer, texCoordBufferMap);
            }

            // 地图叠加（圆角）
            if (isMapEnabled && mapTexture2DId >= 0 && mapTextureInitialized) {
                float mapLeft = (float) mapPositionX / width * 2.0f - 1.0f;
                float mapRight = (float) (mapPositionX + mapWidth) / width * 2.0f - 1.0f;
                float mapTop = 1.0f - (float) mapPositionY / height * 2.0f;
                float mapBottom = 1.0f - (float) (mapPositionY + mapHeight) / height * 2.0f;
                float[] mapVertices = {
                        mapLeft, mapBottom,
                        mapRight, mapBottom,
                        mapLeft, mapTop,
                        mapRight, mapTop
                };
                updateFloatBuffer(mapVertexBuffer, mapVertices);
                drawTextureRounded2D(mapTexture2DId, mapVertexBuffer, texCoordBufferMap,
                        mapWidth, mapHeight, mapCornerRadius);
            }

            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawTextureOES(int textureId, FloatBuffer vertices, FloatBuffer texCoords) {
            GLES20.glUseProgram(shaderProgramOES);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureHandleOES, 0);
            GLES20.glUniformMatrix4fv(texMatrixHandleOES, 1, false, cameraTexMatrix, 0);
            GLES20.glEnableVertexAttribArray(positionHandleOES);
            GLES20.glVertexAttribPointer(positionHandleOES, 2, GLES20.GL_FLOAT, false, 8, vertices);
            GLES20.glEnableVertexAttribArray(texCoordHandleOES);
            GLES20.glVertexAttribPointer(texCoordHandleOES, 2, GLES20.GL_FLOAT, false, 8, texCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandleOES);
            GLES20.glDisableVertexAttribArray(texCoordHandleOES);
        }

        private void drawTexture2D(int textureId, FloatBuffer vertices, FloatBuffer texCoords) {
            GLES20.glUseProgram(shaderProgram2D);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(textureHandle2D, 0);
            GLES20.glEnableVertexAttribArray(positionHandle2D);
            GLES20.glVertexAttribPointer(positionHandle2D, 2, GLES20.GL_FLOAT, false, 8, vertices);
            GLES20.glEnableVertexAttribArray(texCoordHandle2D);
            GLES20.glVertexAttribPointer(texCoordHandle2D, 2, GLES20.GL_FLOAT, false, 8, texCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle2D);
            GLES20.glDisableVertexAttribArray(texCoordHandle2D);
        }

        private void drawTextureRounded2D(int textureId, FloatBuffer vertices, FloatBuffer texCoords,
                                          float w, float h, float radius) {
            GLES20.glUseProgram(shaderProgramRounded2D);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(textureHandleRounded2D, 0);
            GLES20.glUniform2f(sizeHandleRounded2D, w, h);
            GLES20.glUniform1f(radiusHandleRounded2D, radius);
            GLES20.glEnableVertexAttribArray(positionHandleRounded2D);
            GLES20.glVertexAttribPointer(positionHandleRounded2D, 2, GLES20.GL_FLOAT, false, 8, vertices);
            GLES20.glEnableVertexAttribArray(texCoordHandleRounded2D);
            GLES20.glVertexAttribPointer(texCoordHandleRounded2D, 2, GLES20.GL_FLOAT, false, 8, texCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandleRounded2D);
            GLES20.glDisableVertexAttribArray(texCoordHandleRounded2D);
        }
    }

    private FloatBuffer createFloatBuffer(float[] array) {
        ByteBuffer bb = ByteBuffer.allocateDirect(array.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(array);
        fb.position(0);
        return fb;
    }

    private void updateFloatBuffer(FloatBuffer buf, float[] values) {
        buf.position(0);
        buf.put(values);
        buf.position(0);
    }

    /**
     * ⭐ 计算相机画面顶点（center-crop 填满 viewport，无变形）。
     * viewport aspect Va = videoW/videoH；相机画面 oriented aspect Ca = camBufferW/camBufferH
     * （横屏锁定 + net 0° 旋转，buffer 不发生宽高互换）。
     * Ca > Va：宽度溢出、左右裁剪；Ca < Va：高度溢出、上下裁剪；相等则全屏。
     * camBuffer 未知时退化为全屏（无校正）。
     */
    private void updateCameraVertices() {
        int cw = camBufferWidth, ch = camBufferHeight;
        int vw = videoWidth, vh = videoHeight;
        float sx, sy;
        if (cw <= 0 || ch <= 0 || vw <= 0 || vh <= 0) {
            sx = 1f; sy = 1f;
        } else {
            // 90°/270° 旋转会使画面宽高互换，需用交换后的 oriented aspect 校正
            boolean swap = (CAMERA_ROT_DEG % 180) != 0;
            float camAspect = swap ? (float) ch / (float) cw : (float) cw / (float) ch;
            float viewAspect = (float) vw / (float) vh;
            sx = Math.max(1f, camAspect / viewAspect);   // 半宽
            sy = Math.max(1f, viewAspect / camAspect);   // 半高
        }
        float[] verts = {
                -sx, -sy,
                 sx, -sy,
                -sx,  sy,
                 sx,  sy
        };
        updateFloatBuffer(cameraVertexBuffer, verts);
    }

    /**
     * ⭐ 构造相机 OES 纹理矩阵：绕中心旋转 CAMERA_ROT_DEG（正值=图像视觉 CCW）+ 可选翻转。
     * 不使用 SurfaceTexture.getTransformMatrix()（本机会引入额外镜像/旋转）。
     * 数学等价：s' = rotate(s-0.5, t-0.5) + 0.5；90° → (1-t, s)。
     */
    private void buildCameraTexMatrix() {
        android.opengl.Matrix.setIdentityM(cameraTexMatrix, 0);
        android.opengl.Matrix.translateM(cameraTexMatrix, 0, 0.5f, 0.5f, 0f);
        int rot = ((CAMERA_ROT_DEG % 360) + 360) % 360;
        if (rot != 0) {
            android.opengl.Matrix.rotateM(cameraTexMatrix, 0, rot, 0f, 0f, 1f);
        }
        if (CAMERA_FLIP_H) {
            android.opengl.Matrix.scaleM(cameraTexMatrix, 0, -1f, 1f, 1f);
        }
        if (CAMERA_FLIP_V) {
            android.opengl.Matrix.scaleM(cameraTexMatrix, 0, 1f, -1f, 1f);
        }
        android.opengl.Matrix.translateM(cameraTexMatrix, 0, -0.5f, -0.5f, 0f);
    }

    public void updateMapBitmap(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        android.graphics.Bitmap oldBitmap;
        synchronized (bitmapLock) {
            oldBitmap = pendingMapBitmap;
            pendingMapBitmap = bitmap;
        }
        if (oldBitmap != null && !oldBitmap.isRecycled()) oldBitmap.recycle();
    }

    public void updateOverlayBitmap(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        android.graphics.Bitmap oldBitmap;
        synchronized (bitmapLock) {
            oldBitmap = pendingOverlayBitmap;
            pendingOverlayBitmap = bitmap;
        }
        if (oldBitmap != null && !oldBitmap.isRecycled()) oldBitmap.recycle();
    }

    // ===== Setter =====
    public void setMapEnabled(boolean enabled) { this.isMapEnabled = enabled; }
    public void setOverlayEnabled(boolean enabled) { this.isOverlayEnabled = enabled; }
    public void setVideoSize(int width, int height) {
        if (this.videoWidth != width || this.videoHeight != height) {
            this.videoWidth = width;
            this.videoHeight = height;
            cameraVerticesDirty = true;
        }
    }
    public void setMapSize(int width, int height) { this.mapWidth = width; this.mapHeight = height; }
    public void setMapPosition(int x, int y) { this.mapPositionX = x; this.mapPositionY = y; }
    public void setMapCornerRadius(float radius) { this.mapCornerRadius = Math.max(0f, radius); }
    public void setOverlaySize(int width, int height) { this.overlayWidth = width; this.overlayHeight = height; }
    public void setOverlayPosition(int x, int y) { this.overlayPositionX = x; this.overlayPositionY = y; }

    public Surface getCameraSurface() {
        if (cameraSurfaceTexture != null) {
            return new Surface(cameraSurfaceTexture);
        }
        return null;
    }

    /**
     * ⭐ 设置相机 SurfaceTexture 的 buffer 大小为 CameraX 实际请求的分辨率（而非录制 videoSize）。
     * 解耦相机输出分辨率与录制分辨率：相机按自身分辨率产出（无黑边/裁剪），
     * GLES viewport 仍为 videoSize，OES 全屏绘制自动缩放（16:9 无畸变）。
     * 必须在 getCameraSurface()/provideSurface 之前调用。
     */
    public void setCameraBufferSize(int width, int height) {
        if (width > 0 && height > 0) {
            if (camBufferWidth != width || camBufferHeight != height) {
                camBufferWidth = width;
                camBufferHeight = height;
                cameraVerticesDirty = true;
                // ⭐ 诊断日志：相机 buffer 分辨率 vs GLES viewport(videoSize)，用于排查相机输出与容器分辨率不匹配
                Log.d(TAG, "[诊断] 相机 buffer 分辨率=" + width + "x" + height
                        + ", GLES viewport(videoSize)=" + videoWidth + "x" + videoHeight
                        + ", camAspect=" + String.format(java.util.Locale.US, "%.3f", (float) width / height)
                        + ", viewAspect=" + String.format(java.util.Locale.US, "%.3f", (float) videoWidth / Math.max(1, videoHeight)));
            }
            if (cameraSurfaceTexture != null) {
                cameraSurfaceTexture.setDefaultBufferSize(width, height);
            }
        }
    }

    /** ⭐ 诊断用：获取当前相机 buffer 分辨率（CameraX 实际请求） */
    public int[] getCameraBufferSize() {
        return new int[]{camBufferWidth, camBufferHeight};
    }

    public void release() {
        stopRendering();

        synchronized (bitmapLock) {
            if (pendingMapBitmap != null && !pendingMapBitmap.isRecycled()) pendingMapBitmap.recycle();
            pendingMapBitmap = null;
            if (pendingOverlayBitmap != null && !pendingOverlayBitmap.isRecycled()) pendingOverlayBitmap.recycle();
            pendingOverlayBitmap = null;
        }

        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface);
                eglEncoderSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglPreviewSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglPreviewSurface);
                eglPreviewSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        hasEncoderSurface = false;
        Log.d(TAG, "OpenGL ES 资源已释放");
    }
}
