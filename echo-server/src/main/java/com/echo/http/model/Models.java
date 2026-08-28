package com.echo.http.model;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 网关新增业务域的领域模型（内存态 POJO 容器）。
 *
 * <p>用嵌套静态类聚合，避免为每个小实体单独开文件；字段用 public 便于 {@link com.echo.http.store.EchoStore}
 * 内存实现直接读写、由 handler 组装成契约 JSON。与 {@code src/main/resources/sql/schema.sql} 中新增表
 * （t_pet / t_flower_log / t_remember / t_postcard / t_record / t_message / t_relation / t_account_profile）
 * 字段一一对应，为后续 PG 落库（TODO: PgEchoStore）预留形状。</p>
 */
public final class Models {

    private Models() {
    }

    /**
     * 建档期主体类型的四个字段：取值域、兜底值、以及归一化。
     *
     * <h2>🔴 这四个字段是干什么用的</h2>
     * <p><b>它们的用途是让 {@code SR-D1} 能在服务端求值，不是给展示用的。</b>
     * {@code SR-D1} 的触发条件是「<b>素材置信度低</b> 且 <b>{@code subjectSource != "user"}</b>」，
     * 后半句的判据就在 {@link #DEFAULT_SUBJECT_SOURCE} 这个字段里；
     * 服务端拿不到它，那条兜底就只在纸上成立。</p>
     * <p>🔴 <b>所以：不要把这四个字段放进任何响应体、不要拿去渲染。</b>
     * {@code COPY-GUIDE} 有一条禁令是「不得在任何面向用户的文案中暴露主体识别的判定结果」，
     * 下发即违规。它们只在服务端内部被门控读取。</p>
     * <p>🔴 <b>也不要把它们与 {@code objectKind}/{@code objectStatus} 互相赋值。</b>
     * 这里的「主体类型」是<b>建档期识别结果，用户可随手纠正</b>；
     * {@code objectStatus}（生存状态）是另一个维度，{@code MOD5}/{@code SR-D1} 都明文禁止互推。</p>
     *
     * <h2>🔴 为什么兜底是 other / default 而不是 animal</h2>
     * <p>「认不出 + 用户没答 → 悄悄判成能力最宽的类别」与保守兜底的方向正好相反，
     * 而 <b>「字段缺失」正是老客户端与第三方调用的常态</b> —— 前端修好不代表这个口子关了，
     * 所以两处兜底都收在服务端这一侧。</p>
     */
    public static final class SubjectFields {

        /**
         * 🔴 {@code subjectType} 缺失或取值不认识时落的值。
         *
         * <p><b>是 {@code other}，不是 {@code animal}。</b>{@code animal} 是三类里能力最宽的一类，
         * 拿它当兜底等于「认不出来就按最宽的放行」。与前端
         * {@code SUBJECT_LOW_CONFIDENCE_FALLBACK} 同值，两侧必须一致。</p>
         */
        public static final String DEFAULT_SUBJECT_TYPE = "other";

        /**
         * 🔴 {@code subjectSource} 缺失或取值不认识时落的值。
         *
         * <p><b>必须是 {@code default}，绝不能是 {@code user}。</b>{@code SR-D1} 的条件是
         * {@code subjectSource != "user"}，所以兜底成 {@code user} 会<b>关掉整条兜底分支</b>——
         * 而且是静默关掉：没有报错，只是低置信度素材从此再也进不了 {@code L1}。
         * 落 {@code default} 则保持 {@code != "user"} 为真，兜底照常可以触发。</p>
         */
        public static final String DEFAULT_SUBJECT_SOURCE = "default";

        private static final List<String> SUBJECT_TYPES = List.of("animal", "person", "other");
        private static final List<String> SUBJECT_SOURCES = List.of("machine", "user", "default");

        private SubjectFields() {
        }

        /**
         * 归一化生效主体类型：不认识的一律落 {@link #DEFAULT_SUBJECT_TYPE}。
         *
         * <p>🔴 <b>刻意不抛 400。</b>照 {@code A7 ③}，主体判定这条链路上的收缩不报错——
         * 报错会把一个内部判定暴露成用户可见的失败，而这里真正要防的只是「别落到 animal」。</p>
         */
        public static String normalizeType(String v) {
            // 🔴 v == null 必须先挡掉：SUBJECT_TYPES 是 List.of(...)，
            //    而 List.of 的 contains(null) 抛 NPE 而不是回 false。
            //    这条路走得到——老库补列前的行读出来就是 null（见 PgEchoStore.toPet）。
            return v != null && SUBJECT_TYPES.contains(v) ? v : DEFAULT_SUBJECT_TYPE;
        }

