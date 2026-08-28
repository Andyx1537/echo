package com.echo.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文案词表安全过滤器（COPY-GUIDE §2 服务端强制口，六项定案 #6）。
 *
 * <p>所有对外文字——AI 生成的近况/来信/侧写、错误提示、系统文案——落地前一律过本过滤器：
 * 命中 {@code COPY-GUIDE §2.1} 禁用词即就地改写为 §2.3 推荐词池的温柔替代，绝不放行红线词。
 * 这是"输出端过滤"（COPY-GUIDE §3.A / §4）的最小实现：不依赖 LLM，纯词表替换，保证即便
 * {@link com.echo.infra.llm.ILlmClient} 兜底/异常也不会漏出冰冷或制造内疚的字眼。</p>
 *
 * <p>设计取舍：用有序的"整词/短语 → 替代"表按长度降序替换（先长后短），避免"逝世"被"逝"先命中而漏改；
 * 对制造内疚/攀比类整句短语直接抹除或替换。词表可随 COPY-GUIDE 演进增补。</p>
 */
public final class CopyGuardFilter {

    /**
     * 禁用词 → 推荐替代（COPY-GUIDE §2.1 / §2.3）。使用 {@link LinkedHashMap} 保序，
     * 构造时会再按 key 长度降序遍历，先替换更长的短语。
     */
    private static final Map<String, String> BANNED = new LinkedHashMap<>();

    static {
        // 硬语（过硬/冰冷）→ 温柔状态词
        BANNED.put("去世", "去了那边");
        BANNED.put("逝世", "远行");
        BANNED.put("死亡", "离开");
        BANNED.put("死了", "不在身边了");
        // 露骨生理化
        BANNED.put("尸体", "它留下的");
        BANNED.put("遗体", "它留下的");
        BANNED.put("骨灰", "它留下的");
        // 制造内疚（整句短语，优先于单字）
        BANNED.put("你害死", "");
        BANNED.put("都怪你", "");
        BANNED.put("要是你", "");
        // 绝望/堵死希望
        BANNED.put("永别", "好好想它的时候它就在");
        BANNED.put("再也见不到", "想它的时候它就在");
        // 攀比/名次
        BANNED.put("排名第", "");
        BANNED.put("击败", "");
        BANNED.put("榜首", "");
        // 焦虑+内疚（"你X天没来它很失落/难过"一类）
        //
        // 🔴 2026-08-27 改写：原替代文案是「它一直在等一个和你说话的时刻」，
        //    它自己就同时踩 CR2（把它的状态归因于你不在场）与 DP2（回访诱导）——
        //    ⚠️ 也就是说这道闸把一句制造内疚的话，换成了另一句制造内疚的话。
        //    比原句更坏的地方在于：换完之后下游没有任何一关会再查它，
        //    「已经过闸」本身成了它的通行证。
        //    新替代只描述它此刻的状态，不含等待、不含缺席归因、不指向用户的任何动作。
        BANNED.put("它很失落", "它今天安静了一些");
        BANNED.put("它很难过", "它今天安静了一些");
        // 单字兜底（放最后，长短语已先处理）
        BANNED.put("亡", "离开");
    }

    /** 命中检测用的合并正则（供 {@link #hasBanned(String)} 快速判断）。 */
    private static final Pattern BANNED_PATTERN =
            Pattern.compile("去世|逝世|死亡|死了|尸体|遗体|骨灰|你害死|都怪你|要是你|永别|再也见不到|排名第|击败|榜首|它很失落|它很难过|亡");

    private CopyGuardFilter() {
    }

    /**
     * 对一段对外文案做禁用词过滤改写。
     *
     * @param text 原始文案（可为 null）
     * @return 过滤后的温柔文案；null 原样返回；空串返回空串
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        // 按 key 长度降序替换，先长短语后单字，避免子串误伤
        for (Map.Entry<String, String> e : sortedByLengthDesc()) {
            if (result.contains(e.getKey())) {
                result = result.replace(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /** 是否命中任一禁用词（用于生成侧自检/单测断言）。 */
    public static boolean hasBanned(String text) {
        return text != null && BANNED_PATTERN.matcher(text).find();
    }

    private static Iterable<Map.Entry<String, String>> sortedByLengthDesc() {
        return BANNED.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();
    }
}
