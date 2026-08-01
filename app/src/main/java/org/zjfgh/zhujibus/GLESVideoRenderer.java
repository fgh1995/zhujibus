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
import android.os.Handler;
import android.os.Looper;
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
 * OpenGL ES视频渲染器
 * ⭐ 用于合成摄像头画面和地图画面，输出到编码器Surface和预览Surface
 * ⭐ 使用OES外部纹理（SurfaceTexture）+ Shader渲染
 */
public class GLESVideoRenderer {
    private static final String TAG = "GLESVideoRenderer";

    // EGL相关
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglEncoderSurface = EGL14.EGL_NO_SURFACE;

    // 纹理相关
    private int cameraTextureId = -1;
    private int mapTextureId = -1;
    private SurfaceTexture cameraSurfaceTexture;
    private SurfaceTexture mapSurfaceTexture;
    private long lastPresentationTimeNs = 0L;
    private final Object bitmapLock = new Object();
    
    // ⭐ 地图纹理（使用GL_TEXTURE_2D，支持Bitmap更新）
    private int mapTexture2DId = -1;
    private boolean useMapTexture2D = true;

    // ⭐ 信息面板纹理（第二个2D纹理，用于POV线路信息叠加）
    private int overlayTexture2DId = -1;

    // 渲染线程
    private RenderThread renderThread;
    private volatile boolean isRunning = false;
    private final Lock frameLock = new ReentrantLock();
    private final Condition frameAvailable = frameLock.newCondition();
    private boolean framePending = false;
    
    // ⭐ 用于等待GL资源初始化完成
    private CountDownLatch glInitLatch = new CountDownLatch(1);
    private volatile boolean glResourcesInitialized = false;
    
    // ⭐ 主线程Handler，用于在渲染线程创建SurfaceTexture后通知主线程
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // 画面尺寸
    private int videoWidth = 1920;
    private int videoHeight = 1080;
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

    // 是否启用叠加
    private boolean isMapEnabled = false;
    private boolean isOverlayEnabled = false;

    // ===== Shader程序 =====
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = aTextureCoord.xy;\n" +
            "}\n";

