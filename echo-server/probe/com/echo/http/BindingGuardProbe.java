package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.governance.CapabilityRegistry;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.FeatureSwitchStore;
import com.echo.http.governance.GovernanceCapability;
import com.echo.http.governance.InteractionPolicy;
import com.echo.http.governance.LeaveWordsStore;
import com.echo.http.governance.ReportService;
import com.echo.http.governance.ReportStore;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.OriginType;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.safety.SafetyMetrics;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.store.InMemoryModerationStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.safety.ContentSafetyConfig;
import com.echo.infra.safety.ContentSafetyGate;
import com.echo.infra.safety.ContentSafetyVerdict;
import com.echo.infra.safety.FakeContentSafetyClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code S1′} 绑定守卫的可执行断言。
 *
 * <h2>为什么这条必须在 probe 里而不是 {@code src/test}</h2>
 *
 * <p>本工程 {@code mvn test} 禁用（见 {@code docs/BUILD-VERIFICATION.md}），
 * {@code src/test} 下的用例<b>一条都不执行</b>。而这次修的缺陷本身就是
 * 「看起来有检查、实际不执行」的同一个形状 —— 🔴 <b>用一个不跑的测试去守它，
 * 等于换了一层伪装继续放行。</b></p>
 *
 * <h2>断言的排法（🔴 顺序是有讲究的）</h2>
 *
 * <ol>
 *   <li>🔴 <b>先立正向</b>：绑定用户<b>确实做得到</b>。⚠️ 少了这一条，端点整个坏掉
 *       （比如路由没注册、卡不存在）时「游客被拦」也会成立，用例照样绿。</li>
 *   <li>再断反向：游客<b>确实被拦</b>，且<b>一个字都没落库</b>。</li>
 *   <li>🔴 <b>再断不误伤</b>：{@code S1′} 射程外的六个端点，游客<b>仍然做得到</b>，
 *       并且断的是<b>结果对</b>（拉黑名单里真有那个人）而不只是「没抛异常」。</li>
 * </ol>
 *
 * <p>⚠️ 全篇不写「断言整体为 false」：{@code isBound} 有三个独立的假法
 * （没 token / 查不到 profile / 是游客），合成一条断言的话，
 * 🔴 <b>把实现退回成 {@code id <= 0} 时那条断言仍然过</b> —— 那正是这次要防的事。</p>
 */
public final class BindingGuardProbe {

    static int failures = 0;

    // 已完成可验证绑定
    static final long BOUND = 5001L;
    // 游客：🔴 accountId 是正数、有 token —— 这正是旧实现放行它的原因
    static final long GUEST = 5002L;
    // 游客，而且是一扇窗的主人（建档路径上没有绑定守卫，所以这种人真实存在）
    static final long GUEST_OWNER = 5003L;
    // 有一个正数 id，但库里没有 profile（会话在、账号行没了）
    static final long NO_PROFILE = 5004L;

    static final long CARD_OF_BOUND = 7001L;
    static final long CARD_OF_GUEST_OWNER = 7002L;

    static EchoStore accounts;
    static LeaveWordsStore words;
    static InMemoryModerationStore cards;
    static BlockService blocks;
    static Router router;

