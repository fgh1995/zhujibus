package org.zjfgh.zhujibus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

public class HorizontalScrollTextView extends View {

    private Paint textPaint;
    private String text = "";

    // ⭐ 多行（按 '\n' 换行）支持：独立于下方单行绘制逻辑，单行文本不受影响
    private boolean isMultiline = false;
    private String[] lines = null;
    private int lineCount = 1;
    private float lineHeight = 0f;
    private int lineGravity = 1; // 多行各行对齐：0=left 1=center 2=right，默认居中

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

    // 动画模式 0=单向无限滚动 1=往返滚动（转向停顿）
    private int animationMode = 0;

    // 往返滚动专用变量
    private boolean isScrollingToLeft = true;  // true:向左滚动, false:向右滚动
    private float bounceOffset = 0f;            // 当前偏移量
    private float maxBounceOffset = 0f;         // 最大偏移量 = textWidth - viewWidth

    private Handler pauseHandler = new Handler(android.os.Looper.getMainLooper());
    private int turnPauseDuration = 1500;       // 转向停顿时间（毫秒）

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
            // 新增属性
            animationMode = a.getInt(R.styleable.HorizontalScrollTextView_hsAnimationMode, 0);
            turnPauseDuration = a.getInt(R.styleable.HorizontalScrollTextView_hsTurnPauseDuration, 1500);
            // 多行各行对齐（独立于单行 hsGravity），默认居中
            lineGravity = a.getInt(R.styleable.HorizontalScrollTextView_hsLineGravity, 1);

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
        requestLayout(); // 行数可能变化，重新测量高度，避免多行被裁切
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
        // ⭐ 多行检测：含 '\n' 则进入多行模式（拆分、按最宽行算 textWidth），否则保持单行
        if (text.indexOf('\n') >= 0) {
            lines = text.split("\n", -1);
            lineCount = lines.length;
            isMultiline = true;
            float maxW = 0f;
            for (String ln : lines) {
                maxW = Math.max(maxW, textPaint.measureText(ln));
            }
            textWidth = maxW;
        } else {
            isMultiline = false;
            lines = null;
            lineCount = 1;
            textWidth = textPaint.measureText(text);
        }

        singleUnitWidth = textWidth + textGap;

