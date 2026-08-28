package com.echo.http.model;

/**
 * 审核 / 申诉 / 举报域的领域模型。
 *
 * <p>与 {@code schema.sql} 的 {@code t_memory_card} / {@code t_moderation} /
 * {@code t_card_visibility_log} / {@code t_audit_log} / {@code t_report} /
 * {@code t_moderation_setting} 字段一一对应。规格真源：
 * {@code SPEC-publish-and-ops §1.5/§1.8/§2.2/§2.6} + {@code API-CONTRACT §15.4/§17}。</p>
 *
 * <p>沿 {@link Models} 的写法：嵌套静态类聚合、字段 public，由 store 直接读写、
 * 由 api 组装成契约 JSON。</p>
 */
public final class ModerationModels {

    private ModerationModels() {
    }

    // ------------------------------------------------------------------ 枚举口径

    /** 卡片状态。§1.4 状态机 + §2.2.1 迁移表。 */
    public static final class CardStatus {
        public static final String DRAFT = "draft";
        public static final String ACTIVE = "active";
        public static final String PENDING = "pending";
        public static final String BLOCKED = "blocked";
        public static final String PUBLIC = "public";
        public static final String REJECTED = "rejected";
        public static final String TAKENDOWN = "takendown";
        public static final String APPEALING = "appealing";
        public static final String DELETED = "deleted";

        private CardStatus() {
        }
    }

    /** 审核工单状态（{@code t_moderation.state}）。与卡片 status 是两个字段，不要合并。 */
    public static final class ModerationState {
        public static final String AUTO_PASS = "auto_pass";
        public static final String PENDING = "pending";
        public static final String BLOCKED = "blocked";
        public static final String APPROVED = "approved";
        public static final String REJECTED = "rejected";
        public static final String TAKENDOWN = "takendown";
        public static final String APPEALING = "appealing";

        private ModerationState() {
        }
    }

    /** 运营人工动作（§17.1）。 */
    public static final class Action {
        public static final String APPROVE = "approve";
        public static final String REJECT = "reject";
        public static final String TAKEDOWN = "takedown";
        public static final String ESCALATE = "escalate";
        /** 申诉处置：维持原处置。 */
        public static final String UPHOLD = "uphold";
        /** 申诉处置：撤销原处置，回 pending 重走人工（🔴 不直接放行）。 */
        public static final String OVERTURN = "overturn";

        private Action() {
        }
    }

    /** {@code t_audit_log.action}（§17.5 的六个增量）。 */
    public static final class AuditAction {
        public static final String APPROVE = "moderation.approve";
        public static final String REJECT = "moderation.reject";
        public static final String TAKEDOWN = "moderation.takedown";
        public static final String ESCALATE = "moderation.escalate";
        public static final String APPEAL = "moderation.appeal";
        public static final String SETTINGS_UPDATE = "moderation.settings.update";
        public static final String APPEAL_UPHOLD = "moderation.appeal.uphold";
        public static final String APPEAL_OVERTURN = "moderation.appeal.overturn";

        private AuditAction() {
        }
    }

    /** 内容来源（§1.8.3b · 前置闸门 G-1）。🔴 审核动作一律不得写、不得改这个字段。 */
    public static final class OriginType {
        public static final String USER = "user";
        public static final String OFFICIAL = "official";

        private OriginType() {
        }
    }

    // ------------------------------------------------------------------ 实体

    /** 回忆卡。t_memory_card。 */
    public static final class MemoryCard {
        public long id;
        public long ownerId;
        public long petId;
        public String sourceType = "";
        public String sourceRef = "";
        /**
         * 封面素材 key。🔴 <b>允许缺失（空串就是合法值），不要加非空校验。</b>
         *
         * <p>本表装的是<b>所有可发布的回忆</b>（{@code OM2}：AI 回声 + 手写 {@code record} +
         * 生命之书），🔴 <b>这两类多半没有标题，也可能没有图。</b>
         * {@code schema.sql} 里它本来就是 {@code NOT NULL DEFAULT ''}，
         * 数据库这一侧从一开始就是允许的 —— 别在应用层反过来收紧。</p>
         */
        public String coverKey = "";
        /**
         * 标题，≤30 字。🔴 <b>必须允许为空</b>，理由同 {@link #coverKey}。
         *
         * <p>🔴 <b>下发 {@code cards[]} 时不要因为空标题就跳过这张卡、也不要塞一个占位标题。</b>
         * 前端的取值链是「{@code title} → 正文首句」，空标题是<b>预期内的输入</b>，
         * 不是数据缺陷。</p>
         *
         * <p>⚠️ <b>那条取值链的后半段（正文首句由谁来切）尚未裁定</b>，
         * 见 {@link #body}。</p>
         */
        public String title = "";
        /**
         * 正文，≤500 字，入库前过《温柔词表》。
         *
         * <p>📌 <b>「正文首句由谁来切」已裁定（2026-08-27）：服务端切</b>，
         * 实现见 {@link com.echo.http.card.CardExcerpt}。理由是前端切意味着
         * <b>全文仍然下发给了不该看全文的人</b>——而 {@code VisibilityMatrix} 的类注释
         * 已经把这条道理写死了：「前端的 fail-closed 渲染保护的是<b>页面上画什么</b>，
         * 保护不了<b>端点下发什么</b>，<b>而抓一次包看的是后者</b>」。</p>
         *
         * <p>🔴 <b>所以 {@code cards[]} 列表面没有 {@code body} 这个键</b>，
         * 全文只在卡详情（单卡、过完可见性）里给。</p>
         */
        public String body = "";
        /** 主题标签 id 数组的 json 文本（0–3 个）。 */
        public String topicIdsJson;

