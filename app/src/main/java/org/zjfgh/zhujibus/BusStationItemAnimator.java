package org.zjfgh.zhujibus;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 线路卡片列表的条目动画（标记模式的"动态榜单"用）。
 * <p>
 * 相比默认实现改了两点：
 * <ul>
 *   <li><b>位置交换</b>（{@code notifyItemMoved}）：默认 250ms、匀速缓动，交换过程一闪而过看不清。
 *       这里把时长放慢，并用 {@link AnticipateOvershootInterpolator} 让卡片
 *       <b>先朝来的方向略退一点（预动作）→ 滑到新位置 → 轻微过冲后回位</b>，
 *       于是"谁换到了哪里"在动画过程中能看清楚；</li>
 *   <li><b>内容刷新</b>（{@code notifyItemChanged}）：关掉淡入淡出的 change 动画。
 *       标记模式下是逐条线路刷新的（每条线路数据回来就刷它自己的卡片），
 *       默认的淡入淡出会让卡片一闪一闪，这里改为原地直接刷新。</li>
 * </ul>
 * 动画的收尾（{@link #endAnimation}/{@link #endAnimations}）也跟着做了收口，
 * 保证整表重绑或快速连续换位时不会残留位移。
 */
public class BusStationItemAnimator extends DefaultItemAnimator {

    /** 位移动画时长：放慢到能看清交换过程（默认 250ms 太快） */
    private static final long MOVE_DURATION_MS = 700;
    /** 预动作力度：越大"先往回收"的幅度越明显 */
    private static final float MOVE_ANTICIPATE_TENSION = 1.6f;
    /** 回位过冲力度：越大到位的弹回越明显 */
    private static final float MOVE_OVERSHOOT_TENSION = 1.2f;

    /** 正在播放的位移动画：用于连续换位 / 列表重建时收尾 */
    private final Map<RecyclerView.ViewHolder, View> runningMoves = new HashMap<>();

    public BusStationItemAnimator() {
        setMoveDuration(MOVE_DURATION_MS);
        // 逐条刷新时不要闪：内容变化不做淡入淡出
        setSupportsChangeAnimations(false);
    }

    @Override
    public boolean animateMove(@NonNull RecyclerView.ViewHolder holder,
                              int fromX, int fromY, int toX, int toY) {
        View view = holder.itemView;
        if (view == null) return super.animateMove(holder, fromX, fromY, toX, toY);

        // 同一张卡片上一次换位还没播完：先收尾，避免两段位移叠加
        endRunningMove(holder);

        int startY = fromY + Math.round(view.getTranslationY());
        int deltaY = toY - startY;
        if (deltaY == 0) {
            dispatchMoveFinished(holder);
            return false;
        }

        // 先把卡片"按回"原位置（translation = -deltaY），再动画到 0（新位置）；
        // 插值器自带"先退一点、再冲过头、最后回位"，交换的动感就出来了。
        view.setTranslationX(0f);
        view.setTranslationY(-deltaY);
        view.animate()
                .translationY(0f)
                .setDuration(MOVE_DURATION_MS)
                .setInterpolator(new AnticipateOvershootInterpolator(
                        MOVE_ANTICIPATE_TENSION, MOVE_OVERSHOOT_TENSION))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.animate().setListener(null);
                        runningMoves.remove(holder);
                        dispatchMoveFinished(holder);
                    }
                })
                .start();
        runningMoves.put(holder, view);
        dispatchMoveStarting(holder);
        return true;
    }

    @Override
    public void endAnimation(@NonNull RecyclerView.ViewHolder item) {
        endRunningMove(item);
        super.endAnimation(item);
    }

    @Override
    public void endAnimations() {
        for (RecyclerView.ViewHolder holder : new ArrayList<>(runningMoves.keySet())) {
            endRunningMove(holder);
        }
        super.endAnimations();
    }

    @Override
    public boolean isRunning() {
        return super.isRunning() || !runningMoves.isEmpty();
    }

    /** 立刻结束某张卡片正在播放的位移，并把位移清零（保证它落在最终位置上） */
    private void endRunningMove(RecyclerView.ViewHolder holder) {
        if (holder == null) return;
        View view = runningMoves.remove(holder);
        if (view == null) return;
        view.animate().setListener(null).cancel();
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        dispatchMoveFinished(holder);
    }
}
