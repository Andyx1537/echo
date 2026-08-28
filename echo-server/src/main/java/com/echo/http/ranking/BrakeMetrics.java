package com.echo.http.ranking;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 制动效果度量：<b>被刹车的卡与未被刹车的卡，平均还能被投多少次。</b>
 *
 * <h2>🔴 这个指标不是「刹车已实现」的证明，是「以后拿什么调」的依据</h2>
 *
 * <p>制作人已知情：<b>当前这组种子数值的实际效果很小。</b>自然衰减本身就很陡，
 * 在那条曲线上再乘 0.80，退出首页的时点只前移 7 次左右投放（约 99 → 92）。
 * 换句话说，<b>被大量推开的内容与无人推开的内容，待遇几乎一样。</b></p>
 *
 * <p>这是刻意选的：先把可配的机制建起来、拿这组数当初值，等真实拒绝率分布出来再调。
 * 完整算术与推导见 {@code docs/BRAKE-CALIBRATION.md}。</p>
 *
 * <p>🔴 <b>所以要盯着这两个数字。</b>它们如果长期贴在一起，说明刹车是装饰——
 * 那时该做的是调表，而不是把这个指标从看板上摘掉。</p>
 *
 * <p>⚠️ 不要把「有制动的卡占比」当成这个指标的替代。有多少卡被踩了刹车，
 * 和踩下去之后有没有区别，是两个问题；前者好看得多，也没有用。</p>
 */
public final class BrakeMetrics {

    private final AtomicLong brakedCards = new AtomicLong();
    private final AtomicLong brakedRemainingTotal = new AtomicLong();
    private final AtomicLong freeCards = new AtomicLong();
    private final AtomicLong freeRemainingTotal = new AtomicLong();

    /**
     * 记一张卡的观测。
     *
     * @param brakeFactor         该卡当前的制动系数；{@code 1.0} 视为未被刹车
     * @param remainingDeliveries {@link WeightModel#remainingDeliveries} 的结果
     */
    public void observe(double brakeFactor, int remainingDeliveries) {
        if (brakeFactor >= 1.0) {
            freeCards.incrementAndGet();
            freeRemainingTotal.addAndGet(remainingDeliveries);
        } else {
            brakedCards.incrementAndGet();
            brakedRemainingTotal.addAndGet(remainingDeliveries);
        }
    }

    public Snapshot snapshot() {
        long bc = brakedCards.get();
        long fc = freeCards.get();
        double brakedAvg = bc == 0 ? Double.NaN : (double) brakedRemainingTotal.get() / bc;
        double freeAvg = fc == 0 ? Double.NaN : (double) freeRemainingTotal.get() / fc;
        return new Snapshot(bc, brakedAvg, fc, freeAvg);
    }

    public void reset() {
        brakedCards.set(0);
        brakedRemainingTotal.set(0);
        freeCards.set(0);
        freeRemainingTotal.set(0);
    }

    /**
     * @param gap 未被刹车 − 被刹车。<b>这个差值就是刹车的全部效果。</b>
     *            它贴近 0 就说明刹车没在做事；任一侧无样本时为 {@code NaN}
     */
    public record Snapshot(long brakedCards, double brakedAvgRemaining,
                           long unbrakedCards, double unbrakedAvgRemaining) {

        public double gap() {
            return unbrakedAvgRemaining - brakedAvgRemaining;
        }

        /** 相对差：刹车让一张卡少拿了百分之多少的剩余投放。 */
        public double relativeGap() {
            if (Double.isNaN(gap()) || unbrakedAvgRemaining == 0.0) {
                return Double.NaN;
            }
            return gap() / unbrakedAvgRemaining;
        }

        @Override
        public String toString() {
            return String.format(
                    "制动效果：被刹车 %d 张平均还能投 %.1f 次；未被刹车 %d 张平均还能投 %.1f 次；"
                            + "差 %.1f 次（%.1f%%）",
                    brakedCards, brakedAvgRemaining, unbrakedCards, unbrakedAvgRemaining,
                    gap(), relativeGap() * 100);
        }
    }
}
