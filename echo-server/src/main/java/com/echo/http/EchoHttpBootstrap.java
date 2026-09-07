package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.aengine.util.thread.NamedThreadFactory;
import com.echo.http.exposure.ExposureConfig;
import com.echo.http.exposure.ExposureRecorder;
import com.echo.http.exposure.FeedRequestRegistry;
import com.echo.http.exposure.ImpressionApi;
import com.echo.http.ranking.RankingReadiness;
import com.echo.http.ranking.S4DrainPolicy;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.governance.CapabilityRegistry;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.FeatureSwitchStore;
import com.echo.http.governance.FollowStore;
import com.echo.http.governance.GovernanceCapability;
import com.echo.http.governance.InteractionPolicy;
import com.echo.http.governance.LeaveWordsStore;
import com.echo.http.governance.ReportService;
import com.echo.http.governance.ReportStore;
import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.safety.SafetyMetrics;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.store.InMemoryModerationStore;
import com.echo.http.store.ModerationStore;
import com.echo.http.store.PgEchoStore;
import com.echo.http.store.PgModerationStore;
import com.echo.http.work.ResourceStore;
import com.echo.http.work.WorkStore;
import com.echo.http.onboarding.EchoOnboardingWindowPort;
import com.echo.http.onboarding.ExecutorOnboardingGenerationPort;
import com.echo.http.onboarding.InMemoryOnboardingRepository;
import com.echo.http.onboarding.OnboardingApi;
import com.echo.http.onboarding.OnboardingRepository;
import com.echo.http.onboarding.PgOnboardingRepository;
import com.echo.http.auth.AuthApi;
import com.echo.http.auth.PgAuthService;
import com.echo.http.auth.SessionAuthenticator;
import com.echo.http.auth.SmsProvider;
import com.echo.http.auth.UnavailableSmsProvider;
import com.echo.infra.corpus.ITrainingCorpus;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.llm.LlmClientFactory;
import com.echo.infra.llm.LlmConfig;
import com.echo.infra.persistence.PgDb;
import com.echo.infra.persistence.PgDbManager;
import com.echo.infra.safety.ContentSafetyFactory;
import com.echo.infra.safety.ContentSafetyGate;
import com.echo.infra.storage.IStorage;
import com.echo.infra.storage.StorageConfig;
import com.echo.infra.storage.StorageFactory;
import com.echo.infra.vision.IVisionClient;
import com.echo.infra.vision.VisionClientFactory;
import com.echo.module.account.AccountService;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.time.Clock;

/**
 * HTTP/JSON 网关的装配与启动（独立于 WebSocket 9001）。
 *
 * <p>由 {@link com.echo.bootstrap.EchoServer} 在 WS 启动后调用。端口由系统属性
 * {@code -Decho.http.port} 配置（默认 {@value #DEFAULT_PORT}）。无 DB 时 {@code accountService}
 * 传 null，网关用内存态跑通闭环；有 DB 时传入 {@link AccountService} 以复用 t_account。</p>
 */
@Slf4j
public final class EchoHttpBootstrap {

    /** 默认 HTTP 端口。 */
    public static final int DEFAULT_PORT = 8080;

    /** HTTP 端口的系统属性键。 */
    public static final String PROP_HTTP_PORT = "echo.http.port";

    private EchoHttpBootstrap() {
    }

