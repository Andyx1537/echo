package com.echo.http.work;

import com.echo.infra.storage.IStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作品的下发形状。
 *
 * <h2>🔴 列表面与详情面是两个方法，不要合并成一个带 flag 的</h2>
 *
 * <p>{@link #listItem} 不下发 {@code body} 全文，{@link #detail} 才下发。
 * 理由同 {@code CardView}：前端的渲染保护挡不住抓包，<b>抓包看的是端点下发了什么</b>。
 * 合并成 {@code of(work, boolean withBody)} 之后，漏传一个 false 就是全文泄漏，
 * 而这个漏传在页面上看不出来。</p>
 */
public final class WorkView {

    /** 列表页正文摘要的字数上限。 */
    private static final int EXCERPT_CHARS = 40;

    private WorkView() {
    }

    /**
     * 瀑布流 / 个人作品页的一条。
     *
     * @param authorView 作者本人在看自己的作品。为 {@code true} 才下发
     *                   {@code status} 与 {@code visibility}——🔴 陌生人不该知道
     *                   这条作品是"审核中"还是"被下架"，那等于把审核结果广播出去
     */
    public static Map<String, Object> listItem(Work w, IStorage storage, boolean authorView) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(w.id));
        m.put("authorId", String.valueOf(w.authorId));
        m.put("mediaType", w.mediaType);
        m.put("mediaUrl", url(storage, w.mediaKey));
        // 图片作品这里是空串。前端据此判断渲染 <img> 还是 <video poster>，
        // 🔴 不要在空的时候回退成 mediaUrl，那会让视频作品在列表里直接开始加载视频流
        m.put("posterUrl", w.isVideo() ? url(storage, w.posterKey) : "");
        m.put("durationMs", w.durationMs);
        m.put("width", w.width);
        m.put("height", w.height);
        m.put("title", w.title == null ? "" : w.title);
        m.put("excerpt", excerpt(w.body));
        m.put("topicIds", parseIds(w.topicIdsJson));
        m.put("publishedAt", w.publishedAt);
        // 🔴 显式标识（S-8）：AI 生成的作品在**列表每一条上**都要能渲染出标记，
        //    不是只在详情页给。所以这个键在列表面必须有，且不分作者视角
        m.put("aiGenerated", w.aiGenerated);
        m.put("fromCard", w.sourceCardId != null);
        if (authorView) {
            m.put("status", w.status);
            m.put("visibility", w.visibility);
            m.put("sourceCardId", w.sourceCardId == null ? null : String.valueOf(w.sourceCardId));
        }
        return m;
    }

    /** 作品详情。含正文全文，调用方必须已经过完可见性判定。 */
    public static Map<String, Object> detail(Work w, IStorage storage, boolean authorView) {
        Map<String, Object> m = listItem(w, storage, authorView);
        m.put("body", w.body == null ? "" : w.body);
        m.put("createdAt", w.createdAt);
        return m;
    }

    private static String url(IStorage storage, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (storage == null) {
            return key;
        }
        String u = storage.externalUrl(key);
        return u == null ? "" : u;
    }

    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String s = body.strip();
        if (s.codePointCount(0, s.length()) <= EXCERPT_CHARS) {
            return s;
        }
        int end = s.offsetByCodePoints(0, EXCERPT_CHARS);
        return s.substring(0, end) + "…";
    }

    /** {@code topicIds} 的 json 文本 → 字符串数组。解析不了就当没有，不抛错。 */
    private static List<String> parseIds(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String t = part.trim().replace("\"", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
