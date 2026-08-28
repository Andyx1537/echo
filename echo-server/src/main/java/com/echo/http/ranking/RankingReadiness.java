package com.echo.http.ranking;

import com.echo.http.exposure.FeedRequestRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动期自检：排序侧这几个模块<b>能不能真的生效</b>，而不是「有没有实现」。
 *
 * <p>🔴 这个类存在的唯一理由是：{@code n}（累计曝光次数）现在恒为 0，而这件事
 * <b>不以任何报错的形式暴露</b>。后果是 {@code γⁿ = 0.97⁰ = 1}，权重衰减不衰减；
 * {@code BrakeTable} 的最低档要求 {@code n ≥ 40}，制动永不触发；
 * {@code SURGE} 的对比基线也无从建立。</p>
 *
 * <p>🔴 <b>{@code WeightModel}、{@code BrakeModel}、{@code SurgeModel} 三者都是能跑通的纯函数，
 * 但都不是生效的机制。</b>它们的单测全绿，日志里一句异常也没有，
 * 报表上看起来就是「这批内容的权重都很稳」。</p>
 *
 * <h2>🔴 2026-08-27 更正：判据从一条变成两条</h2>
 *
 * <p>本类原先只判「广场发的是卡还是窗」，并写「广场改发回忆卡后本条告警自动消失」。
 * ⚠️ <b>那会造出一处新的假绿</b>：广场改发回忆卡之后告警确实消失了，
 * 而 {@code n} <b>仍然恒为 0</b>——因为 {@code GET /plaza} 是<b>网格层</b>，
 * 而 {@code SPEC-feed-surfaces} 概述第 1 条定的是 🔴 <b>{@code n} 只在全屏层记</b>，
 * 网格里被列出、被滚过一律不计。</p>
 *
 * <p>🔴 <b>那种「告警消失了但失效还在」比原来的失效更坏</b>：原先至少有一条告警一直在喊，
 * 改完之后启动日志变干净了，而读日志的人会据此认为排序侧已经通了。</p>
 *
 * <p>所以判据现在是两条<b>相与</b>：下发的是卡（{@link #warnIfExposureSourceIsDead} 的
 * 第一个参数）<b>且</b>全屏单卡层已实现（第二个参数）。两条都满足才算通。</p>
 */
@Slf4j
public final class RankingReadiness {

    private RankingReadiness() {
    }

    /**
     * 在启动装配完成后调一次。
     *
     * @param plazaFeedKind          广场登记 feed 快照时用的口径，传
     *                               {@link com.echo.http.EchoApi#PLAZA_FEED_KIND}
     * @param immersiveFeedImplemented 全屏单卡层是否已实现（会用
     *                               {@link FeedRequestRegistry#SURFACE_IMMERSIVE} 登记快照），
     *                               传 {@link com.echo.http.EchoApi#IMMERSIVE_FEED_IMPLEMENTED}
     */
    public static void warnIfExposureSourceIsDead(String plazaFeedKind,
                                                  boolean immersiveFeedImplemented) {
        boolean cardFeed = FeedRequestRegistry.KIND_CARD.equals(plazaFeedKind);
        if (cardFeed && immersiveFeedImplemented) {
            log.info("排序自检通过：广场下发回忆卡且全屏层已实现，曝光可入账，n 会正常增长");
            return;
        }
        log.warn("""
                ================= 🔴 排序侧未生效（这不是报错，是失效） =================
                n 恒为 0 → γⁿ 恒为 1。以下三者均为「能跑的纯函数，不是生效的机制」：
                  · 权重衰减 WeightModel —— 不衰减
                  · 负反馈制动 BrakeModel —— 最低档要求 n≥40，永不触发
                  · 热度突增 SurgeModel  —— 无曝光基线可比
                🔴 这个失效不会抛任何异常，报表上看起来一切正常。

                两条前置，都得满足才算通：
                  ① 下发的是回忆卡：{}（判据 EchoApi.PLAZA_FEED_KIND = {}）
                  ② 全屏单卡层已实现：{}
                     ⚠️ SPEC-feed-surfaces 概述 ① 定的是「n 只在全屏层记」，
                        GET /plaza 是网格层（瀑布），它的曝光按规格<u>本来就不该</u>记 n。
                        🔴 所以「广场改发回忆卡」只解除第 ① 条，n 仍然是 0。
                =====================================================================""",
                cardFeed ? "✅ 是" : "❌ 否（发的是 " + plazaFeedKind + "）",
                plazaFeedKind,
                immersiveFeedImplemented ? "✅ 是" : "❌ 否（尚无端点用 immersive 登记快照）");
    }
}
