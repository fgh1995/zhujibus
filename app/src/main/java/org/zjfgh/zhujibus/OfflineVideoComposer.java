package org.zjfgh.zhujibus;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OfflineVideoComposer {
    private static final String TAG = "OfflineVideoComposer";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onComplete(Uri outputUri, String outputPath);
        void onError(Exception error);
    }

    public static class OverlayFrame {
        public final long timeMs;
        public final boolean mapEnabled;
        public final boolean infoPanelEnabled;
        public final Bitmap mapBitmap;
        public final Bitmap infoPanelBitmap;
        public final int mapX;
        public final int mapY;
        public final int mapWidth;
        public final int mapHeight;
        public final int infoPanelX;
        public final int infoPanelY;
        public final int infoPanelWidth;
        public final int infoPanelHeight;
        public final int videoWidth;
        public final int videoHeight;

        public OverlayFrame(long timeMs,
                            boolean mapEnabled,
                            boolean infoPanelEnabled,
                            Bitmap mapBitmap,
                            Bitmap infoPanelBitmap,
                            int mapX,
                            int mapY,
                            int mapWidth,
                            int mapHeight,
                            int infoPanelX,
                            int infoPanelY,
                            int infoPanelWidth,
                            int infoPanelHeight,
                            int videoWidth,
                            int videoHeight) {
            this.timeMs = timeMs;
            this.mapEnabled = mapEnabled;
            this.infoPanelEnabled = infoPanelEnabled;
            this.mapBitmap = mapBitmap;
            this.infoPanelBitmap = infoPanelBitmap;
            this.mapX = mapX;
            this.mapY = mapY;
            this.mapWidth = mapWidth;
            this.mapHeight = mapHeight;
            this.infoPanelX = infoPanelX;
            this.infoPanelY = infoPanelY;
            this.infoPanelWidth = infoPanelWidth;
            this.infoPanelHeight = infoPanelHeight;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
        }
    }

    public static void compose(Context context, Uri inputUri, String inputPath, List<OverlayFrame> timeline, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                int frameCount = timeline == null ? 0 : timeline.size();
                Log.i(TAG, "离线合成入口已触发，当前保留原始60fps视频: inputUri=" + inputUri
                        + ", inputPath=" + inputPath + ", overlayFrames=" + frameCount);
                new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(inputUri, inputPath));
            } catch (Exception e) {
                Log.e(TAG, "离线合成入口执行失败", e);
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(e));
            }
        });
    }
}
