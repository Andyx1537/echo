package com.echo.http.pet;

import com.echo.http.model.Models.PetEcho;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * {@code POST /pet/me/visit} 的近况节流：🔴 <b>一天一条，同一天再回访给同一条</b>。
 *
 * <h2>阈值取「一天一条」的依据</h2>
 *
 * <p>🔴 <b>不是拍一个数，而是这个数已经写在产品定义里了。</b>该端点自己的注释就是
 * 「拉取 / 生成<b>今日</b>近况」，{@code GET /pet/me/echoes} 是「它的<b>近况流</b>」——
 * 「今日近况」这四个字本身就规定了一天只有一条。节流之前的行为
 * （每次回访产一条）与它自己的措辞是矛盾的。</p>
 *
 * <p>所以这里<b>没有引入新的产品参数</b>，不需要后台可配，也不需要裁定：
 * ⚠️ <b>可配的阈值反而会让「今日近况」变成一个可以一天有五条的东西</b>，
 * 那是把一个定义问题降级成了一个配置问题。</p>
 *
 * <h2>为什么不是「N 分钟一条」或「每天上限 M 条」</h2>
 *
 * <ul>
 *   <li><b>时间窗（如 30 分钟一条）</b>：用户一天点 10 次仍然可能产出 5 条，
 *       ⚠️ <b>只是把 10 行雷同内容摊薄成 5 行，没有解决「雷同」</b>。
 *       而且它引入一个用户感知不到的隐形冷却 —— 同样的动作有时有反馈有时没有。</li>
 *   <li><b>每天上限 M &gt; 1</b>：同上，且要回答「第 2 条和第 1 条有什么不同」——
 *       🔴 <b>没有答案</b>，两条都是同一天同一只宠物同一份档案生成的，
 *       它们雷同不是巧合，是必然。</li>
 * </ul>
 *
 * <h2>同一天再回访时的行为：给<b>同一条</b>，不给空</h2>
 *
 * <p>🔴 <b>刻意不返回空列表。</b>回访是这个产品里最核心的动作，而
 * ⚠️ <b>「你来了，但今天没有近况」这一屏会被读成「它今天没有消息」</b>，
 * 那是往缺席的方向推（{@code CR2} 的反面）。所以同一天再回访拿到的是<b>今天那一条</b>，
 * 屏幕上永远有内容。</p>
 *
 * <p>额外给一个 {@code echoGenerated} 布尔，前端<b>可以</b>据它决定要不要做
 * 「新内容」的动效，但<b>不下发任何「你今天已经来过 N 次」的计数</b> ——
 * 那会变成对回访行为的计量反馈，属 {@code DP2}。</p>
 *
 * <h2>⚠️ 天的边界与 {@code EchoApi.today()} 同口径</h2>
 *
 * <p>用 {@code yyyyMMdd} 整数 + {@link ZoneId#systemDefault()}，与
 * {@code EchoApi.today()} / {@code ExposureRecorder.today()} 完全一致。
 * 🔴 <b>不要在这里另起一套天口径</b>：同一个进程里两套「今天」的分界点，
 * 会让「献花额度还在但近况已经刷新了」这类错位只在跨零点的那几分钟出现，
 * ⚠️ <b>而那是最难复现、也最不会有人报上来的时段。</b></p>
 */
public final class VisitEchoThrottle {

    private VisitEchoThrottle() {
    }

    /** {@code yyyyMMdd}。与 {@code EchoApi.today()} 同一算法，改一处要一起改。 */
    public static int dayOf(long epochMs, ZoneId zone) {
        LocalDate d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    /**
     * 今天已经有的那条近况；没有则 {@code null}（此时才该生成）。
     *
     * <p>同一天有多条（节流上线前的历史数据）时返回<b>最新</b>的那条，
     * ⚠️ <b>不做清理</b>：历史数据留着，节流只管住往后不再增长。</p>
     *
     * @param echoes 这只宠物的全部近况，顺序不限
     * @param now    当前时刻
     * @param zone   天的分界所在时区，传 {@link ZoneId#systemDefault()}
     */
    public static PetEcho todaysEcho(List<PetEcho> echoes, long now, ZoneId zone) {
        if (echoes == null || echoes.isEmpty()) {
            return null;
        }
        int today = dayOf(now, zone);
        PetEcho best = null;
        for (PetEcho e : echoes) {
            if (e == null || dayOf(e.createdAt, zone) != today) {
                continue;
            }
            if (best == null || e.createdAt > best.createdAt) {
                best = e;
            }
        }
        return best;
    }
}
