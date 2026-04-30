package org.zjfgh.zhujibus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public class HorizontalScrollTextView extends View {

    private Paint textPaint;
    private String text = "";
    private float textWidth = 0f;
    private float textGap = 0f;
    private float singleUnitWidth = 0f;
    private float scrollOffset = 0f;
    private float loopBaseOffset = 0f;
    private boolean needScroll = false;
    private ValueAnimator scrollAnimator;
    private float density;
    private Typeface typeface;
    private float scrollSpeed = 90f;
    private boolean autoStart = true;
    private int gravity = 2;

    public HorizontalScrollTextView(Context context) {
        super(context);
        init(context, null);
    }

    public HorizontalScrollTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public HorizontalScrollTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        density = getResources().getDisplayMetrics().density;

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(30f * density);
        textPaint.setColor(0xFFFF0000);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.HorizontalScrollTextView);
            String xmlText = a.getString(R.styleable.HorizontalScrollTextView_hsText);
            int xmlTextColor = a.getColor(R.styleable.HorizontalScrollTextView_hsTextColor, 0xFFFF0000);
            float xmlTextSize = a.getDimension(R.styleable.HorizontalScrollTextView_hsTextSize, 30f * density);
            float xmlScrollSpeed = a.getFloat(R.styleable.HorizontalScrollTextView_hsScrollSpeed, 100f);
            boolean xmlAutoStart = a.getBoolean(R.styleable.HorizontalScrollTextView_hsAutoStart, true);
            gravity = a.getInt(R.styleable.HorizontalScrollTextView_hsGravity, 2);
            float xmlTextGap = a.getDimension(R.styleable.HorizontalScrollTextView_hsTextGap, 0f);

            if (xmlText != null) {
                text = xmlText;
            }
            textPaint.setColor(xmlTextColor);
            textPaint.setTextSize(xmlTextSize);
            scrollSpeed = xmlScrollSpeed;
            autoStart = xmlAutoStart;
            textGap = xmlTextGap;
            a.recycle();
        }
        
        updateTextWidth();
    }

    public void setText(String text) {
        if (text == null) {
            text = "";
        }
        this.text = text;
        resetScroll();
        updateTextWidth();
        invalidate();
        if (getWidth() > 0) {
            updateScrollState();
        }
    }
    
    public void setTextGap(float gap) {
        this.textGap = gap;
        resetScroll();
        updateTextWidth();
        invalidate();
        if (getWidth() > 0) {
            updateScrollState();
        }
    }
    
    public float getTextGap() {
        return textGap;
    }
    
    private void updateTextWidth() {
        textWidth = textPaint.measureText(text);
        singleUnitWidth = textWidth + textGap;
    }

    private void resetScroll() {
        if (scrollAnimator != null) {
            scrollAnimator.removeAllUpdateListeners();
            scrollAnimator.removeAllListeners();
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        scrollOffset = 0f;
        loopBaseOffset = 0f;
    }

    public String getText() {
        return text;
    }

    public void setTextColor(int color) {
        textPaint.setColor(color);
        invalidate();
    }

    public int getTextColor() {
        return textPaint.getColor();
    }

    public void setTextSize(float size) {
        textPaint.setTextSize(size * density);
        updateTextWidth();
        requestLayout();
        invalidate();
        updateScrollState();
    }

    public float getTextSize() {
        return textPaint.getTextSize() / density;
    }

    public float getTextWidth() {
        return textPaint.measureText(text);
    }

    public void setTypeface(Typeface tf) {
        this.typeface = tf;
        textPaint.setTypeface(tf);
        invalidate();
    }

    public void setScrollSpeed(float pixelsPerSecond) {
        this.scrollSpeed = pixelsPerSecond;
        if (needScroll && scrollAnimator != null && scrollAnimator.isRunning()) {
            startScrollAnimation();
        }
    }

    public float getScrollSpeed() {
        return scrollSpeed;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setGravity(int gravity) {
        this.gravity = gravity;
        invalidate();
    }

    public int getGravity() {
        return gravity;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.bottom - fm.top;
        int height = (int) Math.ceil(textHeight);
        setMeasuredDimension(width, height);
    }

    private void updateScrollState() {
        updateTextWidth();
        int availableWidth = getWidth();
        needScroll = availableWidth > 0 && textWidth > availableWidth;

        if (needScroll && autoStart && (scrollAnimator == null || !scrollAnimator.isRunning())) {
            startScrollAnimation();
        } else if (!needScroll && scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
            scrollOffset = 0f;
            loopBaseOffset = 0f;
        }
    }

    public void startScroll() {
        if (needScroll && (scrollAnimator == null || !scrollAnimator.isRunning())) {
            startScrollAnimation();
        }
    }

    public void stopScroll() {
        resetScroll();
    }

    public boolean isScrolling() {
        return scrollAnimator != null && scrollAnimator.isRunning();
    }

    private void startScrollAnimation() {
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }

        if (singleUnitWidth <= 0 || getWidth() <= 0) {
            return;
        }

        int availableWidth = getWidth();
        float entryDistance = textWidth;
        int entryDuration = (int) (entryDistance / scrollSpeed * 1000);

        scrollAnimator = ValueAnimator.ofFloat(0f, entryDistance);
        scrollAnimator.setDuration(entryDuration);
        scrollAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                loopBaseOffset = entryDistance;
                startLoopAnimation();
            }
        });
        scrollAnimator.start();
    }

    private void startLoopAnimation() {
        if (scrollAnimator != null) {
            scrollAnimator.removeAllUpdateListeners();
            scrollAnimator.removeAllListeners();
            scrollAnimator.cancel();
            scrollAnimator = null;
        }

        if (singleUnitWidth <= 0) {
            return;
        }

        int duration = (int) (singleUnitWidth / scrollSpeed * 1000);
        scrollAnimator = ValueAnimator.ofFloat(0f, singleUnitWidth);
        scrollAnimator.setDuration(duration);
        scrollAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scrollAnimator.setRepeatMode(ValueAnimator.RESTART);
        scrollAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollOffset = loopBaseOffset + (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateScrollState();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (text == null || text.isEmpty()) {
            return;
        }

        if (!needScroll) {
            float textX;
            if (gravity == 0) {
                textX = 0;
            } else if (gravity == 1) {
                textX = (getWidth() - textWidth) / 2;
            } else {
                textX = getWidth() - textWidth;
            }
            canvas.drawText(text, textX, getHeight() * 0.8f, textPaint);
            return;
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = getHeight() * 0.8f;
        int availableWidth = getWidth();

        canvas.save();
        canvas.clipRect(0, 0, availableWidth, getHeight());

        float originX = availableWidth;

        int maxIndex = (int) Math.ceil((availableWidth + scrollOffset - originX) / singleUnitWidth);

        for (int i = 0; i <= maxIndex; i++) {
            float x = originX + i * singleUnitWidth - scrollOffset;
            if (x + textWidth > 0 && x < availableWidth + textWidth) {
                canvas.drawText(text, x, textY, textPaint);
            }
        }

        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
    }
}