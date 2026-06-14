package org.zjfgh.zhujibus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

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
    private Paint normalCirclePaint;    // 白色空心圆 (普通站点)
    private Paint currentCirclePaint;   // 绿色实心圆 (车辆当前到站)
    private Paint selectedPaint;        // 红色实心圆 (用户选中)
    private Paint textPaint;
    private Paint busIconPaint;

    // 图标
    private Bitmap busIconBitmap;

    // 布局常量
    private static final float TOP_PADDING = 80f;
    private static final float BUS_ICON_TOP = 40f;
    private static final float LINE_Y = 120f;
    private static final float CIRCLE_RADIUS = 18f;
    private static final float STATION_SPACING = 180f;
    private static final float TEXT_Y_OFFSET = 50f;
    private static final float TEXT_SIZE = 32f;
    private static final float BUS_ICON_SIZE = 80f;

    // 交互
    private OnStationClickListener listener;
    private float touchDownX, touchDownY;

    public interface OnStationClickListener {
        void onStationClick(BusApiClient.BusLineStation station, int position);
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
        // 1. 线路画笔 (白色实线)
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(3f);
        linePaint.setStyle(Paint.Style.STROKE);

        // 2. 普通站点圆圈 (白色空心)
        normalCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        normalCirclePaint.setColor(Color.WHITE);
        normalCirclePaint.setStyle(Paint.Style.STROKE);
        normalCirclePaint.setStrokeWidth(3f);

        // 3. 当前到站圆圈 (绿色实心 - 表示车辆在该站)
        currentCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currentCirclePaint.setColor(0xFF4CAF50); // Material Green
        currentCirclePaint.setStyle(Paint.Style.FILL);

        // 4. 选中站点圆圈 (红色实心)
        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setColor(Color.RED);
        selectedPaint.setStyle(Paint.Style.FILL);

        // 5. 文字画笔 (白色, 居中对齐)
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TEXT_SIZE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 6. 巴士图标背景色
        busIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        busIconPaint.setColor(0xFF42A5F5); // 蓝色背景
        busIconPaint.setStyle(Paint.Style.FILL);

        // 7. 加载巴士图标
        try {
            busIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bus_icon);
        } catch (Exception e) {
            busIconBitmap = null;
        }
    }

    // --- 数据设置方法 ---

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

    public void setOnStationClickListener(OnStationClickListener listener) {
        this.listener = listener;
    }

    public void setGpsMode(boolean isGpsMode) {
        this.isGpsMode = isGpsMode;
        invalidate();
    }

    public void updateGpsPosition(int position, boolean isArriving) {
        this.gpsPositionIndex = position;
        this.isGpsArriving = isArriving;
        invalidate();
    }

    // --- 交互事件 ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;

            case MotionEvent.ACTION_UP:
                if (isGpsMode) return true;

                float x = event.getX();
                float y = event.getY();
                float moveDistance = (float) Math.sqrt(Math.pow(x - touchDownX, 2) + Math.pow(y - touchDownY, 2));

                if (moveDistance < 10f && stations != null) {
                    for (int i = 0; i < stations.size(); i++) {
                        float stationX = getStartX() + i * STATION_SPACING;
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
            desiredWidth = (int) (200 + (stations.size() - 1) * STATION_SPACING + 200);
            desiredHeight = (int) (TOP_PADDING + 100 + TEXT_Y_OFFSET + TEXT_SIZE + 50);
        }

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        if (widthMode == MeasureSpec.EXACTLY) {
            desiredWidth = widthSize;
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

    // --- 绘制 ---

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (stations == null || stations.isEmpty()) {
            return;
        }

        // 1. 绘制深色背景
        canvas.drawColor(0xFF1A237E);

        float startX = getStartX();
        float centerY = LINE_Y;

        // 2. 绘制左上角的巴士图标
        drawBusIcon(canvas);

        // 3. 绘制水平连接线
        float lineStartX = startX - 40f;
        float lineEndX = startX + (stations.size() - 1) * STATION_SPACING + 40f;
        canvas.drawLine(lineStartX, centerY, lineEndX, centerY, linePaint);

        // 4. 绘制每个站点
        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            float x = startX + i * STATION_SPACING;

            // 绘制站点圆圈
            drawStationCircle(canvas, x, centerY, station, i);

            // 绘制站点名称 (竖排)
            drawStationName(canvas, x, centerY, station);
        }
    }

    private float getStartX() {
        return 180f;
    }

    private void drawBusIcon(Canvas canvas) {
        float x = 30f;
        float y = BUS_ICON_TOP;
        float width = BUS_ICON_SIZE;
        float height = BUS_ICON_SIZE * 0.6f;

        RectF rect = new RectF(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, 10f, 10f, busIconPaint);

        if (busIconBitmap != null) {
            canvas.drawBitmap(busIconBitmap, null, rect, null);
        } else {
            Paint busShapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            busShapePaint.setColor(Color.WHITE);
            busShapePaint.setStyle(Paint.Style.STROKE);
            busShapePaint.setStrokeWidth(4f);
            RectF busRect = new RectF(x + 10, y + 10, x + width - 10, y + height - 10);
            canvas.drawRoundRect(busRect, 8f, 8f, busShapePaint);
            canvas.drawLine(x + 20, y + 10, x + 20, y + height - 10, busShapePaint);
            canvas.drawLine(x + width - 20, y + 10, x + width - 20, y + height - 10, busShapePaint);
        }
    }

    private void drawStationCircle(Canvas canvas, float x, float y, BusApiClient.BusLineStation station, int index) {
        boolean isSelected = (index == selectedPosition && !isGpsMode);
        boolean isGpsCurrent = (isGpsMode && isGpsArriving && index == gpsPositionIndex);
        boolean isApiCurrent = (!isGpsMode && station.status == BusApiClient.BusLineStation.StationStatus.CURRENT);

        Paint currentPaint;

        if (isSelected) {
            // 用户选中: 红色
            currentPaint = selectedPaint;
        } else if (isGpsCurrent || isApiCurrent) {
            // 车辆当前到站: 绿色 (关键修改点)
            currentPaint = currentCirclePaint;
        } else {
            // 普通站点: 白色空心
            currentPaint = normalCirclePaint;
        }

        canvas.drawCircle(x, y, CIRCLE_RADIUS, currentPaint);
    }

    private void drawStationName(Canvas canvas, float x, float y, BusApiClient.BusLineStation station) {
        String name = station.stationName;
        float textX = x;
        float textY = y + CIRCLE_RADIUS + TEXT_Y_OFFSET;
        float lineHeight = TEXT_SIZE + 8f;

        for (int i = 0; i < name.length(); i++) {
            String charStr = String.valueOf(name.charAt(i));
            canvas.drawText(charStr, textX, textY + i * lineHeight, textPaint);
        }
    }
}