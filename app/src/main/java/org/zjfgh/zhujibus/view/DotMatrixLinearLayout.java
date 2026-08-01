package org.zjfgh.zhujibus.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import org.zjfgh.zhujibus.R;

/**
 * 带圆点矩阵背景的 LinearLayout
 */
public class DotMatrixLinearLayout extends LinearLayout {

    private Paint dotPaint;
    private float dotRadius = 3f; // 圆点半径
    private float dotSpacing = 12f; // 圆点间距
    private float offsetX = 0f; // X轴偏移
    private float offsetY = 0f; // Y轴偏移
    private int dotColor = 0x33FF0000; // 圆点颜色（半透明红色）
    private int bgColor = 0xFF1A0505; // 背景色

    public DotMatrixLinearLayout(Context context) {
        super(context);
        init(null);
    }

    public DotMatrixLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public DotMatrixLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.DotMatrixLinearLayout);
            dotRadius = a.getDimension(R.styleable.DotMatrixLinearLayout_dmlDotRadius, dotRadius);
            dotSpacing = a.getDimension(R.styleable.DotMatrixLinearLayout_dmlDotSpacing, dotSpacing);
            dotColor = a.getColor(R.styleable.DotMatrixLinearLayout_dmlDotColor, dotColor);
            bgColor = a.getColor(R.styleable.DotMatrixLinearLayout_dmlBgColor, bgColor);
            offsetX = a.getDimension(R.styleable.DotMatrixLinearLayout_dmlOffsetX, offsetX);
            offsetY = a.getDimension(R.styleable.DotMatrixLinearLayout_dmlOffsetY, offsetY);
            a.recycle();
        }

        dotPaint.setColor(dotColor);
        setBackgroundColor(bgColor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // 绘制圆点矩阵
        for (float x = offsetX; x < width; x += dotSpacing) {
            for (float y = offsetY; y < height; y += dotSpacing) {
                canvas.drawCircle(x, y, dotRadius, dotPaint);
            }
        }
    }

    /**
     * 设置圆点半径
     */
    public void setDotRadius(float radius) {
        this.dotRadius = radius;
        invalidate();
    }

    /**
     * 设置圆点间距
     */
    public void setDotSpacing(float spacing) {
        this.dotSpacing = spacing;
        invalidate();
    }

    /**
     * 设置圆点颜色
     */
    public void setDotColor(int color) {
        this.dotColor = color;
        dotPaint.setColor(color);
        invalidate();
    }

    /**
     * 设置X轴偏移
     */
    public void setOffsetX(float offsetX) {
        this.offsetX = offsetX;
        invalidate();
    }

    /**
     * 设置Y轴偏移
     */
    public void setOffsetY(float offsetY) {
        this.offsetY = offsetY;
        invalidate();
    }

    /**
     * 设置XY轴偏移
     */
    public void setOffset(float offsetX, float offsetY) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        invalidate();
    }

    /**
     * 设置背景色
     */
    public void setBgColor(int color) {
        setBackgroundColor(color);
    }
}