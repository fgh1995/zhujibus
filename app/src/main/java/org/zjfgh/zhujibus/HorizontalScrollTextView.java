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
    private float scrollOffset = 0f;
    private boolean needScroll = false;
    private ValueAnimator scrollAnimator;
    private float density;
    private Typeface typeface;
    private float totalScrollWidth;
    private float scrollSpeed = 100f;
    private float pauseDuration = 800f;
    private boolean isPaused = false;
    private Runnable scrollRunnable;
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

            if (xmlText != null) {
                text = xmlText;
            }
            textPaint.setColor(xmlTextColor);
            textPaint.setTextSize(xmlTextSize);
            scrollSpeed = xmlScrollSpeed;
            autoStart = xmlAutoStart;
            a.recycle();
        }
    }

    public void setText(String text) {
        if (text == null) {
            text = "";
        }
        this.text = text;
        textWidth = textPaint.measureText(text);
        if (getWidth() > 0) {
            needScroll = textWidth > getWidth();
        }
        resetScroll();
        invalidate();
        if (needScroll && autoStart) {
            startScrollAnimation();
        }
    }

    private void resetScroll() {
        if (scrollAnimator != null) {
            scrollAnimator.removeAllUpdateListeners();
            scrollAnimator.removeAllListeners();
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        scrollOffset = 0f;
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
        requestLayout();
        invalidate();
        updateScrollState();
    }

    public float getTextSize() {
        return textPaint.getTextSize() / density;
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

    public void setScrollPauseDuration(float milliseconds) {
        this.pauseDuration = milliseconds;
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
        textWidth = textPaint.measureText(text);
        int availableWidth = getWidth();
        needScroll = availableWidth > 0 && textWidth > availableWidth;

        if (needScroll && autoStart && (scrollAnimator == null || !scrollAnimator.isRunning())) {
            startScrollAnimation();
        } else if (!needScroll && scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollOffset = 0f;
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

        float scrollDistance = getWidth() + textWidth;
        totalScrollWidth = scrollDistance + 100 * density;
        int duration = (int) (totalScrollWidth / scrollSpeed * 1000);
        float endValue = scrollDistance / totalScrollWidth;
        scrollAnimator = ValueAnimator.ofFloat(0f, endValue);
        scrollAnimator.setDuration(duration);
        scrollAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.addListener(new android.animation.ValueAnimator.AnimatorListener() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {}

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (needScroll) {
                    scrollOffset = 0f;
                    invalidate();
                    startScrollAnimation();
                }
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {}

            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {}
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

        float offset = scrollOffset * totalScrollWidth;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = getHeight() * 0.8f;

        float firstTextX = getWidth() - offset;
        float secondTextX = firstTextX + totalScrollWidth;

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.drawText(text, firstTextX, textY, textPaint);
        canvas.drawText(text, secondTextX, textY, textPaint);
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