    public static void main(String[] args) {
        wire();

        judgeSource();
        predicate();
        endpointLeaveWords();
        endpointNoCollateralDamage();
        endpointS1Five();
        onlyOneRejectionSite();

        System.out.println(failures == 0 ? "\n=== 全部通过 ===" : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ==================================================================== 判据

    /** 判据取的是哪个字段 —— 先把「权威来源」这件事钉死。 */
    static void judgeSource() {
        System.out.println("\n---- 权威判据：AccountProfile.guest ----");
        AccountProfile fresh = new AccountProfile();
        check("🔴 新建 profile 默认是游客（默认值站在收紧那一侧）", fresh.guest);

        // /auth/bind 的全部动作就是这一句；除它之外没有第二处记录「绑过没绑过」
        fresh.accountId = 9999L;
        accounts.putProfile(fresh);
        check("落库后读回来仍是游客", accounts.profile(9999L).guest);
        fresh.guest = false;
        accounts.putProfile(fresh);
        check("guest 置 false 后读回来是已绑定", !accounts.profile(9999L).guest);
    }

    static void predicate() {
        System.out.println("\n---- isBound：几个条件分开断，🔴 不合成一条 ----");
        // 🔴 正向先立：判据要是恒假，下面三条全过而这条会红
        check("✅ 正向：已绑定账号 → isBound 为真", BindingGuard.isBound(accounts, BOUND));

        check("🔴 游客（accountId 为正、有 token）→ isBound 为假",
                !BindingGuard.isBound(accounts, GUEST));
        check("🔴 profile 查不到 → 按未绑定处理", !BindingGuard.isBound(accounts, NO_PROFILE));
        check("没带 token（accountId=0）→ 假", !BindingGuard.isBound(accounts, 0L));
        check("账号存储没装配（null）→ 假，不静默放行", !BindingGuard.isBound(null, BOUND));

        System.out.println("\n---- requireBound / requireAuthenticated 的分工 ----");
        check("✅ 正向：requireBound 对已绑定账号放行并回 id",
                BindingGuard.requireBound(accounts, ctx(BOUND)) == BOUND);
        check("🔴 requireBound 拦住游客，且码是 1002（BINDING_REQUIRED，不是 1001）",
                bindingRequired(() -> BindingGuard.requireBound(accounts, ctx(GUEST))));
        check("🔴 requireBound 拦住无 profile 的正数 id",
                bindingRequired(() -> BindingGuard.requireBound(accounts, ctx(NO_PROFILE))));

        // 排查时必须分得出是哪个条件不成立 —— 三种拦法在用户侧回同一句话
        String debug = debugOf(() -> BindingGuard.requireBound(accounts, ctx(GUEST)));
        check("拦游客时 debug 串点明 guest=true（分得出是哪个条件）", debug.contains("guest=true"));
        String debugMissing = debugOf(() -> BindingGuard.requireBound(accounts, ctx(NO_PROFILE)));
        check("拦无 profile 时 debug 串点明 profile=missing", debugMissing.contains("profile=missing"));

        check("✅ 正向：requireAuthenticated 让游客过（它不是 S1′）",
                BindingGuard.requireAuthenticated(ctx(GUEST)) == GUEST);
        check("requireAuthenticated 只拦没带 token 的",
                unauthorized(() -> BindingGuard.requireAuthenticated(ctx(0L))));
    }

    // ================================================================== 端点级

    /**
     * 🔴 这一节才是当初本该抓住缺陷的那一层。
     *
     * <p>判据对不对是一件事，<b>守卫挂在哪</b>是另一件事。旧代码的两个错各占一件：
     * 实现判错了字段，位置也挂在了 {@code S1′} 没点名的端点上。</p>
     */
    static void endpointLeaveWords() {
        System.out.println("\n---- 端点：POST /cards/:cardId/messages（S1′ 射程内）----");
        openSwitch();

        Map<String, Object> receipt = call("POST", "/cards/" + CARD_OF_BOUND + "/messages",
                BOUND, body("text", "它一定很想你"));
        check("✅ 正向：已绑定用户留言拿到成功回执", Map.of("ok", true).equals(receipt));
        check("✅ 正向：而且真的落库了（回执是 ok 但不落库正是这类端点的静默失效形状）",
                words.existing(CARD_OF_BOUND, BOUND) != null);

        check("🔴 游客留言被拦（旧实现在这里放行）",
                bindingRequired(() -> call("POST", "/cards/" + CARD_OF_BOUND + "/messages",
                        GUEST, body("text", "它一定很想你"))));
        check("🔴 而且一个字都没落库", words.existing(CARD_OF_BOUND, GUEST) == null);
    }

    /**
     * {@code S1′} 逐字点名的受限写操作是<b>建档 / 上传素材 / 触发 AI 生成 / 记得 / 献花 /
     * 发布到公开层 / 导出</b>七项，⚠️ <b>举报、拉黑、关互动、处置自己收到的留言一项都不在里面。</b>
     *
     * <p>🔴 把它们一起拦掉是误伤，而且方向与安全约束相反：拦住拉黑等于从被骚扰的人手里
     * 收走自保手段。这一节把「没误伤」变成会红的断言。</p>
     */
    static void endpointNoCollateralDamage() {
        System.out.println("\n---- 🔴 S1′ 射程外：游客仍然做得到（断结果，不只断没抛异常）----");

        // 举报：治理通道对所有能看到内容的人畅通（S3 是 UGC 上线前置之一）
        Map<String, Object> report = call("POST", "/reports", GUEST,
                body("targetType", ReportService.TARGET_CARD, "targetId",
                        String.valueOf(CARD_OF_BOUND), "reasonCode", "other"));
        check("游客能举报，且拿到一条工单（不是空壳回执）",
                report.get("id") != null && !String.valueOf(report.get("id")).isBlank());

        // 拉黑 / 解除：自我保护
        Map<String, Object> blocked = call("POST", "/accounts/" + BOUND + "/block", GUEST, null);
        check("游客能拉黑，回执 blocked=true", Boolean.TRUE.equals(blocked.get("blocked")));
        check("🔴 而且拉黑真的生效了（去存储里看，不看回执）",
                blocks.store().blockedList(GUEST).contains(BOUND));

        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) call("GET", "/accounts/blocked", GUEST, null).get("items");
        check("游客读得到自己的黑名单，且名单里确实是刚拉黑那个人",
                list != null && list.contains(String.valueOf(BOUND)));

        Map<String, Object> unblocked = call("DELETE", "/accounts/" + BOUND + "/block", GUEST, null);
        check("游客能解除拉黑", Boolean.FALSE.equals(unblocked.get("blocked")));
        check("🔴 解除也真的生效了", !blocks.store().blockedList(GUEST).contains(BOUND));

        // 关互动：游客作者关掉自己卡上的留言
        Map<String, Object> patched = call("PATCH", "/cards/" + CARD_OF_GUEST_OWNER + "/interaction",
                GUEST_OWNER, body(InteractionPolicy.LEAVE_WORDS, false));
        @SuppressWarnings("unchecked")
        Map<String, Boolean> authored = (Map<String, Boolean>) patched.get("authored");
        check("🔴 游客作者能关掉自己卡上的留言，且开关真的关了（拦掉他等于让他关不了）",
                authored != null && Boolean.FALSE.equals(authored.get(InteractionPolicy.LEAVE_WORDS)));

        // 处置自己收到的留言：先让一个绑定用户往游客窗主的卡上留一句
        call("PATCH", "/cards/" + CARD_OF_GUEST_OWNER + "/interaction",
                GUEST_OWNER, body(InteractionPolicy.LEAVE_WORDS, true));
        call("POST", "/cards/" + CARD_OF_GUEST_OWNER + "/messages", BOUND, body("text", "路过看了看它"));
        LeaveWordsStore.Entry received = words.existing(CARD_OF_GUEST_OWNER, BOUND);
        check("✅ 前置成立：游客窗主确实收到了一条留言（收不到的话下面两条什么都没验）",
                received != null);

        if (received != null) {
            @SuppressWarnings("unchecked")
            List<Object> pending = (List<Object>) call("GET",
                    "/cards/" + CARD_OF_GUEST_OWNER + "/messages/pending", GUEST_OWNER, null)
                    .get("items");
            check("🔴 游客窗主读得到待处理队列，且队列非空（空列表上什么都断不出来）",
                    pending != null && !pending.isEmpty());

            Map<String, Object> resolved = call("POST", "/messages/" + received.id + "/disposition",
                    GUEST_OWNER, body("disposition", LeaveWordsStore.PRIVATE));
            check("游客窗主能处置自己收到的留言", Map.of("ok", true).equals(resolved));
            check("🔴 处置真的写进去了（不是回了个 ok 就算）",
                    LeaveWordsStore.PRIVATE.equals(words.byId(received.id).disposition));
        }
    }

    // ============================================================ S1′ 五项写操作

    static Router echoRouter;
    static EchoStore echoStore;

    /**
     * {@code S1′} 逐字点名的受限写操作，产品负责人 2026-08-27 选严格档，五项全拦。
     *
     * <p>🔴 每一项都<b>正向先立</b>：先证明已绑定用户做得到，再证明游客做不到。
     * ⚠️ 少了正向那一条，端点整个坏掉（LLM 桩没装、路由没注册、卡不存在）时
     * 「游客被拦」也会成立。</p>
     */
    static void endpointS1Five() {
        System.out.println("\n---- 🔴 S1′ 五项：建档 / 上传 / 触发 AI / 记得 / 献花 ----");
        wireEchoApi();

        long owner = authGuest("dev-owner");
        bind(owner);
        long guest = authGuest("dev-guest");          // 保持游客
        long visitor = authGuest("dev-visitor");
        bind(visitor);

        // ---- 建档 + 触发 AI：四步逐个断 ----
        check("✅ 正向：已绑定用户能跑 onboarding/start（内含一次 LLM 生成候选）",
                echoCall("POST", "/pet/onboarding/start", owner, body("petName", "麦麦"))
                        .get("onboardingId") != null);
        String onboardingId = (String) echoCall("POST", "/pet/onboarding/start", owner,
                body("petName", "麦麦")).get("onboardingId");

        check("🔴 游客过不了 onboarding/detect（下一行就是视觉模型调用）",
                bindingRequired(() -> echoCall("POST", "/pet/onboarding/detect", guest,
                        body("resourceId", "res-1"))));
        check("🔴 游客过不了 onboarding/start（LLM 生成候选）",
                bindingRequired(() -> echoCall("POST", "/pet/onboarding/start", guest,
                        body("petName", "麦麦"))));
        check("🔴 游客过不了 onboarding/refine（重做无上限，等于无限次免费生成入口）",
                bindingRequired(() -> echoCall("POST", "/pet/onboarding/refine", guest,
                        body("onboardingId", onboardingId, "chosenCandidateId", "any",
                                "adjust", "再温柔一点"))));
        // 🔴 拿一个真实可用的 onboardingId 去撞 confirm：否则「被拦」可能只是因为 id 不存在
        check("🔴 游客拿着一个<真实有效>的 onboardingId 也过不了 confirm（建档拦在提交这一刻）",
                bindingRequired(() -> echoCall("POST", "/pet/onboarding/confirm", guest,
                        confirmBody(onboardingId))));

        String petId = (String) echoCall("POST", "/pet/onboarding/confirm", owner,
                confirmBody(onboardingId)).get("petId");
        check("✅ 正向：已绑定用户建档成功，拿到 petId", petId != null && !petId.isBlank());
        openWindow(petId);

        // ---- 触发 AI：回访与回信 ----
        check("✅ 正向：已绑定窗主能回访（生成当天那条近况）",
                echoCall("POST", "/pet/me/visit", owner, null) != null);

        // 存量游客窗主：建档此前没有绑定守卫，所以这种账号在数据上是可能存在的
        unbind(owner);
        check("🔴 存量游客窗主回访被拦（buildEcho → LLM）",
                bindingRequired(() -> echoCall("POST", "/pet/me/visit", owner, null)));
        check("🔴 存量游客窗主回信也被拦（generateReply 无每日节流）",
                bindingRequired(() -> echoCall("POST", "/pet/me/echoes/any/reply", owner,
                        body("text", "我很想你"))));
        bind(owner);

        // ---- 记得：文案逐字 ----
        Map<String, Object> remembered = echoCall("POST", "/windows/" + petId + "/remember",
                visitor, body("remembered", true));
        check("✅ 正向：已绑定访客点得动「记得」",
                Boolean.TRUE.equals(remembered.get("remembered")));
        check("🔴 游客点「记得」被拦",
                bindingRequired(() -> echoCall("POST", "/windows/" + petId + "/remember",
                        guest, body("remembered", true))));
        check("🔴 「记得」那句文案逐字是产品定的那一句（含标点）",
                BindingGuard.COPY_REMEMBER.equals(messageOf(() ->
                        echoCall("POST", "/windows/" + petId + "/remember", guest,
                                body("remembered", true)))));

        // ---- 献花：文案只把「记得」换成「心意」 ----
        Map<String, Object> flowered = echoCall("POST", "/windows/" + petId + "/flower",
                visitor, body("count", 1));
        check("✅ 正向：已绑定访客献得出花", Boolean.TRUE.equals(flowered.get("ok")));
        check("🔴 游客献花被拦",
                bindingRequired(() -> echoCall("POST", "/windows/" + petId + "/flower",
                        guest, body("count", 1))));
        String flowerMsg = messageOf(() -> echoCall("POST", "/windows/" + petId + "/flower",
                guest, body("count", 1)));
        check("🔴 「献花」那句与「记得」那句只差「记得」→「心意」两个字",
                BindingGuard.COPY_FLOWER.equals(flowerMsg)
                        && flowerMsg.equals(BindingGuard.COPY_REMEMBER.replace("记得", "心意")));

        // ---- 读端点仍然不拦（维持上一轮判定）----
        check("🔴 游客仍然逛得了广场（S1′ 第一句是「纯游客只读」）",
                echoCall("GET", "/plaza", guest, null) != null);
        check("🔴 游客仍然看得见窗（读不拦）",
                echoCall("GET", "/windows/" + petId, guest, null) != null);
        check("🔴 游客仍然读得到面孔墙",
                echoCall("GET", "/windows/" + petId + "/remember", guest, null) != null);

        // ---- 前端契约：码与 HTTP 状态 ----
        System.out.println("\n---- 🔴 前端契约：1002 必须映射到 403，不是 401 ----");
        check("BINDING_REQUIRED 是 1002，与 UNAUTHORIZED(1001) 不同码",
                ApiException.BINDING_REQUIRED == 1002
                        && ApiException.BINDING_REQUIRED != ApiException.UNAUTHORIZED);
        check("✅ 正向对照：1001 仍然映射到 401", httpStatusOf(ApiException.UNAUTHORIZED) == 401);
        check("🔴 1002 映射到 403 —— 401 会触发前端「token 失效 → 重取通行证」，转成死循环",
                httpStatusOf(ApiException.BINDING_REQUIRED) == 403);
        // 🔴 出站还要过一次 CopyGuardFilter.sanitize（HttpGateway.writeError）。
        //    产品要的是「逐字」，那就得断到用户真正看见的那一版，不是抛出时那一版。
        check("🔴 「记得」那句过了出站词表过滤后仍然逐字不变",
                BindingGuard.COPY_REMEMBER.equals(CopyGuardFilter.sanitize(BindingGuard.COPY_REMEMBER)));
        check("🔴 「献花」那句过了出站词表过滤后仍然逐字不变",
                BindingGuard.COPY_FLOWER.equals(CopyGuardFilter.sanitize(BindingGuard.COPY_FLOWER)));
    }

    /** 反射取 {@code HttpGateway.httpStatusOf}：这是发给前端的契约，必须有断言守着。 */
    static int httpStatusOf(int code) {
        try {
            var m = HttpGateway.class.getDeclaredMethod("httpStatusOf", int.class);
            m.setAccessible(true);
            return (int) m.invoke(null, code);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("拿不到 HttpGateway.httpStatusOf —— 改名了？", e);
        }
    }

    static void wireEchoApi() {
        echoStore = new InMemoryEchoStore();
        EchoApi api = new EchoApi(echoStore, new IDGenerator(1L), new MockLlmClient(),
                new StubVisionClient(), null, new InMemoryTrainingCorpus());
        echoRouter = api.routes(true);
    }

    static long authGuest(String deviceId) {
        return Long.parseLong((String) echoCall("POST", "/auth/guest", 0L,
                body("deviceId", deviceId)).get("accountId"));
    }

    /** 直接改库位，等价于走完 {@code /auth/bind}（那个端点也只写这一个字段）。 */
    static void bind(long accountId) {
        AccountProfile p = echoStore.profile(accountId);
        p.guest = false;
        echoStore.putProfile(p);
    }

    static void unbind(long accountId) {
        AccountProfile p = echoStore.profile(accountId);
        p.guest = true;
        echoStore.putProfile(p);
    }

    /** 把窗开成 public，否则陌生人连看都看不见，记得/献花会先撞可见性而不是撞守卫。 */
    static void openWindow(String petId) {
        var pet = echoStore.petById(petId);
        pet.visibility = "public";
        echoStore.putPet(pet);
    }

    static JsonObject confirmBody(String onboardingId) {
        JsonObject b = new JsonObject();
        b.addProperty("onboardingId", onboardingId);
        b.addProperty("finalCandidateId", "any");
        JsonObject scene = new JsonObject();
        scene.addProperty("caption", "相遇那天");
        scene.addProperty("allowUse", true);
        b.add("memoryScene", scene);
        return b;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> echoCall(String method, String path, long accountId, JsonObject body) {
        Router.Match m = echoRouter.match(method, path);
        if (m == null) {
            throw new AssertionError("路由没匹配上：" + method + " " + path);
        }
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                body == null ? new JsonObject() : body, accountId);
        try {
            return (Map<String, Object>) m.entry.route.handle(ctx);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String messageOf(Runnable action) {
        try {
            action.run();
            return "";
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    // ================================================================== 结构扫描

    /**
     * 🔴 {@code S1′} 的拒绝<b>只允许有一个产地</b>。
     *
     * <p>这次的缺陷不是写错一行，是<b>同一个错被抄了三份</b>：{@code LeaveWordsApi} /
     * {@code GovernanceApi} / {@code EchoApi} 里各有一份 {@code if (id <= 0) throw}，
     * 方法体一样、注释一样、引的编号一样。⚠️ <b>抄第四份的成本几乎是零</b>，
     * 所以这里把「只能有一个产地」变成机器检查：文案与 debug 串只许出现在
     * {@code BindingGuard.java}，别处出现即失败。</p>
     */
    static void onlyOneRejectionSite() {
        System.out.println("\n---- 🔴 结构：S1′ 的拒绝只有一个产地 ----");

        // 🔴 先验扫描器自己有效：喂一段必须被判违规的样本。
        //    扫描器要是坏的（路径错、正则错），下面「零命中」会恒过 —— 那就是假绿
        List<String> sample = List.of(
                "        long id = ctx.accountId();",
                "        if (id <= 0) {",
                "            throw new ApiException(ApiException.UNAUTHORIZED, \"先绑定一下手机号吧。\",",
                "                    \"verifiable binding required\");");
        check("✅ 扫描器有效性自检：假守卫样本被判违规", !offendersIn("sample.java", sample).isEmpty());
        check("✅ 扫描器有效性自检：干净样本不被误判",
                offendersIn("clean.java", List.of("        return BindingGuard.requireBound(accounts, ctx);")).isEmpty());

        Path root = Path.of("src", "main", "java");
        check("源码目录存在（扫不到就等于这条断言恒过）cwd=" + Path.of("").toAbsolutePath(),
                Files.isDirectory(root));

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("BindingGuard.java")) {
                    continue;   // 唯一的产地，就是它
                }
                scanned++;
                offenders.addAll(offendersIn(f.toString(), Files.readAllLines(f)));
            }
        } catch (IOException e) {
            throw new AssertionError("扫描 src/main/java 失败", e);
        }

        check("确实扫到了源文件（扫了 " + scanned + " 个）", scanned > 100);
        offenders.forEach(o -> System.out.println("       " + o));
        check("🔴 BindingGuard 之外没有第二处产出 S1′ 拒绝（命中 " + offenders.size() + " 处）",
                offenders.isEmpty());

        judgeMustBeUsed(root);
        uploadGuardMustExist();
    }

    /**
     * 🔴 规则 B（本轮补的洞）：<b>提到绑定拒绝码的文件，必须同时用到 {@link BindingGuard}。</b>
     *
     * <h2>为什么规则 A 不够</h2>
     *
     * <p>规则 A 查的是<b>那两句文案的字面量</b>，它拦得住「把假守卫再抄一份」。⚠️ 但它拦不住
     * 「<b>换个说法自己写一个拒绝</b>」—— 比如
     * {@code throw new ApiException(BINDING_REQUIRED, "请先完成绑定", ...)}：
     * 文案不同，规则 A 一个字都不会命中，而那又是一个绕开判据的新产地。</p>
     *
     * <p>🔴 <b>这个洞是交办点出来要我自查的，确实存在，本方法就是补它。</b>
     * 判准换成「谁提这个码，谁就必须用那个判据」——{@code HttpGateway} 因此不需要写进例外表：
     * 它用了 {@code BindingGuard.isBound} 与 {@code BindingGuard.COPY_DEFAULT}。</p>
     *
     * <p>📌 为什么不直接禁止别处提这个码：{@code /upload} 不走 {@link Router}、拿不到抛异常那条路
     * （它要自己 {@code writeError}），所以「只有 BindingGuard 能提这个码」是一条做不到的规则，
     * 而做不到的规则会被加例外，例外表一长这条检查就废了。</p>
     */
    static void judgeMustBeUsed(Path root) {
        List<String> sample = List.of(
                "            throw new ApiException(ApiException.BINDING_REQUIRED, \"请先完成绑定\",",
                "                    \"needs binding\");");
        check("✅ 扫描器自检：自己写拒绝、不经判据的样本被判违规",
                usesCodeWithoutJudge(sample));
        check("✅ 扫描器自检：经过判据的样本不被误判",
                !usesCodeWithoutJudge(List.of(
                        "        if (!BindingGuard.isBound(store, accountId)) {",
                        "            writeError(exchange, 403, ApiException.BINDING_REQUIRED, x, y);")));

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("BindingGuard.java")
                        || f.getFileName().toString().equals("ApiException.java")) {
                    continue;   // 判据本身，和码的定义处
                }
                scanned++;
                if (usesCodeWithoutJudge(Files.readAllLines(f))) {
                    offenders.add(f.toString());
                }
            }
        } catch (IOException e) {
            throw new AssertionError("扫描 src/main/java 失败", e);
        }
        check("确实扫到了源文件（扫了 " + scanned + " 个）", scanned > 100);
        offenders.forEach(o -> System.out.println("       " + o));
        check("🔴 没有任何文件在不经 BindingGuard 的情况下产出绑定拒绝（命中 "
                + offenders.size() + " 处）", offenders.isEmpty());
    }

    static boolean usesCodeWithoutJudge(List<String> lines) {
        boolean mentionsCode = false;
        boolean usesJudge = false;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            if (line.contains("BINDING_REQUIRED")) {
                mentionsCode = true;
            }
            if (line.contains("BindingGuard.")) {
                usesJudge = true;
            }
        }
        return mentionsCode && !usesJudge;
    }

    /**
     * 🔴 {@code POST /upload} 的守卫得单独钉一下。
     *
     * <p>它<b>不在 {@link Router} 里</b>（要原始字节 + multipart 解析），所以上面那些端点级用例
     * 一条都覆盖不到它，⚠️ 而它正是 {@code S1′}「上传素材」的唯一落点。
     * 这里退一步做结构断言：{@code handleUpload} 开头必须有绑定判定，且必须在读请求体之前。</p>
     */
    static void uploadGuardMustExist() {
        System.out.println("\n---- 🔴 POST /upload 不走 Router，单独钉 ----");
        check("✅ 扫描器自检：没有守卫的 handleUpload 样本被判违规",
                !uploadGuarded(List.of(
                        "    private void handleUpload(HttpExchange exchange) throws IOException {",
                        "        long accountId = authenticate(exchange);",
                        "        byte[] body;")));
        check("✅ 扫描器自检：有守卫的样本判通过",
                uploadGuarded(List.of(
                        "    private void handleUpload(HttpExchange exchange) throws IOException {",
                        "        long accountId = authenticate(exchange);",
                        "        if (!BindingGuard.isBound(store, accountId)) {",
                        "            return;",
                        "        }",
                        "        byte[] body;")));
        try {
            Path f = Path.of("src", "main", "java", "com", "echo", "http", "HttpGateway.java");
            check("HttpGateway.java 找得到（找不到就等于这条断言恒过）", Files.isRegularFile(f));
            check("🔴 handleUpload 里有绑定判定，且排在读请求体之前",
                    uploadGuarded(Files.readAllLines(f)));
        } catch (IOException e) {
            throw new AssertionError("读 HttpGateway.java 失败", e);
        }
    }

    /** {@code handleUpload} 的头几行里有 {@code BindingGuard.isBound}，且在 {@code getRequestBody} 之前。 */
    static boolean uploadGuarded(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("private void handleUpload(")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return false;
        }
        for (int i = start; i < Math.min(lines.size(), start + 12); i++) {
            String line = lines.get(i);
            if (line.contains("getRequestBody") || line.contains("byte[] body")) {
                return false;   // 先读体、后判权限 —— 25MB 白读，游客是无限身份
            }
            if (line.contains("BindingGuard.isBound")) {
                return true;
            }
        }
        return false;
    }

    /** 一个文件里「自己造 S1′ 拒绝」的命中行。注释行不算 —— 解释这个错必须能引用它。 */
    static List<String> offendersIn(String name, List<String> lines) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            if (line.contains("先绑定一下手机号吧") || line.contains("verifiable binding required")) {
                hits.add(name + ":" + (i + 1) + "  " + trimmed);
            }
        }
        return hits;
    }

    // ==================================================================== 装配

    static void wire() {
        IDGenerator ids = new IDGenerator(1L);
        cards = new InMemoryModerationStore(ids);
        accounts = new InMemoryEchoStore();
        words = new LeaveWordsStore(null);
        blocks = new BlockService(new BlockStore(null), ids);
        CapabilityRegistry capabilities = new CapabilityRegistry();
        FeatureSwitchService switches = new FeatureSwitchService(
                new FeatureSwitchStore(null), capabilities);
        InteractionPolicy interactions = new InteractionPolicy(switches);
        GovernanceApi governance = new GovernanceApi(
                new ReportService(new ReportStore(null), ids), blocks, interactions, switches, cards);
        OutputSafetyGate gate = new OutputSafetyGate(new SafetyMetrics(), new ContentSafetyGate(
                ContentSafetyConfig.fake(),
                new FakeContentSafetyClient(t -> ContentSafetyVerdict.pass())));

        router = new Router();
        governance.register(router);
        new LeaveWordsApi(words, cards, accounts, governance, switches, gate, ids).register(router);

        BindingGuardProbe.capabilities = capabilities;
        BindingGuardProbe.switches = switches;

        putCard(CARD_OF_BOUND, BOUND);
        putCard(CARD_OF_GUEST_OWNER, GUEST_OWNER);

        putProfile(BOUND, false);
        putProfile(GUEST, true);
        putProfile(GUEST_OWNER, true);
        // NO_PROFILE 故意不建
    }

    static CapabilityRegistry capabilities;
    static FeatureSwitchService switches;

    static void openSwitch() {
        for (GovernanceCapability c : GovernanceCapability.values()) {
            capabilities.register(c, () -> true);
        }
        switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, 1L, 2L);
    }

    static void putCard(long id, long ownerId) {
        MemoryCard card = new MemoryCard();
        card.id = id;
        card.ownerId = ownerId;
        card.status = CardStatus.PUBLIC;
        card.visibilityIntent = "public";
        card.originType = OriginType.USER;
        cards.putCard(card);
    }

    static void putProfile(long accountId, boolean guest) {
        AccountProfile p = new AccountProfile();
        p.accountId = accountId;
        p.guest = guest;
        p.nickname = guest ? "旅人" : "拾光";
        accounts.putProfile(p);
    }

    static RequestContext ctx(long accountId) {
        return new RequestContext("POST", Map.of(), Map.of(), new JsonObject(), accountId);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(String method, String path, long accountId, JsonObject body) {
        Router.Match m = router.match(method, path);
        if (m == null) {
            throw new AssertionError("路由没匹配上：" + method + " " + path);
        }
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                body == null ? new JsonObject() : body, accountId);
        try {
            return (Map<String, Object>) m.entry.route.handle(ctx);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static JsonObject body(String k, Object v) {
        JsonObject b = new JsonObject();
        add(b, k, v);
        return b;
    }

    static JsonObject body(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        JsonObject b = new JsonObject();
        add(b, k1, v1);
        add(b, k2, v2);
        add(b, k3, v3);
        return b;
    }

    static void add(JsonObject b, String k, Object v) {
        if (v instanceof Boolean bool) {
            b.addProperty(k, bool);
        } else {
            b.addProperty(k, String.valueOf(v));
        }
    }

    /** 抛的是 {@code UNAUTHORIZED}（1001）吗。🔴 不接受「抛了别的错」也算拦住。 */
    static boolean unauthorized(Runnable action) {
        return threwWithCode(action, ApiException.UNAUTHORIZED);
    }

    /**
     * 抛的是 {@code BINDING_REQUIRED}（1002）吗。
     *
     * <p>🔴 <b>码必须对得上，1001 不算过。</b>前端要靠这个码把「去绑定」和「重新取通行证」
     * 分开，两者混一个码会让前端清掉游客 token 再发一张再被拦。</p>
     */
    static boolean bindingRequired(Runnable action) {
        return threwWithCode(action, ApiException.BINDING_REQUIRED);
    }

    static boolean threwWithCode(Runnable action, int expected) {
        try {
            action.run();
            return false;
        } catch (ApiException e) {
            if (e.code() != expected) {
                System.out.println("       ⚠️ 抛的不是 " + expected + "，是 " + e.code()
                        + "：" + e.getMessage());
                return false;
            }
            return true;
        }
    }

    static String debugOf(Runnable action) {
        try {
            action.run();
            return "";
        } catch (ApiException e) {
            return String.valueOf(e.detail());
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