    /**
     * 装配并启动 HTTP 网关。
     *
     * @param idGenerator    雪花 ID 生成器（与 WS 侧共用一枚 workerId）
     * @param llm            LLM 兜底客户端（通常为 MockLlmClient）：作为 mock 分支返回与 Api 分支网络降级委托
     * @param accountService DB 开启时的账号服务（复用 t_account）；无 DB 传 null
     * @return 已启动的网关句柄（用于停机）
     */
    public static HttpGateway start(IDGenerator idGenerator, ILlmClient llm, AccountService accountService) {
        int port = Integer.getInteger(PROP_HTTP_PORT, DEFAULT_PORT);

        // 存储实现：DB 开启（PgDbManager 有 "echo" 数据源）→ 落库 PgEchoStore；否则内存态 InMemoryEchoStore。
        PgDb pgDb = PgDbManager.getInstance().get("echo");
        boolean persistent = pgDb != null;
        EchoStore store = persistent ? new PgEchoStore(pgDb, idGenerator) : new InMemoryEchoStore();
        log.info("HTTP 网关存储实现: {}", persistent ? "PgEchoStore(PostgreSQL 落库)" : "InMemoryEchoStore(内存态)");
        // 素材对象存储：按 ECHO_STORAGE_* 装配（默认本地磁盘；预留 OSS/COS/MinIO）。
        IStorage storage = StorageFactory.create(StorageConfig.fromEnv());
        // 肖像识别客户端：按 env 装配（有 key → ApiVisionClient 切真；无 key → 回落 StubVisionClient，行为与桩一致）。
        // 传入 storage 以接上资源解析：resourceId → 公网 URL（OSS/CDN）或压缩后的 data-uri（本地存储）。
        IVisionClient vision = VisionClientFactory.fromEnv(storage);
        // LLM 装配回退：按 ECHO_LLM_PROVIDER 选实现；未配置 key 或 provider=mock 时回落传入的兜底 llm。
        ILlmClient effectiveLlm = LlmClientFactory.create(LlmConfig.fromEnv(), llm);
        // 训练语料回流通道（受 trainConsent 门控写入）：本期内存态，PG 落库为 TODO。
        ITrainingCorpus trainingCorpus = new InMemoryTrainingCorpus();
        // seedDemoRelations=!persistent：仅内存态联调给新游客铺演示亲友（mi-D），持久化模式不种。
        EchoApi api = new EchoApi(store, idGenerator, effectiveLlm, vision, accountService, trainingCorpus, !persistent);
        // dev-only 路由（如 DELETE /pet/me resetPet，mi-2）只认显式开关。
        //
        // 🔴 这里此前是 devRoutes = !persistent || <显式开关>，即**数据库连不上时自动挂载**。
        //    那是 fail-open：故障状态反而多给了权限，而生产环境 PG 抖一下就会落到这条分支上。
        //    「连不上库」和「这是开发机」是两件事，不能用同一个布尔量表示。
        //    代价是本地无 PG 联调要多带 -Decho.devRoutes=true，见 deploy/RUNBOOK.md。
        boolean devRoutes = Boolean.parseBoolean(System.getProperty("echo.devRoutes", "false"));
        if (!persistent && !devRoutes) {
            log.warn("[bootstrap] 内存态启动但未开 dev 路由；本地联调需要 resetPet 等端点时加 -Decho.devRoutes=true");
        }
        Router router = api.routes(devRoutes);

        // 审核/申诉/举报 8 端点（API-CONTRACT §17）。落库模式走 PG（双流水同事务由 PgDb.inTransaction 保证），
        // 内存态走 InMemoryModerationStore（synchronized 模拟同事务），两者对外契约一致。
        ModerationStore moderationStore = persistent
                ? new PgModerationStore(pgDb, idGenerator)
                : new InMemoryModerationStore(idGenerator);
        new ModerationApi(moderationStore, AdminRoles.fromEnv(), idGenerator).register(router);

        // 🔴 GET /plaza 下发回忆卡（契约 C-2），以及作者侧卡列表/可见性/置顶都要读这个 store。
        //    未装配时 /plaza 返回空页 —— 刻意不回落到旧的「发宠物窗口」，那正是第二套 card
        //    形状最容易长出来的地方。
        api.setCardStore(moderationStore);

        // 治理能力就绪探测 + 功能开关（S13）。🔴 探针必须探测真实能力，不能读配置。
        CapabilityRegistry capabilities = new CapabilityRegistry();
        SafetyMetrics safetyMetrics = new SafetyMetrics();
        // 第一关（合规词表）接第三方内容安全服务。未配置时它对每次检测返回「跳过」而非「通过」，
        // 且 isOperational() 恒 false → 文本安全闸能力未就绪 → S13 开关打不开。
        ContentSafetyGate contentSafety = ContentSafetyFactory.fromEnv();
        // 生成路径、留言校验、后台看板共用一个安全闸，拦截计数才是同一份
        OutputSafetyGate safetyGate = new OutputSafetyGate(safetyMetrics, contentSafety);
        api.setSafetyGate(safetyGate);
        FeatureSwitchService switches = new FeatureSwitchService(new FeatureSwitchStore(pgDb), capabilities);

        // S3 三项治理能力：举报 / 拉黑 / 关互动。挂上路由后对应探针才会探到"能力在位"。
        BlockService blockService = new BlockService(new BlockStore(pgDb), idGenerator);
        api.setBlockService(blockService);
        // 关注（E1b）。🔴 必须把 FollowStore 挂成 BlockService 的 FollowUnlinker：
        // 「拉黑自动解除双向关注」是裁定，而此前生产上 followUnlinker 一直是 null，
        // 也就是说那条裁定在代码里有钩子、在运行时不生效——不装配等于没实现。
        FollowStore followStore = new FollowStore(pgDb);
        api.setFollowStore(followStore);
        blockService.setFollowUnlinker(followStore);
        ReportService reportService = new ReportService(new ReportStore(pgDb), idGenerator);
        InteractionPolicy interactionPolicy = new InteractionPolicy(switches);
        GovernanceApi governanceApi = new GovernanceApi(reportService, blockService,
                interactionPolicy, switches, moderationStore);
        governanceApi.register(router);

        // S13 · C1 留一句话：功能照做，🔴 由服务端开关控制且 P0 默认关闭。
        // 开关能不能打开由 FeatureSwitchService 的就绪校验说话，这里只负责把端点挂上。
        new LeaveWordsApi(new LeaveWordsStore(pgDb), moderationStore, store, governanceApi,
                switches, safetyGate, idGenerator).register(router);

        // 作品域（主线第 10 步）。此前服务端没有任何创建可发布内容的入口，
        // 审核与分发整条下游只能处理测试代码手动塞进去的数据，见
        // PRODUCT-IMPLEMENTATION-AUDIT §0。
        // 素材归属由上传口与发布口共用一份，不要各造一个：两份实例在内存态下
        // 各持一张 Map，上传记在 A、发布查 B，校验会永远不通过。
        ResourceStore resourceStore = new ResourceStore(pgDb);
        WorksApi worksApi = new WorksApi(new WorkStore(pgDb), store, storage, resourceStore,
                safetyGate, idGenerator);
        worksApi.setBlockService(blockService);
        worksApi.setCardStore(moderationStore);
        worksApi.register(router);

        registerCapabilityProbes(capabilities, router, contentSafety);
        logGovernanceReadiness(capabilities, switches);

        // 曝光记账（G-2）：/plaza 签发 reqId → /plaza/impressions 按快照做五道校验 → 异步批量落 t_card_exposure。
        // 🔴 单实例前提：多副本会让 reqId 跨实例不可见，曝光被静默判为 unknown_req。启动期就要撞出来。
        FeedRequestRegistry.assertSingleInstance();
        ExposureConfig exposureConfig = ExposureConfig.fromEnv();
        FeedRequestRegistry feedRequests = new FeedRequestRegistry(exposureConfig);
        api.setFeedRequests(feedRequests);
        ExposureRecorder exposureRecorder = new ExposureRecorder(exposureConfig, feedRequests, pgDb, idGenerator);
        new ImpressionApi(exposureRecorder).register(router);
        Runtime.getRuntime().addShutdownHook(new Thread(exposureRecorder::shutdown, "echo-exposure-shutdown"));

        // 🔴 排序侧启动自检：广场还在发窗口时，n 恒为 0，权重衰减/制动/SURGE 全部空转。
        // 这个失效不抛异常、不打错误日志，三个模块看起来都在正常运行——所以必须主动喊出来。
        // 判据取 EchoApi.PLAZA_FEED_KIND，广场改发卡后本条自动消失。
        RankingReadiness.warnIfExposureSourceIsDead(EchoApi.PLAZA_FEED_KIND,
                EchoApi.IMMERSIVE_FEED_IMPLEMENTED);

        // 🔴 S4 自然流掉关闸：RK-H 已定卡级只记入口归因、不记计数，S4 要的那个数不存在，
        // 放它生效会让全库的卡在满 14 天那天一起流掉，而报表上看起来只是「自然退场」。
        // ⚠️ 判据是一条缺失的裁定（S4DrainPolicy.RULED_OPEN），缺失的裁定不会自己补上，
        // 所以本条告警不自熄——它是那条裁定唯一的常驻提醒。
        new S4DrainPolicy(switches).logReadiness();

        // 演示广场窗口：仅内存态联调时播种（无 DB 也能拉到公开窗口/面孔墙）。
        // 落库模式下真实数据持久化，若每次启动重复播种会累积脏数据，故跳过。
        if (!persistent) {
            api.seedDemoWindow("拾光", "麦麦", "金毛");
            api.seedDemoWindow("远山", "橘子", "橘猫");
        }

        ExecutorService executor = new ThreadPoolExecutor(
                4, 16, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                new NamedThreadFactory("echo-http"));

        OnboardingRepository onboardingRepository = persistent
                ? new PgOnboardingRepository(pgDb)
                : new InMemoryOnboardingRepository();
        OnboardingApi onboarding = new OnboardingApi(
                onboardingRepository,
                accountId -> BindingGuard.isBound(store, accountId),
                new ExecutorOnboardingGenerationPort(effectiveLlm, idGenerator, executor),
                new EchoOnboardingWindowPort(store, idGenerator),
                vision,
                idGenerator);
        onboarding.register(router);

        SessionAuthenticator sessionAuthenticator = null;
        String authSecret = System.getenv("ECHO_AUTH_SECRET");
        if (persistent && authSecret != null && authSecret.length() >= 32) {
            PgAuthService auth = new PgAuthService(pgDb, idGenerator, authSecret,
                    runtimeSmsProvider(),
                    (accountId, intent, resourceId, schemaVersion) -> {
                        if ("none".equals(intent)) return true;
                        var session = onboardingRepository.find(resourceId);
                        return session != null && session.accountId == accountId
                                && "ready_to_bind".equals(session.status);
                    }, Clock.systemUTC());
            new AuthApi(auth).register(router);
            sessionAuthenticator = auth;
        } else {
            AuthApi.registerUnavailable(router);
            log.warn("持久身份服务未装配：需要 PostgreSQL 与至少 32 字符的 ECHO_AUTH_SECRET");
        }

        HttpGateway gateway = new HttpGateway(port, router, store, storage, resourceStore, executor, onboarding,
                sessionAuthenticator);
        try {
            gateway.start();
        } catch (Exception e) {
            log.error("HTTP 网关启动失败 port={}", port, e);
            throw new RuntimeException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "echo-http-shutdown"));
        return gateway;
    }

    /**
     * Runtime assembly must never select the controllable test SMS provider, even if legacy
     * development flags are accidentally present. Tests inject StubSmsProvider directly.
     * Replace this fail-closed provider only when a production supplier adapter is configured.
     */
    static SmsProvider runtimeSmsProvider() {
        return new UnavailableSmsProvider();
    }

    /**
     * 注册 {@code S3} 五项治理能力的就绪探针（{@code S13} 开启前置的机器可判实现）。
     *
     * <p>🔴 每个探针都探测<b>真实能力在不在</b>——路由挂没挂、实现注没注入——而不是读一个
     * 配置项。没有登记探针的能力默认未就绪，所以这里漏一项的后果是开关打不开（可发现），
     * 而不是开关能在能力缺失时被打开（不可发现）。</p>
     */
    private static void registerCapabilityProbes(CapabilityRegistry capabilities, Router router,
                                                 ContentSafetyGate contentSafety) {
        // 审核队列：运营侧处置路由挂上了才算就绪
        capabilities.register(GovernanceCapability.MODERATION_QUEUE,
                () -> router.match("POST", "/admin/moderation/1/handle") != null);

        // 举报：🔴 判据是 **C 端能提交**。只有 GET /admin/reports（运营侧只读）不算就绪——
        //      没有提交入口，举报能力对用户就是不存在的。
        capabilities.register(GovernanceCapability.REPORT,
                () -> router.match("POST", "/reports") != null);

        // 拉黑：单向拉黑路由
        capabilities.register(GovernanceCapability.BLOCK,
                () -> router.match("POST", "/accounts/1/block") != null);

        // 关互动：作者关闭某类互动的路由
        capabilities.register(GovernanceCapability.CLOSE_INTERACTION,
                () -> router.match("PATCH", "/cards/1/interaction") != null);

        // 文本安全闸：🔴 判据是第一关**真的调通过一次**，不是「配置填了」也不是「代码写完了」。
        //      一份填错的 endpoint 能让配置检查通过，但过不了这个判据。见 ContentSafetyGate。
        capabilities.register(GovernanceCapability.TEXT_SAFETY_GATE,
                contentSafety::isOperational);
    }

    /** 启动日志里把「开关状态 + 还差哪几项」写清楚，避免上线后靠翻代码确认。 */
    private static void logGovernanceReadiness(CapabilityRegistry capabilities,
                                               FeatureSwitchService switches) {
        var missing = capabilities.notReady();
        log.info("S13 留一句话开关: {}；治理能力就绪 {}/{}",
                switches.isLeaveWordsEnabled() ? "开启" : "关闭（P0 默认）",
                GovernanceCapability.values().length - missing.size(),
                GovernanceCapability.values().length);
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (GovernanceCapability c : missing) {
                if (!sb.isEmpty()) {
                    sb.append('、');
                }
                sb.append(c.displayName());
            }
            log.info("  未就绪（开关不可开启）: {}", sb);
        }
    }
}