    // ⭐ OES纹理Shader（用于摄像头SurfaceTexture）
    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTextureCoord);\n" +
            "}\n";

    // ⭐ 2D纹理Shader（用于地图Bitmap）
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

    private int shaderProgramOES = -1;  // OES纹理程序
    private int shaderProgram2D = -1;   // 2D纹理程序
    private int shaderProgramRounded2D = -1;
    private int positionHandleOES = -1;
    private int texCoordHandleOES = -1;
    private int textureHandleOES = -1;
    private int positionHandle2D = -1;
    private int texCoordHandle2D = -1;
    private int textureHandle2D = -1;
    private int positionHandleRounded2D = -1;
    private int texCoordHandleRounded2D = -1;
    private int textureHandleRounded2D = -1;
    private int sizeHandleRounded2D = -1;
    private int radiusHandleRounded2D = -1;

    // 顶点坐标（全屏）
    private static final float[] FULL_SCREEN_VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
    };

    // ⭐ 纹理坐标：逆时针旋转90度（270°CW）用于摄像头
    // 映射：screen bottom-left→tex(1,0), bottom-right→tex(0,0), top-left→tex(1,1), top-right→tex(0,1)
    private static final float[] TEXTURE_COORDS_CCW_90 = {
            1.0f, 0.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
    };

    // 纹理坐标（标准，无旋转）
    private static final float[] TEXTURE_COORDS_NORMAL = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    // FloatBuffer
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBufferCamera;
    private FloatBuffer texCoordBufferMap;

    /**
     * 初始化EGL环境
     * @param encoderSurface 编码器输入Surface
     */
    public boolean initEGL(Surface encoderSurface) {
        // 1. 获取EGL Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "获取EGL Display失败");
            return false;
        }

        // 2. 初始化EGL
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "初始化EGL失败");
            return false;
        }

        // 3. 配置EGL
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
            Log.e(TAG, "选择EGL配置失败");
            return false;
        }
        EGLConfig eglConfig = configs[0];

        // 4. 创建EGL Context
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "创建EGL Context失败");
            return false;
        }

        // 5. 创建编码器EGL Surface
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        if (encoderSurface != null && encoderSurface.isValid()) {
            eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, surfaceAttribs, 0);
            if (eglEncoderSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "创建编码器EGL Surface失败");
                return false;
            }
            Log.d(TAG, "编码器EGL Surface创建成功");
        }

        // ⭐ 不再创建预览EGL Surface，预览由Camera2直接输出到TextureView
        // 这样避免双Surface切换EGL上下文导致的预览卡死问题

        Log.d(TAG, "EGL初始化成功");
        return true;
    }

    /**
     * 初始化OpenGL ES资源（纹理、Shader）
     * ⭐ 必须在渲染线程中调用，或者先绑定EGL Context
     */
    public boolean initGLResources() {
        // 创建FloatBuffer
        vertexBuffer = createFloatBuffer(FULL_SCREEN_VERTICES);
        // ⭐ 摄像头纹理坐标：使用标准坐标（不旋转）
        // 预览由Camera2直接输出到TextureView（已正确旋转）
        // GLES录制也由Camera2输出，设备可能已自动旋转
        texCoordBufferCamera = createFloatBuffer(TEXTURE_COORDS_NORMAL);
        texCoordBufferMap = createFloatBuffer(TEXTURE_COORDS_NORMAL);

        // ⭐ 创建摄像头纹理（OES外部纹理）
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        cameraTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // ⭐ 创建地图2D纹理（用于Bitmap更新）
        GLES20.glGenTextures(1, textures, 0);
        mapTexture2DId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mapTexture2DId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // ⭐ 创建信息面板2D纹理（用于POV线路信息叠加）
        GLES20.glGenTextures(1, textures, 0);
        overlayTexture2DId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture2DId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // ⭐ 创建OES纹理Shader程序（用于摄像头）
        shaderProgramOES = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        if (shaderProgramOES < 0) {
            Log.e(TAG, "创建OES Shader程序失败");
            return false;
        }
        positionHandleOES = GLES20.glGetAttribLocation(shaderProgramOES, "aPosition");
        texCoordHandleOES = GLES20.glGetAttribLocation(shaderProgramOES, "aTextureCoord");
        textureHandleOES = GLES20.glGetUniformLocation(shaderProgramOES, "uTexture");

        // ⭐ 创建2D纹理Shader程序（用于地图）
        shaderProgram2D = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
        if (shaderProgram2D < 0) {
            Log.e(TAG, "创建2D Shader程序失败");
            return false;
        }
        positionHandle2D = GLES20.glGetAttribLocation(shaderProgram2D, "aPosition");
        texCoordHandle2D = GLES20.glGetAttribLocation(shaderProgram2D, "aTextureCoord");
        textureHandle2D = GLES20.glGetUniformLocation(shaderProgram2D, "uTexture");

        shaderProgramRounded2D = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D_ROUNDED);
        if (shaderProgramRounded2D < 0) {
            Log.e(TAG, "创建圆角2D Shader程序失败");
            return false;
        }
        positionHandleRounded2D = GLES20.glGetAttribLocation(shaderProgramRounded2D, "aPosition");
        texCoordHandleRounded2D = GLES20.glGetAttribLocation(shaderProgramRounded2D, "aTextureCoord");
        textureHandleRounded2D = GLES20.glGetUniformLocation(shaderProgramRounded2D, "uTexture");
        sizeHandleRounded2D = GLES20.glGetUniformLocation(shaderProgramRounded2D, "uSize");
        radiusHandleRounded2D = GLES20.glGetUniformLocation(shaderProgramRounded2D, "uRadius");

        Log.d(TAG, "OpenGL ES资源初始化成功: cameraTex=" + cameraTextureId + ", map2DTex=" + mapTexture2DId);
        return true;
    }

    /**
     * 创建Shader程序
     */
    private int createShaderProgram(String vertexSource, String fragmentSource) {
        int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShader, vertexSource);
        GLES20.glCompileShader(vertexShader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "编译顶点Shader失败: " + GLES20.glGetShaderInfoLog(vertexShader));
            GLES20.glDeleteShader(vertexShader);
            return -1;
        }

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, fragmentSource);
        GLES20.glCompileShader(fragmentShader);

        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "编译片段Shader失败: " + GLES20.glGetShaderInfoLog(fragmentShader));
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
            Log.e(TAG, "链接Shader程序失败: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            GLES20.glDeleteProgram(program);
            return -1;
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    /**
     * 获取摄像头SurfaceTexture
     * ⭐ 必须在startRendering()之后调用，等待GL资源初始化完成
     * ⭐ SurfaceTexture已经在渲染线程中创建，这里只是返回引用
     */
    public SurfaceTexture getCameraSurfaceTexture() {
        // ⭐ 等待GL资源初始化完成
        try {
            glInitLatch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "等待GL初始化被中断");
            return null;
        }

        if (!glResourcesInitialized) {
            Log.e(TAG, "GL资源未初始化成功");
            return null;
        }

        return cameraSurfaceTexture;
    }

    /**
     * 创建地图SurfaceTexture
     */
    public SurfaceTexture createMapSurfaceTexture() {
        if (!glResourcesInitialized) {
            Log.e(TAG, "GL资源未初始化");
            return null;
        }

        mapSurfaceTexture = new SurfaceTexture(mapTextureId);
        mapSurfaceTexture.setDefaultBufferSize(mapWidth, mapHeight);
        Log.d(TAG, "地图SurfaceTexture创建成功: size=" + mapWidth + "x" + mapHeight);
        return mapSurfaceTexture;
    }

    /**
     * 等待GL资源初始化完成
     */
    public boolean waitForGLInit() {
        try {
            glInitLatch.await();
            return glResourcesInitialized;
        } catch (InterruptedException e) {
            Log.e(TAG, "等待GL初始化被中断");
            return false;
        }
    }

    /**
     * 启动渲染线程
     */
    public void startRendering() {
        if (renderThread != null && isRunning) {
            return;
        }
        isRunning = true;
        renderThread = new RenderThread();
        renderThread.start();
        Log.d(TAG, "渲染线程已启动");
    }

    /**
     * 停止渲染线程
     */
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
                Log.e(TAG, "渲染线程join失败", e);
            }
            renderThread = null;
        }
        Log.d(TAG, "渲染线程已停止");
    }

    /**
     * 新帧可用回调（由SurfaceTexture调用）
     */
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
            // ⭐ 在渲染线程开始时绑定EGL Context
            if (!EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext)) {
                Log.e(TAG, "渲染线程绑定EGL Context失败");
                glInitLatch.countDown();  // 即使失败也要通知
                return;
            }

            // ⭐ 初始化GL资源（必须在绑定Context后）
            if (!initGLResources()) {
                Log.e(TAG, "渲染线程GL资源初始化失败");
                glInitLatch.countDown();  // 即使失败也要通知
                return;
            }

            // ⭐ GL资源初始化完成
            glResourcesInitialized = true;
            Log.d(TAG, "GL资源初始化完成，纹理已创建: cameraTex=" + cameraTextureId);

            // ⭐ 在渲染线程中创建SurfaceTexture（必须在EGL Context的线程中）
            cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
            cameraSurfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
            cameraSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    GLESVideoRenderer.this.onFrameAvailable();
                }
            });
            Log.d(TAG, "SurfaceTexture在渲染线程创建成功: texId=" + cameraTextureId);

            // ⭐ SurfaceTexture创建完成后才通知主线程
            glInitLatch.countDown();

            Log.d(TAG, "渲染线程进入渲染循环");

            while (isRunning) {
                // ⭐ 等待新帧可用
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

                // ⭐ 渲染一帧
                try {
                    renderFrameInternal();
                } catch (Exception e) {
                    Log.e(TAG, "渲染出错", e);
                }
            }

            Log.d(TAG, "渲染线程退出");
        }

        /**
         * 内部渲染方法
         */
        private void renderFrameInternal() {
            long presentationTimeNs = System.nanoTime();
            // ⭐ 更新摄像头纹理
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

            // ⭐ 在渲染线程中更新地图2D纹理（GL操作必须在拥有EGL Context的线程）
            android.graphics.Bitmap mapBmp = null;
            android.graphics.Bitmap overlayBmp = null;
            synchronized (bitmapLock) {
                mapBmp = pendingMapBitmap;
                pendingMapBitmap = null;
                overlayBmp = pendingOverlayBitmap;
                pendingOverlayBitmap = null;
            }
            if (isMapEnabled && mapBmp != null) {
                try {
                    if (!mapBmp.isRecycled()) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mapTexture2DId);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mapBmp, 0);
                    }
                } finally {
                    if (!mapBmp.isRecycled()) mapBmp.recycle();
                }
            }

            // ⭐ 更新信息面板2D纹理
            if (isOverlayEnabled && overlayBmp != null) {
                try {
                    if (!overlayBmp.isRecycled()) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture2DId);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBmp, 0);
                    }
                } finally {
                    if (!overlayBmp.isRecycled()) overlayBmp.recycle();
                }
            }

            // ===== 渲染到编码器Surface =====
            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext);

                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glViewport(0, 0, videoWidth, videoHeight);

                // ⭐ 开启Alpha混合（用于叠加半透明纹理）
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                // ⭐ 渲染摄像头画面（全屏，使用OES纹理）
                drawTextureOES(cameraTextureId, vertexBuffer, texCoordBufferCamera);

                // ⭐ 渲染信息面板叠加（顶部，使用2D纹理，在地图下层）
                if (isOverlayEnabled && overlayTexture2DId >= 0) {
                    float oLeft = (float) overlayPositionX / videoWidth * 2.0f - 1.0f;
                    float oRight = (float) (overlayPositionX + overlayWidth) / videoWidth * 2.0f - 1.0f;
                    float oTop = 1.0f - (float) overlayPositionY / videoHeight * 2.0f;
                    float oBottom = 1.0f - (float) (overlayPositionY + overlayHeight) / videoHeight * 2.0f;

                    float[] overlayVertices = {
                            oLeft, oBottom,
                            oRight, oBottom,
                            oLeft, oTop,
                            oRight, oTop
                    };
                    FloatBuffer overlayVertexBuffer = createFloatBuffer(overlayVertices);
                    drawTexture2D(overlayTexture2DId, overlayVertexBuffer, texCoordBufferMap);
                }

                // ⭐ 渲染地图画面（右上角，使用2D纹理，在信息面板上层）
                if (isMapEnabled && mapTexture2DId >= 0) {
                    float mapLeft = (float) mapPositionX / videoWidth * 2.0f - 1.0f;
                    float mapRight = (float) (mapPositionX + mapWidth) / videoWidth * 2.0f - 1.0f;
                    float mapTop = 1.0f - (float) mapPositionY / videoHeight * 2.0f;
                    float mapBottom = 1.0f - (float) (mapPositionY + mapHeight) / videoHeight * 2.0f;

                    float[] mapVertices = {
                            mapLeft, mapBottom,
                            mapRight, mapBottom,
                            mapLeft, mapTop,
                            mapRight, mapTop
                    };
                    FloatBuffer mapVertexBuffer = createFloatBuffer(mapVertices);
                    drawTextureRounded2D(mapTexture2DId, mapVertexBuffer, texCoordBufferMap, mapWidth, mapHeight, mapCornerRadius);
                }

                GLES20.glDisable(GLES20.GL_BLEND);

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglEncoderSurface, presentationTimeNs);
                EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface);
            }

            // ⭐ 不再渲染到预览Surface（预览由Camera2直接输出到TextureView）
        }

        /**
         * 绘制OES纹理（摄像头）
         */
        private void drawTextureOES(int textureId, FloatBuffer vertices, FloatBuffer texCoords) {
            GLES20.glUseProgram(shaderProgramOES);

            // 绑定OES纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureHandleOES, 0);

            // 设置顶点坐标
            GLES20.glEnableVertexAttribArray(positionHandleOES);
            GLES20.glVertexAttribPointer(positionHandleOES, 2, GLES20.GL_FLOAT, false, 8, vertices);

            // 设置纹理坐标
            GLES20.glEnableVertexAttribArray(texCoordHandleOES);
            GLES20.glVertexAttribPointer(texCoordHandleOES, 2, GLES20.GL_FLOAT, false, 8, texCoords);

            // 绘制
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // 清理
            GLES20.glDisableVertexAttribArray(positionHandleOES);
            GLES20.glDisableVertexAttribArray(texCoordHandleOES);
        }

        /**
         * 绘制2D纹理（地图）
         */
        private void drawTexture2D(int textureId, FloatBuffer vertices, FloatBuffer texCoords) {
            GLES20.glUseProgram(shaderProgram2D);

            // 绑定2D纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(textureHandle2D, 0);

            // 设置顶点坐标
            GLES20.glEnableVertexAttribArray(positionHandle2D);
            GLES20.glVertexAttribPointer(positionHandle2D, 2, GLES20.GL_FLOAT, false, 8, vertices);

            // 设置纹理坐标
            GLES20.glEnableVertexAttribArray(texCoordHandle2D);
            GLES20.glVertexAttribPointer(texCoordHandle2D, 2, GLES20.GL_FLOAT, false, 8, texCoords);

            // 绘制
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // 清理
            GLES20.glDisableVertexAttribArray(positionHandle2D);
            GLES20.glDisableVertexAttribArray(texCoordHandle2D);
        }

        private void drawTextureRounded2D(int textureId, FloatBuffer vertices, FloatBuffer texCoords,
                                          float width, float height, float radius) {
            GLES20.glUseProgram(shaderProgramRounded2D);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(textureHandleRounded2D, 0);
            GLES20.glUniform2f(sizeHandleRounded2D, width, height);
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

    /**
     * 创建FloatBuffer
     */
    private FloatBuffer createFloatBuffer(float[] array) {
        ByteBuffer bb = ByteBuffer.allocateDirect(array.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(array);
        fb.position(0);
        return fb;
	}

	// ⭐ 最新地图Bitmap（由主线程设置，由渲染线程读取）
		private volatile android.graphics.Bitmap pendingMapBitmap;

		// ⭐ 最新信息面板Bitmap（由主线程设置，由渲染线程读取）
		private volatile android.graphics.Bitmap pendingOverlayBitmap;

		/**
	     * ⭐ 更新地图纹理（从Bitmap）
	     * ⭐ 只保存引用，实际GL纹理更新在渲染线程中执行
	     */
	    public void updateMapBitmap(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        android.graphics.Bitmap oldBitmap;
        synchronized (bitmapLock) {
            oldBitmap = pendingMapBitmap;
            pendingMapBitmap = bitmap;
        }
        if (oldBitmap != null && !oldBitmap.isRecycled()) oldBitmap.recycle();
    }

    /**
     * ⭐ 更新信息面板叠加纹理（从Bitmap）
     */
    public void updateOverlayBitmap(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        android.graphics.Bitmap oldBitmap;
        synchronized (bitmapLock) {
            oldBitmap = pendingOverlayBitmap;
            pendingOverlayBitmap = bitmap;
        }
        if (oldBitmap != null && !oldBitmap.isRecycled()) oldBitmap.recycle();
    }

    // ===== Setter方法 =====

    public void setMapEnabled(boolean enabled) {
        this.isMapEnabled = enabled;
    }

    public void setOverlayEnabled(boolean enabled) {
        this.isOverlayEnabled = enabled;
    }

    public void setVideoSize(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
    }

    public void setMapSize(int width, int height) {
        this.mapWidth = width;
        this.mapHeight = height;
    }

    public void setMapPosition(int x, int y) {
        this.mapPositionX = x;
        this.mapPositionY = y;
    }

    public void setMapCornerRadius(float radius) {
        this.mapCornerRadius = Math.max(0f, radius);
    }

    public void setOverlaySize(int width, int height) {
        this.overlayWidth = width;
        this.overlayHeight = height;
    }

    public void setOverlayPosition(int x, int y) {
        this.overlayPositionX = x;
        this.overlayPositionY = y;
    }

    /**
     * 获取摄像头Surface
     * ⭐ 用于Camera2输出
     */
    public Surface getCameraSurface() {
        if (cameraSurfaceTexture != null) {
            return new Surface(cameraSurfaceTexture);
        }
        return null;
    }

    /**
     * 获取地图Surface
     */
    public Surface getMapSurface() {
        if (mapSurfaceTexture != null) {
            return new Surface(mapSurfaceTexture);
        }
        return null;
    }

    /**
     * 释放所有资源
     */
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

        if (mapSurfaceTexture != null) {
            mapSurfaceTexture.release();
            mapSurfaceTexture = null;
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

            if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface);
                eglEncoderSurface = EGL14.EGL_NO_SURFACE;
            }

            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }

            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        Log.d(TAG, "OpenGL ES资源已释放");
    }
}