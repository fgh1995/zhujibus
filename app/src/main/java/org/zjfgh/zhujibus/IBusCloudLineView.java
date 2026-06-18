package org.zjfgh.zhujibus;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class IBusCloudLineView extends View {

    // 数据
    private List<BusApiClient.BusLineStation> stations;
    private int selectedPosition = -1;
    private boolean isGpsMode = false;
    private int gpsPositionIndex = -1;
    private boolean isGpsArriving = false;

    // 画笔
    private Paint linePaint;
    private Paint normalCirclePaint;
    private Paint currentCirclePaint;
    private Paint selectedPaint;
    private Paint textPaint;
    private Paint plateTextPaint;
    private Paint busIconPaint;
    private static final int SCROLL_SPEED_MS_PER_PX = 30;
    private static final int SCROLL_PAUSE_DURATION = 1000;  // 暂停时长（毫秒）
    // 图标
    private Bitmap busIconBitmap;
    private Bitmap busRedIconBitmap;

    // 布局常量
    private static final float TOP_PADDING = 20f;
    private static final float LINE_Y = 50f;
    private static final float CIRCLE_RADIUS = 10f;
    private static final float STATION_SPACING = 80f;
    private static final float TEXT_Y_OFFSET = 10f;
    private static final float TEXT_SIZE = 32f;
    private static final float VEHICLE_ICON_SIZE = 30f;
    private static final float LEFT_PADDING = 40f;

    // 站名竖向滚动相关
    private float stationNameMaxHeight = 0f;
    private float availableStationNameHeight = 0f;
    private SparseArray<StationNameAnimator> stationAnimators = new SparseArray<>();

    // 需要旋转的标点符号集合
    private static final HashSet<String> VERTICAL_PUNCTUATIONS = new HashSet<>(Arrays.asList(
            "(", ")", "（", "）", "[", "]", "【", "】", "{", "}",
            "<", ">", "〈", "〉", "《", "》", "「", "」", "『", "』",
            "〔", "〕", "〖", "〗", "〘", "〙", "〚", "〛", "：", "；",
            "！", "？", "…", "～", "·", "、", "。", "，", "．"
    ));

    // 横向滚动相关
    private float scrollOffset = 0f;
    private float lastTouchX = 0f;
    private boolean isDragging = false;

    // 交互
    private OnStationClickListener listener;
    private OnGpsArrivalListener gpsArrivalListener;
    private float touchDownX, touchDownY;

    public interface OnStationClickListener {
        void onStationClick(BusApiClient.BusLineStation station, int position);
    }

    public interface OnGpsArrivalListener {
        void onGpsArrival(int stationIndex);
    }

    // 站名竖向滚动动画类
    private class StationNameAnimator {
        int stationIndex;
        float textHeight;
        float viewHeight;
        float maxOffset;
        float currentOffset;
        boolean isScrollingUp;
        ValueAnimator animator;
        Handler pauseHandler;
        boolean isAnimating;

        void stop() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (pauseHandler != null) {
                pauseHandler.removeCallbacksAndMessages(null);
            }
            isAnimating = false;
            currentOffset = 0;
        }

        void start() {
            if (isAnimating) return;
            isAnimating = true;
            isScrollingUp = true;
            currentOffset = 0;
            pauseHandler = new Handler(Looper.getMainLooper());
            startScrollUp();
        }

        private void startScrollUp() {
            if (animator != null) {
                animator.cancel();
            }

            float startOffset = currentOffset;
            float endOffset = maxOffset;

            if (Math.abs(endOffset - startOffset) < 0.5f) {
                pauseHandler.postDelayed(this::startScrollDown, SCROLL_PAUSE_DURATION);
                return;
            }

            // 修改：固定速度，不再根据距离动态计算时长
            float distance = Math.abs(endOffset - startOffset);
            int duration = (int) (distance * SCROLL_SPEED_MS_PER_PX);
            // 设置最小和最大时长限制，避免过快或过慢
            duration = Math.max(300, Math.min(duration, 3000));

            animator = ValueAnimator.ofFloat(startOffset, endOffset);
            animator.setDuration(duration);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                currentOffset = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    pauseHandler.postDelayed(() -> startScrollDown(), SCROLL_PAUSE_DURATION);
                }
            });
            animator.start();
        }

        private void startScrollDown() {
            if (animator != null) {
                animator.cancel();
            }

            float startOffset = currentOffset;
            float endOffset = 0f;

            if (Math.abs(endOffset - startOffset) < 0.5f) {
                pauseHandler.postDelayed(this::startScrollUp, SCROLL_PAUSE_DURATION);
                return;
            }

            // 修改：固定速度，不再根据距离动态计算时长
            float distance = Math.abs(endOffset - startOffset);
            int duration = (int) (distance * SCROLL_SPEED_MS_PER_PX);
            // 设置最小和最大时长限制
            duration = Math.max(300, Math.min(duration, 3000));

            animator = ValueAnimator.ofFloat(startOffset, endOffset);
            animator.setDuration(duration);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                currentOffset = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    pauseHandler.postDelayed(() -> startScrollUp(), SCROLL_PAUSE_DURATION);
                }
            });
            animator.start();
        }
    }

    public IBusCloudLineView(Context context) {
        super(context);
        init();
    }

    public IBusCloudLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public IBusCloudLineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(3f);
        linePaint.setStyle(Paint.Style.STROKE);

        normalCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        normalCirclePaint.setColor(Color.WHITE);
        normalCirclePaint.setStyle(Paint.Style.STROKE);
        normalCirclePaint.setStrokeWidth(3f);

        currentCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currentCirclePaint.setColor(0xFF2196F3);
        currentCirclePaint.setStyle(Paint.Style.FILL);

        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setColor(Color.RED);
        selectedPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TEXT_SIZE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        plateTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        plateTextPaint.setColor(Color.WHITE);
        plateTextPaint.setTextSize(24f);
        plateTextPaint.setTextAlign(Paint.Align.CENTER);
        busIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        busIconPaint.setColor(0xFF42A5F5);
        busIconPaint.setStyle(Paint.Style.FILL);

        try {
            busIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_bus_lateral);
            busRedIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_bus_red_lateral);
        } catch (Exception e) {
            busIconBitmap = null;
            busRedIconBitmap = null;
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);  // 启用硬件加速
    }

    // --- 数据设置方法 ---

    public void setStations(List<BusApiClient.BusLineStation> stations) {
        this.stations = stations;
        stopAllStationAnimations();
        invalidate();
    }

    public void updateBusPositions(List<BusApiClient.BusPosition> positions) {
        if (stations == null || positions == null || positions.isEmpty()) {
            resetAllStations();
            invalidate();
            return;
        }

        resetAllStations();

        for (BusApiClient.BusPosition bus : positions) {
            if (bus.currentStationOrder <= 0 || bus.currentStationOrder > stations.size()) {
                continue;
            }
            BusApiClient.BusLineStation currentStation = stations.get(bus.currentStationOrder - 1);
            String normalizedPlateNumber = normalizePlateNumber(bus.plateNumber);

            if (bus.isArrived) {
                currentStation.status = BusApiClient.BusLineStation.StationStatus.CURRENT;
                currentStation.plateNumber = normalizedPlateNumber;
            } else {
                currentStation.status = BusApiClient.BusLineStation.StationStatus.NEXT_STATION;
                currentStation.plateNumber = normalizedPlateNumber;
            }
        }
        invalidate();
    }

    private String normalizePlateNumber(String plateNumber) {
        if (plateNumber == null || plateNumber.isEmpty()) return plateNumber;
        return plateNumber;
    }

    public void resetAllStations() {
        if (stations == null) return;
        for (BusApiClient.BusLineStation station : stations) {
            station.status = BusApiClient.BusLineStation.StationStatus.NORMAL;
            station.plateNumber = null;
        }
    }

    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        invalidate();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setOnStationClickListener(OnStationClickListener listener) {
        this.listener = listener;
    }

    public void setOnGpsArrivalListener(OnGpsArrivalListener listener) {
        this.gpsArrivalListener = listener;
    }

    public void setGpsMode(boolean isGpsMode) {
        this.isGpsMode = isGpsMode;
        if (isGpsMode) {
            resetAllStations();
            selectedPosition = -1;
        } else {
            gpsPositionIndex = -1;
            isGpsArriving = false;
        }
        invalidate();
    }

    public void updateGpsPosition(int position, boolean isArriving) {
        this.gpsPositionIndex = position;
        this.isGpsArriving = isArriving;
        if (isArriving && gpsArrivalListener != null && position >= 0) {
            gpsArrivalListener.onGpsArrival(position);
        }
        invalidate();
    }

    public void clearGpsPosition() {
        this.gpsPositionIndex = -1;
        this.isGpsArriving = false;
        invalidate();
    }

    public void setStationNameMaxHeight(float maxHeightPx) {
        this.stationNameMaxHeight = maxHeightPx;
        stopAllStationAnimations();
        invalidate();
    }

    private void stopAllStationAnimations() {
        for (int i = 0; i < stationAnimators.size(); i++) {
            StationNameAnimator animator = stationAnimators.valueAt(i);
            if (animator != null) {
                animator.stop();
            }
        }
        stationAnimators.clear();
    }

    private void startStationNameScroll(int index, float textHeight, float availableHeight) {
        if (textHeight <= availableHeight) {
            StationNameAnimator existing = stationAnimators.get(index);
            if (existing != null) {
                existing.stop();
                stationAnimators.remove(index);
            }
            return;
        }

        StationNameAnimator animator = stationAnimators.get(index);
        if (animator == null) {
            animator = new StationNameAnimator();
            animator.stationIndex = index;
            animator.textHeight = textHeight;
            animator.viewHeight = availableHeight;
            animator.maxOffset = textHeight - availableHeight;
            animator.currentOffset = 0;
            stationAnimators.put(index, animator);
        } else {
            animator.textHeight = textHeight;
            animator.viewHeight = availableHeight;
            animator.maxOffset = textHeight - availableHeight;
        }

        if (!animator.isAnimating) {
            animator.start();
        }
    }

    // --- 交互事件 ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                lastTouchX = event.getX();
                isDragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getX() - lastTouchX;
                if (Math.abs(deltaX) > 10f) {
                    isDragging = true;
                    scrollOffset -= deltaX;
                    lastTouchX = event.getX();

                    if (scrollOffset < 0) {
                        scrollOffset = 0;
                    }
                    float maxScroll = getContentWidth() - getWidth();
                    if (maxScroll < 0) {
                        maxScroll = 0;
                    }
                    if (scrollOffset > maxScroll) {
                        scrollOffset = maxScroll;
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    isDragging = false;
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                float moveDistance = (float) Math.sqrt(Math.pow(x - touchDownX, 2) + Math.pow(y - touchDownY, 2));

                if (moveDistance < 10f && stations != null && !isGpsMode) {
                    for (int i = 0; i < stations.size(); i++) {
                        float stationX = getStartX() + i * STATION_SPACING - scrollOffset;
                        if (x >= stationX - STATION_SPACING / 2 && x <= stationX + STATION_SPACING / 2 &&
                                y >= LINE_Y - 80 && y <= LINE_Y + 80) {
                            if (listener != null) {
                                listener.onStationClick(stations.get(i), i);
                            }
                            setSelectedPosition(i);
                            return true;
                        }
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    // --- 测量 ---

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = 0;
        int desiredHeight = 0;

        if (stations != null && !stations.isEmpty()) {
            desiredWidth = (int) (LEFT_PADDING * 2 + (stations.size() - 1) * STATION_SPACING + 100);
            desiredHeight = (int) (TOP_PADDING + 60 + TEXT_Y_OFFSET + TEXT_SIZE * 2 + 50);
        }

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        if (widthMode == MeasureSpec.EXACTLY) {
            desiredWidth = Math.max(desiredWidth, widthSize);
        } else if (widthMode == MeasureSpec.AT_MOST) {
            desiredWidth = Math.min(desiredWidth, widthSize);
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            desiredHeight = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            desiredHeight = Math.min(desiredHeight, heightSize);
        }

        setMeasuredDimension(desiredWidth, desiredHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // 计算站名可用的实际高度
        // 文字起始位置：圆圈底部 + TEXT_Y_OFFSET
        float textStartY = LINE_Y + CIRCLE_RADIUS + TEXT_Y_OFFSET;
        // 可用高度 = View总高度 - 文字起始Y - 底部留白（减小留白）
        float bottomPadding = 5f;  // 减小底部留白
        availableStationNameHeight = h - textStartY - bottomPadding;

        // 设置最小高度
        if (availableStationNameHeight < 30f) {
            availableStationNameHeight = 30f;
        }

        this.stationNameMaxHeight = availableStationNameHeight;

        // 调试日志
        android.util.Log.d("IBusCloudLineView", "onSizeChanged: h=" + h + ", textStartY=" + textStartY + ", availableHeight=" + availableStationNameHeight);

        stopAllStationAnimations();
        invalidate();
    }

    // --- 绘制 ---

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (stations == null || stations.isEmpty()) {
            return;
        }

        canvas.save();
        canvas.translate(-scrollOffset, 0);

        float startX = getStartX();
        float centerY = LINE_Y;

        for (int i = 0; i < stations.size() - 1; i++) {
            float x1 = startX + i * STATION_SPACING;
            float x2 = startX + (i + 1) * STATION_SPACING;
            float lineStartX = x1 + CIRCLE_RADIUS;
            float lineEndX = x2 - CIRCLE_RADIUS;
            canvas.drawLine(lineStartX, centerY, lineEndX, centerY, linePaint);
        }

        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            float x = startX + i * STATION_SPACING;
            drawStation(canvas, x, centerY, station, i);
        }

        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            float x = startX + i * STATION_SPACING;
            drawVehicleIcon(canvas, x, centerY, station, i, startX);
        }

        canvas.restore();
    }

    private float getStartX() {
        return LEFT_PADDING;
    }

    private float getContentWidth() {
        if (stations == null || stations.isEmpty()) {
            return 0;
        }
        return LEFT_PADDING * 2 + (stations.size() - 1) * STATION_SPACING + 100;
    }

    private void drawStation(Canvas canvas, float x, float y, BusApiClient.BusLineStation station, int index) {
        boolean isSelected = (index == selectedPosition && !isGpsMode);
        boolean isGpsCurrent = (isGpsMode && isGpsArriving && index == gpsPositionIndex);
        boolean isApiCurrent = (!isGpsMode && station.status == BusApiClient.BusLineStation.StationStatus.CURRENT);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);

        Paint textPaintToUse = textPaint;

        if (isSelected) {
            fillPaint.setColor(Color.RED);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setColor(Color.WHITE);
            textPaintToUse = new Paint(textPaint);
            textPaintToUse.setColor(Color.RED);
        } else if (isGpsCurrent || isApiCurrent) {
            fillPaint.setColor(0xFF2196F3);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setColor(0xFF2196F3);
            textPaintToUse = new Paint(textPaint);
            textPaintToUse.setColor(0xFF2196F3);
        } else {
            fillPaint.setColor(0xFF151517);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setColor(Color.WHITE);
            textPaintToUse = textPaint;
        }

        canvas.drawCircle(x, y, CIRCLE_RADIUS, fillPaint);
        canvas.drawCircle(x, y, CIRCLE_RADIUS, strokePaint);
        drawStationName(canvas, x, y, station, index, textPaintToUse);
    }

    private boolean needRotate(String charStr) {
        return VERTICAL_PUNCTUATIONS.contains(charStr);
    }

    private void drawStationName(Canvas canvas, float x, float y, BusApiClient.BusLineStation station, int index, Paint textPaintToUse) {
        String name = station.stationName;
        if (name == null || name.isEmpty()) return;

        int charCount = name.length();
        Paint.FontMetrics fm = textPaintToUse.getFontMetrics();
        float fontHeight = fm.descent - fm.ascent;
        float availableHeight = stationNameMaxHeight;

        if (availableHeight <= 0) {
            availableHeight = 80f;
        }

        float actualLineHeight;
        float fontTotalHeight = charCount * fontHeight;

        if (availableHeight > fontTotalHeight && charCount > 0) {
            actualLineHeight = availableHeight / charCount;
        } else {
            actualLineHeight = fontHeight;
        }

        float firstCharTop = y + CIRCLE_RADIUS + TEXT_Y_OFFSET;
        float baseTextY = firstCharTop - fm.ascent;
        float firstCharActualTop = baseTextY + fm.ascent;
        boolean needScroll = availableHeight < fontTotalHeight;
        float charWidth = textPaintToUse.measureText("中");
        float maxCharWidth = Math.max(charWidth, textPaintToUse.measureText("Ｗ"));
        float textCenterX = x;

        if (!needScroll) {
            for (int i = 0; i < charCount; i++) {
                String charStr = String.valueOf(name.charAt(i));
                float currentY = baseTextY + i * actualLineHeight;
                if (needRotate(charStr)) {
                    canvas.save();
                    canvas.translate(textCenterX, currentY);
                    canvas.rotate(90);
                    float rotatedCharWidth = textPaintToUse.measureText(charStr);
                    canvas.drawText(charStr, -rotatedCharWidth / 2, charWidth / 3, textPaintToUse);
                    canvas.restore();
                } else {
                    canvas.drawText(charStr, textCenterX, currentY, textPaintToUse);
                }
            }
            return;
        }

        float scrollLineHeight = fontHeight;
        float scrollBaseTextY = firstCharTop - fm.ascent;
        float scrollFirstCharTop = scrollBaseTextY + fm.ascent;

        startStationNameScroll(index, fontTotalHeight, availableHeight);
        StationNameAnimator animator = stationAnimators.get(index);

        if (animator != null && animator.isAnimating) {
            float clipLeft = textCenterX - maxCharWidth * 2;
            float clipRight = textCenterX + maxCharWidth * 2;
            float clipTop = scrollFirstCharTop;
            float clipBottom = scrollFirstCharTop + availableHeight;

            canvas.save();
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom);

            for (int i = 0; i < charCount; i++) {
                String charStr = String.valueOf(name.charAt(i));
                float currentY = scrollBaseTextY + i * scrollLineHeight - animator.currentOffset;
                if (needRotate(charStr)) {
                    canvas.save();
                    canvas.translate(textCenterX, currentY);
                    canvas.rotate(90);
                    float rotatedCharWidth = textPaintToUse.measureText(charStr);
                    canvas.drawText(charStr, -rotatedCharWidth / 2, charWidth / 3, textPaintToUse);
                    canvas.restore();
                } else {
                    canvas.drawText(charStr, textCenterX, currentY, textPaintToUse);
                }
            }
            canvas.restore();
        }
    }

    private void drawVehicleIcon(Canvas canvas, float x, float y, BusApiClient.BusLineStation station, int index, float startX) {
        boolean shouldShowIcon = false;
        Bitmap iconToUse = busIconBitmap;
        float iconX = x;
        float iconY = y;
        boolean isBetweenStations = false;
        int targetStationIndex = -1;

        if (isGpsMode) {
            if (isGpsArriving && index == gpsPositionIndex) {
                shouldShowIcon = true;
                iconToUse = busRedIconBitmap;
            } else if (!isGpsArriving && index == gpsPositionIndex) {
                shouldShowIcon = true;
                iconToUse = busRedIconBitmap;
                isBetweenStations = true;
                targetStationIndex = index;
            }
        } else {
            if (station.status == BusApiClient.BusLineStation.StationStatus.CURRENT) {
                shouldShowIcon = true;
                iconToUse = busIconBitmap;
            } else if (station.status == BusApiClient.BusLineStation.StationStatus.NEXT_STATION) {
                shouldShowIcon = true;
                iconToUse = busIconBitmap;
                isBetweenStations = true;
                targetStationIndex = index;
            }
        }

        if (shouldShowIcon && iconToUse != null) {
            // 保持图标原始宽高比
            int originalWidth = iconToUse.getWidth();
            int originalHeight = iconToUse.getHeight();
            float targetHeight = VEHICLE_ICON_SIZE;
            float targetWidth = targetHeight * originalWidth / originalHeight;

            if (isBetweenStations) {
                if (targetStationIndex >= 0 && targetStationIndex < stations.size() - 1) {
                    iconX = startX + targetStationIndex * STATION_SPACING + STATION_SPACING / 2 - targetWidth / 2;
                    iconY = y - CIRCLE_RADIUS - targetHeight - 8f;
                } else {
                    iconX = x - targetWidth / 2;
                    iconY = y - CIRCLE_RADIUS - targetHeight - 8f;
                }
            } else {
                iconX = x - targetWidth / 2;
                iconY = y - CIRCLE_RADIUS - targetHeight - 8f;
            }
            canvas.drawBitmap(iconToUse, null, new RectF(iconX, iconY, iconX + targetWidth, iconY + targetHeight), null);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAllStationAnimations();
    }
}