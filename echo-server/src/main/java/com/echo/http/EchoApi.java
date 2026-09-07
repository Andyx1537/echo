package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.Candidate;
import com.echo.http.model.Models.FlowerLog;
import com.echo.http.model.Models.LifeBookItem;
import com.echo.http.model.Models.MessageEntry;
import com.echo.http.model.Models.Onboarding;
import com.echo.http.model.Models.PetEcho;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.model.Models.Postcard;
import com.echo.http.model.Models.ReactionMark;
import com.echo.http.model.Models.RecordEntry;
import com.echo.http.model.Models.RelationEntry;
import com.echo.http.model.Models.ShadowArea;
import com.echo.http.model.Models.SpectrumNode;
import com.echo.http.model.Models.SubjectFields;
import com.echo.http.card.CardView;
import com.echo.http.card.CardVisibility;
import com.echo.http.card.PinPolicy;
import com.echo.http.exposure.FeedRequestRegistry;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.FollowStore;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.pet.VisitEchoThrottle;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.store.ModerationStore;
import com.echo.http.safety.ObjectContext;
import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.safety.SafetyMetrics;
import com.echo.http.store.EchoStore;
import com.echo.http.visibility.ViewerRole;
import com.echo.http.visibility.VisibilityMatrix;
import com.echo.http.visibility.WindowBlock;
import com.echo.infra.corpus.ITrainingCorpus;
import com.echo.infra.corpus.TrainSample;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.vision.DetectResult;
import com.echo.infra.vision.DetectSubject;
import com.echo.infra.vision.IVisionClient;
import com.echo.module.account.AccountService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * REST 网关业务层：把契约 §1-§11 的端点绑定到 {@link Router}，路由到既有 service/仓储与
 * {@link EchoStore} 内存态；六项定案（API-CONTRACT §12）在此服务端强制。
 *
 * <p>复用既有资产：</p>
 * <ul>
 *   <li>{@link AccountService}（DB 开启时）：{@code /auth/guest} 建/复用 t_account，游客一等公民；
 *       无 DB 时用 {@link IDGenerator} 直接发号，闭环仍可跑。</li>
 *   <li>{@link ILlmClient}：近况/来信/光谱暗语的 AI 生成入口，输出一律过 {@link CopyGuardFilter}（定案 #6）。</li>
 *   <li>{@link Temperature}：温度规则（地板 60、不惩罚、只主人回访驱动；与献花解耦，定案 #5）。</li>
 * </ul>
 */
@Slf4j
public class EchoApi {

    /** 每日免费献花额度（定案 #3）。 */
    public static final int DAILY_FREE_FLOWERS = 5;

    private final EchoStore store;
    private final IDGenerator idGenerator;
    private final ILlmClient llm;
    /** 肖像识别客户端（建档第 1 步 /detect）：插件化，桩实现返回中性默认、绝不乱认。 */
    private final IVisionClient vision;
    /** DB 开启时注入以复用账号仓储；无 DB 时为 null。 */
    private final AccountService accountService;
    /** 训练语料回流（受 trainConsent 门控写入；AI-CAPABILITIES §7）。 */
    private final ITrainingCorpus trainingCorpus;
    /** dev/内存联调：新游客首次建号时是否铺演示亲友种子（持久化模式为 false，不种；mi-D）。 */
    private final boolean seedDemoRelations;
    /**
     * feed 下发快照登记处；{@code GET /plaza} 用它签发 {@code reqId}。
     * 未装配时（老单测、纯内存联调）{@code /plaza} 不带 reqId，曝光上报会因 unknown_req 整批拒收。
     */
    private FeedRequestRegistry feedRequests;
    /**
     * 输出侧安全闸五关（{@code SPEC-security §4.3}）。AI 生成内容投递前必过。
     * 默认自带一套，保证任何构造方式下生成路径都有闸——不允许出现"没注入就不检查"。
     */
    private OutputSafetyGate safetyGate = new OutputSafetyGate(new SafetyMetrics());
    /**
     * 拉黑判定。未装配 = 一律放行（老单测/无治理装配的联调）。
     * 🔴 生产装配路径见 {@code EchoHttpBootstrap}，那里必定注入。
     */
    private BlockService blockService;

    public EchoApi(EchoStore store, IDGenerator idGenerator, ILlmClient llm,
                   IVisionClient vision, AccountService accountService, ITrainingCorpus trainingCorpus) {
        this(store, idGenerator, llm, vision, accountService, trainingCorpus, false);
    }

    /**
     * @param seedDemoRelations dev/内存模式为 true，给新游客铺几条演示亲友种子（联调亲友页不空白，mi-D）；
     *                          持久化模式传 false，不种。
     */
    public EchoApi(EchoStore store, IDGenerator idGenerator, ILlmClient llm,
                   IVisionClient vision, AccountService accountService, ITrainingCorpus trainingCorpus,
                   boolean seedDemoRelations) {
        this.store = store;
        this.idGenerator = idGenerator;
        this.llm = llm;
        this.vision = vision;
        this.accountService = accountService;
        this.trainingCorpus = trainingCorpus;
        this.seedDemoRelations = seedDemoRelations;
    }

    /** 装配曝光记账所需的下发快照登记处（由 bootstrap 调用；不装配则退化为不签发 reqId）。 */
    public void setFeedRequests(FeedRequestRegistry feedRequests) {
        this.feedRequests = feedRequests;
    }

    /** 装配共享的输出侧安全闸（让拦截计数与后台看板落在同一个 {@link SafetyMetrics} 上）。 */
    public void setSafetyGate(OutputSafetyGate safetyGate) {
        this.safetyGate = safetyGate;
    }

    /**
     * 装配拉黑判定。
     *
     * <p>未装配时一律放行——但这只发生在老单测与无治理装配的联调里。生产装配见
     * {@code EchoHttpBootstrap}。</p>
     */
    public void setBlockService(BlockService blockService) {
        this.blockService = blockService;
    }

    /**
     * 关注关系。未装配 = 关注端点整体不可用（老单测/无治理装配的联调）。
     * 🔴 生产装配路径见 {@code EchoHttpBootstrap}，那里必定注入。
     */
    private FollowStore followStore;

    public void setFollowStore(FollowStore followStore) {
        this.followStore = followStore;
    }

    /**
     * 回忆卡存储。⚠️ <b>未装配时 {@code GET /plaza} 返回空页</b>（老单测/无审核域装配的联调）。
     *
     * <p>🔴 返回空页而不是回落到旧的「发宠物窗口」：两套 card 形状是这一轮明确要避免的事，
     * 而一个静默回落的旧路径就是第二套形状最容易长出来的地方。</p>
     */
    private ModerationStore cardStore;

    public void setCardStore(ModerationStore cardStore) {
        this.cardStore = cardStore;
    }

    /** 置顶口径（上限后台可配）。未装配时用默认上限 3。 */
    private PinPolicy pinPolicy = new PinPolicy();

    public void setPinPolicy(PinPolicy pinPolicy) {
        this.pinPolicy = pinPolicy;
    }

    /** 两人之间有任一方向的拉黑——彻底不可见的判定（{@code S8}）。 */
    private boolean hiddenBetween(long a, long b) {
        return blockService != null && blockService.hidden(a, b);
    }

    /**
     * {@code actor} 能否对 {@code ownerId} 的内容产生新互动。
     *
     * <p>🔴 false 时调用方必须<b>返回成功形状但不生效</b>（静默失效），不得抛错——
     * 理由见 {@link BlockService} 类注释。</p>
     */
    private boolean canInteract(long ownerId, long actor) {
        return blockService == null || blockService.canInteract(ownerId, actor);
    }

    /**
     * 注册全部路由到 router（base path /api/v1 由网关剥离）。
     * dev-only 路由默认按系统属性 {@code echo.devRoutes} 决定（生产默认关）。
     */
    public Router routes() {
        return routes(Boolean.parseBoolean(System.getProperty("echo.devRoutes", "false")));
    }

    /**
     * 注册全部路由；{@code devRoutes=true} 时额外挂载仅测试/联调用的 dev-only 端点
     * （如 {@code DELETE /pet/me}，mi-2）。生产（PG 落库）模式应传 false。
     */
    public Router routes(boolean devRoutes) {
        Router r = new Router();
        // §1 鉴权/账号
        Route retiredAuth = ctx -> { throw new ApiException(ApiException.GONE,
                "登录方式已经更新，请使用手机号登录。", "endpoint_retired"); };
        r.addPublic("POST", "/auth/guest", retiredAuth);
        r.add("POST", "/auth/bind", retiredAuth);
        r.add("GET", "/me", this::me);
        // §2 建档
        if (Boolean.parseBoolean(System.getProperty("echo.onboarding.legacy.enabled", "true"))) {
            r.add("POST", "/pet/onboarding/detect", this::onboardingDetect);
            r.add("POST", "/pet/onboarding/start", this::onboardingStart);
            r.add("POST", "/pet/onboarding/refine", this::onboardingRefine);
            r.add("POST", "/pet/onboarding/confirm", this::onboardingConfirm);
        } else {
            Route retired = ctx -> { throw new ApiException(ApiException.GONE,
                    "旧建档入口已经更新，请从新的建档流程继续。", "endpoint_retired"); };
            r.add("POST", "/pet/onboarding/detect", retired);
            r.add("POST", "/pet/onboarding/start", retired);
            r.add("POST", "/pet/onboarding/refine", retired);
            r.add("POST", "/pet/onboarding/confirm", retired);
        }
        // 注：POST /upload 由 HttpGateway 直接处理（需原始字节 + multipart 解析），不走 JSON 路由
        // §3 我的它
        r.add("GET", "/pet/me", this::petMe);
        r.add("PATCH", "/pet/me", this::petMePatch);
        r.add("POST", "/pet/me/visit", this::petVisit);
        // mi-2 · DELETE /pet/me（resetPet）——dev-only（非生产）：仅测试/联调重入建档流程用，
        // 生产（PG 落库）模式不挂载，见 API-CONTRACT §3 注释。
        if (devRoutes) {
            r.add("DELETE", "/pet/me", this::resetPet);
        }
        // §4 近况/来信
        r.add("GET", "/pet/me/echoes", this::petEchoes);
        r.add("POST", "/pet/me/echoes/:echoId/reply", this::petEchoReply);
        // §5 献花 & 记得（拆开）
        r.add("GET", "/flowers/quota", this::flowerQuota);
        // 🔴 占位符统一叫 :petId —— 这些端点收的是宠物窗口 id。URL 段仍是 /windows/，
        //    但 LeaveWordsApi 挂在同一段下收的却是 cardId，见该类注释。
        r.add("POST", "/windows/:petId/flower", this::flowerOffer);
        r.add("POST", "/windows/:petId/remember", this::rememberSet);
        r.add("GET", "/windows/:petId/remember", this::rememberWall);
        // §6 窗口/广场
        r.add("GET", "/plaza", this::plaza);
        r.add("GET", "/windows/:petId", this::windowDetail);
        r.add("POST", "/windows/:petId/seen", this::windowSeen);

        // §6b 回忆卡（契约 C-2 / C-5 / 置顶）
        r.add("GET", "/pet/me/cards", this::myCards);
        r.add("GET", "/users/:id/cards", this::userCards);
        r.add("PATCH", "/cards/:id/visibility", this::patchCardVisibility);
        r.add("PUT", "/cards/:id/pin", this::pinCard);
        r.add("DELETE", "/cards/:id/pin", this::unpinCard);
        r.add("GET", "/pet/me/insights", this::petInsights);
        // §7 明信片墙 + 商店
        r.add("GET", "/pet/me/postcards", this::postcards);
        r.add("POST", "/pet/me/postcards/:id/unlock", this::postcardUnlock);
        r.add("GET", "/shop/postcard-skins", this::shopSkins);
        r.add("POST", "/shop/purchase", this::shopPurchase);
        // §8 亲友
        r.add("GET", "/relations", this::relations);
        r.add("PATCH", "/relations/:id", this::relationPatch);
        r.add("POST", "/relations/:id/reel-seen", this::relationReelSeen);
        // §8b 他人主页 & 关注（术语统一用 follow；拉黑走 GovernanceApi 的 /accounts/:id/block）
        r.add("GET", "/users/:id", this::userProfile);
        r.add("GET", "/users/:id/windows", this::userWindows);
        r.add("POST", "/users/:id/follow", this::followUser);
        r.add("DELETE", "/users/:id/follow", this::unfollowUser);
        // §9 记录
        r.add("GET", "/records", this::recordsList);
        r.add("POST", "/records", this::recordCreate);
        // §10 消息
        r.add("GET", "/messages", this::messages);
        r.add("GET", "/messages/arrivals", this::reactionArrivals);
        r.add("POST", "/messages/read", this::messagesRead);
        // §11 光谱
        r.add("GET", "/spectrum", this::spectrum);
        r.add("POST", "/spectrum/anchor", this::spectrumAnchor);
        r.add("POST", "/spectrum/shadows/:id/integrate", this::spectrumIntegrate);
        return r;
    }

    // ========================================================== §1 鉴权/账号

    private Object authGuest(RequestContext ctx) {
        String deviceId = Json.getString(ctx.body(), "deviceId", null);
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = "anon-" + UUID.randomUUID();
        }
        Long existing = store.accountByDevice(deviceId);
        AccountProfile profile;
        if (existing != null) {
            profile = store.profile(existing);
        } else {
            long accountId = allocateAccountId(deviceId);
            profile = new AccountProfile();
            profile.accountId = accountId;
            profile.deviceId = deviceId;
            profile.guest = true;
            profile.createTime = System.currentTimeMillis();
            store.putProfile(profile);
            // dev/内存联调：为新游客铺几条演示亲友，避免联调时亲友页空白（mi-D；持久化模式不种）。
            if (seedDemoRelations) {
                seedRelationsFor(profile.accountId);
            }
        }
        String token = newToken();
        store.bindToken(token, profile.accountId);
        logEvent("guest_created", profile);