        int viewWidth = getWidth();
        if (viewWidth > 0 && textWidth > viewWidth) {
            maxBounceOffset = textWidth - viewWidth;
        } else {
            maxBounceOffset = 0;
        }
    }

    private void resetScroll() {
        if (scrollAnimator != null) {
            scrollAnimator.removeAllUpdateListeners();
            scrollAnimator.removeAllListeners();
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        pauseHandler.removeCallbacksAndMessages(null);
        scrollOffset = 0f;
        loopBaseOffset = 0f;
        bounceOffset = 0f;
        isScrollingToLeft = true;
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
        return textWidth; // 单行=measureText(text)，多行=最宽行（已缓存）
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

    // 设置动画模式：0=单向无限滚动，1=往返滚动（转向停顿）
    public void setAnimationMode(int mode) {
        this.animationMode = mode;
        resetScroll();
        if (getWidth() > 0) {
            updateScrollState();
        }
        invalidate();
    }

    public int getAnimationMode() {
        return animationMode;
    }

    // 设置转向停顿时间（毫秒）
    public void setTurnPauseDuration(int duration) {
        this.turnPauseDuration = duration;
    }

    public int getTurnPauseDuration() {
        return turnPauseDuration;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = fm.bottom - fm.top;
        // ⭐ 多行时高度按行数翻倍；单行仍为原高度
        int count = isMultiline ? Math.max(1, lineCount) : 1;
        int height = (int) Math.ceil(lineHeight * count);

        // ⭐ 关键修复：wrap_content 必须返回内容宽度，而不是父给的可用宽度。
        // 否则在 horizontal LinearLayout 中会被当作"撑满父宽"吃掉兄弟控件的空间
        // （这也是 mode_switch 排不到 next_station_info 右边、被挤出屏幕的根因）。
        int contentWidth = (int) Math.ceil(textWidth);
        int width;
        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            width = Math.min(contentWidth, widthSize);
        } else { // UNSPECIFIED
            width = contentWidth;
        }
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
            bounceOffset = 0f;
        }
    }

    public void startScroll() {
        if (needScroll && (scrollAnimator == null || !scrollAnimator.isRunning())) {
            startScrollAnimation();
        }
    }

    public void stopScroll() {
        resetScroll();
        invalidate();
    }

    public boolean isScrolling() {
        return scrollAnimator != null && scrollAnimator.isRunning();
    }

    private void startScrollAnimation() {
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }
        pauseHandler.removeCallbacksAndMessages(null);

        if (animationMode == 0) {
            startInfiniteScrollAnimation();
        } else {
            startBounceScrollAnimation();
        }
    }

    // 原有的单向无限滚动
    private void startInfiniteScrollAnimation() {
        if (singleUnitWidth <= 0 || getWidth() <= 0) {
            return;
        }

        float entryDistance = textWidth;
        int entryDuration = (int) (entryDistance / scrollSpeed * 1000);

        scrollAnimator = ValueAnimator.ofFloat(0f, entryDistance);
        scrollAnimator.setDuration(entryDuration);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                loopBaseOffset = entryDistance;
                startInfiniteLoopAnimation();
            }
        });
        scrollAnimator.start();
    }

    private void startInfiniteLoopAnimation() {
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
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollOffset = loopBaseOffset + (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    // 往返滚动动画（转向时停顿）
    private void startBounceScrollAnimation() {
        if (maxBounceOffset <= 0 || getWidth() <= 0) {
            return;
        }

        // 重置位置：从完整显示开始（偏移量为0）
        bounceOffset = 0;
        isScrollingToLeft = true;
        invalidate();

        // 开始向左滚动
        startScrollToLeft();
    }

    // 向左滚动（文字向左移动，逐渐消失）
    private void startScrollToLeft() {
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }

        float startOffset = bounceOffset;
        float endOffset = maxBounceOffset;

        // 如果已经到达终点，直接停顿后反向
        if (Math.abs(endOffset - startOffset) < 0.1f) {
            pauseHandler.postDelayed(this::startScrollToRight, turnPauseDuration);
            return;
        }

        float distance = Math.abs(endOffset - startOffset);
        int duration = (int) (distance / scrollSpeed * 1000);

        scrollAnimator = ValueAnimator.ofFloat(startOffset, endOffset);
        scrollAnimator.setDuration(Math.max(16, duration));
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            bounceOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 到达最左边，停顿后向右滚动
                pauseHandler.postDelayed(() -> startScrollToRight(), turnPauseDuration);
            }
        });
        scrollAnimator.start();
    }

    // 向右滚动（文字向右移动，逐渐恢复）
    private void startScrollToRight() {
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }

        float startOffset = bounceOffset;
        float endOffset = 0f;

        // 如果已经到达终点，直接停顿后反向
        if (Math.abs(endOffset - startOffset) < 0.1f) {
            pauseHandler.postDelayed(() -> startScrollToLeft(), turnPauseDuration);
            return;
        }

        float distance = Math.abs(endOffset - startOffset);
        int duration = (int) (distance / scrollSpeed * 1000);

        scrollAnimator = ValueAnimator.ofFloat(startOffset, endOffset);
        scrollAnimator.setDuration(Math.max(16, duration));
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            bounceOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 到达最右边，停顿后向左滚动
                pauseHandler.postDelayed(() -> startScrollToLeft(), turnPauseDuration);
            }
        });
        scrollAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTextWidth();
        updateScrollState();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (text == null || text.isEmpty()) {
            return;
        }

        // ⭐ 多行模式：走独立绘制路径，完全不经过下方单行逻辑
        if (isMultiline) {
            drawMultiline(canvas);
            return;
        }

        int availableWidth = getWidth();

        // 不需要滚动时，按对齐方式绘制
        if (!needScroll) {
            float textX;
            if (gravity == 0) {
                textX = 0;
            } else if (gravity == 1) {
                textX = (availableWidth - textWidth) / 2;
            } else {
                textX = availableWidth - textWidth;
            }
            canvas.drawText(text, textX, getHeight() * 0.8f, textPaint);
            return;
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = getHeight() * 0.8f;

        canvas.save();
        canvas.clipRect(0, 0, availableWidth, getHeight());

        if (animationMode == 0) {
            // 单向无限滚动模式：绘制多份文本
            float originX = availableWidth;
            int maxIndex = (int) Math.ceil((availableWidth + scrollOffset - originX) / singleUnitWidth);
            for (int i = 0; i <= maxIndex; i++) {
                float x = originX + i * singleUnitWidth - scrollOffset;
                if (x + textWidth > 0 && x < availableWidth + textWidth) {
                    canvas.drawText(text, x, textY, textPaint);
                }
            }
        } else {
            // 往返滚动模式：绘制一份文本，通过偏移量控制位置
            // bounceOffset 表示文字向左移动的距离
            float drawX = -bounceOffset;
            canvas.drawText(text, drawX, textY, textPaint);
        }

        canvas.restore();
    }

    /**
     * ⭐ 多行绘制（仅当文本含 '\n' 时调用）：与单行逻辑完全独立。
     * 逐行绘制，各行按 {@link #lineGravity} 对齐（默认居中）；水平滚动时整块一起左右移。
     */
    private void drawMultiline(Canvas canvas) {
        if (lines == null || lineCount <= 0) return;

        int availableWidth = getWidth();
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lh = (lineHeight > 0f) ? lineHeight : (fm.bottom - fm.top);

        // 单行内某行的水平起点：受 lineGravity 控制（0=左 1=居中 2=右）
        // 滚动模式下整块从 -offset 开始，lineGravity 仅作用于静态显示
        if (!needScroll) {
            for (int i = 0; i < lineCount; i++) {
                float tw = textPaint.measureText(lines[i]);
                float x;
                if (lineGravity == 0) {
                    x = 0;
                } else if (lineGravity == 1) {
                    x = (availableWidth - tw) / 2f;
                } else {
                    x = availableWidth - tw;
                }
                canvas.drawText(lines[i], x, i * lh - fm.top, textPaint);
            }
            return;
        }

        canvas.save();
        canvas.clipRect(0, 0, availableWidth, getHeight());

        if (animationMode == 0) {
            float originX = availableWidth;
            int maxIndex = (int) Math.ceil((availableWidth + scrollOffset - originX) / singleUnitWidth);
            for (int i = 0; i <= maxIndex; i++) {
                float x = originX + i * singleUnitWidth - scrollOffset;
                if (x + textWidth > 0 && x < availableWidth + textWidth) {
                    for (int l = 0; l < lineCount; l++) {
                        canvas.drawText(lines[l], x, l * lh - fm.top, textPaint);
                    }
                }
            }
        } else {
            float drawX = -bounceOffset;
            for (int l = 0; l < lineCount; l++) {
                canvas.drawText(lines[l], drawX, l * lh - fm.top, textPaint);
            }
        }

        canvas.restore();
    }

    /** 设置多行各行水平对齐方式（0=左，1=居中，2=右），仅对含 '\n' 的多行文本生效 */
    public void setLineGravity(int gravity) {
        this.lineGravity = gravity;
        invalidate();
    }

    public int getLineGravity() {
        return lineGravity;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        pauseHandler.removeCallbacksAndMessages(null);
    }

    private static class Handler extends android.os.Handler {
        public Handler(Looper looper) {
            super(looper);
        }
    }
}