        /** 归一化机器原判 / 用户填写值：{@code null} 是合法值（表示「没有」），保持 {@code null}。 */
        public static String normalizeNullableType(String v) {
            if (v == null) {
                return null;
            }
            return SUBJECT_TYPES.contains(v) ? v : DEFAULT_SUBJECT_TYPE;
        }

        /**
         * 归一化来源：不认识的一律落 {@link #DEFAULT_SUBJECT_SOURCE}（🔴 不会落成 {@code user}）。
         *
         * <p>🔴 {@code null} 同样先挡掉，理由见 {@link #normalizeType}。</p>
         */
        public static String normalizeSource(String v) {
            return v != null && SUBJECT_SOURCES.contains(v) ? v : DEFAULT_SUBJECT_SOURCE;
        }
    }

    /** 账号 HTTP 概要（游客一等公民）。t_account_profile。 */
    public static final class AccountProfile {
        public long accountId;
        public String deviceId;
        public boolean guest = true;
        public String nickname = "旅人";
        public String avatar = "";
        /** 可见性默认档：private|friends|public，默认 private（定案 #1）。 */
        public String visibilityDefault = "private";
        public boolean hasPet;
        /** 训练用途同意（PIPL 独立 opt-in，默认 false；与纪念场景 allowUse 分开）。 */
        public boolean trainConsent;
        public long createTime;
    }

    /** 往宠档案。t_pet。 */
    public static final class PetProfile {
        public String petId;
        public long ownerAccountId;
        public String name;
        public String species;
        public String signature = "";
        /** 温度（PRD §3.11：地板 60，正常初值 72）。 */
        public double temperature = 72.0;
        /** 可见性：private|friends|public，默认 private（定案 #1）。 */
        public String visibility = "private";
        public String coverGradient = "dusk";
        public String coverEmoji = "\uD83D\uDC3E";
        public String memoryCaption = "";
        public long lastVisitAt;
        // owner 私域计数（定案 #4：看过/羁绊只 owner 内部可见）
        public long seenCount;
        public long flowersReceived;
        /** 训练用途同意（随建档确认从账号/建档态带入，默认 false；PIPL 门控用）。 */
        public boolean trainConsent;
        /**
         * 建档期主体类型的四个字段，随 {@code /pet/onboarding/confirm} 从建档态带入。
         *
         * <p>🔴 <b>用途、取值域与兜底方向全部见 {@link SubjectFields}——尤其那两条「不下发、不互推」。</b></p>
         *
         * <p>🔴 <b>为什么落在 {@code t_pet} 而不是只留在 {@link Onboarding} 里</b>：
         * 建档态是<b>短生命周期内存态</b>，建完就没了；而 {@code SR-D1} 要在<b>后续每一次生成</b>
         * 时求值。只接不存等于字段仍然拿不到，缺口没补上。</p>
         */
        public String subjectType = SubjectFields.DEFAULT_SUBJECT_TYPE;
        public String machineSubjectType;
        public String userSubjectType;
        public String subjectSource = SubjectFields.DEFAULT_SUBJECT_SOURCE;
        public long createTime;
        public final List<LifeBookItem> lifeBook = new ArrayList<>();
        /** 性情词（建档采集，用作回声生成的上下文）。 */
        public final List<String> traits = new ArrayList<>();
    }

    /** 生命之书条目。 */
    public static final class LifeBookItem {
        public String title;
        public int year;
        public String desc;

        public LifeBookItem() {
        }

        public LifeBookItem(String title, int year, String desc) {
            this.title = title;
            this.year = year;
            this.desc = desc;
        }
    }

    /** 一条 AI 近况/来信。 */
    public static final class PetEcho {
        public String echoId;
        public String petId;
        public String text;
        public String tone = "gentle";
        public long createdAt;
        public String reply;
    }

