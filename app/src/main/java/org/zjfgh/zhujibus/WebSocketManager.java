package org.zjfgh.zhujibus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * WebSocket 管理器 - 独立管理 WebSocket 连接
 * 消息格式: type:data
 * 自动处理协议转换: http→ws, https→wss
 * 支持：心跳保活 (ping/pong)、Android ID 上报、无限自动重连（指数退避）
 */
public class WebSocketManager {
    private static final String TAG = "WebSocketManager";

    // 心跳间隔（秒）
    private static final int HEARTBEAT_INTERVAL = 30;
    // 心跳超时时间（秒）
    private static final int HEARTBEAT_TIMEOUT = 10;
    // 重连基础延迟（毫秒）
    private static final int RECONNECT_BASE_DELAY = 1000;
    // 重连最大延迟（毫秒）
    private static final int RECONNECT_MAX_DELAY = 30000;

    // 消息类型
    private static final String MSG_TYPE_ID = "id";
    private static final String MSG_TYPE_PING = "ping";
    private static final String MSG_TYPE_PONG = "pong";
    private static final String MSG_TYPE_DATA = "data";
    private static final String MSG_TYPE_EVENT = "event";
    private static final String MSG_TYPE_ONLINE = "online";  // ⭐ 在线人数广播
    // ⭐ 版本号 JSON 消息格式：{"type":"version","version":"1.0.0(00001)"}

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String httpAddress;
    private String wsUrl;
    private WebSocket webSocket;
    private OkHttpClient client;
    private boolean isConnected = false;
    private boolean isManualClosed = false;  // 是否手动关闭（停止重连）

    // 重连相关
    private int reconnectAttempts = 0;
    private Handler reconnectHandler;
    private Runnable reconnectRunnable;

    // 心跳相关
    private Handler heartBeatHandler;
    private Runnable heartBeatRunnable;
    private long lastPongTime = 0;
    private boolean isPongReceived = true;

    // 回调接口
    private OnWebSocketListener listener;

    public interface OnWebSocketListener {
        void onConnected();
        void onDisconnected();
        void onDataReceived(String data);
        void onEventReceived(String event);
        void onError(String error);
        void onPongReceived();
        void onReconnecting(int attempt, long delayMs);
        /** ⭐ 收到在线人数广播（count = 当前在线客户端数量） */
        void onOnlineCountReceived(int count);
    }

