package org.zjfgh.zhujibus.view;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.zjfgh.zhujibus.R;

import java.util.ArrayList;
import java.util.List;

public class AutoScrollTextView extends FrameLayout {

    private TextView tvCurrent;
    private TextView tvNext;
    private List<String> items = new ArrayList<>();
    private int currentIndex = 0;
    private int scrollDuration = 800;
    private int intervalDuration = 3000;
    private boolean isScrolling = false;
    private ValueAnimator animator;
    private int measuredHeight = 0;

    public AutoScrollTextView(Context context) {
        super(context);
        init(null);
    }

    public AutoScrollTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public AutoScrollTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        // 改用FrameLayout，让两个TextView可以重叠
        setClipChildren(false);
        setClipToPadding(false);

        // 解析自定义属性
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.AutoScrollTextView);
            scrollDuration = ta.getInt(R.styleable.AutoScrollTextView_scrollDuration, 800);
            intervalDuration = ta.getInt(R.styleable.AutoScrollTextView_intervalDuration, 3000);
            ta.recycle();
        }

        // 创建两个TextView，都放在FrameLayout中，位置重叠
        tvCurrent = createTextView();
        tvNext = createTextView();

        // 初始时tvCurrent可见，tvNext隐藏在底部
        tvCurrent.setAlpha(1f);
        tvCurrent.setTranslationY(0);
        tvNext.setAlpha(0f);
        tvNext.setTranslationY(100); // 先设置一个大致的值，会在测量后更新

        addView(tvCurrent);
        addView(tvNext);
    }

    private TextView createTextView() {
        TextView tv = new TextView(getContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_VERTICAL;
        tv.setLayoutParams(params);
        tv.setTextSize(15);
        tv.setTextColor(Color.BLACK);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setSingleLine(false);  // 明确设置为多行模式
        tv.setPadding(0, 8, 0, 8);
        return tv;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 先测量子View
        measureChildren(widthMeasureSpec, heightMeasureSpec);

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);

        if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED) {
            // 使用tvCurrent的高度作为控件高度
            if (tvCurrent.getMeasuredHeight() > 0) {
                measuredHeight = tvCurrent.getMeasuredHeight();
            } else {
                measuredHeight = dp2px(50);
            }
            setMeasuredDimension(width, measuredHeight);
        } else {
            measuredHeight = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, measuredHeight);
        }
    }

    /**
     * 设置公告数据列表
     */
    public void setItems(List<String> items) {
        stopScroll();
        this.items.clear();
        if (items != null) {
            this.items.addAll(items);
        }
        this.currentIndex = 0;

        if (!this.items.isEmpty()) {
            tvCurrent.setText(this.items.get(0));
            tvCurrent.setAlpha(1f);
            tvCurrent.setTranslationY(0);
            tvNext.setText("");
            tvNext.setAlpha(0f);
            tvNext.setTranslationY(measuredHeight);

            requestLayout();
        }

        if (this.items.size() > 1) {
            startScroll();
        }
    }

    /**
     * 设置单条公告
     */
    public void setText(String text) {
        List<String> single = new ArrayList<>();
        single.add(text);
        setItems(single);
    }

    /**
     * 开始滚动
     */
    public void startScroll() {
        if (isScrolling || items.size() <= 1) return;
        isScrolling = true;
        startNextScroll();
    }

    /**
     * 停止滚动
     */
    public void stopScroll() {
        isScrolling = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        tvCurrent.setTranslationY(0);
        tvCurrent.setAlpha(1f);
        tvNext.setTranslationY(measuredHeight);
        tvNext.setAlpha(0f);
    }

    private void startNextScroll() {
        if (!isScrolling || items.size() <= 1) return;

        // 停留一段时间后开始滚动
        postDelayed(() -> {
            if (!isScrolling || items.size() <= 1) return;

            int nextIndex = (currentIndex + 1) % items.size();
            String nextText = items.get(nextIndex);

            // 设置下一个文本内容，并将其放在底部
            tvNext.setText(nextText);
            tvNext.setAlpha(0f);
            tvNext.setTranslationY(measuredHeight);

            // 执行滚动动画
            animateScroll(() -> {
                // 动画完成，交换两个TextView的角色
                currentIndex = nextIndex;

                // 交换TextView的引用
                TextView temp = tvCurrent;
                tvCurrent = tvNext;
                tvNext = temp;

                // 重置tvNext（原来的tvCurrent）到初始状态
                tvNext.setText("");
                tvNext.setAlpha(0f);
                tvNext.setTranslationY(measuredHeight);

                // 继续下一次滚动
                if (isScrolling && items.size() > 1) {
                    startNextScroll();
                }
            });

        }, intervalDuration);
    }

    /**
     * 执行滚动动画：当前文本从中间向上滚出，下一个文本从底部滚入到中间
     */
    private void animateScroll(Runnable onComplete) {
        if (animator != null) {
            animator.cancel();
        }

        final int height = measuredHeight;

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(scrollDuration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();

            // 当前文本：从中间(0) 向上移动到顶部(-height)，透明度从1到0
            tvCurrent.setTranslationY(-height * progress);
            tvCurrent.setAlpha(1 - progress);

            // 下一个文本：从底部(height) 移动到中间(0)，透明度从0到1
            tvNext.setTranslationY(height * (1 - progress));
            tvNext.setAlpha(progress);
        });

        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                // 确保初始状态正确
                tvCurrent.setTranslationY(0);
                tvCurrent.setAlpha(1f);
                tvNext.setTranslationY(height);
                tvNext.setAlpha(0f);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) {
                    onComplete.run();
                }
                animator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // 取消时恢复状态
                tvCurrent.setTranslationY(0);
                tvCurrent.setAlpha(1f);
                tvNext.setTranslationY(height);
                tvNext.setAlpha(0f);
                animator = null;
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
            }
        });

        animator.start();
    }

    private int dp2px(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScroll();
    }
}