    /** 献花流水（owner 可见的私密羁绊名单；定案 #3：不写温度、不排名）。t_flower_log。 */
    public static final class FlowerLog {
        public String id;
        /**
         * 名叫 {@code windowId}，装的<b>就是</b> {@code petId} —— 献花记在<b>窗</b>上。
         *
         * <h2>🔴 2026-08-27 更正：这里原先写的「目标态是卡级」是错的</h2>
         *
         * <p>本字段原先标着「🔴 【迁移并存点·待拆】…目标态是卡级（{@code t_memory_card.id}），
         * 第三步『献花与记得迁到卡级』会把列一起改掉」。⚠️ 🔴 <b>那个第三步不存在，
         * 而且不会存在。</b></p>
         *
         * <p>{@code DECISIONS RK-H}（2026-08-26 制作人裁定）定的是
         * 🔴 <b>「卡级只增加一列入口归因，<u>互动本身不搬家</u>」</b>：窗级仍然一人一次、
         * 仍然决定暖意与面孔墙（{@code D8} 原意一字不改），
         * <b>暖意 / 面孔墙 / {@code warmthLevel} / 独立互动者数一律仍从窗级取</b>。</p>
         *
         * <p>而 {@code RESEARCH-window-content-ownership §3.4} 进一步把那句话本身判为错话：
         * ⚠️ 🔴 <b>「把献花迁到卡级」字面读下来就是把互动挂到<u>作品</u>上，而那正是点赞 ——
         * 立项时明确砍掉的东西。</b>该文档同时记明：这句错话的源头是<b>代码注释</b>，
         * 文档线是跟着代码写的，「代码侧同类注释归后端线」。🔴 <b>本次更正就是那一笔。</b></p>
         *
         * <p>所以：<b>这个字段是终态，不是过渡态。</b>它永远装 petId。
         * 📌 唯一仍然成立的提醒是 <b>它跟 {@code LeaveWordsApi} 里的 cardId 不是一套 id</b>，
         * 别按名字理解。真要改名（{@code windowId} → {@code petId}）是一次纯重命名，
         * 与任何迁移无关。</p>
         *
         * <p>⚠️ {@code S4 自然流掉} 仍然必须关闭，🔴 <b>但理由变了</b>：不是「等迁移完成」，
         * 而是 {@code RK-H} 把 {@code S4} 按字面要的那个数（每张卡的独立互动者）
         * <b>裁定为不存在</b>。见 {@link com.echo.http.ranking.S4DrainPolicy}。</p>
         */
        public String windowId;
        public long fromAccountId;
        public long toOwnerAccountId;
        public int count;
        public String type = "daily";
        public String message = "";
        public boolean anonymous;
        public long createdAt;
        /** 领取免费额度的自然日（yyyyMMdd），用于每日 5 朵额度核算。 */
        public int day;
    }

    /**
     * 别人对我的窗做出的<b>一次</b>回应（{@code PRODUCT-MINDMAP §6.2 B20} 到达的源数据）。
     *
     * <p>由 {@code t_flower_log} 与 {@code t_remember} 归一而来：两张表形状不同，
     * 但对「被接住」来说它们是同一件事——有人来过、留下了一点暖意。</p>
     *
     * <p>🔴 这里<b>没有「几个人」</b>。合并成一条通知时人数是可以数出来的，但一旦有个字段装着它，
     * 它迟早会被渲染成「3 个人记得了它」——那与共鸣厅「不显热度与精确记得数」是同一条红线。
     * 不持有这个数，比约束下游别渲染要牢固。</p>
     */
    public static final class ReactionMark {
        /** 稳定 id：同一次回应重复拉取要得到同一个值（前端按 id 去重）。 */
        public String id;
        /** remember | flower。 */
        public String kind;
        /**
         * 被回应的那扇窗（合并键）。装的是 <b>petId</b>。
         *
         * <h2>🔴 2026-08-27 更正：出参里那个 {@code cardId} 键不会自己变对</h2>
         *
         * <p>本字段原先标着「目标态是 cardId，与 {@link FlowerLog#windowId} 同一批迁移。
         * 出参里这个值被放进 {@code cardId} 键，那个键名今天是提前取好的名字，不是事实」。</p>
         *
         * <p>⚠️ 🔴 <b>「提前取好的名字」这个说法要撤销：它预设了一次会把名字兑现的迁移，
         * 而按 {@code RK-H} 那次迁移不会发生</b>（互动不搬家，理由见
         * {@link FlowerLog#windowId}）。所以 {@code /messages/arrivals} 出参里的
         * {@code cardId} 键 🔴 <b>是一个<u>永久</u>错名</b>，不是暂时的。</p>
         *
         * <p>📋 <b>待裁定，两条路，本轮不擅自选</b>：</p>
         * <ol>
         *   <li><b>改键名</b> {@code cardId} → {@code windowId}。诚实、便宜，
         *       但前端要跟着改一次，且「到达按窗折叠」这个产品行为就此固定下来。</li>
         *   <li>🔴 <b>改数据源</b>：到达改成读 {@code t_resonance}
         *       —— ⚠️ <b>那张表本来就是卡级的</b>（{@code targetScope='card'}、
         *       {@code cardId NOT NULL}），而且 {@code GTM4} 北极星的分子读的正是它
         *       （{@code t_resonance_type.countsToAcceptance}，
         *       {@code DECISIONS §GTM4} 明确它是「什么算被接住」的<b>唯一真源</b>）。
         *       这一路能让键名真正名副其实，也顺带对齐 {@code D22}
         *       「同一张卡被多人回应必须合并成一条」—— 🔴 <b>而按窗折叠恰好做不到这条</b>：
         *       按窗合并时，同一扇窗<u>不同卡</u>收到的回应也会被并成一条。</li>
         * </ol>
         *
         * <p>🔴 <b>不要再写「迁完它自动名副其实」</b>—— 没有那个「迁完」。</p>
         */
        public String windowId;
        /**
         * 回应者。🔴 <b>仅供服务端判断「不是自己回应自己」</b>，绝不下发——
         * 到达只说「有人」，点进去看的是窗本身，暖光面孔墙自会呈现是谁。
         */
        public long actorAccountId;
        public long createdAt;
    }

