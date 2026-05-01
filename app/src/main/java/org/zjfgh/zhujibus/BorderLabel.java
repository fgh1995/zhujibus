package org.zjfgh.zhujibus;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public class BorderLabel extends View {
    private Paint borderPaint;
    private Paint textPaint;
    private String text = "文本";
    private boolean isLit = true;
    private Rect textBounds = new Rect();
    private int litColor = 0xFF00FF0B;
    private int dimColor = 0xFF660000;
    private float textSize = 48f;
    private static final float STROKE_WIDTH = 2f;
    private static final float CORNER_RADIUS = 3f;
    private static final float PADDING = 16f;

    public BorderLabel(Context context) {
        super(context);
        init();
    }

    public BorderLabel(Context context, AttributeSet attrs) {
        super(context, attrs);
        initAttrs(attrs);
        init();
    }

    public BorderLabel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttrs(attrs);
        init();
    }

    private void initAttrs(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.BorderLabel);
            text = a.getString(R.styleable.BorderLabel_labelText);
            if (text == null) text = "文本";
            litColor = a.getColor(R.styleable.BorderLabel_labelLitColor, 0xFF00FF0B);
            dimColor = a.getColor(R.styleable.BorderLabel_labelDimColor, 0xFF660000);
            textSize = a.getDimension(R.styleable.BorderLabel_labelTextSize, 48f);
            a.recycle();
        }
    }

    private void init() {
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(STROKE_WIDTH);
        borderPaint.setColor(litColor);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(textSize);
        textPaint.setColor(litColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
    }

    public void setLit(boolean lit) {
        if (this.isLit != lit) {
            this.isLit = lit;
            updateColors();
            invalidate();
        }
    }

    public boolean isLit() {
        return isLit;
    }

    public void setColors(int litColor, int dimColor) {
        this.litColor = litColor;
        this.dimColor = dimColor;
        updateColors();
        invalidate();
    }

    public void setColor(int color) {
        this.litColor = color;
        this.dimColor = color;
        updateColors();
        invalidate();
    }

    public void setLitColor(int color) {
        this.litColor = color;
        if (isLit) {
            updateColors();
            invalidate();
        }
    }

    public void setDimColor(int color) {
        this.dimColor = color;
        if (!isLit) {
            updateColors();
            invalidate();
        }
    }

    private void updateColors() {
        int color = isLit ? litColor : dimColor;
        borderPaint.setColor(color);
        textPaint.setColor(color);
    }

    public int getLitColor() {
        return litColor;
    }

    public int getDimColor() {
        return dimColor;
    }

    public void setTextSize(float size) {
        this.textSize = size;
        textPaint.setTextSize(size);
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
        requestLayout();
        invalidate();
    }

    public float getTextSize() {
        return textSize;
    }

    public void setText(String text) {
        this.text = text;
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
        requestLayout();
        invalidate();
    }

    public String getText() {
        return text;
    }

    public void setTypeface(Typeface tf) {
        if (tf != null) {
            textPaint.setTypeface(tf);
            textPaint.getTextBounds(text, 0, text.length(), textBounds);
            requestLayout();
            invalidate();
        }
    }

    public void setTypeface(Typeface tf, int style) {
        textPaint.setTypeface(Typeface.create(tf, style));
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
        requestLayout();
        invalidate();
    }

    public Typeface getTypeface() {
        return textPaint.getTypeface();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float textWidth = textBounds.width();
        float textHeight = textBounds.height();
        float desiredWidth = textWidth + PADDING * 2 + STROKE_WIDTH * 2;
        float desiredHeight = textHeight + PADDING * 2 + STROKE_WIDTH * 2;

        int width = resolveSize((int) Math.ceil(desiredWidth), widthMeasureSpec);
        int height = resolveSize((int) Math.ceil(desiredHeight), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float left = STROKE_WIDTH / 2;
        float top = STROKE_WIDTH / 2;
        float right = getWidth() - STROKE_WIDTH / 2;
        float bottom = getHeight() - STROKE_WIDTH / 2;

        canvas.drawRoundRect(left, top, right, bottom, CORNER_RADIUS, CORNER_RADIUS, borderPaint);

        float textX = getWidth() / 2f;
        float textY = getHeight() / 2f - textBounds.exactCenterY();

        canvas.drawText(text, textX, textY, textPaint);
    }
}