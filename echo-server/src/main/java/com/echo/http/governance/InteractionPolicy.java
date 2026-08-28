package com.echo.http.governance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 单条内容的互动开关（{@code SPEC-publish-and-ops}「窗口主人可关闭互动」，{@code S3} 五项之一）。
 *
 * <p>存储形状见 {@code t_memory_card.interaction}：
 * {@code {"remember":bool,"footprint":bool,"leaveWords":bool,"meToo":bool,"sharedFlower":bool}}。
 * 🔴 <b>缺键 = 开启</b>（默认全开）。</p>
 *
 * <h2>🔴 全局关 &gt; 单条开</h2>
 *
 * <p>判定顺序<b>先全局、后单条</b>，且单条只能<b>关</b>不能<b>开</b>：</p>
 *
 * <pre>
 *   enabled = 全局开关(该能力) AND 单条设置(该能力)
 * </pre>
 *
 * <p>这个与运算的方向是要害。做成 {@code 单条设置 OR 全局} 或者「单条显式 true 时覆盖全局」，
 * 就等于让<b>任何一个作者都能反向打开一个被全局关掉的能力</b>——而全局开关关着的原因
 * 恰恰是治理能力还没就绪（{@code S13}）。那样一来 {@code S13} 的整套前置校验会被一条
 * 用户可写的 json 绕过去，且流水上看起来只是作者改了个设置。</p>
 */
@Slf4j
public final class InteractionPolicy {

    /** 互动能力键名。与 {@code t_resonance_type.slug} 的语义对应，但用驼峰以贴合 json 出参。 */
    public static final String REMEMBER = "remember";           // R1 记得
    public static final String FOOTPRINT = "footprint";         // R2 留脚印
    public static final String LEAVE_WORDS = "leaveWords";      // R3 留一句话
    public static final String ME_TOO = "meToo";                // R4 我也想起一件事
    public static final String SHARED_FLOWER = "sharedFlower";  // R5 共同留一束心意

    public static final Set<String> KEYS =
            Set.of(REMEMBER, FOOTPRINT, LEAVE_WORDS, ME_TOO, SHARED_FLOWER);

    /**
     * 受全局开关门控的能力 → 开关 key。
     *
     * <p>{@code R3 留一句话} 与 {@code R4 我也想起一件事} 都是自由文本，同受
     * {@code S13} 的留一句话开关门控（裁定原文：「{@code R4} 我也想起一件事同样是自由文本」）。</p>
     */
    private static final Map<String, String> GLOBAL_GATE = Map.of(
            LEAVE_WORDS, FeatureSwitchService.KEY_LEAVE_WORDS,
            ME_TOO, FeatureSwitchService.KEY_LEAVE_WORDS);

    private final FeatureSwitchService switches;

    public InteractionPolicy(FeatureSwitchService switches) {
        this.switches = switches;
    }

    /**
     * 该能力在这张卡上是否可用。
     *
     * @param interactionJson {@code t_memory_card.interaction} 原文；null/空 = 作者未设置 = 全开
     */
    public boolean enabled(String key, String interactionJson) {
        // ① 先看全局。🔴 全局关则单条无论怎么设都不可用
        String gate = GLOBAL_GATE.get(key);
        if (gate != null && !switches.isEnabled(gate)) {
            return false;
        }
        // ② 再看作者的单条设置（只能关，不能反向开——上一步已经 return 掉了）
        return authorAllows(key, interactionJson);
    }

    /** 作者的单条设置是否允许（不含全局判断）。 */
    public boolean authorAllows(String key, String interactionJson) {
        JsonObject o = parse(interactionJson);
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return true; // 缺键 = 开启
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("interaction json 里 {} 不是布尔值，按开启处理: {}", key, interactionJson);
            return true;
        }
    }

    /**
     * 作者更新单条设置后的 json。
     *
     * <p>🔴 只接受白名单键，未知键丢弃——否则 {@code interaction} 会变成一个用户可写的
     * 任意 json 存储位。</p>
     */
    public String merge(String existingJson, Map<String, Boolean> patch) {
        JsonObject o = parse(existingJson);
        JsonObject next = new JsonObject();
        if (o != null) {
            for (String k : KEYS) {
                if (o.has(k) && !o.get(k).isJsonNull()) {
                    next.addProperty(k, authorAllows(k, existingJson));
                }
            }
        }
        for (Map.Entry<String, Boolean> e : patch.entrySet()) {
            if (KEYS.contains(e.getKey()) && e.getValue() != null) {
                next.addProperty(e.getKey(), e.getValue());
            }
        }
        return next.toString();
    }

    /**
     * 这张卡上每项能力的最终可用状态（含全局门控）。
     *
     * <p>前端据此渲染/隐藏互动入口——与 {@code A7} 的能力查询同一思路：
     * <b>让前端能提前知道"这里没有这个入口"，而不是等用户点了再报错</b>。</p>
     */
    public Map<String, Boolean> effective(String interactionJson) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : new String[]{REMEMBER, FOOTPRINT, LEAVE_WORDS, ME_TOO, SHARED_FLOWER}) {
            out.put(k, enabled(k, interactionJson));
        }
        return out;
    }

    private static JsonObject parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            log.warn("interaction json 解析失败，按全开处理: {}", json);
            return null;
        }
    }
}
