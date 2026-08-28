package com.echo.http.governance;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * 举报（{@code SPEC-security §4.4/§4.5}「举报入口必须常驻可达」，{@code S3} 五项之一）。
 *
 * <h2>为什么复用 {@code t_report} 而不另起新表</h2>
 *
 * <p>举报是<b>一个概念、多种对象</b>（回忆卡 / 留言 / 账号）。拆成三张表的代价是：
 * 审核台的一个队列要变成三张表的 union，而<b>每加一类举报对象就要再改一次所有读路径</b>——
 * 列表、计数、按对象查、工单流转全都要动。加一列 {@code targetType} 只需改一次。</p>
 *
 * <p>反过来的代价（外键只能对 {@code card} 一类生效）是可接受的：留言与账号的引用完整性
 * 由 {@code CHECK} 约束 + 应用层校验兜住，而举报表本身不是这两者的所有者。</p>
 *
 * <h2>🔴 举报人身份不得对被举报方可见</h2>
 *
 * <p>这条在本类里是<b>结构性</b>保证，不靠"记得别返回"：C 端出参用
 * {@link ReporterView}，它<b>没有 reporterId 字段</b>。想泄漏就得先改这个 record 的定义，
 * 那是一次会被 CR 看到的改动。</p>
 *
 * <h2>🔴 反馈用词</h2>
 *
 * <p>丧亲场景不做对抗感。所以对举报人的反馈只有两档——「收到了」与「看过了」，
 * <b>绝不出现「已处罚」「已封禁」「已删除对方内容」这类表述</b>，
 * 也不透露被举报方发生了什么。{@link #FEEDBACK_RECEIVED} / {@link #FEEDBACK_REVIEWED}
 * 是唯一两句可对外的文案。</p>
 */
@Slf4j
public final class ReportService {

    /** 举报对象类型。 */
    public static final String TARGET_CARD = "card";
    public static final String TARGET_TEXT = "text";
    public static final String TARGET_ACCOUNT = "account";
    private static final Set<String> TARGETS = Set.of(TARGET_CARD, TARGET_TEXT, TARGET_ACCOUNT);

    /** 3430：重复举报（同一人对同一对象已有未处理的举报）。 */
    public static final int ERR_DUPLICATE_REPORT = 3430;

    // ------------------------------------------------------------ 🔴 对外文案
    /** 刚提交。 */
    public static final String FEEDBACK_RECEIVED = "收到了，我们会看一看。谢谢你告诉我们。";
    /**
     * 已处理。
     *
     * <p>🔴 刻意<b>不说</b>处理结果。举报人无权知道被举报方发生了什么——
     * 那是被举报方的事；而告诉他「已处罚」会把一次举报变成一次胜负。</p>
     */
    public static final String FEEDBACK_REVIEWED = "我们看过了，谢谢你。";

    /**
     * 理由码。
     *
     * <p>⚠️ <b>规格里没有理由码字典</b>（{@code API-CONTRACT §17.6} 原文明确「本轮不代拟」）。
     * 这里只放一个<b>最小可用集</b>让端点能跑，🔴 并且<b>不假装它是完整的</b>：
     * {@link #REASON_DICT_PROVISIONAL} 恒为 true，后台与回执都能看出这套码是临时的。
     * 定稿前不要拿它做运营分类统计——分类维度会变。</p>
     */
    public static final Set<String> PROVISIONAL_REASONS = Set.of(
            "harassment",     // 骚扰、辱骂
            "fraud",          // 诈骗、引流、收款
            "porn",           // 色情低俗
            "illegal",        // 违法违规
            "impersonation",  // 冒用他人/宠物身份
            "privacy",        // 侵犯隐私
            "other");

    /** 🔴 理由码字典为临时集合，未经产品定稿。 */
    public static final boolean REASON_DICT_PROVISIONAL = true;

    /**
     * 对举报人可见的举报视图。
     *
     * <p>🔴 <b>没有 reporterId，也没有被举报方的任何标识与处置结果。</b>
     * 举报人只需要知道"我报过、系统看过了"。</p>
     */
    public record ReporterView(long id, String targetType, String state, String feedback,
                               long createdAt) {
    }

    private final ReportStore store;
    private final IDGenerator idGenerator;

    public ReportService(ReportStore store, IDGenerator idGenerator) {
        this.store = store;
        this.idGenerator = idGenerator;
    }

    /**
     * 提交举报。
     *
     * @throws ApiException 3430 同一人对同一对象已有未处理举报
     */
    public ReporterView submit(long reporterId, String targetType, long targetId,
                               String reasonCode, String note) {
        if (!TARGETS.contains(targetType)) {
            throw new ApiException(ApiException.BAD_PARAM, "这个不太对，换个方式试试。",
                    "unsupported report targetType: " + targetType);
        }
        if (targetId <= 0) {
            throw new ApiException(ApiException.BAD_PARAM, "这个不太对，换个方式试试。",
                    "targetId required");
        }
        if (TARGET_ACCOUNT.equals(targetType) && targetId == reporterId) {
            throw new ApiException(ApiException.BAD_PARAM, "这个人是你自己呀。",
                    "cannot report self");
        }
        String reason = PROVISIONAL_REASONS.contains(reasonCode) ? reasonCode : "other";
        if (note != null && note.length() > 500) {
            note = note.substring(0, 500);
        }

        // 🔴 去重靠落库层的部分唯一索引（open 状态下唯一），不是先查再写——
        //    先查再写有竞态窗口，两个并发请求都能查到"还没报过"。
        ReportStore.Row row = store.insertIfNoOpen(idGenerator.nextId(), reporterId,
                targetType, targetId, reason, note, System.currentTimeMillis());
        if (row == null) {
            throw new ApiException(ERR_DUPLICATE_REPORT, "这个我们已经收到了，正在看。",
                    "duplicate open report by " + reporterId + " on " + targetType + ":" + targetId);
        }
        log.info("举报提交 target={}:{} reason={}", targetType, targetId, reason);
        return view(row);
    }

    /** 我的举报列表。🔴 出参不含 reporterId 与任何处置结果。 */
    public List<ReporterView> mine(long reporterId, int limit) {
        return store.byReporter(reporterId, limit).stream().map(ReportService::view).toList();
    }

    private static ReporterView view(ReportStore.Row r) {
        boolean handled = "handled".equals(r.status());
        return new ReporterView(r.id(), r.targetType(),
                handled ? "reviewed" : "received",
                handled ? FEEDBACK_REVIEWED : FEEDBACK_RECEIVED,
                r.createdAt());
    }

    public ReportStore store() {
        return store;
    }
}
