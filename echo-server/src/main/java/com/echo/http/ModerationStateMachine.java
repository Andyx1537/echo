package com.echo.http;

import com.echo.http.model.ModerationModels.Action;
import com.echo.http.model.ModerationModels.AuditAction;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.ModerationState;

import java.util.Map;
import java.util.Set;

/**
 * 审核状态迁移的合法性判定（{@code SPEC-publish-and-ops §2.2.1}）。
 *
 * <p>为什么要有一张<b>封闭</b>的迁移表：§2.2 的流程图给的是"正常路径"，照它实现时
 * 「已下架的卡再点通过」这类调用只能靠开发临场判断。这里把允许的组合逐条列全，
 * 其余一律 {@code 3412 moderation_state_conflict}，且<b>服务端校验、不信任前端</b>。</p>
 *
 * <p>两条容易实现错的：</p>
 * <ul>
 *   <li>🔴 {@code takendown} 不得由审核台直接放行（{@code approve} 不在它的允许集里），
 *       必须经作者申诉 + {@code overturn} 回 {@code pending} 重走人工。否则下架就成了
 *       一个可以私下撤销的动作。</li>
 *   <li>🔴 {@code overturn} 只回 {@code pending}，不直接到 {@code public}。撤销的含义是
 *       "原处置有问题、重新判一次"，不是"改判为通过"——直接放行会让申诉变成一条绕过
 *       人工审核的通道。</li>
 * </ul>
 */
public final class ModerationStateMachine {

    /** 3412：审核动作与当前状态不兼容（非法状态迁移）。 */
    public static final int ERR_STATE_CONFLICT = 3412;
    /** 3413：驳回 / 下架未带 reasonCode。 */
    public static final int ERR_REASON_REQUIRED = 3413;
    /** 3410：申诉机会已用完（一张卡只能申诉一次）。 */
    public static final int ERR_APPEAL_USED = 3410;
    /** 3411：当前状态不可申诉。 */
    public static final int ERR_APPEAL_NOT_APPLICABLE = 3411;

    /** 运营人工四动作在各状态下的允许集（§2.2.1）。未列出的状态 = 不允许任何动作。 */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            CardStatus.PENDING, Set.of(Action.APPROVE, Action.REJECT, Action.ESCALATE),
            CardStatus.BLOCKED, Set.of(Action.APPROVE, Action.REJECT, Action.ESCALATE),
            // 🔴 public 只能下架或升级复核：approve 是"已经是通过态"，reject 无从驳回
            CardStatus.PUBLIC, Set.of(Action.TAKEDOWN, Action.ESCALATE),
            // 🔴 appealing 只接受申诉处置两动作，且都不直接放行到 public
            CardStatus.APPEALING, Set.of(Action.UPHOLD, Action.OVERTURN)
            // rejected / takendown：运营无动作可做（只能等作者申诉）
            // 🔴 deleted：软删后卡与其互动一并不可见，审核台不应再看到它 → 无任何动作
    );

    /** 可申诉的状态（§17.2 前置）。 */
    private static final Set<String> APPEALABLE = Set.of(CardStatus.REJECTED, CardStatus.TAKENDOWN);

    /** 需要 reasonCode 的动作（§17.3 的 3413）。 */
    private static final Set<String> REASON_REQUIRED = Set.of(Action.REJECT, Action.TAKEDOWN);

    private ModerationStateMachine() {
    }

    /** 该状态下是否允许该动作。 */
    public static boolean allows(String currentStatus, String action) {
        return ALLOWED.getOrDefault(currentStatus, Set.of()).contains(action);
    }

    /** 当前状态是否可由作者申诉（不含"是否已用掉机会"的判断，后者看 appealAt）。 */
    public static boolean appealable(String currentStatus) {
        return APPEALABLE.contains(currentStatus);
    }

    public static boolean requiresReasonCode(String action) {
        return REASON_REQUIRED.contains(action);
    }

    /**
     * 动作执行后卡片的目标状态。
     *
     * @param currentStatus  当前状态
     * @param action         动作
     * @param preAppealStatus 仅 {@code uphold} 用到：申诉前的原状态（维持原处置 = 回到它）
     */
    public static String targetCardStatus(String currentStatus, String action, String preAppealStatus) {
        return switch (action) {
            case Action.APPROVE -> CardStatus.PUBLIC;
            case Action.REJECT -> CardStatus.REJECTED;
            case Action.TAKEDOWN -> CardStatus.TAKENDOWN;
            // 升级复核：只标记进主管队列，状态不变
            case Action.ESCALATE -> currentStatus;
            // 维持原处置：回到申诉前的那个状态（rejected 或 takendown）
            case Action.UPHOLD -> preAppealStatus;
            // 🔴 撤销原处置只回 pending，重走人工
            case Action.OVERTURN -> CardStatus.PENDING;
            default -> throw new IllegalArgumentException("unknown action: " + action);
        };
    }

    /** 动作执行后审核工单的目标状态。 */
    public static String targetModerationState(String currentStatus, String action, String preAppealStatus) {
        return switch (action) {
            case Action.APPROVE -> ModerationState.APPROVED;
            case Action.REJECT -> ModerationState.REJECTED;
            case Action.TAKEDOWN -> ModerationState.TAKENDOWN;
            // 升级复核不改工单状态（仍在队列里，只是进主管的升级 tab）
            case Action.ESCALATE -> currentStatus.equals(CardStatus.BLOCKED)
                    ? ModerationState.BLOCKED : ModerationState.PENDING;
            case Action.UPHOLD -> CardStatus.TAKENDOWN.equals(preAppealStatus)
                    ? ModerationState.TAKENDOWN : ModerationState.REJECTED;
            case Action.OVERTURN -> ModerationState.PENDING;
            default -> throw new IllegalArgumentException("unknown action: " + action);
        };
    }

    /** 🔴 仅 approve 写 reviewedAt（且落库层只在 reviewedAt IS NULL 时才真的写）。 */
    public static boolean writesReviewedAt(String action) {
        return Action.APPROVE.equals(action);
    }

    /** {@code t_audit_log.action} 取值（§17.5）。 */
    public static String auditAction(String action) {
        return switch (action) {
            case Action.APPROVE -> AuditAction.APPROVE;
            case Action.REJECT -> AuditAction.REJECT;
            case Action.TAKEDOWN -> AuditAction.TAKEDOWN;
            case Action.ESCALATE -> AuditAction.ESCALATE;
            case Action.UPHOLD -> AuditAction.APPEAL_UPHOLD;
            case Action.OVERTURN -> AuditAction.APPEAL_OVERTURN;
            default -> throw new IllegalArgumentException("unknown action: " + action);
        };
    }

    /** 运营四动作之一。 */
    public static boolean isModeratorAction(String action) {
        return Action.APPROVE.equals(action) || Action.REJECT.equals(action)
                || Action.TAKEDOWN.equals(action) || Action.ESCALATE.equals(action);
    }

    /** 申诉处置两动作之一。 */
    public static boolean isAppealAction(String action) {
        return Action.UPHOLD.equals(action) || Action.OVERTURN.equals(action);
    }
}