    public WebSocketManager(Context context, String httpAddress) {
        this.context = context;
        this.httpAddress = httpAddress;
        // 协议转换：http -> ws, https -> wss
        this.wsUrl = httpAddress
                .replace("https://", "wss://")
                .replace("http://", "ws://");
        this.client = new OkHttpClient.Builder()
                .build();
        this.heartBeatHandler = new Handler(Looper.getMainLooper());
        this.reconnectHandler = new Handler(Looper.getMainLooper());
        this.heartBeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartBeat();
                if (isConnected) {
                    heartBeatHandler.postDelayed(this, HEARTBEAT_INTERVAL * 1000);
                }
            }
        };
        Log.d(TAG, "WebSocketManager 初始化: " + httpAddress + " -> " + wsUrl);
    }

    public void setOnWebSocketListener(OnWebSocketListener listener) {
        this.listener = listener;
    }

    public void connect() {
        if (isConnected) {
            Log.d(TAG, "WebSocket 已连接，无需重复连接");
            return;
        }
        if (isManualClosed) {
            Log.d(TAG, "已手动关闭，不再重连");
            return;
        }

        Log.d(TAG, "WebSocket 正在连接: " + wsUrl);

        Request request = new Request.Builder()
                .url(wsUrl)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                super.onOpen(webSocket, response);
                isConnected = true;
                reconnectAttempts = 0;  // 重置重连计数
                lastPongTime = System.currentTimeMillis();
                isPongReceived = true;
                Log.d(TAG, "WebSocket 连接成功");

                // 发送 Android ID
                sendAndroidId();

                // 启动心跳
                startHeartBeat();

                // 回调
                if (listener != null) {
                    mainHandler.post(() -> listener.onConnected());
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                super.onMessage(webSocket, text);
                Log.d(TAG, "收到消息: " + text);

                String trimmed = text.trim();
                String[] parts = trimmed.split(":", 2);
                String type = parts[0];
                String data = parts.length > 1 ? parts[1] : "";

                switch (type) {
                    case MSG_TYPE_PONG:
                        onPongReceived();
                        Log.d(TAG, "收到心跳回复 pong");
                        if (listener != null) {
                            mainHandler.post(() -> listener.onPongReceived());
                        }
                        break;
                    case MSG_TYPE_ONLINE:
                        // ⭐ 收到在线人数广播：data 是数字字符串
                        try {
                            int count = Integer.parseInt(data);
                            Log.d(TAG, "收到在线人数: " + count);
                            if (listener != null) {
                                mainHandler.post(() -> listener.onOnlineCountReceived(count));
                            }
                        } catch (NumberFormatException nfe) {
                            Log.w(TAG, "在线人数格式错误: " + data);
                        }
                        break;
                    case MSG_TYPE_DATA:
                        Log.d(TAG, "收到数据: " + data);
                        if (listener != null) {
                            mainHandler.post(() -> listener.onDataReceived(data));
                        }
                        break;
                    case MSG_TYPE_EVENT:
                        Log.d(TAG, "收到事件: " + data);
                        if (listener != null) {
                            mainHandler.post(() -> listener.onEventReceived(data));
                        }
                        break;
                    default:
                        Log.d(TAG, "收到未知类型消息: type=" + type + ", data=" + data);
                        break;
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                super.onMessage(webSocket, bytes);
                Log.d(TAG, "收到二进制消息 (长度: " + bytes.size() + ")");
                // 可在此处理二进制数据
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                super.onClosing(webSocket, code, reason);
                Log.d(TAG, "WebSocket 正在关闭: code=" + code + ", reason=" + reason);
                isConnected = false;
                stopHeartBeat();
                webSocket.close(code, reason);
                if (listener != null) {
                    mainHandler.post(() -> listener.onDisconnected());
                }
                // 如果不是手动关闭，触发重连
                if (!isManualClosed) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                super.onClosed(webSocket, code, reason);
                Log.d(TAG, "WebSocket 已关闭: code=" + code + ", reason=" + reason);
                isConnected = false;
                stopHeartBeat();
                if (listener != null) {
                    mainHandler.post(() -> listener.onDisconnected());
                }
                // 如果不是手动关闭，触发重连
                if (!isManualClosed) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                super.onFailure(webSocket, t, response);
                Log.e(TAG, "WebSocket 连接失败", t);
                isConnected = false;
                stopHeartBeat();
                if (listener != null) {
                    mainHandler.post(() -> listener.onError(t.getMessage()));
                }
                // 如果不是手动关闭，触发重连
                if (!isManualClosed) {
                    scheduleReconnect();
                }
            }
        });
    }

    /**
     * 调度重连（指数退避）
     */
    private void scheduleReconnect() {
        if (isManualClosed) {
            Log.d(TAG, "已手动关闭，不进行重连");
            return;
        }

        reconnectAttempts++;
        // 指数退避：1s, 2s, 4s, 8s, 16s, 30s, 30s...
        long delay = Math.min(RECONNECT_BASE_DELAY * (1L << Math.min(reconnectAttempts - 1, 5)), RECONNECT_MAX_DELAY);

        Log.d(TAG, String.format("第 %d 次重连，延迟 %d ms", reconnectAttempts, delay));

        if (listener != null) {
            mainHandler.post(() -> listener.onReconnecting(reconnectAttempts, delay));
        }

        // 取消之前的重连任务
        reconnectHandler.removeCallbacks(reconnectRunnable);
        reconnectRunnable = () -> {
            if (!isManualClosed && !isConnected) {
                Log.d(TAG, "执行重连...");
                connect();
            }
        };
        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    /**
     * 发送 Android ID
     */
    private void sendAndroidId() {
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            if (androidId != null && !androidId.isEmpty()) {
                String msg = MSG_TYPE_ID + ":" + androidId;
                sendMessage(msg);
                Log.d(TAG, "发送 Android ID: " + msg);

                // ⭐ 延迟 100ms 发送版本号，确保服务端先处理完 id 注册
                mainHandler.postDelayed(this::sendAppVersion, 100);
            } else {
                Log.w(TAG, "无法获取 Android ID");
            }
        } catch (Exception e) {
            Log.e(TAG, "获取 Android ID 失败", e);
        }
    }

    /**
     * ⭐ 发送应用版本号（JSON 格式）
     *    消息格式：{"type":"version","version":"x.x.x(00000)"}
     */
    private void sendAppVersion() {
        try {
            String versionName = "";
            int versionCode = 0;
            try {
                versionName = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
                versionCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;
            } catch (Exception e) {
                Log.w(TAG, "获取版本号失败，使用默认值", e);
            }

            // 格式化为五位数（00000）
            String versionStr = String.format("%s(%05d)",
                    versionName != null ? versionName : "0.0.0", versionCode);

            // ⭐ 用 JSON 封装
            String json = "{\"type\":\"version\",\"version\":\"" + versionStr + "\"}";
            sendMessage(json);
            Log.d(TAG, "发送版本号 JSON: " + json);
        } catch (Exception e) {
            Log.e(TAG, "发送版本号失败", e);
        }
    }

    /**
     * 发送心跳包 (ping)
     */
    private void sendHeartBeat() {
        if (!isConnected) {
            Log.w(TAG, "WebSocket 未连接，停止心跳");
            return;
        }

        // 检查上次 pong 是否超时
        if (!isPongReceived) {
            long now = System.currentTimeMillis();
            if (now - lastPongTime > HEARTBEAT_TIMEOUT * 1000) {
                Log.e(TAG, "心跳超时，连接可能已断开，主动关闭并重连");
                // 主动关闭连接，触发重连
                if (webSocket != null) {
                    webSocket.close(1000, "心跳超时");
                }
                return;
            }
        }

        isPongReceived = false;
        String msg = MSG_TYPE_PING + ":";
        sendMessage(msg);
        Log.d(TAG, "发送心跳 ping");
    }

    /**
     * 收到 pong 回复
     */
    private void onPongReceived() {
        isPongReceived = true;
        lastPongTime = System.currentTimeMillis();
    }

    /**
     * 启动心跳
     */
    private void startHeartBeat() {
        stopHeartBeat();
        heartBeatHandler.postDelayed(heartBeatRunnable, HEARTBEAT_INTERVAL * 1000);
        Log.d(TAG, "心跳已启动，间隔 " + HEARTBEAT_INTERVAL + " 秒");
    }

    /**
     * 停止心跳
     */
    private void stopHeartBeat() {
        heartBeatHandler.removeCallbacks(heartBeatRunnable);
        Log.d(TAG, "心跳已停止");
    }

    /**
     * 发送消息（底层）
     */
    private void sendMessage(String message) {
        if (webSocket != null && isConnected) {
            webSocket.send(message);
            Log.d(TAG, "发送消息: " + message);
        } else {
            Log.w(TAG, "WebSocket 未连接，无法发送消息");
        }
    }

    /**
     * 发送自定义数据（带类型）
     */
    public void sendData(String data) {
        String msg = MSG_TYPE_DATA + ":" + data;
        sendMessage(msg);
    }

    /**
     * 发送自定义事件
     */
    public void sendEvent(String event) {
        String msg = MSG_TYPE_EVENT + ":" + event;
        sendMessage(msg);
    }

    /**
     * 关闭连接（手动关闭，不触发重连）
     */
    public void close() {
        isManualClosed = true;
        stopHeartBeat();
        // 取消重连任务
        reconnectHandler.removeCallbacks(reconnectRunnable);
        if (webSocket != null) {
            webSocket.close(1000, "正常关闭");
            Log.d(TAG, "WebSocket 已关闭");
        }
        isConnected = false;
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
    }

    /**
     * 手动重置重连状态（例如重新启用重连）
     */
    public void enableReconnect() {
        isManualClosed = false;
        reconnectAttempts = 0;
        Log.d(TAG, "重连已启用");
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isManualClosed() {
        return isManualClosed;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }
}
