package org.zjfgh.zhujibus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

public class BusLineView extends View {
    private List<BusApiClient.BusLineStation> stations;
    private int selectedPosition = -1;
    private Paint linePaint;
    private Paint selectedLinePaint;
    private Paint circlePaint;
    private Paint selectedCirclePaint;
    private Paint textPaint;
    private Paint busIconPaint;
    private Paint plateTextPaint;
    private Bitmap busIconBitmap;
    private float animationProgress = 0f;
    private ValueAnimator arrowAnimator;
    private float arrowOffset = 0f;
    private OnStationClickListener listener;
    private float stationSpacing = 150f;
    private float startY = 60f;
    private float centerX;
    private float radius = 32f;
    private float blinkProgress = 0f;
    private ValueAnimator blinkAnimator;
    private float touchDownX;
    private float touchDownY;

    public interface OnStationClickListener {
        void onStationClick(BusApiClient.BusLineStation station, int position);
    }

    public BusLineView(Context context) {
        super(context);
        init();
    }

    public BusLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BusLineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0xFF0070FD);
        linePaint.setStrokeWidth(10f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0f));

        selectedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedLinePaint.setColor(0xFFFF0000);
        selectedLinePaint.setStrokeWidth(10f);
        selectedLinePaint.setStyle(Paint.Style.STROKE);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(0xFF0070FD);
        circlePaint.setStyle(Paint.Style.FILL);

        selectedCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedCirclePaint.setColor(0xFFFF0000);
        selectedCirclePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(48f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        busIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        busIconPaint.setColor(0xFF666666);
        busIconPaint.setStyle(Paint.Style.FILL);

        plateTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        plateTextPaint.setColor(0xFF666666);
        plateTextPaint.setTextSize(20f);
        plateTextPaint.setTextAlign(Paint.Align.CENTER);

        busIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bus_icon);

        startArrowAnimation();
        startBlinkAnimation();
    }

    public void setStations(List<BusApiClient.BusLineStation> stations) {
        this.stations = stations;
        invalidate();
    }

    public void updateBusPositions(List<BusApiClient.BusPosition> positions) {
        if (stations == null || positions == null || positions.isEmpty()) {
            resetAllStations();
            invalidate();
            return;
        }

        resetAllStations();
        clearBusPositionCache();

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
        if (plateNumber == null || plateNumber.isEmpty()) {
            return plateNumber;
        }
        
        String[] provincePrefixes = {"京", "津", "冀", "晋", "蒙", "辽", "吉", "黑", "沪", "苏", "浙", 
                                   "皖", "闽", "赣", "鲁", "豫", "鄂", "湘", "粤", "桂", "琼", 
                                   "渝", "川", "贵", "云", "藏", "陕", "甘", "青", "宁", "新", "港", "澳", "台"};
        
        for (String prefix : provincePrefixes) {
            String doublePrefix = prefix + prefix;
            if (plateNumber.startsWith(doublePrefix)) {
                return prefix + plateNumber.substring(doublePrefix.length());
            }
        }
        
        return plateNumber;
    }

    private void clearBusPositionCache() {
        cachedBusPositionInfo = null;
        cachedUserSelectedPosition = -1;
    }

    private void resetAllStations() {
        if (stations == null) {
            return;
        }
        for (BusApiClient.BusLineStation station : stations) {
            station.status = BusApiClient.BusLineStation.StationStatus.NORMAL;
            station.plateNumber = null;
        }
    }

    public void setSelectedPosition(int position) {
        if (this.selectedPosition != position) {
            this.selectedPosition = position;
            android.util.Log.d("BusLineView", "用户选择站点: " + position + " (序号: " + (position + 1) + ")");
        }
        invalidate();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setOnStationClickListener(OnStationClickListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;
                
            case MotionEvent.ACTION_UP:
                float x = event.getX();
                float y = event.getY();
                
                float moveDistance = (float) Math.sqrt(
                    Math.pow(x - touchDownX, 2) + Math.pow(y - touchDownY, 2)
                );
                
                if (moveDistance < 10f) {
                    if (stations != null) {
                        for (int i = 0; i < stations.size(); i++) {
                            float stationY = startY + i * stationSpacing;
                            
                            if (y >= stationY - stationSpacing / 2 && y <= stationY + stationSpacing / 2) {
                                if (listener != null) {
                                    listener.onStationClick(stations.get(i), i);
                                }
                                setSelectedPosition(i);
                                return true;
                            }
                        }
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void startArrowAnimation() {
        arrowAnimator = ValueAnimator.ofFloat(0f, 1f);
        arrowAnimator.setDuration(18000);
        arrowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        arrowAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        arrowAnimator.addUpdateListener(animation -> {
            animationProgress = (float) animation.getAnimatedValue();
            arrowOffset = animationProgress * 20f;
            invalidate();
        });
        arrowAnimator.start();
    }

    private void startBlinkAnimation() {
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f);
        blinkAnimator.setDuration(2000);
        blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnimator.setRepeatMode(ValueAnimator.RESTART);
        blinkAnimator.addUpdateListener(animation -> {
            blinkProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        blinkAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (arrowAnimator != null) {
            arrowAnimator.cancel();
        }
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (arrowAnimator != null && !arrowAnimator.isRunning()) {
            arrowAnimator.start();
        }
        if (blinkAnimator != null && !blinkAnimator.isRunning()) {
            blinkAnimator.start();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = 0;
        int desiredWidth = 0;
        
        if (stations != null && !stations.isEmpty()) {
            desiredHeight = (int) (stations.size() * stationSpacing + startY + 50);
            
            float leftPadding = 20f;
            float iconSize = 96f;
            float busAreaWidth = iconSize;
            float spacing = 70f;
            float stationRadius = 32f;
            float stationNameWidth = 0f;
            
            for (BusApiClient.BusLineStation station : stations) {
                Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                namePaint.setColor(0xFF333333);
                namePaint.setTextSize(52f);
                namePaint.setTextAlign(Paint.Align.LEFT);
                float nameWidth = namePaint.measureText(station.stationName);
                stationNameWidth = Math.max(stationNameWidth, nameWidth);
                
                if (station.status != null && station.plateNumber != null && !station.plateNumber.isEmpty()) {
                    float plateWidth = plateTextPaint.measureText(station.plateNumber) + 16f;
                    busAreaWidth = Math.max(busAreaWidth, Math.max(iconSize, plateWidth));
                }
            }
            
            desiredWidth = (int) (leftPadding + busAreaWidth + spacing + stationRadius + 35f + stationNameWidth + 50f);
        }
        
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        
        if (widthMode == MeasureSpec.EXACTLY) {
            desiredWidth = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            desiredWidth = Math.min(desiredWidth, widthSize);
        }
        
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        
        if (heightMode == MeasureSpec.EXACTLY) {
            desiredHeight = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            desiredHeight = Math.min(desiredHeight, heightSize);
        }
        
        int width = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY);
        int height = MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY);
        super.onMeasure(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (stations == null || stations.isEmpty()) {
            return;
        }

        float leftPadding = 20f;
        float busAreaWidth = calculateBusAreaWidth();
        float spacing = 70f;
        centerX = leftPadding + busAreaWidth + spacing;

        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            float stationY = startY + i * stationSpacing;

            drawBusIcon(canvas, leftPadding, stationY, station);
            drawStation(canvas, centerX, stationY, station, i);

            if (i < stations.size() - 1) {
                drawConnectingLine(canvas, centerX, stationY, i);
            }
        }
    }

    private float calculateBusAreaWidth() {
        float maxWidth = 0f;
        float iconSize = 96f;
        
        for (BusApiClient.BusLineStation station : stations) {
            if (station.status != null && station.plateNumber != null && !station.plateNumber.isEmpty()) {
                float plateWidth = plateTextPaint.measureText(station.plateNumber) + 16f;
                float busWidth = iconSize;
                maxWidth = Math.max(maxWidth, Math.max(busWidth, plateWidth));
            }
        }
        
        return maxWidth > 0 ? maxWidth : iconSize;
    }

    private void drawStation(Canvas canvas, float x, float y, BusApiClient.BusLineStation station, int index) {
        float radius = 32f;
        
        BusPositionInfo busInfo = findBusPosition(selectedPosition);
        boolean shouldDim = false;
        boolean isNextStation = false;
        boolean shouldLightUpGreen = false;
        
        if (busInfo != null && busInfo.isBeforeUserSelected) {
            if (busInfo.status == BusApiClient.BusLineStation.StationStatus.NEXT_STATION) {
                shouldDim = (index < busInfo.position);
                if (index == busInfo.position + 1) {
                    isNextStation = true;
                }
                
                if (index >= busInfo.position) {
                    int totalSegments = stations.size() - busInfo.position;
                    int totalLights = totalSegments * 4;
                    float totalProgress = animationProgress * (totalLights - 1);
                    int activeStep = (int) totalProgress;
                    
                    int stationLightIndex = (index - busInfo.position) * 4 - 1;
                    if (stationLightIndex >= activeStep - 3 && stationLightIndex <= activeStep && stationLightIndex >= 0) {
                        shouldLightUpGreen = true;
                    }
                }
            } else if (busInfo.status == BusApiClient.BusLineStation.StationStatus.CURRENT) {
                shouldDim = (index < busInfo.position);
                
                if (index >= busInfo.position) {
                    int totalSegments = stations.size() - busInfo.position;
                    int totalLights = totalSegments * 4;
                    float totalProgress = animationProgress * (totalLights - 1);
                    int activeStep = (int) totalProgress;
                    
                    int stationLightIndex = (index - busInfo.position) * 4 - 1;
                    if (stationLightIndex >= activeStep - 3 && stationLightIndex <= activeStep && stationLightIndex >= 0) {
                        shouldLightUpGreen = true;
                    }
                }
            } else {
                shouldDim = (index < busInfo.position);
            }
        }
        
        Paint circlePaintToUse;
        Paint textPaintToUse;
        Paint namePaintToUse;
        
        if (index == selectedPosition) {
            circlePaintToUse = selectedCirclePaint;
            textPaintToUse = textPaint;
        } else {
            circlePaintToUse = circlePaint;
            textPaintToUse = textPaint;
        }
        
        if (shouldDim) {
            circlePaintToUse = new Paint(circlePaintToUse);
            circlePaintToUse.setColor(0xFF333333);
            circlePaintToUse.setStyle(Paint.Style.STROKE);
            circlePaintToUse.setStrokeWidth(3f);
            textPaintToUse = new Paint(textPaintToUse);
            textPaintToUse.setColor(0xFF666666);
            textPaintToUse.setAlpha(150);
        }
        
        if (isNextStation) {
            circlePaintToUse = new Paint(circlePaintToUse);
            int yellowColor = 0xFFFFD700;
            int alpha = blinkProgress < 0.5f ? 255 : 0;
            circlePaintToUse.setColor(yellowColor);
            circlePaintToUse.setAlpha(alpha);
            circlePaintToUse.setStyle(Paint.Style.FILL);
            
            textPaintToUse = new Paint(textPaintToUse);
            textPaintToUse.setColor(0xFF000000);
            textPaintToUse.setAlpha(alpha);
        }
        
        if (shouldLightUpGreen) {
            circlePaintToUse = new Paint(circlePaintToUse);
            circlePaintToUse.setColor(0xFF00FF00);
            circlePaintToUse.setStyle(Paint.Style.FILL);
            
            textPaintToUse = new Paint(textPaintToUse);
            textPaintToUse.setColor(0xFF000000);
        }
        
        canvas.drawCircle(x, y, radius, circlePaintToUse);
        canvas.drawText(String.valueOf(station.stationOrder), x, y + 18f, textPaintToUse);

        float nameX = x + radius + 35f;
        float nameY = y + 18f;
        namePaintToUse = new Paint(textPaint);
        namePaintToUse.setTextAlign(Paint.Align.LEFT);
        namePaintToUse.setTextSize(52f);
        
        if (index == selectedPosition) {
            namePaintToUse.setColor(0xFFFF0000);
        } else {
            namePaintToUse.setColor(0xFF333333);
        }
        
        if (shouldDim) {
            namePaintToUse.setAlpha(100);
        }
        
        canvas.drawText(station.stationName, nameX, nameY, namePaintToUse);
    }

    private void drawBusIcon(Canvas canvas, float x, float y, BusApiClient.BusLineStation station) {
        if (station.status == null) {
            return;
        }
        
        float iconSize = 96f;
        float iconX;
        float iconY;

        switch (station.status) {
            case CURRENT:
                iconY = y - iconSize / 2;
                if (station.plateNumber != null && !station.plateNumber.isEmpty()) {
                    float plateWidth = plateTextPaint.measureText(station.plateNumber) + 16f;
                    drawPlateNumber(canvas, x, iconY + iconSize + 15f, station.plateNumber);
                    iconX = x + plateWidth - iconSize;
                } else {
                    iconX = x - iconSize;
                }
                drawBus(canvas, iconX, iconY, iconSize);
                break;
            case NEXT_STATION:
                iconY = y + stationSpacing / 2 - iconSize / 2;
                if (station.plateNumber != null && !station.plateNumber.isEmpty()) {
                    float plateWidth = plateTextPaint.measureText(station.plateNumber) + 16f;
                    drawPlateNumber(canvas, x, iconY + iconSize + 15f, station.plateNumber);
                    iconX = x + plateWidth - iconSize;
                } else {
                    iconX = x - iconSize;
                }
                drawBus(canvas, iconX, iconY, iconSize);
                break;
        }
    }

    private void drawBus(Canvas canvas, float x, float y, float size) {
        if (busIconBitmap != null) {
            canvas.drawBitmap(busIconBitmap, null, new android.graphics.RectF(x, y, x + size, y + size), null);
        }
    }

    private void drawPlateNumber(Canvas canvas, float x, float y, String plateNumber) {
        float padding = 8f;
        
        plateTextPaint.setColor(0xFF666666);
        plateTextPaint.setTextSize(32f);
        plateTextPaint.setTextAlign(Paint.Align.LEFT);
        
        float textWidth = plateTextPaint.measureText(plateNumber);
        android.graphics.Paint.FontMetrics fontMetrics = plateTextPaint.getFontMetrics();
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        
        float plateX = x;
        float plateY = y;
        float plateWidth = textWidth + padding * 2;
        float plateHeight = textHeight + padding * 2;
        
        plateTextPaint.setColor(0xFFF5F5F5);
        canvas.drawRoundRect(plateX, plateY, plateX + plateWidth, plateY + plateHeight, 4f, 4f, plateTextPaint);
        
        plateTextPaint.setColor(0xFF666666);
        float textX = plateX + padding;
        float textY = plateY + padding - fontMetrics.ascent;
        canvas.drawText(plateNumber, textX, textY, plateTextPaint);
    }

    private void drawArrow(Canvas canvas, float fromX, float fromY, float toY) {
        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFF9800);
        arrowPaint.setStrokeWidth(6f);
        arrowPaint.setStyle(Paint.Style.STROKE);

        Path arrowPath = new Path();
        arrowPath.moveTo(fromX, fromY);
        arrowPath.lineTo(fromX, toY);

        canvas.drawPath(arrowPath, arrowPaint);

        arrowPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(fromX, toY, 6f, arrowPaint);
    }

    private void drawMovingArrow(Canvas canvas, float x, float y) {
        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFFD700);
        arrowPaint.setStrokeWidth(6f);
        arrowPaint.setStyle(Paint.Style.FILL);

        float arrowSize = 20f;
        Path arrowPath = new Path();
        arrowPath.moveTo(x - arrowSize / 2, y - arrowSize);
        arrowPath.lineTo(x + arrowSize / 2, y - arrowSize);
        arrowPath.lineTo(x, y);
        arrowPath.close();

        canvas.drawPath(arrowPath, arrowPaint);
    }

    private void drawConnectingLine(Canvas canvas, float x, float y, int index) {
        float lineStart = y + radius;
        float lineEnd = y + stationSpacing - radius;

        BusPositionInfo busInfo = findBusPosition(selectedPosition);
        boolean shouldDim = false;
        boolean shouldDrawLED = false;
        
        if (busInfo != null && busInfo.isBeforeUserSelected) {
            if (busInfo.status == BusApiClient.BusLineStation.StationStatus.NEXT_STATION) {
                shouldDim = (index < busInfo.position);
                if (index >= busInfo.position) {
                    shouldDrawLED = true;
                }
            } else if (busInfo.status == BusApiClient.BusLineStation.StationStatus.CURRENT) {
                shouldDim = (index < busInfo.position);
                if (index >= busInfo.position) {
                    shouldDrawLED = true;
                }
            } else {
                shouldDim = (index < busInfo.position);
            }
        }
        
        float ledRadius = 8f;
        float led1Y = lineStart + (lineEnd - lineStart) * 0.25f;
        float led2Y = lineStart + (lineEnd - lineStart) * 0.5f;
        float led3Y = lineStart + (lineEnd - lineStart) * 0.75f;
        
        Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledPaint.setStyle(Paint.Style.FILL);
        
        if (shouldDrawLED) {
            int totalSegments = stations.size() - busInfo.position;
            int currentSegmentIndex = index - busInfo.position;
            
            if (currentSegmentIndex < 0 || currentSegmentIndex >= totalSegments) {
                ledPaint.setColor(shouldDim ? 0xFF999999 : 0xFF0070FD);
                canvas.drawCircle(x, led1Y, ledRadius, ledPaint);
                canvas.drawCircle(x, led2Y, ledRadius, ledPaint);
                canvas.drawCircle(x, led3Y, ledRadius, ledPaint);
                return;
            }
            
            int totalLights = totalSegments * 4;
            float totalProgress = animationProgress * (totalLights - 1);
            int activeStep = (int) totalProgress;
            
            float led1Intensity = 0f, led2Intensity = 0f, led3Intensity = 0f;
            
            int led1Index = currentSegmentIndex * 4;
            int led2Index = currentSegmentIndex * 4 + 1;
            int led3Index = currentSegmentIndex * 4 + 2;
            
            if (led1Index >= activeStep - 3 && led1Index <= activeStep) {
                led1Intensity = 1f;
            }
            if (led2Index >= activeStep - 3 && led2Index <= activeStep) {
                led2Intensity = 1f;
            }
            if (led3Index >= activeStep - 3 && led3Index <= activeStep) {
                led3Intensity = 1f;
            }
            
            if (led1Intensity > 0) {
                int alpha1 = (int) (255 * led1Intensity);
                ledPaint.setColor(0xFF00FF00);
                ledPaint.setAlpha(alpha1);
                canvas.drawCircle(x, led1Y, ledRadius, ledPaint);
            } else {
                ledPaint.setColor(0xFF999999);
                ledPaint.setAlpha(255);
                canvas.drawCircle(x, led1Y, ledRadius, ledPaint);
            }
            
            if (led2Intensity > 0) {
                int alpha2 = (int) (255 * led2Intensity);
                ledPaint.setColor(0xFF00FF00);
                ledPaint.setAlpha(alpha2);
                canvas.drawCircle(x, led2Y, ledRadius, ledPaint);
            } else {
                ledPaint.setColor(0xFF999999);
                ledPaint.setAlpha(255);
                canvas.drawCircle(x, led2Y, ledRadius, ledPaint);
            }
            
            if (led3Intensity > 0) {
                int alpha3 = (int) (255 * led3Intensity);
                ledPaint.setColor(0xFF00FF00);
                ledPaint.setAlpha(alpha3);
                canvas.drawCircle(x, led3Y, ledRadius, ledPaint);
            } else {
                ledPaint.setColor(0xFF999999);
                ledPaint.setAlpha(255);
                canvas.drawCircle(x, led3Y, ledRadius, ledPaint);
            }
        } else {
            ledPaint.setColor(shouldDim ? 0xFF999999 : 0xFF0070FD);
            canvas.drawCircle(x, led1Y, ledRadius, ledPaint);
            canvas.drawCircle(x, led2Y, ledRadius, ledPaint);
            canvas.drawCircle(x, led3Y, ledRadius, ledPaint);
        }
    }

    private float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private BusPositionInfo cachedBusPositionInfo;
    private int cachedUserSelectedPosition = -1;
    
    private BusPositionInfo findBusPosition(int userSelectedPosition) {
        if (stations == null || stations.isEmpty()) {
            return null;
        }
        
        if (cachedUserSelectedPosition == userSelectedPosition && cachedBusPositionInfo != null) {
            return cachedBusPositionInfo;
        }
        
        List<Integer> busPositions = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            if (station.status == BusApiClient.BusLineStation.StationStatus.CURRENT || 
                station.status == BusApiClient.BusLineStation.StationStatus.NEXT_STATION) {
                busPositions.add(i);
            }
        }
        
        if (busPositions.isEmpty()) {
            cachedUserSelectedPosition = userSelectedPosition;
            cachedBusPositionInfo = null;
            return null;
        }
        
        int closestBusPosition = -1;
        BusApiClient.BusLineStation.StationStatus closestBusStatus = null;
        
        for (int i = 0; i < busPositions.size(); i++) {
            int busPos = busPositions.get(i);
            if (busPos < userSelectedPosition) {
                if (closestBusPosition == -1 || busPos > closestBusPosition) {
                    closestBusPosition = busPos;
                    closestBusStatus = stations.get(busPos).status;
                }
            }
        }
        
        if (closestBusPosition == -1) {
            cachedUserSelectedPosition = userSelectedPosition;
            cachedBusPositionInfo = null;
            return null;
        }

        android.util.Log.d("BusLineView", "用户选择索引: " + userSelectedPosition + " (序号: " + (userSelectedPosition + 1) + "), 最近车辆索引: " + closestBusPosition + " (序号: " + (closestBusPosition + 1) + "), isBefore: true, 车辆状态: " + closestBusStatus);

        BusPositionInfo info = new BusPositionInfo();
        info.position = closestBusPosition;
        info.isBeforeUserSelected = true;
        info.status = closestBusStatus;
        cachedUserSelectedPosition = userSelectedPosition;
        cachedBusPositionInfo = info;
        return info;
    }

    private static class BusPositionInfo {
        int position;
        boolean isBeforeUserSelected;
        BusApiClient.BusLineStation.StationStatus status;
    }
}
