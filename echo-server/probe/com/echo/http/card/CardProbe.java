package com.echo.http.card;

import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** cards[] 契约 / 卡级可见性 / 置顶 的真断言。javac+java 直跑，不走 Maven。 */
public final class CardProbe {

    static int failures = 0;

    public static void main(String[] args) {
        excerpt();
        visibility();
        pinning();
        view();

        System.out.println(failures == 0
                ? "\n=== 全部通过 ==="
                : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 首句由服务端切
    static void excerpt() {
        section("正文首句（服务端切）");
        eq("空正文回空串，不回 null", "", CardExcerpt.firstSentence(null));
        eq("空白正文回空串", "", CardExcerpt.firstSentence("   \n  "));
        eq("句号切一句（含标点）", "它今天又睡在阳台了。",
                CardExcerpt.firstSentence("它今天又睡在阳台了。太阳很好，风也很轻。"));
        eq("问号也算句末", "你还记得那年冬天吗？",
                CardExcerpt.firstSentence("你还记得那年冬天吗？我记得。"));
        eq("换行算句末但不进结果", "第一行",
                CardExcerpt.firstSentence("第一行\n第二行"));
        eq("短于一句原样返回、不补省略号", "只有几个字",
                CardExcerpt.firstSentence("只有几个字"));

        // 🔴 英文句点不算：小数会被切在半途
        eq("英文句点不切（小数）", "它 3.5 岁那年学会了握手",
                CardExcerpt.firstSentence("它 3.5 岁那年学会了握手"));

        // 🔴 超长无标点 → 截断补省略号，且按码点数
        String long54 = "字".repeat(54);
        eq("刚好 54 字不截断", long54, CardExcerpt.firstSentence(long54));
        String long100 = "字".repeat(100);
        String cut = CardExcerpt.firstSentence(long100);
        check("超长截断补省略号", cut.endsWith(CardExcerpt.ELLIPSIS));
        eq("截断后正好 54 字 + 省略号", 54, cut.codePointCount(0, cut.length()) - 1);

        // 🔴 emoji：按码点切，不许劈开代理对
        String emoji = "🐕".repeat(100);
        String ecut = CardExcerpt.firstSentence(emoji);
        eq("emoji 也按码点切成 54 个", 54, ecut.codePointCount(0, ecut.length()) - 1);
        check("🔴 没有劈开代理对（无孤立 surrogate）", noLoneSurrogate(ecut));

        eq("标题 null 归一化成空串", "", CardExcerpt.normalizeTitle(null));
        eq("标题去首尾空白", "麦麦", CardExcerpt.normalizeTitle("  麦麦  "));
    }

    static boolean noLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------- 卡不得宽于窗（读时取交集）
    static void visibility() {
        section("卡级可见性三档 + 卡不得宽于窗");
        eq("档位序 private=0", 0, CardVisibility.rank("private"));
        eq("档位序 friends=1", 1, CardVisibility.rank("friends"));
        eq("档位序 public=2", 2, CardVisibility.rank("public"));
        eq("🔴 不认识的取值按最窄算（fail-closed）", 0, CardVisibility.rank("everyone"));
        eq("null 也按最窄算", 0, CardVisibility.rank(null));

        // 取交集
        eq("卡公开 + 窗公开 → 公开", "public",
                CardVisibility.effective("public", "public"));
        eq("🔴 卡公开 + 窗收窄成亲友 → 亲友（窗关了内容不许留在外面）", "friends",
                CardVisibility.effective("public", "friends"));
        eq("卡公开 + 窗私密 → 私密", "private",
                CardVisibility.effective("public", "private"));
        eq("卡亲友 + 窗公开 → 亲友（卡更窄时取卡）", "friends",
                CardVisibility.effective("friends", "public"));

        check("卡公开 + 窗亲友：生效不公开", !CardVisibility.effectivelyPublic("public", "friends"));
        check("卡公开 + 窗公开：生效公开", CardVisibility.effectivelyPublic("public", "public"));

        // 🔴 关键：窗改回来之后，作者原意恢复
        String cardIntent = "public";
        eq("窗收窄期间生效为 friends", "friends",
                CardVisibility.effective(cardIntent, "friends"));
        eq("🔴 窗改回 public 之后原样恢复公开（这就是取交集而非级联的理由）", "public",
                CardVisibility.effective(cardIntent, "public"));

        // 写时闸门
        check("卡比窗宽 → widerThanWindow 为真",
                CardVisibility.widerThanWindow("public", "friends"));
        check("卡与窗同档 → 不算宽",
                !CardVisibility.widerThanWindow("friends", "friends"));
        boolean threw = false;
        try {
            CardVisibility.assertNotWiderThanWindow("public", "friends");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("🔴 写时拒绝：卡设得比窗宽必须抛", threw);
        try {
            CardVisibility.assertNotWiderThanWindow("friends", "public");
            check("卡窄于窗不抛", true);
        } catch (IllegalArgumentException e) {
            check("卡窄于窗不抛", false);
        }
    }

    // ------------------------------------------------------------------ 置顶
    static void pinning() {
        section("置顶（上限 / 条件 / 排序 / 自动解除）");
        PinPolicy p = new PinPolicy();
        eq("默认上限 3", 3, p.maxPinned());
        eq("🔴 上限配成 0 回落到 3，不当成不限", 3, new PinPolicy(0).maxPinned());
        eq("上限配成 -1 也回落", 3, new PinPolicy(-1).maxPinned());
        eq("后台可配成 5", 5, new PinPolicy(5).maxPinned());

        check("上限判定：已置顶 3 张即满", p.atCapacity(3));
        check("已置顶 2 张未满", !p.atCapacity(2));

        // 可置顶条件
        MemoryCard ok = card(1, CardStatus.PUBLIC, "public", 1000L);
        check("public + 已过审 + 窗公开 → 可置顶", p.pinnable(ok, "public"));

        MemoryCard notReviewed = card(2, CardStatus.PUBLIC, "public", null);
        check("🔴 未过审（reviewedAt=null）不可置顶", !p.pinnable(notReviewed, "public"));

        MemoryCard takendown = card(3, CardStatus.TAKENDOWN, "public", 1000L);
        check("🔴 已下架不可置顶（reviewedAt 非空但 status 不是 public）",
                !p.pinnable(takendown, "public"));

        MemoryCard friendsCard = card(4, CardStatus.PUBLIC, "friends", 1000L);
        check("非公开不可置顶", !p.pinnable(friendsCard, "public"));

        MemoryCard okButWindowNarrow = card(5, CardStatus.PUBLIC, "public", 1000L);
        check("🔴 窗收窄后生效不公开 → 不可置顶（判生效档位不是卡那一列）",
                !p.pinnable(okButWindowNarrow, "friends"));

        // 自动解除
        MemoryCard pinned = card(6, CardStatus.PUBLIC, "public", 1000L);
        pinned.pinnedAt = 5000L;
        check("仍然合格 → 不该解除", !p.shouldUnpin(pinned, "public"));
        pinned.status = CardStatus.REJECTED;
        check("🔴 审核打回 → 该自动解除", p.shouldUnpin(pinned, "public"));
        MemoryCard neverPinned = card(7, CardStatus.REJECTED, "public", 1000L);
        check("没置顶过的不需要解除", !p.shouldUnpin(neverPinned, "public"));

        // 排序：pinnedAt DESC NULLS LAST, publishedAt DESC
        MemoryCard a = card(101, CardStatus.PUBLIC, "public", 1L);
        a.publishedAt = 100L;
        MemoryCard b = card(102, CardStatus.PUBLIC, "public", 1L);
        b.publishedAt = 300L;
        MemoryCard c = card(103, CardStatus.PUBLIC, "public", 1L);
        c.publishedAt = 200L;
        c.pinnedAt = 50L;
        MemoryCard d = card(104, CardStatus.PUBLIC, "public", 1L);
        d.publishedAt = 50L;
        d.pinnedAt = 90L;

        List<MemoryCard> list = new ArrayList<>(List.of(a, b, c, d));
        list.sort(PinPolicy.ORDER);
        List<Long> ids = list.stream().map(x -> x.id).toList();
        // d(pin 90) → c(pin 50) → b(pub 300) → a(pub 100)
        eq("🔴 排序 = pinnedAt DESC NULLS LAST, publishedAt DESC",
                List.of(104L, 103L, 102L, 101L), ids);
        check("🔴 所有置顶卡都排在未置顶之前",
                list.get(0).pinnedAt != null && list.get(1).pinnedAt != null
                        && list.get(2).pinnedAt == null && list.get(3).pinnedAt == null);

        // publishedAt 都为 null 时不炸，且顺序稳定
        MemoryCard e1 = card(201, CardStatus.PUBLIC, "public", 1L);
        MemoryCard e2 = card(202, CardStatus.PUBLIC, "public", 1L);
        List<MemoryCard> nulls = new ArrayList<>(List.of(e1, e2));
        nulls.sort(PinPolicy.ORDER);
        eq("publishedAt 全空时按 id 倒序兜底（分页不重不漏）",
                List.of(202L, 201L), nulls.stream().map(x -> x.id).toList());
    }

    // ------------------------------------------------------------ cards[] 形状
    static void view() {
        section("cards[] 形状（C-2）");
        MemoryCard c = card(9001, CardStatus.PUBLIC, "public", 1000L);
        c.petId = 777L;
        c.title = "";                       // 🔴 无标题
        c.coverKey = "";                    // 🔴 无封面
        c.body = "它今天又睡在阳台了。太阳很好。";
        c.publishedAt = 12345L;
        c.pinnedAt = 999L;

        Map<String, Object> stranger = CardView.of(c, "public", List.of(), false);
        eq("id 是 cardId", "9001", stranger.get("id"));
        eq("另给 petId 供跳窗口页", "777", stranger.get("petId"));
        eq("🔴 title 可空，不塞占位", "", stranger.get("title"));
        eq("🔴 cover 可缺", "", stranger.get("cover"));
        eq("hasCover=false", false, stranger.get("hasCover"));
        eq("excerpt 是服务端切的首句", "它今天又睡在阳台了。", stranger.get("excerpt"));
        check("🔴 陌生人视图不含正文全文 body", !stranger.containsKey("body"));
        check("🔴 陌生人视图不含 pinnedAt", !stranger.containsKey("pinnedAt"));
        check("🔴 陌生人视图不含 visibility", !stranger.containsKey("visibility"));

        Map<String, Object> author = CardView.of(c, "friends", List.of("t1"), true);
        eq("作者视图给 pinnedAt", 999L, author.get("pinnedAt"));
        eq("🔴 作者视图的 visibility 是生效档位（被窗收窄）", "friends", author.get("visibility"));
        eq("🔴 同时给作者的原始意图，前端据此提示", "public", author.get("visibilityIntent"));
        eq("topicIds 透传", List.of("t1"), author.get("topicIds"));
        check("作者视图也不含 body", !author.containsKey("body"));

        MemoryCard withCover = card(9002, CardStatus.PUBLIC, "public", 1L);
        withCover.coverKey = "oss://a.jpg";
        eq("有封面时 hasCover=true", true,
                CardView.of(withCover, "public", null, false).get("hasCover"));
        eq("topicIds 传 null 回空数组", List.of(),
                CardView.of(withCover, "public", null, false).get("topicIds"));
    }

    // ------------------------------------------------------------------ 工具
    static MemoryCard card(long id, String status, String visibility, Long reviewedAt) {
        MemoryCard c = new MemoryCard();
        c.id = id;
        c.ownerId = 1L;
        c.petId = 1L;
        c.status = status;
        c.visibilityIntent = visibility;
        c.reviewedAt = reviewedAt;
        c.body = "";
        c.title = "";
        c.coverKey = "";
        c.sourceType = "record";
        return c;
    }

    static void section(String name) {
        System.out.println("\n---- " + name + " ----");
    }

    static void eq(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "ok   - " : "FAIL - ") + what
                + (ok ? "" : "  [期望 " + expected + " 实际 " + actual + "]"));
        if (!ok) {
            failures++;
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