        /**
         * 🔴 <b>作者<u>设定</u>的可见性档位 —— 不是「这张卡现在对谁可见」。</b>
         *
         * <h2>⚠️ 为什么这个字段不叫 {@code visibility}</h2>
         *
         * <p>它对应的数据库列<b>就叫 {@code visibility}</b>（{@code t_memory_card.visibility}，
         * 三值 {@code CHECK}）。字段名与列名刻意不一致，🔴 <b>这是有意的，不是疏漏</b>：</p>
         *
         * <p>按 {@code C-5}，「卡不得宽于窗」这条硬约束走的是<b>读时取交集</b>而不是级联改卡
         * （理由见 {@link com.echo.http.card.CardVisibility}：级联会把作者从没做过的决定
         * 静默写进库里，且窗改回来时无法复原）。代价是 🔴 <b>库里会长期存在
         * 「本列 = public 但实际对外不可见」的卡行</b>——窗收窄了，而卡行刻意不跟着改。</p>
         *
         * <p>于是这一列<b>单独读出来永远是偏宽的</b>，而
         * ⚠️ 🔴 <b>偏宽的可见性判断就是内容泄漏</b>。原先这条纪律只写在
         * {@link com.echo.http.card.CardVisibility} 的注释里，靠人记；
         * 而它是<b>泄漏方向</b>的风险，注释撑不住。</p>
         *
         * <p>改名把它变成三件事：</p>
         * <ol>
         *   <li><b>编译期</b>：所有既有裸读一次性全部断掉，由编译器列给我，不靠人找；</li>
         *   <li><b>读代码时</b>：{@code card.visibilityIntent} 自己就说了它是<b>意图</b>，
         *       ⚠️ 而 {@code card.visibility} 读起来像是答案；</li>
         *   <li>🔴 <b>grep 时可分辨</b>：此前 {@code card.visibility} 与
         *       {@code pet.visibility}（窗级，直接读是<b>对的</b>）在检索里长得一模一样，
         *       所以「扫出所有卡级裸读」这件事<u>做不到</u>。现在做得到了，
         *       常驻探针 {@code VisibilityScanProbe} 就是靠这个名字扫的。</li>
         * </ol>
         *
         * <p>🔴 <b>要判「这张卡对外可见吗」，走
         * {@link com.echo.http.card.CardVisibility#effective}</b>，把本字段与所属窗的
         * 可见性一起传进去。直接读本字段只在两种情况下正确：<b>回显作者的设定</b>、
         * <b>写入前的 CAS 比较</b>。</p>
         */
        public String visibilityIntent = "private";
        public String status = CardStatus.DRAFT;
        /** {@code {remember,heart,echo}} 作者互动开关的 json 文本。 */
        public String interactionJson;
        public long createdAt;
        public long updatedAt;
        /** 作者点发布的时刻。与 reviewedAt 并存、各有其用，两者差值 = 审核积压时长。 */
        public Long publishedAt;
        /**
         * 首次过审时刻 = 内容真正可被他人看见的那一刻，北极星 7 天窗口的起点。
         * 🔴 只写一次；编辑后复审、下架再上架都不刷新。
         */
        public Long reviewedAt;
        /**
         * 置顶时刻；{@code null} = 未置顶。
         *
         * <p>🔴 <b>作用域只有作者自己那一页的公开卡列表</b>，绝不进共鸣厅公开流——
         * 那会和权重衰减正面打架。上限、可置顶条件、自动解除口径全在
         * {@link com.echo.http.card.PinPolicy}。</p>
         *
         * <p>🔴 <b>取消发布 / 审核打回 / 下架时必须一并清空</b>，不许留悬空值：
         * 悬空的 {@code pinnedAt} 会在卡重新过审时让它自己跳回置顶位，
         * 而作者并没有再做这个决定。</p>
         */
        public Long pinnedAt;
        public Long deletedAt;
        public Long deletedBy;
        public String deleteReason;
        /** 🔴 发布时由发布路径显式落库，无默认值。审核动作不得写、不得改。 */
        public String originType;
        public boolean assistedByOps;
    }

