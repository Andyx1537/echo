package com.echo.http.visibility;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 🔴 <b>逐块可见性的唯一配置点。</b>要改「哪一档身份能看哪一块」，只改这一张表。
 *
 * <p>矩阵来源：{@code RESEARCH-window-content-ownership §4.1}（{@code W1}–{@code W15}），
 * 上游是 {@code SPEC-interaction-flow §10}（{@code N3}）。表里每一行都在
 * {@link WindowBlock} 的常量注释里留了「当初凭什么定的」。</p>
 *
 * <h2>为什么是白名单</h2>
 * <p>🔴 <b>未被显式判为「该身份可见」的，一律不下发。</b>不写成黑名单的理由不是风格偏好，
 * 是两种写法的出错代价完全不对称：漏标一个字段，黑名单下是一次隐私事故（而这类事故通常要等
 * 用户投诉才会被发现），白名单下只是少发了一块内容（下一次联调就会有人报）。</p>
 *
 * <h2>它保护不了什么</h2>
 * <p>🔴 <b>这张表只管「响应体里放什么」，不管「这个端点该不该被调到」。</b>
 * 拉黑与窗的可见性三档要在<b>进这张表之前</b>判掉（{@code §4.0} 的判定顺序：
 * ①任一方向拉黑 → 整页不可达；②可见性三档不允许 → 整页不可达；③才逐块裁剪）。
 * 前端的 fail-closed 渲染同理——它保护的是「页面上画什么」，
 * 保护不了「端点下发什么」，而抓一次包看的是后者。</p>
 */
public final class VisibilityMatrix {

    /**
     * 🔴🔴 <b>暂定项，等截图确认 —— 要回退只改这一个 {@code boolean}，别处一行都不用动。</b>
     *
     * <p>{@code W11}「明信片<b>已解锁</b>位」对<b>陌生人</b>是否可见。当前 = {@code true}（可见）。</p>
     *
     * <p><b>暂定为可见的理由</b>（{@code RESEARCH-window-content-ownership §4.4}）：已解锁位就是
     * 一张图 + 一句 caption + 日期，与一张公开的回忆卡在敏感度上分不出来；而整墙不下发会让
     * 陌生人那一屏只剩封面和名字（{@code §10.3} 自己写了这一条）。</p>
     *
     * <p>⚠️ <b>为什么是「暂定」</b>：另有一条线在出两案的真实截图对比，产品负责人要看图再拍。
     * 所以这一格<b>刻意收成一个开关</b>，而不是把「陌生人能看明信片」这个判断散进别处——
     * 散出去之后回退要改的就不止一行了。</p>
     *
     * <p>🔴 <b>这个开关不管未解锁位。</b>{@code W12}（未解锁位 + {@code unlockHint}）已按
     * 「仅本人」收紧，与本开关无关，翻这个开关<b>不会</b>把 {@code unlockHint} 放出去。</p>
     */
    private static final boolean POSTCARD_UNLOCKED_VISIBLE_TO_STRANGER = true;

    /**
     * 白名单本体：内容块 → 允许看到它的身份集合。
     *
     * <p>🔴 <b>没列进来的块，对谁都不可见</b>（{@link #visible} 取不到时回空集）。
     * 所以新增一块内容而忘了配表，表现是「少发了一块」，不是「多发了一块」。</p>
     */
    private static final Map<WindowBlock, Set<ViewerRole>> ALLOW = buildAllow();

    private VisibilityMatrix() {
    }

    private static Map<WindowBlock, Set<ViewerRole>> buildAllow() {
        Map<WindowBlock, Set<ViewerRole>> m = new EnumMap<>(WindowBlock.class);

        // —— 三档全可见 ——
        // W1 主语是它、材料是 owner 自己写的，不含第三方信息也不含行为度量：这五块里唯一「干净」的一块。
        m.put(WindowBlock.LIFE_BOOK, all());
        // W2 「它被记挂着」这个事实本身，是共鸣厅唯一的社会性信号；且它今天已在广场每张卡上。
        m.put(WindowBlock.WARMTH_LEVEL, all());
        // W4 访客自己的状态，不是窗的属性。
        m.put(WindowBlock.ME_REMEMBERED, all());

        // —— 非本人可见（本人自己不出：不能记得自己、不能给自己献花）——
        m.put(WindowBlock.REMEMBER_BUTTON, EnumSet.of(ViewerRole.FRIEND, ViewerRole.STRANGER));
        m.put(WindowBlock.FLOWER_BUTTON, EnumSet.of(ViewerRole.FRIEND, ViewerRole.STRANGER));

        // —— 本人 + 亲友 ——
        // W13 沿用 N3：陌生人不出。⚠️ 亲友一侧是否也收掉是 Q-12，本表沿用现状（可见），不作扩大解释。
        m.put(WindowBlock.TEMPERATURE, EnumSet.of(ViewerRole.OWNER, ViewerRole.FRIEND));

        // W11 已解锁的明信片。陌生人那一档是开关控的暂定项，见 POSTCARD_UNLOCKED_VISIBLE_TO_STRANGER。
        m.put(WindowBlock.POSTCARD_UNLOCKED, POSTCARD_UNLOCKED_VISIBLE_TO_STRANGER
                ? all()
                : EnumSet.of(ViewerRole.OWNER, ViewerRole.FRIEND));

        // —— 仅本人 ——
        // W3 面孔墙。已裁定：本人保留可见（不收回 owner 今天已经能看到的面，且他本来就有
        //    rememberFacesCount 精确数，头像不构成新增信息量）；亲友与陌生人不下发——
        //    「静默誓约」要防的是第三方身份被暴露给别人，不是防主人自己。
        //    🔴 accountId 一律不下发，换成 isMe 布尔（见 EchoApi.facesView）。
        m.put(WindowBlock.REMEMBER_FACES, EnumSet.of(ViewerRole.OWNER));
        // W12 unlockHint 是「你还差多久」的判语，比温度更该收——这是对 §10.3 B15「仅亲友」的收紧。
        m.put(WindowBlock.POSTCARD_LOCKED, EnumSet.of(ViewerRole.OWNER));
        m.put(WindowBlock.VISIBILITY_SETTING, EnumSet.of(ViewerRole.OWNER));
        m.put(WindowBlock.RECENT_ECHO_LIST, EnumSet.of(ViewerRole.OWNER));
        m.put(WindowBlock.FLOWERS_RECEIVED, EnumSet.of(ViewerRole.OWNER));
        m.put(WindowBlock.SEEN_COUNT, EnumSet.of(ViewerRole.OWNER));

        return Map.copyOf(m);
    }

    private static Set<ViewerRole> all() {
        return EnumSet.allOf(ViewerRole.class);
    }

    /**
     * {@code role} 能不能看到 {@code block}。
     *
     * <p>🔴 未配表的块回 {@code false}（fail-closed），见类注释。</p>
     */
    public static boolean visible(WindowBlock block, ViewerRole role) {
        return ALLOW.getOrDefault(block, Set.of()).contains(role);
    }
}