    /** 明信片（里程碑解锁；付费只加速/款式，定案 #2）。t_postcard。 */
    public static final class Postcard {
        public String id;
        public String petId;
        public String date;
        public String caption;
        public boolean locked = true;
        public String unlockHint = "";
        public String skin = "classic";
        public long createdAt;
    }

    /** 记录（双向：给它/给自己；定案：非打卡）。t_record。 */
    public static final class RecordEntry {
        public String id;
        public long accountId;
        public String scope = "self"; // pet|self
        public String text;
        public long createdAt;
    }

    /** 消息集散地条目。t_message。 */
    public static final class MessageEntry {
        public String id;
        public long accountId;
        public String kind = "system"; // friend|system|pet
        public String title;
        public String preview;
        public boolean read;
        public long createdAt;
        public String routeType; // window|relation|record|echo
        public String routeId;
    }

    /** 亲友关系。t_relation。 */
    public static final class RelationEntry {
        public String id;
        public long accountId;
        public long peerAccountId;
        public String peerName;
        public String peerAvatar = "";
        public boolean online;
        public boolean priority;
        public long mutedUntil;
        public boolean hasUnseenReel;
        /** 最近活跃时间（毫秒时间戳，契约 §8 M-5：排序"最近活跃"用，前端映射为相对时间）。 */
        public long lastActiveAt;
        public long createdAt;
    }

    /** 光谱节点（暖光星云）。 */
    public static final class SpectrumNode {
        public String id;
        public String label;
        public double intensity = 0.6;
        public long createdAt;

        public SpectrumNode() {
        }

        public SpectrumNode(String id, String label, double intensity, long createdAt) {
            this.id = id;
            this.label = label;
            this.intensity = intensity;
            this.createdAt = createdAt;
        }
    }

    /** 光谱暗面（whisper 第一人称、氛围化、不可归因；§2.11 红线）。 */
    public static final class ShadowArea {
        public String id;
        public String whisper;
        public double depth = 0.3;

        public ShadowArea() {
        }

        public ShadowArea(String id, String whisper, double depth) {
            this.id = id;
            this.whisper = whisper;
            this.depth = depth;
        }
    }

    /** 建档流程态（短生命周期，内存态）。 */
    public static final class Onboarding {
        public String onboardingId;
        public long accountId;
        public String petName;
        public String species;
        /**
         * 生效的主体类型：{@code animal|person|other}。
         *
         * <p>🔴 <b>初值是 {@code other} 而不是 {@code animal}</b>，理由见
         * {@link SubjectFields#DEFAULT_SUBJECT_TYPE}。</p>
         */
        public String subjectType = SubjectFields.DEFAULT_SUBJECT_TYPE;
        /** 机器原判，{@code null} = 机器没给出可用判定（低置信度）。见 {@link SubjectFields}。 */
        public String machineSubjectType;
        /** 用户主动填写的值，{@code null} = 用户没动过预填值。见 {@link SubjectFields}。 */
        public String userSubjectType;
        /** {@code subjectType} 这个值是谁定的：{@code machine|user|default}。见 {@link SubjectFields}。 */
        public String subjectSource = SubjectFields.DEFAULT_SUBJECT_SOURCE;
        public String rawDesc;
        public final List<String> traits = new ArrayList<>();
        public final List<Candidate> candidates = new ArrayList<>();
        public String chosenCandidateId;
        /** 训练用途同意（/pet/onboarding/start 入参 trainConsent，默认 false；PIPL 独立 opt-in）。 */
        public boolean trainConsent;
        /** 输入素材 resourceId 列表（训练语料回流用；可空）。 */
        public final List<String> inputRefs = new ArrayList<>();
        /** 识别得到的种类（/detect 输出或 start 带入；训练语料回流用）。 */
        public String detectedSpecies;
        /** 定妆重做次数（每次 refine +1；训练语料回流用的负向信号）。 */
        public int redoCount;
        public long createTime;
    }

    /** 定妆候选。 */
    public static final class Candidate {
        public String id;
        public String gradient;
        public String emoji;
        public String signature;

        public Candidate() {
        }

        public Candidate(String id, String gradient, String emoji, String signature) {
            this.id = id;
            this.gradient = gradient;
            this.emoji = emoji;
            this.signature = signature;
        }
    }
}