        Map<String, Object> data = Json.map();
        data.put("token", token);
        data.put("accountId", String.valueOf(profile.accountId));
        data.put("isGuest", profile.guest);
        data.put("hasPet", profile.hasPet);
        return data;
    }

    private long allocateAccountId(String deviceId) {
        if (accountService != null) {
            // 复用 AccountService：以 deviceId 作 openId 幂等建/取正式账号行（t_account）
            return accountService.login(deviceId).account().getId();
        }
        return idGenerator.nextId();
    }

    private Object authBind(RequestContext ctx) {
        String type = Json.requireString(ctx.body(), "type");
        Json.requireString(ctx.body(), "credential");
        if (!type.equals("phone") && !type.equals("wechat")) {
            throw new ApiException(ApiException.BAD_PARAM, "这种绑定方式还没开放，先用手机或微信吧。", "unsupported bind type");
        }
        AccountProfile profile = requireProfile(ctx);
        profile.guest = false; // token 不变、账号升级（isGuest=false）
        store.putProfile(profile);
        logEvent("bind_account", profile);
        return Map.of("isGuest", false);
    }

    private Object me(RequestContext ctx) {
        AccountProfile profile = requireProfile(ctx);
        Map<String, Object> data = Json.map();
        data.put("accountId", String.valueOf(profile.accountId));
        data.put("isGuest", profile.guest);
        data.put("nickname", profile.nickname);
        data.put("hasPet", profile.hasPet);
        data.put("visibilityDefault", profile.visibilityDefault);
        return data;
    }

    // ========================================================== §2 建档

    /**
     * 肖像识别（建档第 1 步）：上传一张 → 顺路认出种类，支持一张图多个主体。走 {@link IVisionClient}，
     * 桩实现返回中性默认（animal/狗/0.5），绝不随机乱认；{@code subjects} 按 confidence 降序，
     * 多主体时前端强制先选定单一主体，用户仍需点「就是 ta」确认或滚轮纠正（不阻断流程）。
     *
     * <p>出参带 {@code source}（{@code model|fallback}）如实标明来源：兜底默认不是识别结果，
     * 前端在 {@code fallback} 时不得把 {@code species} 当作「AI 认出来的」展示，直接请用户自己选
     * （AI 诚实标识红线）。</p>
     */
    private Object onboardingDetect(RequestContext ctx) {
        requireProfile(ctx);
        // 🔴 S1′「触发 AI 生成」：下一行就是一次视觉模型调用，花的是真钱。
        //    拦在动作发生前 —— 这一条不是可选的，产品选的是严格档。
        requireBound(ctx);
        String resourceId = Json.requireString(ctx.body(), "resourceId");
        DetectResult result = vision.detectWithSource(resourceId);
        logEvent("onboarding_detect", store.profile(ctx.accountId()));
        List<Object> views = new ArrayList<>();
        for (DetectSubject s : result.subjects()) {
            views.add(detectSubjectView(s));
        }
        Map<String, Object> data = Json.map();
        data.put("subjects", views);
        data.put("source", result.source().wire());
        return data;
    }

    private Map<String, Object> detectSubjectView(DetectSubject s) {
        Map<String, Object> v = Json.map();
        v.put("subjectType", s.subjectType().wire());
        v.put("species", s.species());
        v.put("confidence", s.confidence());
        if (s.box() != null) {
            Map<String, Object> box = Json.map();
            box.put("x", s.box().x());
            box.put("y", s.box().y());
            box.put("w", s.box().w());
            box.put("h", s.box().h());
            v.put("box", box);
        }
        return v;
    }

    private Object onboardingStart(RequestContext ctx) {
        AccountProfile profile = requireProfile(ctx);
        // 🔴 S1′「触发 AI 生成」：本方法末尾 generateCandidates 是一次 LLM 调用
        requireBound(ctx);
        JsonObject b = ctx.body();
        Onboarding o = new Onboarding();
        o.onboardingId = newId();
        o.accountId = profile.accountId;
        o.petName = Json.requireString(b, "petName");
        o.species = Json.getString(b, "species", "毛孩子");
        // 主体类型四个字段。🔴 用途是让 SR-D1 能在服务端求值，不是给展示用的——
        // 详见 Models.SubjectFields（含「为什么兜底是 other/default 而不是 animal/user」）。
        // 🔴 后三个字段此前是静默丢弃的，而 SR-D1 的后半句判据就在其中，
        //    缺了它那条兜底只在纸上成立。
        o.subjectType = SubjectFields.normalizeType(
                Json.getString(b, "subjectType", SubjectFields.DEFAULT_SUBJECT_TYPE));
        // null 是合法值：表示机器没给出可用判定 / 用户没动过预填值。
        // 🔴 不要「补齐」成某个具体类型——那会让「用户没答」这个状态消失，而它正是 SR-D1 的触发条件。
        o.machineSubjectType = SubjectFields.normalizeNullableType(
                Json.getString(b, "machineSubjectType", null));
        o.userSubjectType = SubjectFields.normalizeNullableType(
                Json.getString(b, "userSubjectType", null));
        o.subjectSource = SubjectFields.normalizeSource(
                Json.getString(b, "subjectSource", SubjectFields.DEFAULT_SUBJECT_SOURCE));
        o.rawDesc = Json.getString(b, "rawDesc", "");
        o.traits.addAll(readStringArray(b, "traits"));
        // PIPL 独立 opt-in：训练用途同意（默认 false），与纪念场景 allowUse 分开，随建档存起来
        o.trainConsent = Json.getBool(b, "trainConsent", false);
        // 训练语料回流用的可选素材/识别上下文（不影响主流程）
        o.inputRefs.addAll(readStringArray(b, "inputRefs"));
        String singleRef = Json.getString(b, "resourceId", null);
        if (singleRef != null && !o.inputRefs.contains(singleRef)) {
            o.inputRefs.add(singleRef);
        }
        o.detectedSpecies = Json.getString(b, "detectedSpecies", null);
        // 同意状态同步到账号维度（便于查询/撤回；纪念 allowUse 仍在 confirm 单独门控）
        profile.trainConsent = o.trainConsent;
        store.putProfile(profile);
        o.createTime = System.currentTimeMillis();
        o.candidates.addAll(generateCandidates(o, null));
        store.putOnboarding(o);
        logEvent("onboarding_start", profile);
        return Map.of("onboardingId", o.onboardingId, "candidates", candidateViews(o.candidates));
    }

    private Object onboardingRefine(RequestContext ctx) {
        requireProfile(ctx);
        // 🔴 S1′「触发 AI 生成」：重做一次候选就是再一次 LLM 调用，
        //    而 redoCount 没有上限 —— 这一处不拦，等于给了一个无限次的免费生成入口
        requireBound(ctx);
        JsonObject b = ctx.body();
        Onboarding o = requireOnboarding(Json.requireString(b, "onboardingId"));
        o.chosenCandidateId = Json.requireString(b, "chosenCandidateId");
        o.redoCount += 1; // 重做次数：训练语料回流的负向信号（候选越不满意越高）
        String adjust = Json.getString(b, "adjust", "");
        o.candidates.clear();
        o.candidates.addAll(generateCandidates(o, adjust));
        store.putOnboarding(o);
        logEvent("onboarding_refine", store.profile(o.accountId));
        return Map.of("candidates", candidateViews(o.candidates));
    }

    /**
     * 建档第 4 步：确认 —— 🔴 <b>{@code S1′}「建档」这一项的拦截点就在这里。</b>
     *
     * <h2>⚠️ 「拦在提交这一刻、不是进门就要」是一个<u>可推翻</u>的选择，不是 {@code S1′} 的要求</h2>
     *
     * <p>{@code S1′} 只说「建档需先绑定」，<b>没说在哪一步问</b>。选在这里是产品负责人
     * 2026-08-27 定的默认，理由是<b>填完四步再要身份，和进门就要，转化差得远</b>。
     * 🔴 <b>这一条随时可以推翻</b>（改成 {@link #onboardingStart} 或更早），
     * 推翻它不需要动 {@code S1′}。</p>
     *
     * <p>🔴 <b>但今天这个选择在实际流程里到不了</b>：建档第 1 步要先 {@code POST /upload}
     * （已拦）、第 2 步 {@link #onboardingDetect} 与第 3 步 {@link #onboardingStart} 都触发 AI
     * （已拦）。⚠️ 也就是说游客在第 1 步就被拦住了，<b>本方法这道守卫今天是第二道锁</b>。
     * 📌 这个冲突已报出等裁定：要让「填完四步再要身份」真的成立，必须先放开上传与 AI 那三处，
     * 而那三处正是花真钱的地方。</p>
     */
    private Object onboardingConfirm(RequestContext ctx) {
        AccountProfile profile = requireProfile(ctx);
        // 🔴 S1′「建档」+「触发 AI 生成」（本方法末尾 seedFirstEcho 会生成第一条近况）
        requireBound(ctx);
        JsonObject b = ctx.body();
        Onboarding o = requireOnboarding(Json.requireString(b, "onboardingId"));
        String finalId = Json.requireString(b, "finalCandidateId");
        JsonObject scene = b.has("memoryScene") && b.get("memoryScene").isJsonObject()
                ? b.getAsJsonObject("memoryScene") : new JsonObject();
        boolean allowUse = Json.getBool(scene, "allowUse", false);
        if (!allowUse) {
            // 护栏：纪念场景须用户明示允许使用才落库（PRD 要求）
            throw new ApiException(ApiException.RULE_FORBIDDEN,
                    "这段回忆要不要放进来，由你决定——同意后我们再收好它。", "memoryScene.allowUse must be true");
        }
        Candidate chosen = o.candidates.stream().filter(c -> c.id.equals(finalId)).findFirst()
                .orElse(o.candidates.isEmpty() ? null : o.candidates.get(0));

        PetProfile pet = new PetProfile();
        pet.petId = newId();
        pet.ownerAccountId = profile.accountId;
        pet.name = o.petName;
        pet.species = o.species;
        pet.signature = chosen != null ? chosen.signature : CopyGuardFilter.sanitize("换了个方式，一直陪着你");
        pet.temperature = Temperature.normalize(72.0);
        pet.visibility = "private"; // 定案 #1：默认 private
        if (chosen != null) {
            pet.coverGradient = chosen.gradient;
            pet.coverEmoji = chosen.emoji;
        }
        pet.memoryCaption = CopyGuardFilter.sanitize(Json.getString(scene, "caption", ""));
        pet.traits.addAll(o.traits); // 性情词随档案带入，供回声生成作上下文
        pet.trainConsent = o.trainConsent; // 训练同意随建档落到宠物维度
        // 🔴 主体类型四个字段必须落到宠物维度：建档态是短生命周期内存态，建完即弃，
        //    而 SR-D1 要在后续每一次生成时求值。只接不存等于没接。见 Models.SubjectFields。
        pet.subjectType = o.subjectType;
        pet.machineSubjectType = o.machineSubjectType;
        pet.userSubjectType = o.userSubjectType;
        pet.subjectSource = o.subjectSource;
        pet.createTime = System.currentTimeMillis();
        seedLifeBook(pet);
        store.putPet(pet);
        seedPostcards(pet);
        seedFirstEcho(pet);

        profile.hasPet = true;
        profile.trainConsent = o.trainConsent;
        store.putProfile(profile);
        logEvent("onboarding_confirm", profile);

        // 训练语料回流（PIPL 门控）：仅 trainConsent==true 才写入；未同意不落训练集（素材本身仍正常业务存）
        recordTrainSample(o, pet, finalId, null);
        return Map.of("petId", pet.petId);
    }

    // ========================================================== §3 我的它

    private Object petMe(RequestContext ctx) {
        PetProfile pet = requireMyPet(ctx);
        return myPetView(pet);
    }

    private Object petMePatch(RequestContext ctx) {
        PetProfile pet = requireMyPet(ctx);
        JsonObject b = ctx.body();
        if (b.has("signature")) {
            pet.signature = CopyGuardFilter.sanitize(Json.getString(b, "signature", pet.signature));
        }
        if (b.has("visibility")) {
            String v = Json.getString(b, "visibility", pet.visibility);
            pet.visibility = requireVisibility(v);
        }
        store.putPet(pet);
        return myPetView(pet);
    }

    /**
     * {@code POST /pet/me/visit} —— 记一次主人回访。
     *
     * <p>🔴 <b>近况一天一条</b>（{@link VisitEchoThrottle}）。节流前每次回访都产一条，
     * 一天点 10 次就 10 行 —— 而这 10 行是同一天、同一只宠物、同一份档案生成的，
     * ⚠️ <b>它们雷同不是巧合而是必然</b>。发布已改成用户主动，所以这不再是公开面问题，
     * 但它仍然是<b>存储</b>与<b>近况流质量</b>问题：用户翻自己的近况流会看到一屏复读。</p>
     *
     * <p>同一天再回访拿到的是<b>今天那一条</b>，🔴 <b>不是空列表</b> ——
     * 理由见 {@link VisitEchoThrottle} 类文档（「你来了，但今天没有近况」会被读成
     * 「它今天没有消息」，那是往缺席的方向推）。</p>
     *
     * <p>⚠️ 🔴 <b>温度这一侧没有节流，且本轮刻意<u>没有</u>顺手加。</b>
     * {@link Temperature#onOwnerVisit} 每调一次就朝天花板走一个 {@code HEAL_RATE}，
     * 所以一天点 10 次≈直接顶到 100。这是<b>同一个根因</b>（可重复写入没有闸），
     * 但改它会改变<b>回暖速度</b>这个产品可感知的数字，属产品裁定，不是工程修复。
     * 📋 <b>已报出，等裁定；不要在这里顺手改。</b></p>
     */
    private Object petVisit(RequestContext ctx) {
        // 🔴 S1′「触发 AI 生成」：回访会生成当天那条近况（buildEcho → LLM），一天一条。
        // 📌 新游客建不了档、走不到这里；这道守卫拦的是<b>存量游客窗主</b>——
        //    建档此前没有绑定守卫，所以那种账号在数据上是可能存在的。
        requireBound(ctx);
        PetProfile pet = requireMyPet(ctx);
        long now = System.currentTimeMillis();
        // 温度：只由主人回访驱动回暖（与献花无关，定案 #5）。
        // 📌 免费用户长期不来会按 kDecay 缓慢回落至地板 60（API-CONTRACT §3 / DECISIONS TM1），
        //    ⚠️ 回落尚未实现，且它不是惩罚式扣分 —— 详见 Temperature 类文档。
        pet.temperature = Temperature.onOwnerVisit(pet.temperature);
        pet.lastVisitAt = now;
        store.putPet(pet);

        // 🔴 今天已经有近况就复用，不再生成第二条
        PetEcho todays = VisitEchoThrottle.todaysEcho(
                store.echoesOfPet(pet.petId), now, ZoneId.systemDefault());
        boolean generated = todays == null;
        if (generated) {
            todays = buildEcho(pet);
            store.addEcho(todays);
        }
        logEvent("pet_visit", store.profile(pet.ownerAccountId));

        Map<String, Object> data = Json.map();
        data.put("temperature", round1(pet.temperature));
        data.put("newEchoes", List.of(echoView(todays)));
        // 前端可据此决定要不要做「新内容」动效。
        // 🔴 刻意不给「今天第几次来」的计数 —— 那是对回访行为的计量反馈，属 DP2。
        data.put("echoGenerated", generated);
        return data;
    }

    /**
     * dev-only（mi-2）：删除当前账号的宠物，回到未建档态，便于测试/联调重入建档流程。
     * 仅在 {@link #routes(boolean)} 以 devRoutes=true 装配时挂载；生产不暴露。
     */
    private Object resetPet(RequestContext ctx) {
        AccountProfile profile = requireProfile(ctx);
        PetProfile pet = store.petOfOwner(profile.accountId);
        if (pet != null) {
            store.deletePet(pet.petId, profile.accountId);
        }
        profile.hasPet = false;
        store.putProfile(profile);
        return Map.of("ok", true);
    }

    // ========================================================== §4 近况/来信

    private Object petEchoes(RequestContext ctx) {
        PetProfile pet = requireMyPet(ctx);
        List<PetEcho> all = store.echoesOfPet(pet.petId);
        all.sort((a, x) -> Long.compare(x.createdAt, a.createdAt));
        List<Object> views = new ArrayList<>();
        for (PetEcho e : all) {
            views.add(echoView(e));
        }
        logEvent("echo_view", store.profile(pet.ownerAccountId));
        return paginate(views, ctx.queryInt("cursor", 0), ctx.queryInt("limit", 20));
    }

    private Object petEchoReply(RequestContext ctx) {
        // 🔴 S1′「触发 AI 生成」：generateReply 是一次 LLM 调用，且无每日节流
        requireBound(ctx);
        PetProfile pet = requireMyPet(ctx);
        String echoId = ctx.path("echoId");
        PetEcho echo = store.echoById(echoId);
        if (echo == null || !echo.petId.equals(pet.petId)) {
            throw new ApiException(ApiException.NOT_FOUND, "这封信我没找到，也许它被收到别处去了。", "echo not found");
        }
        String text = Json.requireString(ctx.body(), "text");
        echo.reply = CopyGuardFilter.sanitize(generateReply(pet, text));
        store.addEcho(echo); // 覆盖引用即可（同对象）
        logEvent("echo_reply", store.profile(pet.ownerAccountId));
        // 回声反馈回流（停留/互动）：受 trainConsent 门控
        recordFeedbackSample(pet, echo.text, "stay");
        Map<String, Object> reply = Json.map();
        reply.put("text", echo.reply);
        reply.put("tone", echo.tone);
        return Map.of("echoId", echoId, "reply", reply);
    }

    // ========================================================== §5 献花 & 记得

    private Object flowerQuota(RequestContext ctx) {
        long accountId = ctx.accountId();
        int used = store.flowersUsedToday(accountId, today());
        int remaining = Math.max(0, DAILY_FREE_FLOWERS - used);
        return quotaView(used, remaining);
    }

    private Object flowerOffer(RequestContext ctx) {
        AccountProfile me = requireProfile(ctx);
        // 🔴 S1′：「献花」逐字在受限清单里，与「记得」同一条理由。
        // 📌 排在 requireProfile 之后：先确认「有没有这个账号」，再问「绑没绑」——
        //    profile 缺失时该回的是通行证的事，不是绑定的事。
        requireBound(ctx, BindingGuard.COPY_FLOWER);
        PetProfile pet = requireWindow(ctx.path("petId"));
        int count = Json.getInt(ctx.body(), "count", 1);
        String type = Json.getString(ctx.body(), "type", "daily");
        if (count <= 0) {
            throw new ApiException(ApiException.BAD_PARAM, "至少要有一束心意呀。", "count must be > 0");
        }
        // 🔴 拉黑静默失效：返回成功形状，但不落库、不累计、不通知任何人。
        //    见 GovernanceApi.interactionGuard —— 报错会把一次拉黑变成一次冲突升级。
        if (!canInteract(pet.ownerAccountId, me.accountId)) {
            int usedNow = store.flowersUsedToday(me.accountId, today());
            Map<String, Object> data = Json.map();
            data.put("ok", true);
            data.put("quota", quotaView(usedNow, Math.max(0, DAILY_FREE_FLOWERS - usedNow)));
            data.put("bondMark", "你为它献过 " + store.flowersFromTo(me.accountId, pet.ownerAccountId) + " 朵");
            return data;
        }
        // 看不见的窗不能献花：否则任何人都能给一扇 private 的窗献花，
        // 落进 t_flower_log 并累加 pet.flowersReceived。判在 canInteract 之后，理由见 rememberSet。
        requireVisibleWindow(pet, me.accountId);
        // 额度校验 + 落库放在同一同步块内，避免并发超发（定案 #3：每日 5 朵/可买）
        synchronized (store.flowerLock()) {
            int used = store.flowersUsedToday(me.accountId, today());
            int remaining = Math.max(0, DAILY_FREE_FLOWERS - used);
            int purchasedBalance = 0; // 购买余额本期占位为 0（充值为 TODO）
            if ("daily".equals(type) && count > remaining + purchasedBalance) {
                throw new ApiException(ApiException.RULE_QUOTA_EXCEEDED,
                        "今天的花都送出去啦，明天再来给它捎一束吧。",
                        "daily flower quota exceeded: need " + count + ", remaining " + remaining);
            }
            FlowerLog fl = new FlowerLog();
            fl.id = newId();
            fl.windowId = pet.petId;
            fl.fromAccountId = me.accountId;
            fl.toOwnerAccountId = pet.ownerAccountId;
            fl.count = count;
            fl.type = type;
            fl.message = CopyGuardFilter.sanitize(Json.getString(ctx.body(), "message", ""));
            fl.anonymous = Json.getBool(ctx.body(), "anonymous", false);
            fl.day = today();
            fl.createdAt = System.currentTimeMillis();
            store.addFlowerLog(fl);
            // 献花只累 owner 私域收花计数，绝不写温度（定案 #5）
            pet.flowersReceived += count;
            store.putPet(pet);

            int usedAfter = store.flowersUsedToday(me.accountId, today());
            int remainingAfter = Math.max(0, DAILY_FREE_FLOWERS - usedAfter);
            int bondTotal = store.flowersFromTo(me.accountId, pet.ownerAccountId);
            logEvent("flower_offer", me);
            Map<String, Object> data = Json.map();
            data.put("ok", true);
            data.put("quota", quotaView(usedAfter, remainingAfter));
            data.put("bondMark", "你为它献过 " + bondTotal + " 朵");
            return data;
        }
    }

    private Object rememberSet(RequestContext ctx) {
        // 🔴 S1′：「记得」逐字在受限清单里。理由是它汇成公开层的暖光与面孔墙，
        //    而游客是无限身份 —— 暖光可被零成本 farm，那不是「不获利」而是污染公开层信号。
        // 📌 拦在最前面：这个方法后面每一步（拉黑静默、可见性、落库）都不该为游客走。
        long accountId = requireBound(ctx, BindingGuard.COPY_REMEMBER);
        PetProfile pet = requireWindow(ctx.path("petId"));
        boolean remembered = Json.getBool(ctx.body(), "remembered", true);
        // 🔴 拉黑静默失效：回显"请求的那个值"，让被拉黑方看到的与成功完全一致。
        //    这里不能回 store.isRemembered(...)——那会回真实状态（false），
        //    与他刚刚提交的 true 不符，等于把拉黑暴露出来。
        // 🔴 这一判必须排在 canView 之前：换成先判 canView，被拉黑方会收到 404，
        //    而 404 与静默成功可区分，等于把拉黑说出来。
        if (!canInteract(pet.ownerAccountId, accountId)) {
            return Map.of("remembered", remembered);
        }
        // 看不见的窗不能记得：否则任何人都能对一扇 private 的窗按下「记得」，
        // 落进 t_remember 并抬高 owner 的 rememberFacesCount。
        requireVisibleWindow(pet, accountId);
        store.setRemember(pet.petId, accountId, remembered); // 幂等：状态开关，非累计
        logEvent("remember_toggle", store.profile(accountId));
        return Map.of("remembered", store.isRemembered(pet.petId, accountId));
    }

    /**
     * {@code GET /windows/:id/remember} —— 面孔墙。
     *
     * <p>🔴 <b>这个端点此前连 {@code canView} 都没有</b>：一扇 {@code private} 的窗，
     * 它的面孔墙任何人都能直接拉到，与页面渲染不渲染完全无关。
     * 「门在主路上（{@code /windows/:id} 有 {@code canView}），旁边全是小门」是这一族问题的形状。</p>
     *
     * <p>🔴 补上拉黑判定同时收掉了另一条路：前端 {@code toggleRemember} 写完回读这个端点，
     * 而它此前返回<b>真实状态</b>，把 {@link #rememberSet} 刻意做的静默失效一次抵消掉
     * （按钮翻回未选中 = 拉黑被暴露）。现在被拉黑方读不到这扇窗，回读不再是一条泄漏路径。</p>
     */
    private Object rememberWall(RequestContext ctx) {
        long accountId = ctx.accountId();
        PetProfile pet = requireWindow(ctx.path("petId"));
        if (!canReach(pet, accountId)) {
            throw new ApiException(ApiException.NOT_FOUND, "这扇窗现在是关着的，改天也许会为你开。", "window not visible");
        }
        return rememberWallView(pet, accountId);
    }

    /**
     * 看不见的窗一律当作不存在（与 {@link #windowDetail} 同一种回法）。
     *
     * <p>🔴 <b>只判可见性，不判拉黑</b>：写侧的拉黑是静默失效，调用方必须在进这里之前
     * 自己判过 {@link #canInteract}，否则被拉黑方会拿到 404。</p>
     */
    private void requireVisibleWindow(PetProfile pet, long viewer) {
        if (!canView(pet, viewer)) {
            throw new ApiException(ApiException.NOT_FOUND, "这扇窗现在是关着的，改天也许会为你开。", "window not visible");
        }
    }

    /**
     * 记得的"暖光面孔墙"视图（§5 GET remember / §6 窗口详情共用）：
     * {@code {warmthLevel, faces:[{isMe,avatar}], meRemembered}}，🔴 <b>按访客身份裁剪</b>。
     *
     * <p>红线：不返回精确总数、不排名。</p>
     *
     * <p>🔴 <b>{@code faces} 对非本人一律裁成空数组</b>（{@code W3}）。它下发的不是窗主的数据，
     * 是<b>第三方的名单</b>——名单上的人按下开关时，产品承诺的是 {@code D8} 静默誓约、
     * {@code D4} 不显精确数、{@code CR6} 不出现人数，没有一处告诉他头像会挂在窗上给别人看。
     * 窗主无权替他们决定，所以这不是可见性档位问题，是同意范围问题。</p>
     *
     * <p>🔴 <b>裁成空数组而不是删掉键</b>：{@code M-3} 要求这个键必含 {@code faces}，
     * 删键会让前端的面孔墙直接崩。空数组本身不泄露任何东西——它与「还没有人记得」不可区分。</p>
     */
    private Map<String, Object> rememberWallView(PetProfile pet, long viewer) {
        ViewerRole role = roleOf(pet, viewer);
        List<Long> faces = store.rememberFaces(pet.petId);
        Map<String, Object> data = Json.map();
        if (VisibilityMatrix.visible(WindowBlock.WARMTH_LEVEL, role)) {
            data.put("warmthLevel", warmthLevel(faces.size()));
        }
        data.put("faces", VisibilityMatrix.visible(WindowBlock.REMEMBER_FACES, role)
                ? facesView(faces, viewer) : List.of());
        if (VisibilityMatrix.visible(WindowBlock.ME_REMEMBERED, role)) {
            data.put("meRemembered", store.isRemembered(pet.petId, viewer));
        }
        return data;
    }

    /**
     * 面孔墙的一屏头像（有上限）。
     *
     * <p>🔴 <b>不下发 {@code accountId}</b>：那个 id 拿去调 {@code GET /users/:id} 就能换回昵称、
     * 签名、粉丝数——「静默誓约」在网络层根本不匿名。前端拿它只做一件事（判断这张脸是不是自己），
     * 那件事一个布尔就够，所以这里给 {@code isMe}。</p>
     */
    private List<Object> facesView(List<Long> faces, long viewer) {
        List<Object> faceViews = new ArrayList<>();
        int cap = Math.min(faces.size(), 60);
        for (int i = 0; i < cap; i++) {
            long fid = faces.get(i);
            AccountProfile fp = store.profile(fid);
            Map<String, Object> fv = Json.map();
            fv.put("isMe", fid == viewer);
            fv.put("avatar", fp != null ? fp.avatar : "");
            faceViews.add(fv);
        }
        return faceViews;
    }

    // ========================================================== §6 窗口/广场

    /**
     * 广场当前下发的口径：🔴 <b>回忆卡</b>（{@code cardId}）。
     *
     * <p>🔴 <b>2026-08-27 从 {@code KIND_WINDOW} 改成 {@code KIND_CARD}</b>——广场改发回忆卡。
     * 抽成常量是为了让启动自检的判据和实际下发口径<b>只有一个来源</b>，
     * 不会出现「改了下发忘了改告警」。</p>
     *
     * <p>⚠️ 🔴 <b>改成 {@code KIND_CARD} 并不让 {@code n} 开始增长。</b>
     * {@code GET /plaza} 是<b>网格层</b>（瀑布发现层），而 {@code SPEC-feed-surfaces} 概述第 1 条
     * 定的是「{@code n} 只在全屏层记，网格里被列出、被滚过一律不计」。
     * 所以广场的曝光仍然会被拒收，只是原因从
     * {@code not_card_feed} 换成了 {@code grid_surface}——<b>这一次是规格要求的拒收，
     * 不是口径不通的拒收</b>。见 {@link com.echo.http.ranking.RankingReadiness}。</p>
     */
    public static final String PLAZA_FEED_KIND = FeedRequestRegistry.KIND_CARD;

    /**
     * 广场所在的下发面：🔴 <b>网格层</b>。
     *
     * <p>决定这批曝光算不算 {@code n}（不算，见 {@link FeedRequestRegistry#SURFACE_GRID}）。</p>
     */
    public static final String PLAZA_FEED_SURFACE = FeedRequestRegistry.SURFACE_GRID;

    /**
     * 全屏单卡层是否已实现。⚠️ <b>尚未实现</b>——没有任何端点用
     * {@link FeedRequestRegistry#SURFACE_IMMERSIVE} 登记快照。
     *
     * <p>🔴 它是 {@code n} 能不能增长的<b>第二条</b>前置（第一条是 {@link #PLAZA_FEED_KIND}）。
     * 立成常量而不是让自检去猜，理由同 {@code PLAZA_FEED_KIND}：判据与事实要同源。
     * 实现全屏层的那一天把它改成 {@code true}。</p>
     */
    public static final boolean IMMERSIVE_FEED_IMPLEMENTED = false;

    /** 广场单页上限。 */
    private static final int PLAZA_PAGE_MAX = 20;

    /**
     * {@code GET /plaza} —— 共鸣厅瀑布，🔴 <b>下发回忆卡</b>（不是宠物窗口）。
     *
     * <p>🔴 <b>{@code items[].id} 是 {@code cardId}，不再是 {@code petId}。</b>
     * 卡上另给 {@code petId} 供前端跳窗口页——所有 {@code /windows/:petId/...} 请改用那个字段，
     * 拿 {@code id} 去调会 404。</p>
     *
     * <p>三道过滤，缺一不可：</p>
     * <ol>
     *   <li>卡自身 {@code status='public'}（已过审）；</li>
     *   <li>🔴 <b>生效可见性为 public</b>——{@code min(卡, 窗)}，见 {@link CardVisibility}。
     *       ⚠️ <b>不能只看 {@code card.visibility}</b>：窗收窄之后那一列是偏宽的，
     *       只看它等于「窗关了、内容还在外面」；</li>
     *   <li>拉黑双方互不出现在对方的瀑布上（{@code S8}）。少了这一条，拉黑就只挡住了「人」
     *       （{@code /users/:id} 已 404），没挡住「内容」。</li>
     * </ol>
     *
     * <p>🔴 <b>不按 {@code pinnedAt} 排序</b>：置顶只作用于作者自己那一页，
     * 绝不进共鸣厅公开流——那会和权重衰减正面打架（{@link PinPolicy}）。</p>
     */
    private Object plaza(RequestContext ctx) {
        long viewer = ctx.accountId();
        List<Object> items = new ArrayList<>();
        if (cardStore != null) {
            // 多取一些再过滤：可见性交集与拉黑都得在应用层判，SQL 层拿不到窗的可见性
            for (MemoryCard card : cardStore.publicCards(PLAZA_PAGE_MAX * 10)) {
                PetProfile pet = store.petById(String.valueOf(card.petId));
                if (pet == null) {
                    continue;
                }
                // 🔴 取交集，不是读 card.visibility
                if (!CardVisibility.effectivelyPublic(card.visibilityIntent, pet.visibility)) {
                    continue;
                }
                if (hiddenBetween(pet.ownerAccountId, viewer)) {
                    continue;
                }
                // authorView=false：陌生人不拿 pinnedAt / visibility / status
                items.add(CardView.of(card, pet.visibility, topicIdsOf(card), false));
            }
        }
        logEvent("window_open", store.profile(ctx.accountId()));
        Map<String, Object> page = paginate(items, ctx.queryInt("cursor", 0),
                ctx.queryInt("limit", PLAZA_PAGE_MAX));
        attachReqId(ctx, page);
        return page;
    }

    // ================================================== §6b 回忆卡（C-2 / C-5 / 置顶）

    /**
     * {@code GET /pet/me/cards} —— 作者自己那一页的卡列表。
     *
     * <p>🔴 <b>置顶只在这里生效</b>：顺序 {@code pinnedAt DESC NULLS LAST, publishedAt DESC}
     * （{@link PinPolicy#ORDER}）。含草稿 / 审核中 / 被打回的卡——作者要看得到自己的全部内容。</p>
     */
    private Object myCards(RequestContext ctx) {
        long me = requireAccount(ctx);
        PetProfile pet = store.petOfOwner(me);
        if (pet == null || cardStore == null) {
            return Map.of("items", List.of(), "maxPinned", pinPolicy.maxPinned(), "pinned", 0);
        }
        List<Object> items = new ArrayList<>();
        for (MemoryCard card : cardStore.cardsOfOwner(me, 200)) {
            // authorView=true：本人才拿 pinnedAt / visibility / visibilityIntent
            Map<String, Object> v = CardView.of(card, pet.visibility, topicIdsOf(card), true);
            // 作者视图额外给状态：他需要知道哪张还压在审核里、哪张被打回
            v.put("status", card.status);
            v.put("pinnable", pinPolicy.pinnable(card, pet.visibility));
            items.add(v);
        }
        Map<String, Object> out = Json.map();
        out.put("items", items);
        out.put("maxPinned", pinPolicy.maxPinned());
        out.put("pinned", cardStore.countPinned(me));
        // 🔴 把窗的可见性一并回：卡的可见性上限就是它，前端要据此禁掉更宽的档位，
        //    而不是让用户点了才被拒（那是一次可以提前避免的失败）。
        out.put("windowVisibility", pet.visibility);
        return out;
    }

    /**
     * {@code GET /users/:id/cards} —— 看别人主页的卡列表。
     *
     * <p>🔴 <b>只出生效可见性满足访客身份的卡</b>，且不下发 {@code pinnedAt}——
     * 置顶是作者的自我编排，不是对外的排序信号。⚠️ 顺序仍按 {@link PinPolicy#ORDER}：
     * 作者主页<b>是</b>置顶的作用域，这与"不进共鸣厅公开流"不矛盾。</p>
     */
    private Object userCards(RequestContext ctx) {
        long viewer = ctx.accountId();
        long targetId = parseAccountId(ctx.path("id"));
        PetProfile pet = store.petOfOwner(targetId);
        if (pet == null || cardStore == null || hiddenBetween(targetId, viewer)) {
            // 🔴 拉黑后连空列表都不给出区别：与「这人没有卡」不可区分（S8）
            return Map.of("items", List.of());
        }
        boolean isMine = viewer == targetId;
        ViewerRole role = roleOf(pet, viewer);
        List<Object> items = new ArrayList<>();
        for (MemoryCard card : cardStore.cardsOfOwner(targetId, 200)) {
            if (!isMine && !CardStatus.PUBLIC.equals(card.status)) {
                continue;   // 别人只看得到已过审的
            }
            String effective = CardVisibility.effective(card.visibilityIntent, pet.visibility);
            if (!isMine && !visibleToRole(effective, role)) {
                continue;
            }
            items.add(CardView.of(card, pet.visibility, topicIdsOf(card), isMine));
        }
        return Map.of("items", items);
    }

    /** 生效可见性对该访客身份是否可见。{@code public} 谁都行，{@code friends} 限亲友与本人。 */
    private static boolean visibleToRole(String effectiveVisibility, ViewerRole role) {
        if (CardVisibility.PUBLIC.equals(effectiveVisibility)) {
            return true;
        }
        if (CardVisibility.FRIENDS.equals(effectiveVisibility)) {
            return role == ViewerRole.OWNER || role == ViewerRole.FRIEND;
        }
        return role == ViewerRole.OWNER;   // private
    }

    /**
     * {@code PATCH /cards/:id/visibility} —— 🔴 作者自改单张卡的可见性（三档）。
     *
     * <p>数据层与流水白名单早就假设作者可自改可见性
     * （{@code t_card_visibility_log.changedRole} 含 {@code author}），缺的一直是这个端点。</p>
     *
     * <p>🔴 <b>硬约束「卡不得宽于窗」在这里是<b>写时拒绝</b></b>，不是静默收窄：
     * 静默收窄的话作者点了「公开」、界面回成功，而这张卡其实只有亲友能看——
     * ⚠️ 他会以为自己已经发出去了，而这个误解要等到「怎么没人看」才会被发现。</p>
     *
     * <p>🔴 <b>收窄到非 public 时自动解除置顶</b>，与可见性同一事务，不留悬空。</p>
     */
    private Object patchCardVisibility(RequestContext ctx) {
        long me = requireAccount(ctx);
        long cardId = parseCardId(ctx.path("id"));
        requireCardStore();
        MemoryCard card = cardStore.card(cardId);
        if (card == null || card.deletedAt != null || card.ownerId != me) {
            // 🔴 不区分"不是你的"与"不存在"，避免成为归属探测工具
            throw new ApiException(ApiException.NOT_FOUND, "这张回忆卡找不到了。",
                    "card not found or not owned: " + cardId);
        }
        String to = Json.getString(ctx.body(), "visibility", "");
        if (!CardVisibility.isValid(to)) {
            throw new ApiException(ApiException.BAD_PARAM, "这个可见范围不太对，换一个试试。",
                    "invalid visibility: " + to + ", expected one of " + CardVisibility.TIERS);
        }
        // 🔴 S1′/S2：放宽到 public 就是「发布到公开层」，那一项逐字在受限清单里，必须已绑定。
        // ⚠️ 只拦「放宽到 public」这一个方向：
        //    · 收窄（public → friends/private）是安全正向动作，任何情况下都不许拦；
        //    · 本来就是 public 时重复提交是幂等无操作，拦它只是白挡一次。
        if (CardVisibility.PUBLIC.equals(to) && !CardVisibility.PUBLIC.equals(card.visibilityIntent)) {
            requireBound(ctx);
        }
        PetProfile pet = store.petById(String.valueOf(card.petId));
        String windowVisibility = pet == null ? CardVisibility.PRIVATE : pet.visibility;
        if (CardVisibility.widerThanWindow(to, windowVisibility)) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "这扇窗现在没有开那么大，先把窗的可见范围放宽一点吧。",
                    "card visibility " + to + " wider than window " + windowVisibility);
        }

        boolean clearPin = !CardVisibility.PUBLIC.equals(to) && card.pinnedAt != null;
        if (!cardStore.changeVisibilityAtomically(cardId, me, card.visibilityIntent, to, clearPin,
                System.currentTimeMillis())) {
            // CAS 未命中：另一个请求抢先改过。不重试——重试会让流水上出现一条 from 是错的记录，
            // 而 t_card_visibility_log 只追加不修改，写错了没法回头改
            throw new ApiException(ModerationStateMachine.ERR_STATE_CONFLICT,
                    "刚刚已经改过一次了，刷新看看？", "visibility CAS miss on card " + cardId);
        }
        Map<String, Object> out = Json.map();
        out.put("cardId", String.valueOf(cardId));
        out.put("visibilityIntent", to);
        // 生效值可能比意图窄（窗更窄时）。回生效值，让作者立刻看到真实结果
        out.put("visibility", CardVisibility.effective(to, windowVisibility));
        out.put("windowVisibility", windowVisibility);
        out.put("unpinned", clearPin);
        return out;
    }

    /**
     * {@code PUT /cards/:id/pin} —— 置顶。
     *
     * <p>🔴 上限 {@link PinPolicy#maxPinned()}（默认 3，后台可配），
     * 仅 {@code public} 且已过审可置顶，上限校验与写入同一事务。</p>
     */
    private Object pinCard(RequestContext ctx) {
        return setPin(ctx, true);
    }

    /** {@code DELETE /cards/:id/pin} —— 取消置顶。幂等。 */
    private Object unpinCard(RequestContext ctx) {
        return setPin(ctx, false);
    }

    private Object setPin(RequestContext ctx, boolean pin) {
        // 置顶是作者的自我编排（只作用于自己主页、不进共鸣厅公开流），不产生公开层信号，
        // 所以不在 S1′ 射程内 —— 这里要的只是「有账号」
        long me = requireAccount(ctx);
        long cardId = parseCardId(ctx.path("id"));
        requireCardStore();
        ModerationStore.PinOutcome outcome = cardStore.setPinnedAtomically(
                cardId, me, pin, pinPolicy.maxPinned(), System.currentTimeMillis());
        switch (outcome) {
            case AT_CAPACITY -> throw new ApiException(ApiException.BAD_PARAM,
                    "最多只能置顶 " + pinPolicy.maxPinned() + " 张，先取消一张吧。",
                    "pin capacity reached: max=" + pinPolicy.maxPinned());
            case NOT_FOUND -> throw new ApiException(ApiException.NOT_FOUND,
                    "这张回忆卡还不能置顶——先把它公开发布出去。",
                    "card not pinnable: " + cardId + " (needs own + public + reviewed)");
            default -> { /* OK */ }
        }
        Map<String, Object> out = Json.map();
        out.put("cardId", String.valueOf(cardId));
        out.put("pinned", pin);
        out.put("maxPinned", pinPolicy.maxPinned());
        out.put("pinnedCount", cardStore.countPinned(me));
        return out;
    }

    private void requireCardStore() {
        if (cardStore == null) {
            throw new ApiException(ApiException.SERVER_ERROR,
                    "这里出了点小状况，待会儿再来看看它好吗？", "card store not wired");
        }
    }

    /**
     * 只要求有账号 —— 🔴 <b>这不是 {@code S1′}</b>，游客过得去。
     *
     * <p>🔴 <b>此前这个方法体里的注释逐字写着「{@code S1′}：一切写操作都需要可验证的身份绑定」，
     * 而实现是 {@code if (id <= 0) throw}</b> —— 只判有没有 token，游客有，所以三处调用点
     * 全部放行。⚠️ 它与 {@code LeaveWordsApi} / {@code GovernanceApi} 里那两份
     * {@code requireBound} 是<b>同一个错的第三份拷贝</b>（同样的方法体、同样的注释、同样的编号）。</p>
     *
     * <p>注释已经拿掉：这个方法要的就是「有一个已鉴权账号」，它<b>本来也没打算</b>执行
     * {@code S1′}。真绑定判定在 {@link BindingGuard#requireBound}。</p>
     */
    private long requireAccount(RequestContext ctx) {
        return BindingGuard.requireAuthenticated(ctx);
    }

    /**
     * {@code S1′} 的绑定判定（本类内的入口），用兜底文案。
     *
     * <p>📌 本类内的调用点：{@link #onboardingDetect} · {@link #onboardingStart} ·
     * {@link #onboardingRefine} · {@link #onboardingConfirm} · {@link #petVisit} ·
     * {@link #petEchoReply} · {@link #patchCardVisibility}（仅放宽到 public 那一档）。
     * 「记得」与「献花」走 {@link #requireBound(RequestContext, String)}，产品给了专门文案。</p>
     */
    private long requireBound(RequestContext ctx) {
        return BindingGuard.requireBound(store, ctx);
    }

    /** 同上，用产品指定的那一句文案（只许传 {@code BindingGuard.COPY_*}）。 */
    private long requireBound(RequestContext ctx, String userMessage) {
        return BindingGuard.requireBound(store, ctx, userMessage);
    }

    private static long parseCardId(String raw) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            throw new ApiException(ApiException.BAD_PARAM, "这个不太对，换个方式试试。",
                    "invalid card id: " + raw);
        }
    }

    /** 解析 {@code topicIds} 的 json 文本成字符串数组；解析不了回空数组（不抛错、不半发）。 */
    static List<String> topicIdsOf(MemoryCard card) {
        if (card.topicIdsJson == null || card.topicIdsJson.isBlank()) {
            return List.of();
        }
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser
                    .parseString(card.topicIdsJson).getAsJsonArray();
            List<String> out = new ArrayList<>(arr.size());
            for (com.google.gson.JsonElement e : arr) {
                out.add(e.getAsString());
            }
            return out;
        } catch (RuntimeException e) {
            // 脏数据不该让整张卡发不出去：标签是附属信息，卡本体才是内容
            log.warn("卡 {} 的 topicIds 解析失败，按空数组下发：{}", card.id, card.topicIdsJson);
            return List.of();
        }
    }

    /**
     * 给本次下发登记 reqId 快照并写进响应（曝光上报的五道防刷校验全部以此为依据，
     * TECH-DESIGN §3.10.4 校验 1–4）。未注入 registry 时（纯内存联调）静默跳过。
     */
    private void attachReqId(RequestContext ctx, Map<String, Object> page) {
        if (feedRequests == null) {
            return;
        }
        Object items = page.get("items");
        if (!(items instanceof List<?> list)) {
            return;
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                if (id != null) {
                    ids.add(String.valueOf(id));
                }
            }
        }
        // channel/pool 留空：召回通道与池归属由排序引擎（尚未实现）填，此处先记"下发了什么给谁"。
        // 🔴 boostIds 同理为空 —— 冷启动保底位未实现，现阶段全部曝光的 viaBoost 都是 0。
        page.put("reqId", feedRequests.register(ctx.accountId(),
                PLAZA_FEED_KIND, PLAZA_FEED_SURFACE, ids, Set.of(), "", ""));
    }

    private Object windowDetail(RequestContext ctx) {
        long viewer = ctx.accountId();
        PetProfile pet = requireWindow(ctx.path("petId"));
        // 🔴 canReach 而不是 canView：拉黑过的两个人之间，公开窗也不可达（S8）。
        //    只过 canView 的话，被拉黑方照样能点进详情——拉黑挡住了「人」，没挡住「内容」。
        if (!canReach(pet, viewer)) {
            throw new ApiException(ApiException.NOT_FOUND, "这扇窗现在是关着的，改天也许会为你开。", "window not visible");
        }
        ViewerRole role = roleOf(pet, viewer);
        Map<String, Object> data = windowCard(pet);
        if (VisibilityMatrix.visible(WindowBlock.VISIBILITY_SETTING, role)) {
            data.put("visibility", pet.visibility);
        }
        if (VisibilityMatrix.visible(WindowBlock.LIFE_BOOK, role)) {
            data.put("lifeBook", lifeBookView(pet));
        }
        data.put("flowerAllowed", VisibilityMatrix.visible(WindowBlock.FLOWER_BUTTON, role));
        data.put("rememberAllowed", VisibilityMatrix.visible(WindowBlock.REMEMBER_BUTTON, role));
        // 🔴 归属与「能不能献花」分开下发。两者今天恰好互为反面，但 flowerAllowed 迟早会
        //    多出别的限制（对方关了互动、被拉黑），届时若前端还拿它当归属判据，
        //    留言的作者侧界面会跑到别人的窗上去。
        data.put("isMine", viewer == pet.ownerAccountId);
        // M-3：记得面孔墙键名固定为 rememberWall 且必含 faces（前端面孔墙依赖，§6/§5 同形状）
        data.put("rememberWall", rememberWallView(pet, viewer));
        return data;
    }

    /**
     * {@code POST /windows/:id/seen} —— 看过一次。
     *
     * <p>🔴 <b>不可达时静默不计</b>：被拉黑方（以及任何看不到这扇窗的人）的脚印不该累进
     * owner 的 {@code seenCount}——否则 owner 在「我的」看到的「N 次被看见」里含着拉黑对象，
     * 与 {@code S8}「彻底不可见」相悖。回成功形状而不报错，理由同献花/记得的静默失效。</p>
     */
    private Object windowSeen(RequestContext ctx) {
        PetProfile pet = requireWindow(ctx.path("petId"));
        if (canReach(pet, ctx.accountId())) {
            pet.seenCount += 1; // 看过数只累 owner 内部计数，不对外暴露（定案 #4）
            store.putPet(pet);
            logEvent("window_seen", store.profile(ctx.accountId()));
        }
        return Map.of("ok", true);
    }

    private Object petInsights(RequestContext ctx) {
        PetProfile pet = requireMyPet(ctx);
        // owner 私域数据：看过数/记得面孔数/收花数仅本人可见
        Map<String, Object> data = Json.map();
        data.put("seenCount", pet.seenCount);
        data.put("rememberFacesCount", store.rememberFaces(pet.petId).size());
        data.put("flowersReceived", pet.flowersReceived);
        return data;
    }

    // ========================================================== §7 明信片 + 商店

    private Object postcards(RequestContext ctx) {
        PetProfile pet = requireMyPet(ctx);
        List<Object> views = new ArrayList<>();
        for (Postcard c : store.postcards(pet.petId)) {
            views.add(postcardView(c));
        }
        // 统一分页信封 {items,nextCursor}（QA M-8），与 records/messages 对齐；复用 paginate()。
        // 🔴 但顺序相反：这里是 createdAt 升序（最早一张在最上面），records/messages 是降序。
        // 明信片墙是一条时间线叙事，从头看到尾才成立——不要为了"统一"把它翻过来。
        return paginate(views, ctx.queryInt("cursor", 0), ctx.queryInt("limit", 20));
    }

    private Object postcardUnlock(RequestContext ctx) {
        PetProfile pet = requireMyPet(ctx);
        String id = ctx.path("id");
        Postcard card = store.postcard(pet.petId, id);
        if (card == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这张明信片还没到时候，再陪它走一段就好。", "postcard not found");
        }
        // 护栏：内容永远靠陪伴解锁；即便请求里带 paid 也拒绝对"内容本身"付费解锁（定案 #2）
        if (Json.getBool(ctx.body(), "paid", false)) {
            throw new ApiException(ApiException.RULE_FORBIDDEN,
                    "这些回忆不卖——它们只会在你们相伴够久时，自己走出来。", "content unlock cannot be purchased");
        }
        card.locked = false;
        // 覆盖回写
        List<Postcard> cards = store.postcards(pet.petId);
        store.putPostcards(pet.petId, cards);
        logEvent("postcard_unlock", store.profile(pet.ownerAccountId));
        return Map.of("unlocked", true);
    }

    private Object shopSkins(RequestContext ctx) {
        requireProfile(ctx);
        // 只卖皮肤/边框/材质，绝不锁内容（定案 #2）
        List<Object> skins = new ArrayList<>();
        skins.add(skin("skin_dusk", "暮色", "gradient", 0));
        skins.add(skin("skin_gold_frame", "暖金边框", "frame", 6));
        skins.add(skin("skin_paper", "旧纸材质", "material", 6));
        return Map.of("items", skins);
    }

    private Object shopPurchase(RequestContext ctx) {
        requireProfile(ctx);
        String skinId = Json.requireString(ctx.body(), "skinId");
        // 服务端护栏：购买仅款式，不影响任何解锁进度
        return Map.of("ok", true, "skinId", skinId, "affectsUnlock", false);
    }

    // ========================================================== §8 亲友

    private Object relations(RequestContext ctx) {
        long accountId = ctx.accountId();
        long now = System.currentTimeMillis();
        List<RelationEntry> list = new ArrayList<>(store.relations(accountId));
        // 排序：置顶 > 在线 > 最近活跃；静音(mutedUntil>now)降权
        list.sort((a, b) -> {
            int pa = a.priority ? 1 : 0;
            int pb = b.priority ? 1 : 0;
            if (pa != pb) {
                return pb - pa;
            }
            int oa = (a.online && a.mutedUntil <= now) ? 1 : 0;
            int ob = (b.online && b.mutedUntil <= now) ? 1 : 0;
            if (oa != ob) {
                return ob - oa;
            }
            return Long.compare(lastActiveOf(b), lastActiveOf(a)); // 最近活跃优先
        });
        List<Object> views = new ArrayList<>();
        for (RelationEntry rel : list) {
            views.add(relationView(rel, now));
        }
        return Map.of("items", views);
    }

    private Object relationPatch(RequestContext ctx) {
        long accountId = ctx.accountId();
        RelationEntry rel = store.relation(accountId, ctx.path("id"));
        if (rel == null) {
            throw new ApiException(ApiException.NOT_FOUND, "没找到这位亲友，也许 TA 换了扇窗。", "relation not found");
        }
        JsonObject b = ctx.body();
        if (b.has("priority")) {
            rel.priority = Json.getBool(b, "priority", rel.priority);
        }
        if (b.has("mute")) {
            rel.mutedUntil = resolveMute(Json.getString(b, "mute", "clear"));
        }
        store.updateRelation(rel); // 回写（PG 落库据此 UPDATE；内存态为引用语义空操作）
        return relationView(rel, System.currentTimeMillis());
    }

    private Object relationReelSeen(RequestContext ctx) {
        long accountId = ctx.accountId();
        RelationEntry rel = store.relation(accountId, ctx.path("id"));
        if (rel == null) {
            throw new ApiException(ApiException.NOT_FOUND, "没找到这位亲友，也许 TA 换了扇窗。", "relation not found");
        }
        rel.hasUnseenReel = false;
        store.updateRelation(rel); // 回写（PG 落库据此 UPDATE；内存态为引用语义空操作）
        return Map.of("ok", true);
    }

    // ================================================ §8b 他人主页 & 关注

    /**
     * {@code GET /users/:id} —— 别人的主页。
     *
     * <p>🔴 <b>粉丝数公开且精确</b>（{@code E1b}）：给真数，不模糊化成「1000+」。
     * 但全站<b>不做「最受欢迎作者」榜</b>——所以这个数字只在<b>本人主页上</b>出现，
     * 没有任何端点会把一堆人按它排序。这两条不矛盾：
     * 「你有多少粉丝」是作者自己的事实，「谁粉丝最多」是平台替所有人排的名次。</p>
     *
     * <p>🔴 <b>两人之间有任一方向拉黑时 404</b>，与 {@link #windowDetail} 对不可见的窗
     * 用同一种回法。不能回一个「你被拉黑了」——那会把一次拉黑变成一次冲突升级
     * （理由见 {@link BlockService} 类注释）；也不能回一个空壳主页，
     * 那等于告诉对方「这个人存在但不给你看」。</p>
     */
    private Object userProfile(RequestContext ctx) {
        long me = ctx.accountId();
        long target = parseAccountId(ctx.path("id"));
        AccountProfile profile = store.profile(target);
        if (profile == null || hiddenBetween(me, target)) {
            throw new ApiException(ApiException.NOT_FOUND, "没找到这个人，也许 TA 换了扇窗。",
                    "user not found or hidden");
        }
        Map<String, Object> v = Json.map();
        v.put("userId", String.valueOf(target));
        v.put("nickname", profile.nickname);
        v.put("avatar", profile.avatar);
        v.put("isMe", me == target);
        v.put("followerCount", followStore == null ? 0 : followStore.followerCount(target));
        v.put("followingCount", followStore == null ? 0 : followStore.followingCount(target));
        v.put("following", followStore != null && followStore.isFollowing(me, target));
        return v;
    }

    /**
     * {@code GET /users/:id/windows} —— 这个人的窗，按<b>我能不能看</b>过滤。
     *
     * <p>可见性判定复用 {@link #canView}，🔴 <b>不要在这里另写一套</b>：
     * 两处各自判断谁能看什么，迟早会分叉，而分叉的方向是把不该露的露出去。</p>
     *
     * <p>一个账号目前只有一扇窗（{@code store.petOfOwner}），所以这里最多一条。
     * 仍然套 {@code {items,nextCursor}} 信封：等哪天一人多窗，
     * 客户端不用改形状，也不用再判断一次「这个端点是不是分页的」。</p>
     */
    private Object userWindows(RequestContext ctx) {
        long me = ctx.accountId();
        long target = parseAccountId(ctx.path("id"));
        if (store.profile(target) == null || hiddenBetween(me, target)) {
            throw new ApiException(ApiException.NOT_FOUND, "没找到这个人，也许 TA 换了扇窗。",
                    "user not found or hidden");
        }
        List<Object> windows = new ArrayList<>();
        PetProfile pet = store.petOfOwner(target);
        if (pet != null && canView(pet, me)) {
            windows.add(windowCard(pet));
        }
        return paginate(windows, ctx.queryInt("cursor", 0), ctx.queryInt("limit", 20));
    }

    /**
     * {@code POST /users/:id/follow} —— 关注。幂等。
     *
     * <p>🔴 <b>被拉黑时静默失效</b>：返回与成功完全一致的形状，但不落库
     * （与献花 / 记得同一套口径）。这里之所以不会泄漏，是因为
     * {@link #userProfile} 对拉黑双方一律 404——关注方根本读不到那个粉丝数，
     * 也就无从对照出自己这一次没生效。</p>
     *
     * <p>出参<b>只回关注状态、不回粉丝数</b>。静默失效那条路上如果要回粉丝数，
     * 就只有两个选择：回真数（没涨，等于承认没生效）或者回假数（凭空捏一个）。
     * 不回，两难就不存在；真要看数去读主页。</p>
     */
    private Object followUser(RequestContext ctx) {
        long me = ctx.accountId();
        long target = parseAccountId(ctx.path("id"));
        requireFollowTarget(me, target);
        if (!hiddenBetween(me, target)) {
            followStore.follow(idGenerator.nextId(), me, target, System.currentTimeMillis());
        }
        return Map.of("following", true);
    }

    /** {@code DELETE /users/:id/follow} —— 取关。幂等。 */
    private Object unfollowUser(RequestContext ctx) {
        long me = ctx.accountId();
        long target = parseAccountId(ctx.path("id"));
        requireFollowTarget(me, target);
        followStore.unfollow(me, target);
        return Map.of("following", false);
    }

    private void requireFollowTarget(long me, long target) {
        if (followStore == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这个功能还没开。", "follow store not wired");
        }
        if (me == target) {
            throw new ApiException(ApiException.BAD_PARAM, "这个人是你自己呀。", "cannot follow self");
        }
        if (store.profile(target) == null) {
            throw new ApiException(ApiException.NOT_FOUND, "没找到这个人，也许 TA 换了扇窗。", "user not found");
        }
    }

    private long parseAccountId(String raw) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ApiException.BAD_PARAM, "没找到这个人，也许 TA 换了扇窗。",
                    "invalid account id: " + raw);
        }
    }

    // ========================================================== §9 记录

    private Object recordsList(RequestContext ctx) {
        long accountId = ctx.accountId();
        String scope = ctx.query("scope", "all");
        List<RecordEntry> all = store.records(accountId);
        all.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        List<Object> views = new ArrayList<>();
        for (RecordEntry rec : all) {
            if (!"all".equals(scope) && !scope.equals(rec.scope)) {
                continue;
            }
            Map<String, Object> v = Json.map();
            v.put("id", rec.id);
            v.put("scope", rec.scope);
            v.put("text", rec.text);
            v.put("createdAt", rec.createdAt);
            views.add(v);
        }
        return paginate(views, ctx.queryInt("cursor", 0), ctx.queryInt("limit", 20));
    }

    private Object recordCreate(RequestContext ctx) {
        long accountId = ctx.accountId();
        JsonObject b = ctx.body();
        String scope = Json.getString(b, "scope", "self");
        if (!scope.equals("pet") && !scope.equals("self")) {
            throw new ApiException(ApiException.BAD_PARAM, "记给它，还是记给自己？选一个吧。", "invalid scope");
        }
        RecordEntry rec = new RecordEntry();
        rec.id = newId();
        rec.accountId = accountId;
        rec.scope = scope;
        rec.text = CopyGuardFilter.sanitize(Json.requireString(b, "text"));
        rec.createdAt = System.currentTimeMillis();
        store.addRecord(rec);
        logEvent("record_create", store.profile(accountId));
        // 护栏：绝不做成打卡任务——不返回连续天数/红点，仅回落库结果
        Map<String, Object> v = Json.map();
        v.put("id", rec.id);
        v.put("scope", rec.scope);
        v.put("text", rec.text);
        v.put("createdAt", rec.createdAt);
        return v;
    }

    // ========================================================== §10 消息

    private Object messages(RequestContext ctx) {
        long accountId = ctx.accountId();
        List<MessageEntry> all = store.messages(accountId);
        all.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        List<Object> views = new ArrayList<>();
        for (MessageEntry m : all) {
            Map<String, Object> v = Json.map();
            v.put("id", m.id);
            v.put("kind", m.kind);
            v.put("title", m.title);
            v.put("preview", m.preview);
            v.put("createdAt", m.createdAt);
            v.put("read", m.read);
            Map<String, Object> route = Json.map();
            route.put("type", m.routeType);
            route.put("id", m.routeId);
            v.put("routeTo", route);
            views.add(v);
        }
        return paginate(views, ctx.queryInt("cursor", 0), ctx.queryInt("limit", 20));
    }

    /** 合并后的到达消息 id 前缀；一张卡一条，故 id 由 cardId 派生（与前端 {@code api/arrivals.ts} 同一常量）。 */
    private static final String ARRIVAL_ID_PREFIX = "arrival:";

    /** 一次最多把多少条原始回应纳入合并（够用的理由见 {@link #reactionArrivals}）。 */
    private static final int ARRIVAL_SCAN_LIMIT = 500;

    /**
     * 「被接住」的到达（{@code PRODUCT-MINDMAP §6.2 B20} · {@code D22}）：别人对我的窗做的回应。
     *
     * <p>复用既有的「回声到达」那一套（{@code B21}/{@code B22}）：这里只下发<b>未合并</b>的回应行，
     * 合成一条通知由前端 {@code mergeArrivals} 做。🔴 <b>合并放在两处是有意的</b>——
     * 「一张卡只出一条」是红线，红线不该只有一处实现。</p>
     *
     * <p>🔴 <b>游标按「卡」推进，不按「回应行」推进。</b>分页与按卡合并天然冲突：
     * 同一张卡的回应一旦被切到两页，前端就会合出两条通知，直接打穿那条硬约束。
     * 这里让一张卡的全部回应<b>整体落在同一页</b>，冲突在服务端就不成立，
     * 前端合不合、怎么翻页都不会分裂。</p>
     *
     * <p>每张卡只下发<b>每类回应最新的那一行</b>（至多两行：记得 / 献花），于是热卡撑不爆一页。
     * 折叠不改变前端的合并结果：合并只用到三样东西——最新时刻、回应类型的并集、有没有未读。
     * 前两样由每类最新那行给全；<b>第三样由 {@link #collapseByKind} 在折叠时对整组取「与」得到</b>，
     * 而不是读代表行自己的已读位。</p>
     *
     * <p>🔴 <b>第三样为什么必须聚合，不能图省事读代表行。</b>
     * 「读最新那行的已读位」只在一个前提下才等价于「整组有没有未读」：
     * <b>已读由单调水位派生</b>——淹掉新的必然淹掉老的，所以老的不可能比新的更未读。
     * 今天确实如此（{@link EchoStore#markReactionsSeen}），但那是<b>另一个模块的性质</b>，
     * 而这里是<b>本方法的正确性</b>。一旦有人把已读从水位改成逐行标记，
     * 「同一张卡同一类回应、老的未读而新的已读」立刻可能出现，
     * 读代表行就会把那盏暖点<b>静默吞掉</b>——不报错、不掉测试、没人会发现。</p>
     *
     * <p>所以这里不把它写成注释里的一句提醒，而是<b>让代码不再依赖它</b>：
     * 折叠时把整组的已读取「与」，无论已读怎么派生，折叠都无条件等价。
     * 🔴 <b>不要把它「简化」回读代表行的已读位。</b></p>
     *
     * <p>🔴 <b>不下发人数、不下发回应者。</b>到达只说「有人」，点进去看的是窗本身。</p>
     */
    private Object reactionArrivals(RequestContext ctx) {
        long accountId = ctx.accountId();
        Map<String, List<ReactionMark>> byCard = new java.util.LinkedHashMap<>();
        for (ReactionMark m : store.reactionsReceived(accountId, ARRIVAL_SCAN_LIMIT)) {
            byCard.computeIfAbsent(m.windowId, k -> new ArrayList<>()).add(m);
        }
        // 卡按"最近一次被回应"倒序：刚热起来的窗自然浮上来
        List<String> cards = new ArrayList<>(byCard.keySet());
        cards.sort((a, b) -> Long.compare(latestOf(byCard.get(b)), latestOf(byCard.get(a))));

        int from = Math.max(0, ctx.queryInt("cursor", 0));
        int limit = ctx.queryInt("limit", 20);
        int lim = limit <= 0 ? 20 : Math.min(limit, 100);
        int to = Math.min(cards.size(), from + lim);

        List<Object> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String petId = cards.get(i);
            PetProfile pet = store.petById(petId);
            if (pet == null) {
                continue; // 窗已不在：没有标题可说，整卡略过，而不是发一条没有名字的到达
            }
            long seenAt = store.reactionsSeenAt(accountId, petId);
            for (CollapsedKind c : collapseByKind(byCard.get(petId), t -> t <= seenAt).values()) {
                Map<String, Object> v = Json.map();
                v.put("id", c.newest().id);
                // 🔴 键名叫 cardId，装进去的却是 petId——到达是按窗折叠的。
                //
                // 🔴 2026-08-27 更正：原注释写「迁移完成时它才名副其实」，那是错的。
                //    按 DECISIONS RK-H「互动本身不搬家」，那次迁移不会发生，
                //    ⚠️ 所以这是一个【永久错名】，不是暂时的。两条待裁定的出路
                //    （改键名 / 改成读本来就是卡级的 t_resonance）写在
                //    Models.ReactionMark#windowId 上，不在这里重复。
                v.put("cardId", petId);
                v.put("cardTitle", pet.name);
                v.put("reaction", c.newest().kind);
                v.put("createdAt", c.newest().createdAt);
                v.put("read", c.allSeen());
                items.add(v);
            }
        }
        Map<String, Object> data = Json.map();
        data.put("items", items);
        data.put("nextCursor", to < cards.size() ? String.valueOf(to) : null);
        return data;
    }

    /**
     * 一张卡上某一类回应折叠后的代表。
     *
     * @param newest  该类最新的那条（时刻与 id 取它的）
     * @param allSeen 该类<b>整组</b>是否都已看过。🔴 是整组取「与」，不是 {@code newest} 自己的已读位
     */
    record CollapsedKind(ReactionMark newest, boolean allSeen) { }

    /**
     * 把一张卡上的回应按类折叠：每类留最新那条，已读<b>对整组取「与」</b>。
     *
     * <p>{@code seen} 是「某个时刻的回应算不算已看过」的判据，由调用方注入。
     * 生产上它是水位比较（{@code t -> t <= seenAt}），但本方法<b>不假设它单调</b>——
     * 正因为不假设，已读换成任何别的派生方式，折叠都仍然等价。理由见 {@link #reactionArrivals}。</p>
     */
    static Map<String, CollapsedKind> collapseByKind(List<ReactionMark> cardMarks,
                                                     java.util.function.LongPredicate seen) {
        Map<String, CollapsedKind> out = new java.util.LinkedHashMap<>();
        for (ReactionMark m : cardMarks) {
            boolean seenThis = seen.test(m.createdAt);
            CollapsedKind prev = out.get(m.kind);
            if (prev == null) {
                out.put(m.kind, new CollapsedKind(m, seenThis));
                continue;
            }
            ReactionMark newest = m.createdAt > prev.newest().createdAt ? m : prev.newest();
            out.put(m.kind, new CollapsedKind(newest, prev.allSeen() && seenThis));
        }
        return out;
    }

    private static long latestOf(List<ReactionMark> marks) {
        long latest = 0L;
        for (ReactionMark m : marks) {
            latest = Math.max(latest, m.createdAt);
        }
        return latest;
    }

    private Object messagesRead(RequestContext ctx) {
        long accountId = ctx.accountId();
        Set<String> ids = new java.util.HashSet<>(readStringArray(ctx.body(), "ids"));
        int n = 0;
        for (MessageEntry m : store.messages(accountId)) {
            if (ids.contains(m.id)) {
                m.read = true;
                store.updateMessage(m); // 回写（PG 落库据此 UPDATE；内存态为引用语义空操作）
                n++;
            }
        }
        n += markArrivalsSeen(accountId, ids);
        return Map.of("ok", true, "updated", n);
    }

    /**
     * 把合并后的到达（{@code arrival:<cardId>}）标为看过。
     *
     * <p>这些 id 在 {@code t_message} 里没有对应行——它们是前端合出来的，
     * 已读因此落到该窗的<b>已看水位</b>上，整卡的回应一起散掉（{@code B23} 看过即散）。</p>
     *
     * <p>🔴 水位推到<b>此刻这扇窗最新一条回应</b>，而不是推到 {@code now()}：
     * 请求在途时新到的那条回应还没有被谁看见，用 {@code now()} 会把它一并吞掉。</p>
     */
    private int markArrivalsSeen(long accountId, Set<String> ids) {
        List<String> petIds = new ArrayList<>();
        for (String id : ids) {
            if (id != null && id.startsWith(ARRIVAL_ID_PREFIX)) {
                petIds.add(id.substring(ARRIVAL_ID_PREFIX.length()));
            }
        }
        if (petIds.isEmpty()) {
            return 0;
        }
        List<ReactionMark> marks = store.reactionsReceived(accountId, ARRIVAL_SCAN_LIMIT);
        int n = 0;
        for (String petId : petIds) {
            PetProfile pet = store.petById(petId);
            // 只有窗主能散自己那盏暖点；别人的 cardId 传进来一律无视（水位本就按 owner 分键，
            // 这里再挡一道，免得往库里落一堆没有意义的行）
            if (pet == null || pet.ownerAccountId != accountId) {
                continue;
            }
            long latest = 0L;
            for (ReactionMark m : marks) {
                if (petId.equals(m.windowId)) {
                    latest = Math.max(latest, m.createdAt);
                }
            }
            if (latest > 0L) {
                store.markReactionsSeen(accountId, petId, latest);
                n++;
            }
        }
        return n;
    }

    // ========================================================== §11 光谱

    private Object spectrum(RequestContext ctx) {
        long accountId = ctx.accountId();
        List<SpectrumNode> nodes = store.spectrumNodes(accountId);
        List<ShadowArea> shadows = store.spectrumShadows(accountId);
        if (nodes.isEmpty()) {
            // 光面结构地板：至少给若干暖光点，保证"光不被暗吞"（§2.11）
            nodes.add(new SpectrumNode(newId(), "你愿意留下来", 0.7, System.currentTimeMillis()));
            nodes.add(new SpectrumNode(newId(), "记得那天的光", 0.6, System.currentTimeMillis()));
        }
        if (shadows.isEmpty()) {
            shadows.add(new ShadowArea(newId(),
                    CopyGuardFilter.sanitize("有时候我会安静下来，像在等一个还没说出口的词。"), 0.3));
        }
        List<Object> nodeViews = new ArrayList<>();
        for (SpectrumNode n : nodes) {
            nodeViews.add(nodeView(n));
        }
        List<Object> shadowViews = new ArrayList<>();
        for (ShadowArea s : shadows) {
            shadowViews.add(shadowView(s));
        }
        return Map.of("nodes", nodeViews, "shadows", shadowViews);
    }

    private Object spectrumAnchor(RequestContext ctx) {
        long accountId = ctx.accountId();
        String label = CopyGuardFilter.sanitize(Json.requireString(ctx.body(), "label"));
        SpectrumNode node = new SpectrumNode(newId(), label, 0.65, System.currentTimeMillis());
        store.spectrumNodes(accountId).add(node);
        logEvent("spectrum_anchor", store.profile(accountId));
        return nodeView(node);
    }

    private Object spectrumIntegrate(RequestContext ctx) {
        long accountId = ctx.accountId();
        String shadowId = ctx.path("id");
        List<ShadowArea> shadows = store.spectrumShadows(accountId);
        ShadowArea target = shadows.stream().filter(s -> s.id.equals(shadowId)).findFirst().orElse(null);
        if (target == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这处暗角已经散了，光正好照进来。", "shadow not found");
        }
        shadows.remove(target);
        SpectrumNode node = new SpectrumNode(newId(),
                CopyGuardFilter.sanitize("被你看见的地方，暖了起来"), 0.7, System.currentTimeMillis());
        store.spectrumNodes(accountId).add(node);
        logEvent("spectrum_integrate", store.profile(accountId));
        return Map.of("node", nodeView(node));
    }

    // ========================================================== 视图/工具

    /**
     * 账号行必须在 —— 🔴 <b>这不是绑定校验</b>，游客有 profile，过得去。
     *
     * <p>🔴 <b>括号里那句「（游客也可以）」已经删掉。</b>它此前挂在献花、建档、上传这些
     * {@code S1′} 受限写操作的最前面，⚠️ <b>而这些路径今天全部要求绑定</b> ——
     * 一句「游客也可以」贴在一道游客过不去的门上，是明确的误导：用户照它做，
     * 走到下一步才被拦，而他会以为是系统出错了。</p>
     *
     * <p>📌 只删那半句，其余一字不动：这句话本身仍然是对的（取通行证确实游客也可以，
     * 那正是 {@code /auth/guest}），错的只是它出现在这些路径上时的<b>言外之意</b>。</p>
     */
    private AccountProfile requireProfile(RequestContext ctx) {
        AccountProfile p = store.profile(ctx.accountId());
        if (p == null) {
            throw new ApiException(ApiException.UNAUTHORIZED, "先取一张通行证吧。", "profile not found");
        }
        return p;
    }

    private PetProfile requireMyPet(RequestContext ctx) {
        AccountProfile profile = requireProfile(ctx);
        PetProfile pet = store.petOfOwner(profile.accountId);
        if (pet == null) {
            throw new ApiException(ApiException.NOT_FOUND, "还没有它的窗——先把它接进来好吗？", "pet not created yet");
        }
        return pet;
    }

    private PetProfile requireWindow(String petId) {
        PetProfile pet = store.petById(petId);
        if (pet == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这扇窗我没有找到，也许它换了个地方。", "window not found: " + petId);
        }
        return pet;
    }

    private Onboarding requireOnboarding(String id) {
        Onboarding o = store.onboarding(id);
        if (o == null) {
            throw new ApiException(ApiException.NOT_FOUND, "建档的进度找不到了，我们从头再来一次吧。", "onboarding not found");
        }
        return o;
    }

    private boolean canView(PetProfile pet, long viewer) {
        if (pet.ownerAccountId == viewer) {
            return true;
        }
        return switch (pet.visibility) {
            case "public" -> true;
            case "friends" -> isFriend(pet.ownerAccountId, viewer);
            default -> false; // private
        };
    }

    private boolean isFriend(long owner, long viewer) {
        for (RelationEntry rel : store.relations(owner)) {
            if (rel.peerAccountId == viewer) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读侧的总门：这个人能不能拿到这扇窗的<b>任何</b>内容。
     *
     * <p>判定顺序照 {@code RESEARCH-window-content-ownership §4.0}：
     * ①任一方向拉黑 → 整页不可达；②窗的可见性三档不允许 → 整页不可达；
     * ③才逐块裁剪（{@link VisibilityMatrix}）。</p>
     *
     * <p>🔴 <b>{@link #canView} 里不要加拉黑判定</b>：写侧要的是「被拉黑方看起来成功」
     * （静默失效，见 {@link BlockService}），读侧要的是「不可达」。两种语义合进一个谓词，
     * 写侧就会给被拉黑方回 404，等于把拉黑说出来。</p>
     */
    private boolean canReach(PetProfile pet, long viewer) {
        return !hiddenBetween(pet.ownerAccountId, viewer) && canView(pet, viewer);
    }

    /**
     * 访客对这扇窗是哪一档身份（{@code SPEC-interaction-flow §10.2} 三档）。
     *
     * <p>🔴 <b>这里不判拉黑。</b>被拉黑方不是「陌生人」——他是不可达的，在 {@link #canReach}
     * 那一层就该被挡掉。把拉黑揉进身份判定会让「不可达」退化成「按陌生人裁剪」，
     * 那等于给被拉黑方留了一条看公开内容的路。</p>
     */
    private ViewerRole roleOf(PetProfile pet, long viewer) {
        if (pet.ownerAccountId == viewer) {
            return ViewerRole.OWNER;
        }
        return isFriend(pet.ownerAccountId, viewer) ? ViewerRole.FRIEND : ViewerRole.STRANGER;
    }

    private String requireVisibility(String v) {
        if (!v.equals("private") && !v.equals("friends") && !v.equals("public")) {
            throw new ApiException(ApiException.BAD_PARAM, "可见性就三档：私密 / 挚友可见 / 公开。", "invalid visibility");
        }
        return v;
    }

    /** 本人视角的档案（{@code GET /pet/me}）。裁剪逻辑与他人视角共用一条，避免两处分叉。 */
    private Map<String, Object> myPetView(PetProfile pet) {
        return petView(pet, ViewerRole.OWNER);
    }

    /**
     * 宠物档案视图（FE {@code MyPet} 形状），🔴 <b>按访客身份裁剪后下发</b>。
     *
     * <p>档位一律问 {@link VisibilityMatrix}，🔴 <b>不要在这里就地写 {@code if (role == ...)} 判档</b>：
     * 那张表是唯一配置点，散落的 if 会让「矩阵定下来后只改一处」这条失效。</p>
     *
     * <p>⚠️ {@code recent}（最新一条近况，一行）<b>不在裁剪范围内</b>，随窗的可见性三档走。
     * 它与广场每张卡上的那一行是同一个值（{@link #windowCard}），属于「公开的卡与基本介绍」；
     * 而 {@code §10.3 B11} 判「仅本人」的是**近况列表**，那一块由 {@code GET /pet/me/echoes}
     * 的 {@code requireMyPet} 兜住，根本不经这个视图。</p>
     */
    private Map<String, Object> petView(PetProfile pet, ViewerRole role) {
        Map<String, Object> data = Json.map();
        data.put("petId", pet.petId);
        data.put("name", pet.name);
        data.put("signature", pet.signature);
        if (VisibilityMatrix.visible(WindowBlock.TEMPERATURE, role)) {
            data.put("temperature", round1(pet.temperature));
        }
        if (VisibilityMatrix.visible(WindowBlock.VISIBILITY_SETTING, role)) {
            data.put("visibility", pet.visibility);
        }
        Map<String, Object> cover = Json.map();
        cover.put("gradient", pet.coverGradient);
        cover.put("emoji", pet.coverEmoji);
        data.put("cover", cover);
        data.put("recent", latestEchoText(pet));
        if (VisibilityMatrix.visible(WindowBlock.LIFE_BOOK, role)) {
            data.put("lifeBook", lifeBookView(pet));
        }
        data.put("postcards", postcardWallView(pet, role));
        return data;
    }

    /** 最新一条近况正文；一条都没有时回温柔兜底（绝不回空串，前端拿它当卡片文案）。 */
    private String latestEchoText(PetProfile pet) {
        List<PetEcho> echoes = store.echoesOfPet(pet.petId);
        echoes.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return echoes.isEmpty() ? CopyGuardFilter.sanitize("它在那边，挺好的。") : echoes.get(0).text;
    }

    /**
     * 明信片墙，🔴 <b>按位分档</b>：已解锁位（{@code W11}）与未解锁位（{@code W12}）是两个档，
     * 不是一块内容一个档。
     *
     * <p>理由：未解锁位的 {@code unlockHint} 泄露的是<b>主人的行为度量</b>
     * （「相伴满 100 天」「温度到 90°」），而温度这个更弱的信号已被 {@code B5} 判为陌生人不出。
     * 把整墙当成一个档，会让敏感度更高的那一半跟着敏感度更低的那一半走。</p>
     */
    private List<Object> postcardWallView(PetProfile pet, ViewerRole role) {
        boolean showUnlocked = VisibilityMatrix.visible(WindowBlock.POSTCARD_UNLOCKED, role);
        boolean showLocked = VisibilityMatrix.visible(WindowBlock.POSTCARD_LOCKED, role);
        List<Object> cards = new ArrayList<>();
        for (Postcard c : store.postcards(pet.petId)) {
            if (c.locked ? showLocked : showUnlocked) {
                cards.add(postcardView(c));
            }
        }
        return cards;
    }

    private Map<String, Object> windowCard(PetProfile pet) {
        AccountProfile owner = store.profile(pet.ownerAccountId);
        List<PetEcho> echoes = store.echoesOfPet(pet.petId);
        echoes.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        Map<String, Object> w = Json.map();
        w.put("id", pet.petId);
        w.put("petName", pet.name);
        // 有 ownerId 才能从窗口点进作者主页。昵称不唯一，反查会点错人，所以必须下发 id。
        w.put("ownerId", String.valueOf(pet.ownerAccountId));
        w.put("ownerName", owner != null ? owner.nickname : "旅人");
        w.put("ownerAvatar", owner != null ? owner.avatar : "");
        w.put("recent", echoes.isEmpty() ? CopyGuardFilter.sanitize("它在那边，挺好的。") : echoes.get(0).text);
        w.put("signature", pet.signature);
        w.put("warmthLevel", warmthLevel(store.rememberFaces(pet.petId).size()));
        Map<String, Object> cover = Json.map();
        cover.put("gradient", pet.coverGradient);
        cover.put("emoji", pet.coverEmoji);
        w.put("cover", cover);
        w.put("span", "tall");
        return w;
    }

    private List<Object> lifeBookView(PetProfile pet) {
        List<Object> lb = new ArrayList<>();
        for (LifeBookItem it : pet.lifeBook) {
            Map<String, Object> v = Json.map();
            v.put("title", it.title);
            v.put("year", it.year);
            v.put("desc", it.desc);
            v.put("placeholder", Json.map());
            lb.add(v);
        }
        return lb;
    }

    private Map<String, Object> postcardView(Postcard c) {
        Map<String, Object> v = Json.map();
        v.put("id", c.id);
        v.put("date", c.date);
        v.put("caption", c.caption);
        v.put("placeholder", Json.map());
        v.put("locked", c.locked);
        if (c.locked) {
            v.put("unlockHint", c.unlockHint);
        }
        return v;
    }

    private Map<String, Object> echoView(PetEcho e) {
        Map<String, Object> v = Json.map();
        v.put("echoId", e.echoId);
        v.put("text", e.text);
        v.put("tone", e.tone);
        v.put("createdAt", e.createdAt);
        v.put("placeholder", Json.map());
        return v;
    }

    private Map<String, Object> quotaView(int used, int remaining) {
        Map<String, Object> q = Json.map();
        q.put("dailyFree", DAILY_FREE_FLOWERS);
        q.put("usedToday", used);
        q.put("remaining", remaining);
        q.put("purchasedBalance", 0);
        return q;
    }

    /**
     * 亲友视图（§8 M-5，FE {@code RelationUser} 形状）。
     * <ul>
     *   <li>{@code lastActive}：毫秒时间戳（前端映射为相对时间）；</li>
     *   <li>{@code viewableByMe}：依对方（peer）宠物可见性真实计算；</li>
     *   <li>无权查看者：不下发其 {@code reels}/动态与宠物主页内容（落实 TC-08）；</li>
     *   <li>{@code pet}：可见时为 FE {@code MyPet} 形状（非 Window），🔴 <b>按我对那扇窗的身份裁剪</b>。</li>
     * </ul>
     *
     * <p>🔴 <b>这里此前发的是 {@code myPetView(peerPet)} —— 主人自己那一份</b>：温度、可见性设置、
     * 整墙明信片（含未解锁位的解锁提示）一并下发，亲友视角靠前端布尔 {@code isFriendView} 不渲染。
     * 「已隐藏」等于「没画出来」，不等于「没发出去」。</p>
     *
     * <p>🔴 <b>而且亲友关系是有方向的</b>：{@code rel} 在我的名单里，不代表我在对方的名单里。
     * 对方没把我列为亲友、窗又是 {@code public} 时，{@link #canView} 放行，但我对那扇窗的身份
     * 其实是<b>陌生人</b>——旧写法给的是主人视图，等于把温度和明信片直接发给了一个陌生人。</p>
     */
    private Map<String, Object> relationView(RelationEntry rel, long now) {
        Map<String, Object> v = Json.map();
        v.put("id", rel.id);
        v.put("name", rel.peerName);
        v.put("avatar", rel.peerAvatar);
        v.put("online", rel.online);
        v.put("priority", rel.priority);
        v.put("mutedUntil", rel.mutedUntil);
        v.put("lastActive", lastActiveOf(rel)); // 毫秒
        boolean muted = rel.mutedUntil > now;
        PetProfile peerPet = store.petOfOwner(rel.peerAccountId);
        // viewableByMe：对方宠物可见性是否允许"我"（rel.accountId）查看其动态。
        // canReach 而非 canView：任一方向拉黑后，名单上的这一条也不该再带出内容。
        boolean viewable = peerPet != null && canReach(peerPet, rel.accountId);
        v.put("viewableByMe", viewable);
        if (viewable) {
            v.put("hasUnseenReel", rel.hasUnseenReel && !muted);
            v.put("reels", reelsOf(peerPet));
            v.put("pet", petView(peerPet, roleOf(peerPet, rel.accountId))); // FE MyPet 形状，已按身份裁剪
        } else {
            // 无权查看：不下发 reels/动态与宠物主页（TC-08 无权查看者不显动态）
            v.put("hasUnseenReel", false);
            v.put("reels", List.of());
            v.put("pet", null);
        }
        return v;
    }

    private long lastActiveOf(RelationEntry rel) {
        return rel.lastActiveAt > 0 ? rel.lastActiveAt : rel.createdAt;
    }

    /** 亲友动态圈（reels 1-3 条）：由对方宠物近况真实映射，最新在前。 */
    private List<Object> reelsOf(PetProfile pet) {
        List<PetEcho> echoes = store.echoesOfPet(pet.petId);
        echoes.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        List<Object> reels = new ArrayList<>();
        int cap = Math.min(echoes.size(), 3);
        for (int i = 0; i < cap; i++) {
            PetEcho e = echoes.get(i);
            Map<String, Object> reel = Json.map();
            reel.put("id", e.echoId);
            reel.put("text", e.text);
            reel.put("createdAt", e.createdAt); // 毫秒；前端映射为相对时间
            Map<String, Object> ph = Json.map();
            ph.put("gradient", pet.coverGradient);
            ph.put("emoji", pet.coverEmoji);
            reel.put("placeholder", ph);
            reels.add(reel);
        }
        return reels;
    }

    private Map<String, Object> nodeView(SpectrumNode n) {
        Map<String, Object> v = Json.map();
        v.put("id", n.id);
        v.put("label", n.label);
        v.put("intensity", n.intensity);
        v.put("createdAt", n.createdAt);
        return v;
    }

    private Map<String, Object> shadowView(ShadowArea s) {
        Map<String, Object> v = Json.map();
        v.put("id", s.id);
        v.put("whisper", s.whisper);
        v.put("depth", s.depth);
        return v;
    }

    private Map<String, Object> skin(String id, String name, String kind, int price) {
        Map<String, Object> v = Json.map();
        v.put("id", id);
        v.put("name", name);
        v.put("kind", kind); // gradient|frame|material —— 只款式，不锁内容
        v.put("price", price);
        return v;
    }

    private List<Object> candidateViews(List<Candidate> candidates) {
        List<Object> out = new ArrayList<>();
        for (Candidate c : candidates) {
            Map<String, Object> v = Json.map();
            v.put("id", c.id);
            Map<String, Object> cover = Json.map();
            cover.put("gradient", c.gradient);
            cover.put("emoji", c.emoji);
            v.put("cover", cover);
            v.put("signature", c.signature);
            out.add(v);
        }
        return out;
    }

    /** 简单 offset 分页信封：{items, nextCursor}。nextCursor=null 到底。 */
    private Map<String, Object> paginate(List<Object> all, int cursor, int limit) {
        int lim = limit <= 0 ? 20 : Math.min(limit, 100);
        int from = Math.max(0, cursor);
        int to = Math.min(all.size(), from + lim);
        List<Object> page = from >= all.size() ? List.of() : all.subList(from, to);
        Map<String, Object> data = Json.map();
        data.put("items", new ArrayList<>(page));
        data.put("nextCursor", to < all.size() ? String.valueOf(to) : null);
        return data;
    }

    // -------- AI 生成（走 ILlmClient + 基调约束 + 词表过滤，定案 #6）

    /**
     * 近况兜底池。🔴 <b>这些句子会原样下发给用户</b>，是真正的产品文案，不是占位符。
     *
     * <p>三条硬约束，加一条比它们都要紧的：</p>
     * <ul>
     *   <li><b>CR2 弱化强缺席暗示</b>：不得把它的状态归因于用户的不在场。
     *       「等你笑」「等你来陪它坐一会儿」都是这一类——句子把它此刻在做什么，
     *       解释成了"因为你没来"。</li>
     *   <li><b>DP2 不做拉回访诱导</b>：不得写成邀请用户回来的话。
     *       「等你有空来」是把陪伴写成了待办事项。</li>
     *   <li><b>要能过 {@link OutputSafetyGate}</b>：{@link #petObjectContext} 传的是
     *       {@code UNKNOWN}，此时第四关 B 组（「在那边 / 远行 / 走了」…）全拦。</li>
     *   <li>🔴 <b>但兜底池根本不过闸</b>——{@link #gateOrFallback} 是在闸拦下之后才回落到这里的，
     *       所以这一池句子是<b>唯一一条能绕过五关直达用户的路径</b>。它的正确性只能靠
     *       写的人自觉，以及 {@code EchoFallbackCopyTest} 那几条断言。</li>
     * </ul>
     *
     * <p>删掉一条比改坏一条安全：宁可池子小，也不要放一句踩线的进来。</p>
     */
    private static final String[] ECHO_POOL = {
            "今天阳光很好，它慵懒地打了个哈欠，翻了个身继续晒太阳。",
            "它追着一片叶子跑了好远，回过头来的时候，眼睛是亮的。",
            "有风的午后，它趴在窗边打盹，尾巴一下一下轻轻晃。",
            "它记得你揉它耳朵的样子，今天又想起了那个瞬间。",
            "它最喜欢的还是那个角落，午后的光每天都会照进去。"
    };

    /** 回应兜底池。约束同 {@link #ECHO_POOL}。 */
    private static final String[] REPLY_POOL = {
            "它歪了歪头，好像把你的话都收进心里了。",
            "它蹭了蹭你的手，像在说：我一直都在。",
            "它眨了眨眼，那一刻你们又靠得很近。"
    };

    /**
     * 两个兜底池的只读视图，供 {@code EchoFallbackCopyTest} 逐条过闸。
     *
     * <p>🔴 开这两个口子是因为：兜底池是唯一能<b>绕过</b>输出侧五关直达用户的路径
     * （{@link #gateOrFallback} 只在闸拦下之后才回落到它），而这一点此前只写在注释里
     * 「兜底池里的句子是人工写好且已过闸的」——那句话当时就是错的：
     * 池子里既有踩 CR2/DP2 的回访邀约，也有会被第四关 B 组拦下的措辞，
     * 而没有任何一行代码在检查它。</p>
     */
    static List<String> echoFallbackPool() {
        return List.of(ECHO_POOL);
    }

    /** 见 {@link #echoFallbackPool()}。 */
    static List<String> replyFallbackPool() {
        return List.of(REPLY_POOL);
    }

    /** 兜底池实际投递时用的对象状态，测试必须用同一个，否则断言的不是线上那条路径。 */
    static ObjectContext fallbackObjectContext() {
        return petObjectContext(null);
    }

    private PetEcho buildEcho(PetProfile pet) {
        PetEcho e = new PetEcho();
        e.echoId = newId();
        e.petId = pet.petId;
        e.text = generateEchoText(pet);
        e.tone = "gentle";
        e.createdAt = System.currentTimeMillis();
        return e;
    }

    private String generateEchoText(PetProfile pet) {
        String prompt = copyGuardSystemPrompt()
                + petContext(pet)
                + "\n请以温柔克制的笔触，写一句它此刻的近况，一句话即可，不要加解释或引号。";
        return gateOrFallback(prompt, ECHO_POOL, petObjectContext(pet));
    }

    private String generateReply(PetProfile pet, String ownerText) {
        String prompt = copyGuardSystemPrompt() + petContext(pet)
                + "\n主人对它说：" + ownerText + "\n请写一句它温柔的回应，一句话即可，不要加解释或引号。";
        return gateOrFallback(prompt, REPLY_POOL, petObjectContext(pet));
    }

    /**
     * 生成 → 过输出侧安全闸 → 不过则重生成一次 → 仍不过回落兜底池。
     *
     * <p>🔴 这里<b>不能</b>用 {@link CopyGuardFilter#sanitize} 收尾。{@code SPEC-security §4.3}
     * 明确「安全闸不做词面替换后放行，命中即整条不投递」——词面替换可被绕过（同一个断言换个说法
     * 就穿过去了），且替换后的句子没人再看一眼是否还通顺、还温柔。{@code CopyGuardFilter} 的定位是
     * 给<b>我们自己写的静态文案</b>兜底改写，不是安全闸。</p>
     *
     * <p>兜底池里的句子是人工写好且已过闸的，所以回落是安全的终点。</p>
     */
    private String gateOrFallback(String prompt, String[] fallbackPool, ObjectContext ctx) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String out = safeComplete(prompt);
            if (out == null || out.isBlank() || out.trim().startsWith("{")) {
                break; // Mock/异常：不重试，直接回落
            }
            if (safetyGate.inspect(out, ctx).passed()) {
                return out;
            }
            // 命中 → 整条丢弃，重生成一次（§4.3：允许重生成一次，仍不过则兜底）
        }
        return fallbackPool[ThreadLocalRandom.current().nextInt(fallbackPool.length)];
    }

    /**
     * 宠物的对象状态入参。
     *
     * <p>🔴 显式传 {@code PET} 而不是依赖默认值：{@link ObjectContext.Kind#parse} 的默认是
     * {@code PERSON}（最保守分支），漏传会被第五关拦住。P0 只做宠物，这里就是那个"显式传"的地方。</p>
     *
     * <p>⚠️ <b>{@code objectStatus} 取 {@code UNKNOWN}，因为建档流程当前不采集这个字段。</b>
     * 这是 {@code §4.3.1} 规定的默认值与最保守分支，不是随手填的：</p>
     * <ul>
     *   <li>规格明确「必须由 context 传入，默认 {@code unknown}，🔴 不允许模型推断、
     *       不允许安全闸从文本反推」——我们既然没采集，就只能是 {@code unknown}。</li>
     *   <li>🔴 <b>不能因为「本产品都是纪念已离开的宠物」就硬编码 {@code deceased}</b>。
     *       规格明确宠物可以在世（{@code §4.3.3} 末段「含在世宠物」），而对在世对象放行
     *       「在那边 / 远行」是<b>事实错误</b>——我们会替用户宣布一只还活着的宠物已经离开。</li>
     * </ul>
     *
     * <p><b>代价</b>：{@code unknown} 下第四关 B 组全拦，所以模型若生成「它在那边」这类克制措辞
     * 会被判命中并重生成。这不是 bug，是没有采集字段时唯一正确的行为。要拿回这批措辞，
     * 需要建档时采集对象状态——见交付说明。</p>
     */
    private static ObjectContext petObjectContext(PetProfile pet) {
        return ObjectContext.pet(ObjectContext.Status.UNKNOWN);
    }

    /** 把建档信息/性情词拼成生成上下文（注入 prompt，让近况/回声贴合这只宠物）。 */
    private String petContext(PetProfile pet) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n它叫「").append(pet.name).append("」，是一只").append(pet.species).append("。");
        if (pet.traits != null && !pet.traits.isEmpty()) {
            sb.append("性情：").append(String.join("、", pet.traits)).append("。");
        }
        if (pet.signature != null && !pet.signature.isBlank()) {
            sb.append("它的签名：").append(pet.signature).append("。");
        }
        return sb.toString();
    }

    private String safeComplete(String prompt) {
        try {
            return llm.complete(prompt);
        } catch (Exception e) {
            log.warn("LLM complete 失败，走兜底文案", e);
            return null;
        }
    }

    /** COPY-GUIDE §4 system prompt 约束片段（注入生成侧）。 */
    private String copyGuardSystemPrompt() {
        return "你是「回声·往宠」里温柔叙述的声音。硬约束：基调温柔克制、它在那边/换了个方式陪着；"
                + "禁止死亡/去世/永别/再也见不到、任何制造内疚或攀比名次；欢迎正向不施压；邀请而非命令。";
    }

    // -------- 训练语料回流（AI-CAPABILITIES §7；受 trainConsent 门控，PIPL 合规）

    /**
     * 建档确认时的一条训练样本：仅 {@code trainConsent==true} 才写入语料；未同意直接跳过。
     * 账号做去标识（hash），仅留内容与信号。
     */
    private void recordTrainSample(Onboarding o, PetProfile pet, String chosenCandidateId, String feedback) {
        if (trainingCorpus == null || o == null || !o.trainConsent) {
            return; // 未配置语料通道 / 未同意：不落训练集（素材本身仍按正常业务存）
        }
        TrainSample s = new TrainSample();
        s.accountId = deidentify(pet.ownerAccountId);
        s.petId = pet.petId;
        s.inputRefs = new ArrayList<>(o.inputRefs);
        s.detectedSpecies = o.detectedSpecies;
        s.correctedSpecies = pet.species; // 用户最终确认的种类（与 detected 不同即为纠偏信号）
        s.chosenCandidateId = chosenCandidateId;
        s.redoCount = o.redoCount;
        s.traits = new ArrayList<>(pet.traits);
        // 🔴 OrNull 版本：空的时候不要拿展示用的兜底句去凑样本，那会把我们自己写的话当模型产出喂回去
        s.echoText = latestEchoTextOrNull(pet);
        s.feedback = feedback;
        s.createdAt = System.currentTimeMillis();
        s.consent = true;
        boolean written = trainingCorpus.write(s);
        logEvent(written ? "train_sample_write" : "train_sample_skip", store.profile(pet.ownerAccountId));
    }

    /** 回声反馈处的训练样本（记得/献花/停留）：受宠物主人的 trainConsent 门控。 */
    private void recordFeedbackSample(PetProfile pet, String echoText, String feedback) {
        if (trainingCorpus == null || pet == null || !pet.trainConsent) {
            return;
        }
        TrainSample s = new TrainSample();
        s.accountId = deidentify(pet.ownerAccountId);
        s.petId = pet.petId;
        s.correctedSpecies = pet.species;
        s.traits = new ArrayList<>(pet.traits);
        s.echoText = echoText;
        s.feedback = feedback;
        s.createdAt = System.currentTimeMillis();
        s.consent = true;
        trainingCorpus.write(s);
    }

    /**
     * 最新一条近况正文，一条都没有时回 {@code null}。
     *
     * <p>🔴 <b>与 {@link #latestEchoText} 只差在"空的时候回什么"，而那个差别是刻意的，
     * 不要合并这两个方法。</b>展示路径拿它当卡片文案，空串会渲染成一张空卡，所以那边回兜底句；
     * 这里喂的是训练语料，回一句我们自己写的兜底句等于<b>把兜底文案当成模型产出喂回去</b>——
     * 那条样本会教模型说这句话，而它根本不是模型说的。</p>
     *
     * <p>⚠️ 这两个方法此前<b>同名同参</b>并存于本类，也就是说 {@code echo-server} 从
     * 纳入版本控制起就编译不过（{@code javac: already defined}）。之所以一直没被发现，
     * 是因为 {@code target/classes} 里一直躺着一份更早的、能用的产物，
     * 增量编译不重编没改过的文件——<b>本地 {@code mvn compile} 一直是空转的通过</b>。</p>
     */
    private String latestEchoTextOrNull(PetProfile pet) {
        List<PetEcho> echoes = store.echoesOfPet(pet.petId);
        echoes.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return echoes.isEmpty() ? null : echoes.get(0).text;
    }

    /** 去标识：对 accountId 做 SHA-256 后取前 16 位十六进制，训练样本不留真实身份（PIPL）。 */
    private String deidentify(long accountId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("echo-train:" + accountId).getBytes(StandardCharsets.UTF_8));
            return "acct_" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "acct_" + Integer.toHexString(Long.hashCode(accountId));
        }
    }

    private List<Candidate> generateCandidates(Onboarding o, String adjust) {
        String[] gradients = {"dusk", "aurora", "meadow"};
        String[] emojis = {"\uD83D\uDC36", "\uD83D\uDC31", "\uD83D\uDC30"};
        String base = adjust == null || adjust.isBlank() ? "" : "（" + adjust + "）";
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String sig = CopyGuardFilter.sanitize(o.petName + "，换了个方式一直陪着你" + base);
            list.add(new Candidate(newId(), gradients[i % gradients.length], emojis[i % emojis.length], sig));
        }
        return list;
    }

    // -------- 种子数据（新建宠物时铺一点内容，便于无 DB 联调）

    private void seedLifeBook(PetProfile pet) {
        int year = LocalDate.now().getYear();
        pet.lifeBook.add(new LifeBookItem("我们相遇", year - 3, CopyGuardFilter.sanitize("你第一次把它抱回家的那天。")));
        pet.lifeBook.add(new LifeBookItem("最爱的角落", year - 1, CopyGuardFilter.sanitize("它总在那扇窗下晒太阳。")));
    }

    private void seedPostcards(PetProfile pet) {
        List<Postcard> cards = new ArrayList<>();
        Postcard c1 = new Postcard();
        c1.id = newId();
        c1.petId = pet.petId;
        c1.date = LocalDate.now().toString();
        c1.caption = CopyGuardFilter.sanitize("相遇的第一张明信片");
        c1.locked = false;
        c1.createdAt = System.currentTimeMillis();
        cards.add(c1);
        Postcard c2 = new Postcard();
        c2.id = newId();
        c2.petId = pet.petId;
        c2.date = LocalDate.now().plusDays(100).toString();
        c2.caption = CopyGuardFilter.sanitize("相伴满 100 天");
        c2.locked = true;
        c2.unlockHint = "相伴满 100 天解锁";
        c2.createdAt = System.currentTimeMillis();
        cards.add(c2);
        store.putPostcards(pet.petId, cards);
    }

    private void seedFirstEcho(PetProfile pet) {
        store.addEcho(buildEcho(pet));
    }

    /** 供 bootstrap 铺设演示广场窗口（无 DB 联调时广场也有内容）。 */
    public void seedDemoWindow(String nickname, String petName, String species) {
        AccountProfile owner = new AccountProfile();
        owner.accountId = idGenerator.nextId();
        owner.deviceId = "demo-" + owner.accountId;
        owner.guest = false;
        owner.nickname = nickname;
        owner.visibilityDefault = "public";
        owner.hasPet = true;
        owner.createTime = System.currentTimeMillis();
        store.putProfile(owner);

        PetProfile pet = new PetProfile();
        pet.petId = newId();
        pet.ownerAccountId = owner.accountId;
        pet.name = petName;
        pet.species = species;
        pet.signature = CopyGuardFilter.sanitize(petName + "，换了个方式一直陪着你");
        pet.temperature = 80.0;
        pet.visibility = "public";
        pet.createTime = System.currentTimeMillis();
        seedLifeBook(pet);
        store.putPet(pet);
        seedPostcards(pet);
        seedFirstEcho(pet);
    }

    /**
     * dev/内存模式演示亲友种子（mi-D）：为指定账号铺几条亲友关系，使联调时亲友页不空白。
     * 每条关系背后建一个公开往宠的对端账号（含一条近况 echo，故 reels 有内容、viewableByMe=true），
     * 关系体带 online/priority/lastActive 等字段以覆盖排序与相对时间展示。仅内存联调调用，持久化模式不种。
     */
    private void seedRelationsFor(long viewerAccountId) {
        long now = System.currentTimeMillis();
        seedFriend(viewerAccountId, "拾光", "麦麦", "金毛", true, true, now - 2L * 60 * 1000);
        seedFriend(viewerAccountId, "远山", "橘子", "橘猫", true, false, now - 3L * 60 * 60 * 1000);
        seedFriend(viewerAccountId, "阿岸", "团子", "柯基", false, false, now - 26L * 60 * 60 * 1000);
    }

    /** 铺一位演示亲友：建对端账号 + 公开往宠（有近况/明信片/生命之书）+ 指向 viewer 的亲友关系。 */
    private void seedFriend(long viewerAccountId, String peerNick, String petName, String species,
                            boolean online, boolean priority, long lastActiveAt) {
        AccountProfile peer = new AccountProfile();
        peer.accountId = idGenerator.nextId();
        peer.deviceId = "demo-rel-" + peer.accountId;
        peer.guest = false;
        peer.nickname = peerNick;
        peer.visibilityDefault = "public";
        peer.hasPet = true;
        peer.createTime = System.currentTimeMillis();
        store.putProfile(peer);

        PetProfile pet = new PetProfile();
        pet.petId = newId();
        pet.ownerAccountId = peer.accountId;
        pet.name = petName;
        pet.species = species;
        pet.signature = CopyGuardFilter.sanitize(petName + "，换了个方式一直陪着你");
        pet.temperature = 80.0;
        pet.visibility = "public"; // 公开 → 我有权查看，relationView 才下发 reels/pet
        pet.createTime = System.currentTimeMillis();
        seedLifeBook(pet);
        store.putPet(pet);
        seedPostcards(pet);
        seedFirstEcho(pet); // 让 reels 有一条真实近况

        RelationEntry rel = new RelationEntry();
        rel.id = newId();
        rel.accountId = viewerAccountId;
        rel.peerAccountId = peer.accountId;
        rel.peerName = peerNick;
        rel.peerAvatar = peer.avatar;
        rel.online = online;
        rel.priority = priority;
        rel.hasUnseenReel = true;
        rel.lastActiveAt = lastActiveAt;
        rel.createdAt = System.currentTimeMillis();
        store.addRelation(rel);
    }

    // -------- 杂项

    private long resolveMute(String mute) {
        long now = System.currentTimeMillis();
        return switch (mute) {
            case "7d" -> now + 7L * 24 * 3600 * 1000;
            case "3m" -> now + 90L * 24 * 3600 * 1000;
            case "permanent" -> Long.MAX_VALUE;
            default -> 0L; // clear
        };
    }

    private double warmthLevel(int faces) {
        // 记得人数 → 暖光浓度（0..1），饱和函数，绝不对外暴露精确数字
        return Math.min(1.0, faces / 20.0);
    }

    private List<String> readStringArray(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray arr = o.getAsJsonArray(key);
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonNull()) {
                    out.add(arr.get(i).getAsString());
                }
            }
        }
        return out;
    }

    private void logEvent(String event, AccountProfile profile) {
        // 埋点（RELEASE §3.4 / API-CONTRACT §13）：账号匿名 + isGuest + ts
        if (profile == null) {
            log.info("[track] event={}, ts={}", event, System.currentTimeMillis());
        } else {
            log.info("[track] event={}, accountId={}, isGuest={}, ts={}",
                    event, profile.accountId, profile.guest, System.currentTimeMillis());
        }
    }

    private String newId() {
        return String.valueOf(idGenerator.nextId());
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int today() {
        LocalDate d = LocalDate.now(ZoneId.systemDefault());
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
