package com.echo.http.card;

/**
 * 卡列表里那句「正文首句」——🔴 <b>由服务端切，不由前端切</b>。
 *
 * <h2>为什么这不是渲染问题</h2>
 *
 * <p>让前端切首句，意味着列表响应里必须带上<b>全文</b>。而全文本来就不该出现在列表面上：
 * 🔴 <b>前端的 fail-closed 渲染保护的是「页面上画什么」，保护不了「端点下发什么」，
 * 而抓一次包看的是后者</b>（{@code VisibilityMatrix} 类注释已经把这条道理写死了）。</p>
 *
 * <p>所以这是<b>下发面</b>的决定，不是渲染偏好。切完之后
 * {@code cards[]} 里<b>没有 {@code body} 这个键</b>，全文只在卡详情（单卡、过完可见性）里给。</p>
 *
 * <h2>切法</h2>
 *
 * <ol>
 *   <li>在 {@link #MAX_CHARS} 个字之内找第一个句末标点（{@code 。！？!?…} 或换行），
 *       找到就切到那里（<b>含标点</b>，不含换行）；</li>
 *   <li>找不到就切满 {@link #MAX_CHARS} 个字并补一个省略号；</li>
 *   <li>正文本来就短于一句的，原样返回，<b>不补省略号</b>——补了会假装后面还有内容。</li>
 * </ol>
 *
 * <p>🔴 <b>空正文回空串，不回 null、不抛异常、不塞占位文案。</b>卡表装的是所有可发布的回忆
 * （{@code OM2}：AI 回声 + 手写 record + 生命之书），⚠️ <b>后两类多半没有标题、也可能正文很短</b>。
 * 前端的取值链是「{@code title} → {@code excerpt}」，两个都空是<b>预期内的输入</b>，
 * 不是数据缺陷——那种卡靠封面撑，没封面就靠留白撑。</p>
 *
 * <h2>⚠️ 按<b>码点</b>数，不按 {@code char} 数</h2>
 *
 * <p>{@link String#length()} 数的是 UTF-16 码元。emoji 与部分生僻字占两个码元，
 * 🔴 <b>用 {@code substring} 按码元截断会把代理对劈成两半，产出一个非法字符</b>——
 * 它在有些客户端上显示成方块、在有些上让整段文本渲染失败，而服务端日志里一切正常。
 * 本产品的正文里 emoji 很常见（宠物内容），所以这不是理论风险。</p>
 */
public final class CardExcerpt {

    /** 首句上限（码点数）。超出即截断补省略号。 */
    public static final int MAX_CHARS = 54;

    /** 截断时补的省略号。中文排版用单字符 {@code …}，不用三个点。 */
    public static final String ELLIPSIS = "…";

    private CardExcerpt() {
    }

    /**
     * 切出正文首句。
     *
     * @param body 正文，允许 {@code null} 或空
     * @return 首句；🔴 <b>永不为 {@code null}</b>，空正文回空串
     */
    public static String firstSentence(String body) {
        if (body == null) {
            return "";
        }
        String text = body.strip();
        if (text.isEmpty()) {
            return "";
        }

        int taken = 0;
        int i = 0;
        while (i < text.length() && taken < MAX_CHARS) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (isTerminator(cp)) {
                // 换行不进结果，句末标点进结果
                int end = cp == '\n' || cp == '\r' ? i : i + width;
                String cut = text.substring(0, end).strip();
                // 开头就是标点（如正文以「。」起头）时不返回空串，继续往后找
                if (!cut.isEmpty()) {
                    return cut;
                }
            }
            i += width;
            taken++;
        }

        if (i >= text.length()) {
            return text;   // 整段都没到上限也没有句末标点：原样给，不补省略号
        }
        return text.substring(0, i).strip() + ELLIPSIS;
    }

    /**
     * 列表里那张卡的显示标题。
     *
     * <p>🔴 <b>不做「标题为空就跳过这张卡」也不塞占位标题。</b>空标题是合法状态，
     * 前端按「{@code title} 非空则用 {@code title}，否则用 {@code excerpt}」取值——
     * 这条链<b>在服务端不做合并</b>，因为两个字段的语义不同（一个是作者取的名字，
     * 一个是正文的开头），合并之后前端就分不清这张卡到底有没有标题了。</p>
     *
     * @return 去掉首尾空白的标题；null 回空串
     */
    public static String normalizeTitle(String title) {
        return title == null ? "" : title.strip();
    }

    /**
     * 是否句末标点。
     *
     * <p>分号与逗号<b>不算</b>——那会把一句话切在半途。
     * ⚠️ <b>英文句点 {@code .} 也刻意不算</b>：它在小数（{@code 3.5 岁}）、
     * 缩写和省略号里出现得比在句末还多，认它会把「它 3.5 岁那年」切成「它 3.」。</p>
     */
    private static boolean isTerminator(int cp) {
        return switch (cp) {
            case '。', '！', '？', '!', '?', '…', '\n', '\r' -> true;
            default -> false;
        };
    }
}
