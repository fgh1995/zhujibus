package org.zjfgh.zhujibus;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class DotMatrixView extends View {

    public static final int PATTERN_ACCESSIBILITY = 0;
    public static final int PATTERN_RIGHT_ARROW = 1;
    public static final int PATTERN_GPS = 2;

    public static final int[][] PATTERN_ACCESSIBILITY_DATA = {
        {0,0,0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0,0,0},
        {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0},
        {0,0,0,1,1,1,0,0,0,0,0,0,0,0,0,0,1,1,1,0,0,0,0},
        {0,0,0,1,1,0,0,1,1,1,1,0,0,0,0,0,0,0,1,1,0,0,0},
        {0,0,1,1,0,0,0,1,1,1,1,0,0,0,0,0,0,0,1,1,1,0,0},
        {0,1,1,1,0,0,0,0,1,1,0,0,0,0,0,0,0,0,0,1,1,1,0},
        {0,1,1,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0,0,1,1,0},
        {1,1,1,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0,0,1,1,1},
        {1,1,0,0,0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0,0,1,1},
        {1,1,0,0,0,0,0,1,1,1,1,0,0,0,0,0,0,0,0,0,0,1,1},
        {1,1,0,0,0,0,0,1,1,0,1,1,0,0,0,0,0,0,0,0,0,1,1},
        {1,1,0,0,0,0,1,1,0,0,1,1,1,1,1,1,1,1,0,0,0,1,1},
        {1,1,0,0,0,0,1,1,0,0,1,1,1,1,1,1,1,1,0,0,0,1,1},
        {1,1,0,0,0,0,1,1,0,0,0,0,0,0,1,1,1,1,0,0,0,1,1},
        {1,1,0,0,0,0,1,1,0,0,0,0,0,0,1,1,1,1,1,0,0,1,1},
        {1,1,1,0,0,0,0,1,1,0,0,0,0,1,1,0,0,1,1,0,0,1,1},
        {1,1,1,0,0,0,0,1,1,1,0,0,1,1,1,0,0,1,1,0,1,1,1},
        {0,1,1,1,0,0,0,0,1,1,1,1,1,0,0,0,0,1,1,0,1,1,0},
        {0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,0},
        {0,0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,0,0},
        {0,0,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,1,1,1,0,0,0},
        {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0},
        {0,0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0}
    };

    public static final int[][] PATTERN_RIGHT_ARROW_DATA = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0}
    };
    public static final int[][] GPS_PATTERN_DATA = {
        {0, 0, 0, 0, 0, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 1, 1, 0, 0},
        {0, 1, 0, 0, 1, 0, 0, 1, 1, 0},
        {1, 1, 1, 0, 0, 1, 1, 0, 1, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 0, 1},
        {1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 1, 1, 1, 1, 1, 1, 1, 0, 0},
        {0, 0, 1, 1, 1, 1, 1, 1, 0, 0},
        {0, 0, 0, 1, 1, 1, 1, 1, 0, 0}
    };

    public static final int PATTERN_UP_DOWN_ARROW = 3;

    public static final int[][] PATTERN_UP_DOWN_ARROW_DATA = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    };

    private int currentPattern = PATTERN_RIGHT_ARROW;
    private int dotColor = 0xFFFFFFFF;
    private float dotSize = 28f;
    private float density;
    private Paint paint;

    public DotMatrixView(Context context) {
        super(context);
        init(context, null);
    }

    public DotMatrixView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public DotMatrixView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        density = getResources().getDisplayMetrics().density;
        if (density == 0) {
            density = 1;
        }
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DotMatrixView);
            int patternIndex = a.getInt(R.styleable.DotMatrixView_dotMatrixPattern, PATTERN_RIGHT_ARROW);
            currentPattern = patternIndex;
            dotColor = a.getColor(R.styleable.DotMatrixView_dotMatrixColor, 0xFFFFFFFF);
            dotSize = a.getDimension(R.styleable.DotMatrixView_dotMatrixDotSize, 28f * density);
            a.recycle();
        }
        paint.setColor(dotColor);
    }

    public void setPattern(int pattern) {
        if (pattern != PATTERN_ACCESSIBILITY && pattern != PATTERN_RIGHT_ARROW && pattern != PATTERN_GPS && pattern != PATTERN_UP_DOWN_ARROW) {
            throw new IllegalArgumentException("Invalid pattern. Use PATTERN_ACCESSIBILITY, PATTERN_RIGHT_ARROW, PATTERN_GPS, or PATTERN_UP_DOWN_ARROW.");
        }
        this.currentPattern = pattern;
        invalidate();
    }

    public int getPattern() {
        return currentPattern;
    }

    public void setDotColor(int color) {
        this.dotColor = color;
        paint.setColor(color);
        invalidate();
    }

    public int getDotColor() {
        return dotColor;
    }

    public void setDotSize(float size) {
        this.dotSize = size;
        requestLayout();
        invalidate();
    }

    public float getDotSize() {
        return dotSize;
    }

    public void setPatternData(int[][] patternData) {
        if (patternData == null || patternData.length == 0) {
            throw new IllegalArgumentException("Pattern data cannot be null or empty.");
        }
        int[][] newPattern = new int[patternData.length][];
        for (int i = 0; i < patternData.length; i++) {
            newPattern[i] = new int[patternData[i].length];
            System.arraycopy(patternData[i], 0, newPattern[i], 0, patternData[i].length);
        }
        this.customPatternData = newPattern;
        this.useCustomPattern = true;
        invalidate();
    }

    private int[][] customPatternData = null;
    private boolean useCustomPattern = false;

    private int[][] getCurrentPatternData() {
        if (useCustomPattern && customPatternData != null) {
            return customPatternData;
        }
        switch (currentPattern) {
            case PATTERN_ACCESSIBILITY:
                return PATTERN_ACCESSIBILITY_DATA;
            case PATTERN_GPS:
                return GPS_PATTERN_DATA;
            case PATTERN_UP_DOWN_ARROW:
                return PATTERN_UP_DOWN_ARROW_DATA;
            case PATTERN_RIGHT_ARROW:
            default:
                return PATTERN_RIGHT_ARROW_DATA;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int patternIndex = currentPattern;
        int[][] pattern;

        if (useCustomPattern && customPatternData != null) {
            pattern = customPatternData;
        } else if (patternIndex == PATTERN_ACCESSIBILITY) {
            pattern = PATTERN_ACCESSIBILITY_DATA;
        } else if (patternIndex == PATTERN_GPS) {
            pattern = GPS_PATTERN_DATA;
        } else if (patternIndex == PATTERN_UP_DOWN_ARROW) {
            pattern = PATTERN_UP_DOWN_ARROW_DATA;
        } else {
            pattern = PATTERN_RIGHT_ARROW_DATA;
        }

        int cols = pattern[0].length;
        int rows = pattern.length;

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int desiredWidth;
        int desiredHeight;

        float intrinsicSize = Math.max(dotSize, 1f);

        if (widthMode == MeasureSpec.EXACTLY) {
            desiredWidth = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            desiredWidth = (int) Math.min(intrinsicSize, widthSize);
        } else {
            desiredWidth = (int) intrinsicSize;
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            desiredHeight = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            desiredHeight = (int) Math.min(intrinsicSize, heightSize);
        } else {
            desiredHeight = (int) intrinsicSize;
        }

        setMeasuredDimension(desiredWidth, desiredHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (isInEditMode()) {
            drawEditModePreview(canvas);
            return;
        }

        if (paint == null) {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dotColor);
        }

        int[][] pattern = getCurrentPatternData();
        if (pattern == null || pattern.length == 0) {
            return;
        }
        int rows = pattern.length;
        int cols = pattern[0].length;

        float viewWidth = getWidth();
        float viewHeight = getHeight();

        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        float singleDotWidth = viewWidth / cols;
        float singleDotHeight = viewHeight / rows;
        float dotDrawSize = Math.min(singleDotWidth, singleDotHeight) - 1;

        float offsetX = (viewWidth - cols * singleDotWidth) / 2;
        float offsetY = (viewHeight - rows * singleDotHeight) / 2;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (pattern[row][col] == 1) {
                    float left = offsetX + col * singleDotWidth;
                    float top = offsetY + row * singleDotHeight;
                    canvas.drawRect(left, top, left + dotDrawSize, top + dotDrawSize, paint);
                }
            }
        }
    }

    private void drawEditModePreview(Canvas canvas) {
        if (paint == null) {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dotColor);
        }

        int[][] pattern = getCurrentPatternData();
        if (pattern == null || pattern.length == 0) {
            return;
        }
        int rows = pattern.length;
        int cols = pattern[0].length;

        float viewWidth = getWidth();
        float viewHeight = getHeight();

        if (viewWidth <= 0 || viewHeight <= 0) {
            viewWidth = dotSize;
            viewHeight = dotSize;
        }

        float singleDotWidth = viewWidth / cols;
        float singleDotHeight = viewHeight / rows;
        float dotDrawSize = Math.min(singleDotWidth, singleDotHeight) - 1;

        float offsetX = (viewWidth - cols * singleDotWidth) / 2;
        float offsetY = (viewHeight - rows * singleDotHeight) / 2;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (pattern[row][col] == 1) {
                    float left = offsetX + col * singleDotWidth;
                    float top = offsetY + row * singleDotHeight;
                    canvas.drawRect(left, top, left + dotDrawSize, top + dotDrawSize, paint);
                }
            }
        }
    }
}
