package org.zjfgh.zhujibus;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class SegmentedProgressBar extends View {

    private Paint lightPaint;
    private Paint darkPaint;
    private RectF rectF;
    private Path clipPath;

    private int lightColor = Color.parseColor("#5ABFFD");
    private int darkColor = Color.parseColor("#4F6FF4");

    private float radius = 10f;
    private float progress = 0.6f;

    private OnProgressChangeListener listener;

    // 标记是否正在拖动
    private boolean isDragging = false;

    public SegmentedProgressBar(Context context) {
        super(context);
        init(null);
    }

    public SegmentedProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public SegmentedProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.SegmentedProgressBar);
            lightColor = ta.getColor(R.styleable.SegmentedProgressBar_lightColor, lightColor);
            darkColor = ta.getColor(R.styleable.SegmentedProgressBar_darkColor, darkColor);
            progress = ta.getFloat(R.styleable.SegmentedProgressBar_progress, progress);
            radius = ta.getDimension(R.styleable.SegmentedProgressBar_radius, radius);
            ta.recycle();
        }

        lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lightPaint.setColor(lightColor);
        lightPaint.setStyle(Paint.Style.FILL);

        darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        darkPaint.setColor(darkColor);
        darkPaint.setStyle(Paint.Style.FILL);

        rectF = new RectF();
        clipPath = new Path();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        // 1. 创建裁剪路径 (左右圆角)
        clipPath.reset();
        rectF.set(0, 0, w, h);
        clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);

        // 2. 绘制背景
        canvas.drawColor(lightColor);

        // 3. 绘制进度
        float progressWidth = w * progress;
        if (progress > 0) {
            rectF.set(0, 0, progressWidth, h);
            canvas.drawRect(rectF, darkPaint);
        }

        canvas.restore();
    }

    // ============ 核心：触摸事件 ============

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 获取手指在 View 中的 X 坐标
        float x = event.getX();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 手指按下，开始拖动
                isDragging = true;
                updateProgress(x);
                return true; // 消费事件

            case MotionEvent.ACTION_MOVE:
                // 手指移动，持续更新进度
                if (isDragging) {
                    updateProgress(x);
                    return true; // 持续消费事件
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 手指抬起，结束拖动
                if (isDragging) {
                    isDragging = false;
                    updateProgress(x);

                    // 回调最终的进度
                    if (listener != null) {
                        listener.onProgressChanged(this, progress);
                    }
                    return true;
                }
                return false;
        }
        return super.onTouchEvent(event);
    }

    // ============ 更新进度方法 ============

    private void updateProgress(float x) {
        // 计算新的进度值
        float newProgress = x / getWidth();

        // 限制范围在 0~1 之间
        if (newProgress < 0) newProgress = 0;
        if (newProgress > 1) newProgress = 1;

        // 更新进度并重绘
        if (this.progress != newProgress) {
            this.progress = newProgress;
            invalidate(); // 触发重绘

            // 实时回调
            if (listener != null) {
                listener.onProgressChanged(this, progress);
            }
        }
    }

    // ============ 设置方法 ============

    public void setProgress(float progress) {
        if (progress < 0) progress = 0;
        if (progress > 1) progress = 1;
        this.progress = progress;
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    public void setLightColor(int color) {
        this.lightColor = color;
        lightPaint.setColor(color);
        invalidate();
    }

    public void setDarkColor(int color) {
        this.darkColor = color;
        darkPaint.setColor(color);
        invalidate();
    }

    public void setColors(int lightColor, int darkColor) {
        this.lightColor = lightColor;
        this.darkColor = darkColor;
        lightPaint.setColor(lightColor);
        darkPaint.setColor(darkColor);
        invalidate();
    }

    public void setOnProgressChangeListener(OnProgressChangeListener listener) {
        this.listener = listener;
    }

    public interface OnProgressChangeListener {
        void onProgressChanged(SegmentedProgressBar bar, float progress);
    }
}