    /** 审核工单。t_moderation（§2.6 + §2.6.1 申诉五列）。 */
    public static final class ModerationTicket {
        public long id;
        public long cardId;
        public long submitBy;
        public String autoRiskLevel = "low";
        public String autoSignalsJson;
        public String state = ModerationState.PENDING;
        public Long handledBy;
        public String reasonCode;
        public String note;
        public String snapshotJson;
        public long createdAt;
        public Long handledAt;

        // ---- 申诉五列（§2.6.1）----
        public String appealText;
        /** 🔴 「一张卡一生只能申诉一次」的唯一判据：非空即视为机会已用掉。刻意不设计数列。 */
        public Long appealAt;
        public Long appealHandledBy;
        public Long appealHandledAt;
        /** uphold | overturn。 */
        public String appealResult;

        /** 申诉机会是否已用掉。🔴 唯一判据 = {@code appealAt IS NOT NULL}（MOD2）。 */
        public boolean appealUsed() {
            return appealAt != null;
        }
    }

    /** 可见性/状态变更流水。t_card_visibility_log（🔴 只追加、不修改、不删除）。 */
    public static final class CardVisibilityLog {
        public long id;
        public long cardId;
        public String fromVisibility;
        public String toVisibility;
        public String fromStatus;
        public String toStatus;
        public long changedBy;
        /** author | moderator | system。审核台一律 moderator。 */
        public String changedRole;
        public String reasonCode;
        public long changedAt;
    }

    /** 审计账本。t_audit_log（API-CONTRACT §15.4；🔴 C 端零暴露）。 */
    public static final class AuditLog {
        public long id;
        public String actor = "";
        /** user | staff | system。 */
        public String actorType = "user";
        public String action;
        public String targetType = "";
        public String targetId = "";
        /** 影响范围的 json 文本；审核处置记 §17.5 的五个键。 */
        public String scopeJson;
        public long ts;
        public String ip;
        public String reviewerId;
    }

    /** 举报。t_report（⚠️ 仅够 GET /admin/reports 读取，提交端点与理由码字典仍缺，见 §17.6）。 */
    public static final class Report {
        public long id;
        public long cardId;
        public long reporterId;
        public String reasonCode = "";
        public String note;
        public String status = "open";
        public long createdAt;
        public Long handledAt;
    }

    /** 先审后发 / 先发后审开关。t_moderation_setting（TC-MOD-05 依赖它）。 */
    public static final class ModerationSetting {
        public static final String REVIEW_FIRST = "review_first";
        public static final String PUBLISH_FIRST = "publish_first";

        /** 默认先审后发（种子期量小、稳）。 */
        public String mode = REVIEW_FIRST;
        public String scopeJson;
        public long updatedBy;
        public long updatedAt;
    }

    // ------------------------------------------------------------------ 处置指令

    /**
     * 一次审核处置的完整指令。
     *
     * <p>🔴 它被设计成"一条指令 = 一个事务"：状态变更、{@code reviewedAt}、
     * {@code t_card_visibility_log}、{@code t_audit_log} 四件事只能通过这一个对象一起提交
     * （{@code ModerationStore.handleAtomically}）。这样 {@code MOD1 ③}「同一事务」就成了
     * <em>结构上做不到分开写</em>，而不是一句注释——分事务写会留下「状态变了、流水没留」的
     * 不可追溯窗口，而流水表只追加、事后修不回来。</p>
     *
     * <p>🔴 刻意<b>没有</b> {@code originType} 字段：{@code MOD1 ②} 要求审核动作一律不得写、
     * 不得改内容来源。官方号内容不进北极星分母靠的是正向白名单 {@code originType='user'}，
     * 审核台若能改这个字段，等于开放了静默改分母的入口，而流水上看起来只是一次正常审核。
     * 指令对象里没有这个字段 → 下游 SQL 不可能带上它。</p>
     */
    public static final class HandleCommand {
        public long moderationId;
        public long cardId;
        /** 期望的当前状态：用于乐观并发校验（CAS），不匹配则整体不生效。 */
        public String expectedCardStatus;
        public String toCardStatus;
        public String fromVisibility;
        public String toVisibility;
        public String toModerationState;
        public long operatorId;
        /** author | moderator | system。 */
        public String changedRole = "moderator";
        public String reasonCode;
        public String note;
        public String auditAction;
        /** user | staff | system —— 审计账本里的操作者类型。 */
        public String auditActorType = "staff";
        public String snapshotJson;
        public String auditScopeJson;
        /** 🔴 仅 approve 为 true；且落库时只在 {@code reviewedAt IS NULL} 时才真的写入。 */
        public boolean writeReviewedAt;
        /** 申诉处置结果（uphold/overturn），非申诉动作为 null。 */
        public String appealResult;
        public long now;
    }

    /** 一次处置的结果（供接口出参回显，便于 QA 直接断言）。 */
    public static final class HandleResult {
        public long moderationId;
        public long cardId;
        public String state;
        public String cardStatus;
        /** 过审动作回显首次过审时刻；其余动作回显既有值（不清空）。 */
        public Long reviewedAt;
        public long handledAt;
    }
}
