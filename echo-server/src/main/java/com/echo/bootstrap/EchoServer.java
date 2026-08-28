package com.echo.bootstrap;

import com.aengine.network.netty.websocket.WebSocketServer;
import com.aengine.network.support.PacketHandlerManager;
import com.aengine.scheduler.Scheduler;
import com.aengine.util.id.IDGenerator;
import com.aengine.util.thread.NamedThreadFactory;
import com.aengine.util.thread.OrderedExecutorService;
import com.echo.gateway.EchoHandler;
import com.echo.gateway.EchoSessionManager;
import com.echo.gateway.HeartbeatHandler;
import com.echo.gateway.LoginHandler;
import com.echo.gateway.MindProfileHandler;
import com.echo.gateway.NoOpRedisLockSupport;
import com.echo.gateway.ResonanceHandler;
import com.echo.gateway.SpaceHandler;
import com.echo.http.EchoHttpBootstrap;
import com.echo.infra.embedding.EmbeddingClientFactory;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.persistence.PgDb;
import com.echo.infra.persistence.PgDbManager;
import com.echo.infra.vector.IVectorStore;
import com.echo.infra.vector.PgVectorStore;
import com.echo.module.account.AccountRepository;
import com.echo.module.account.AccountService;
import com.echo.module.echo.EchoRepository;
import com.echo.module.echo.EchoService;
import com.echo.module.mind.MindProfileRepository;
import com.echo.module.mind.MindProfileService;
import com.echo.module.mind.SelfVectorRepository;
import com.echo.module.resonance.ResonanceService;
import com.echo.module.space.MindSpaceRepository;
import com.echo.module.space.MindSpaceService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 回响 (Echo) 后端启动类（BE-1 脚手架）。
 *
 * <p>目的：证明独立工程能调用 Aengine 网络层把一个最小可用的 WebSocket 服务跑起来。
 * 本期不接数据库、不注册业务 Handler。</p>
 *
 * <p>实际调用的 Aengine API（按真实签名）：</p>
 * <ul>
 *   <li>{@link WebSocketServer#WebSocketServer(int)} —— 指定 worker 线程数构造</li>
 *   <li>{@link com.aengine.network.netty.AbstractSocketServer#register(com.aengine.network.netty.IEventListener)}
 *       —— 注册会话管理器（{@link EchoSessionManager}）为事件监听器</li>
 *   <li>{@link WebSocketServer#bind(String, int)} —— 绑定地址端口</li>
 *   <li>{@link WebSocketServer#start()} —— 启动</li>
 *   <li>{@link WebSocketServer#shutdown()} —— 优雅停机</li>
 *   <li>{@link PacketHandlerManager#PacketHandlerManager(com.aengine.util.lock.distributedLock.RedisLockSupport)}
 *       与 {@link PacketHandlerManager#registHandlers(java.util.List)} —— 协议分发器（本期注册空列表）</li>
 * </ul>
 *
 * <p>运行：{@code java -Decho.port=9001 com.echo.bootstrap.EchoServer}，或将端口作为首个程序参数。</p>
 */
@Slf4j
public final class EchoServer {

    /** 默认 WebSocket 端口。 */
    private static final int DEFAULT_PORT = 9001;

    /** 默认绑定地址。 */
    private static final String DEFAULT_HOST = "0.0.0.0";

    /** 网络 worker 线程数。 */
    private static final int WORKER_THREADS = Runtime.getRuntime().availableProcessors();

    /**
     * 连接 idle 超时（秒）。Aengine {@code WebSocketServer} 默认 30s；客户端默认每 15s 心跳（§3.1），
     * 这里上调到 40s（≈2.7×心跳间隔），给弱网/抖动留足余量，符合 §3.1"服务端超时建议 ≥40s"。
     * 任意上行包都会刷新读时间重置 idle。
     */
    private static final int IDLE_SEC = 40;

    /** 数据源名：须与各仓储 {@code @CRepository(source=...)} 一致。 */
    private static final String DB_SOURCE = "echo";

    private EchoServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);
        String host = System.getProperty("echo.host", DEFAULT_HOST);

        // 1) 协议分发器：本期无 Redis，用占位锁支撑
        PacketHandlerManager handlerManager = new PacketHandlerManager(new NoOpRedisLockSupport());

        // 2) 会话处理线程池：复用引擎有序执行器，保证同一连接的包按序处理
        ExecutorService bizPool = new OrderedExecutorService(
                WORKER_THREADS, WORKER_THREADS * 2, 60,
                new NamedThreadFactory("echo-biz"));

        // 3) 网关会话管理器（作为 IEventListener 注册到服务端）
        EchoSessionManager sessionManager = new EchoSessionManager(bizPool, handlerManager);

        // 4) 共用依赖：雪花 ID + LLM 客户端（WS 与 HTTP 网关共用一枚 workerId / 一个 mock LLM）
        long workerId = Long.getLong("echo.workerId", 1L);
        IDGenerator idGenerator = new IDGenerator(workerId);
        ILlmClient llmClient = new MockLlmClient();

        // 5) 注册业务 Handler：登录闭环需要账号仓储，仅在开启 DB 时装配，
        //    否则保持无 DB 可启动（WebSocket 仍就绪，仅不处理登录）。
        AccountService accountService = registerHandlers(handlerManager, sessionManager, idGenerator, llmClient);

        // 6) 启动 Aengine WebSocket 服务（bind 前配置 idle 超时）
        WebSocketServer server = new WebSocketServer(WORKER_THREADS);
        server.setOpt("IDLE_SEC", IDLE_SEC);
        server.register(sessionManager);
        server.bind(host, port);
        server.start();
        log.info("EchoServer WebSocket 已启动: ws://{}:{} (workers={})", host, port, WORKER_THREADS);

        // 7) 启动轻量 HTTP/JSON REST 网关（默认 8080，-Decho.http.port 可配）。
        //    与 WS 9001 完全隔离；无 DB 时 accountService=null，网关走内存态跑通核心闭环。
        EchoHttpBootstrap.start(idGenerator, llmClient, accountService);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("EchoServer 正在停机...");
            server.shutdown();
        }, "echo-shutdown"));
    }

    /**
     * 装配并注册 Handler。
     *
     * <p>心跳 Handler 无 DB 依赖，始终注册（连接保活在握手后即可用）。登录及各业务闭环依赖
     * 仓储（构造即连库并自动建表），故仅在 {@link EchoDatabase#initIfEnabled()} 成功时才实例化
     * 仓储/服务/Handler 并注册；无 DB 时仅心跳可用，WebSocket 仍就绪。</p>
     *
     * <p>向量库按 DB 开关选择实现注入服务层：DB 开 → {@link PgVectorStore}（pgvector），
     * DB 关 → {@link InMemoryVectorStore}（仅心跳态下不会被业务用到，主要供单测/本地）。</p>
     */
    private static AccountService registerHandlers(PacketHandlerManager handlerManager,
                                                   EchoSessionManager sessionManager,
                                                   IDGenerator idGenerator,
                                                   ILlmClient llmClient) {
        List<Object> handlers = new ArrayList<>();
        // 心跳：始终可用（9001 在白名单，登录前后均可保活）
        handlers.add(new HeartbeatHandler());

        if (!EchoDatabase.initIfEnabled()) {
            handlerManager.registHandlers(handlers);
            log.warn("未接入 DB，仅注册心跳 Handler（WebSocket 仍就绪）。设 -Decho.db.enabled=true 以启用业务闭环。");
            return null;
        }

        // 外部依赖：向量库(pgvector，DB 开)。嵌入通道按 env 装配（有 key → ApiEmbeddingClient 切真；
        // 无 key → 回落 MockEmbeddingClient，与既有确定性哈希逐位一致，行为不变）。
        PgDb pgDb = PgDbManager.getInstance().get(DB_SOURCE);
        IVectorStore vectorStore = new PgVectorStore(pgDb, EmbeddingClientFactory.fromEnv());

        // 仓储
        SelfVectorRepository selfVectorRepository = new SelfVectorRepository();
        MindProfileRepository mindProfileRepository = new MindProfileRepository();
        MindSpaceRepository mindSpaceRepository = new MindSpaceRepository();
        EchoRepository echoRepository = new EchoRepository();

        // 服务
        AccountService accountService = new AccountService(new AccountRepository(), idGenerator);
        MindProfileService mindProfileService = new MindProfileService(
                mindProfileRepository, selfVectorRepository, llmClient, vectorStore, idGenerator);
        MindSpaceService mindSpaceService = new MindSpaceService(
                mindSpaceRepository, selfVectorRepository, idGenerator);
        ResonanceService resonanceService = new ResonanceService(vectorStore);
        EchoService echoService = new EchoService(echoRepository, mindSpaceRepository, idGenerator);

        // 业务 Handler
        handlers.add(new LoginHandler(accountService, sessionManager));
        handlers.add(new MindProfileHandler(mindProfileService));
        handlers.add(new SpaceHandler(mindSpaceService));
        handlers.add(new ResonanceHandler(resonanceService));
        handlers.add(new EchoHandler(echoService));
        handlerManager.registHandlers(handlers);
        log.info("已注册 Handler: Heartbeat(9001) + Login(1001) + MindProfile(1201) + Space(1301/1303) "
                + "+ Resonance(1401) + Echo(1501/1503)");

        // 过期回声清理：Scheduler + Cron
        Scheduler.init("echo-scheduler", 1);
        Scheduler.getInstance().schedule(new EchoExpiryJob(echoService));
        log.info("已调度过期回声清理任务: cron={}", EchoExpiryJob.DEFAULT_CRON);
        return accountService;
    }

    /**
     * 解析端口：优先首个程序参数，其次系统属性 {@code echo.port}，最后默认 {@value #DEFAULT_PORT}。
     */
    private static int resolvePort(String[] args) {
        if (args != null && args.length > 0) {
            try {
                return Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                log.warn("无法解析端口参数 '{}'，回退到默认值", args[0]);
            }
        }
        String prop = System.getProperty("echo.port");
        if (prop != null) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (NumberFormatException e) {
                log.warn("无法解析系统属性 echo.port='{}'，回退到默认值", prop);
            }
        }
        return DEFAULT_PORT;
    }
}
