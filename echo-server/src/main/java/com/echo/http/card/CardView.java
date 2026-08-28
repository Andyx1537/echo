package com.echo.http.card;

import com.echo.http.model.ModerationModels.MemoryCard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cards[]} 的<b>唯一</b>形状（契约 {@code C-2}）。
 *
 * <h2>🔴 只有一个 card 形状</h2>
 *
 * <p>{@code GET /plaza}（共鸣厅瀑布）与作者主页的卡列表<b>共用本类</b>。
 * 🔴 <b>不许各做一套</b>：两套形状会让前端写两个卡组件，然后它们会缓慢地分叉，
 * 而分叉的那一天没有任何报错——只是某个字段在某个页面上不见了。</p>
 *
 * <p>两处的差别只体现在<b>作者侧字段给不给</b>（{@code pinnedAt} / {@code visibility}），
 * 由 {@link #of} 的 {@code authorView} 参数控制，⚠️ <b>而不是靠换一个形状</b>。</p>
 *
 * <h2>字段表</h2>
 *
 * <table>
 *   <tr><th>键</th><th>类型</th><th>可空</th><th>说明</th></tr>
 *   <tr><td>{@code id}</td><td>string</td><td>否</td>
 *       <td>🔴 <b>cardId</b>，不是 petId。int64 序列化为字符串</td></tr>
 *   <tr><td>{@code petId}</td><td>string</td><td>否</td>
 *       <td>所属窗，前端据此跳窗口页</td></tr>
 *   <tr><td>{@code title}</td><td>string</td><td>🔴 <b>可空（空串）</b></td>
 *       <td>作者取的标题，≤30 字。手写 record 与生命之书多半没有</td></tr>
 *   <tr><td>{@code excerpt}</td><td>string</td><td>可为空串</td>
 *       <td>🔴 正文首句，<b>服务端切</b>（{@link CardExcerpt}）</td></tr>
 *   <tr><td>{@code cover}</td><td>string</td><td>🔴 <b>可缺（空串）</b></td>
 *       <td>封面素材 key。⚠️ 空串是合法值，不是缺陷</td></tr>
 *   <tr><td>{@code hasCover}</td><td>bool</td><td>否</td>
 *       <td>前端选布局用。⚠️ 与 {@code cover} 非空等价，但显式给出——
 *           让「没有图」成为一个<b>可判断的状态</b>而不是一次字符串比较</td></tr>
 *   <tr><td>{@code sourceType}</td><td>string</td><td>否</td>
 *       <td>{@code record|book_page|postcard|echo}，三类卡型的判据</td></tr>
 *   <tr><td>{@code topicIds}</td><td>string[]</td><td>否（可空数组）</td>
 *       <td>主题标签 id，0–3 个。⚠️ <b>不上卡封面</b>（设计线已删 chip）</td></tr>
 *   <tr><td>{@code publishedAt}</td><td>int64|null</td><td>是</td>
 *       <td>作者点发布的时刻</td></tr>
 *   <tr><td>{@code pinnedAt}</td><td>int64|null</td><td>是</td>
 *       <td>🔴 <b>仅作者视图下发</b>。置顶不进共鸣厅公开流（{@link PinPolicy}）</td></tr>
 *   <tr><td>{@code visibility}</td><td>string</td><td>否</td>
 *       <td>🔴 <b>仅作者视图下发</b>，且是<b>生效</b>档位而非卡上那一列</td></tr>
 *   <tr><td>{@code visibilityIntent}</td><td>string</td><td>否</td>
 *       <td>🔴 <b>仅作者视图</b>。作者<b>设定</b>的那一档。与 {@code visibility} 不同时
 *           说明被窗收窄了，前端据此提示「这张卡因为窗收窄而暂时不公开」</td></tr>
 * </table>
 *
 * <h2>🔴 刻意<b>不</b>下发的字段</h2>
 *
 * <ul>
 *   <li><b>{@code body}（正文全文）</b> —— 列表面只给 {@code excerpt}。
 *       全文属卡详情，理由见 {@link CardExcerpt}：前端切首句等于把全文发给了不该看全文的人。</li>
 *   <li><b>卡级入口归因</b> —— {@code RK-H} 的护栏：🔴 <b>一旦下发到任何用户能看见的地方，
 *       当场退化成点赞</b>。不进任何响应体、不展示、不排行、不比较。</li>
 *   <li><b>任何互动计数</b> —— {@code D4}「不显精确数字、不排名」。
 *       暖意走窗级 {@code warmthLevel}，不在卡上。</li>
 *   <li><b>{@code status}</b>（非作者视图）—— 「审核中 / 被打回」是作者与平台之间的事，
 *       陌生人不该看到一张卡的审核状态。</li>
 * </ul>
 */
public final class CardView {

    private CardView() {
    }

    /**
     * 组装一张卡。
     *
     * @param card             卡
     * @param windowVisibility 所属窗的可见性，用于算<b>生效</b>可见性
     * @param topicIds         主题标签 id（已解析好的），null 视为空
     * @param authorView       是否作者本人在看。🔴 {@code false} 时不下发
     *                         {@code pinnedAt}/{@code visibility}/{@code visibilityIntent}
     */
    public static Map<String, Object> of(MemoryCard card, String windowVisibility,
                                         List<String> topicIds, boolean authorView) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 🔴 id 是 cardId。广场改发回忆卡之后前端所有 /windows/:id/... 必须改用 petId
        m.put("id", String.valueOf(card.id));
        m.put("petId", String.valueOf(card.petId));
        m.put("title", CardExcerpt.normalizeTitle(card.title));
        m.put("excerpt", CardExcerpt.firstSentence(card.body));
        String cover = card.coverKey == null ? "" : card.coverKey;
        m.put("cover", cover);
        m.put("hasCover", !cover.isBlank());
        m.put("sourceType", card.sourceType == null ? "" : card.sourceType);
        m.put("topicIds", topicIds == null ? List.of() : topicIds);
        m.put("publishedAt", card.publishedAt);
        if (authorView) {
            m.put("pinnedAt", card.pinnedAt);
            // 🔴 生效档位，不是 card.visibilityIntent —— 窗收窄时后者是偏宽的
            m.put("visibility", CardVisibility.effective(card.visibilityIntent, windowVisibility));
            m.put("visibilityIntent", CardVisibility.normalize(card.visibilityIntent));
        }
        return m;
    }
}
