package com.echo.http.store;

import com.aengine.util.id.IDGenerator;
import com.echo.http.card.CardVisibility;
import com.echo.http.card.PinPolicy;
import com.echo.http.model.ModerationModels.AuditLog;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.CardVisibilityLog;
import com.echo.http.model.ModerationModels.HandleCommand;
import com.echo.http.model.ModerationModels.HandleResult;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.ModerationSetting;
import com.echo.http.model.ModerationModels.ModerationTicket;
import com.echo.http.model.ModerationModels.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存态审核存储：无 DB 也能跑通审核闭环，单测无需起库。
 *
 * <p>"同一事务"在内存态用一把对象锁近似：{@link #handleAtomically} 与 {@link #appealAtomically}
 * 整体加锁，中途任何校验不过就在改动<em>之前</em>返回，不留半成品。行为与 PG 实现一致。</p>
 */
public final class InMemoryModerationStore implements ModerationStore {

    private final IDGenerator idGenerator;
    private final Object lock = new Object();

    private final Map<Long, MemoryCard> cards = new LinkedHashMap<>();
    private final Map<Long, ModerationTicket> tickets = new LinkedHashMap<>();
    private final Map<Long, Report> reports = new LinkedHashMap<>();
    private final List<CardVisibilityLog> visibilityLogs = new ArrayList<>();
    private final List<AuditLog> auditLogs = new ArrayList<>();
    private final ModerationSetting setting = new ModerationSetting();

    public InMemoryModerationStore(IDGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public MemoryCard card(long cardId) {
        synchronized (lock) {
            return cards.get(cardId);
        }
    }

    @Override
    public ModerationTicket ticket(long moderationId) {
        synchronized (lock) {
            return tickets.get(moderationId);
        }
    }

    @Override
    public ModerationTicket ticketOfCard(long cardId) {
        synchronized (lock) {
            return tickets.values().stream()
                    .filter(t -> t.cardId == cardId)
                    .max(Comparator.comparingLong(t -> t.createdAt))
                    .orElse(null);
        }
    }

    @Override
    public List<ModerationTicket> queue(String tab, String risk, long cursor, int limit) {
        synchronized (lock) {
            List<ModerationTicket> out = new ArrayList<>();
            List<ModerationTicket> sorted = new ArrayList<>(tickets.values());
            // 待处理 + 高风险优先（§4.3 默认排序）
            sorted.sort(Comparator
                    .comparingInt((ModerationTicket t) -> riskRank(t.autoRiskLevel))
                    .thenComparingLong(t -> t.createdAt));
            for (ModerationTicket t : sorted) {
                if (!matchesTab(t, tab) || !matchesRisk(t, risk)) {
                    continue;
                }
                // 🔴 软删卡根本不应出现在队列里（§2.2.1 最后一行）
                MemoryCard c = cards.get(t.cardId);
                if (c == null || c.deletedAt != null || CardStatus.DELETED.equals(c.status)) {
                    continue;
                }
                if (cursor > 0 && t.id <= cursor) {
                    continue;
                }
                out.add(t);
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    private static int riskRank(String risk) {
        return switch (risk == null ? "" : risk) {
            case "high" -> 0;
            case "mid" -> 1;
            default -> 2;
        };
    }

    private static boolean matchesTab(ModerationTicket t, String tab) {
        if (tab == null || tab.isBlank()) {
            return true;
        }
        return switch (tab) {
            case "pending" -> "pending".equals(t.state);
            case "blocked" -> "blocked".equals(t.state);
            case "appealing" -> "appealing".equals(t.state);
            case "handled" -> t.handledAt != null;
            default -> true;
        };
    }

    private static boolean matchesRisk(ModerationTicket t, String risk) {
        return risk == null || risk.isBlank() || risk.equals(t.autoRiskLevel);
    }

    @Override
    public List<CardVisibilityLog> historyOfCard(long cardId) {
        synchronized (lock) {
            return visibilityLogs.stream()
                    .filter(l -> l.cardId == cardId)
                    .sorted(Comparator.comparingLong(l -> l.changedAt))
                    .toList();
        }
    }

    @Override
    public List<MemoryCard> publicCards(int limit) {
        synchronized (lock) {
            return cards.values().stream()
                    .filter(c -> c.deletedAt == null && CardStatus.PUBLIC.equals(c.status))
                    // publishedAt DESC，null 垫底；id 倒序兜底保证顺序稳定（分页不重不漏）
                    .sorted(Comparator
                            .comparingLong((MemoryCard c) ->
                                    c.publishedAt == null ? Long.MIN_VALUE : c.publishedAt)
                            .reversed()
                            .thenComparing(Comparator.comparingLong((MemoryCard c) -> c.id)
                                    .reversed()))
                    .limit(Math.max(0, limit))
                    .toList();
        }
    }

    @Override
    public List<MemoryCard> cardsOfOwner(long ownerId, int limit) {
        synchronized (lock) {
            return cards.values().stream()
                    .filter(c -> c.ownerId == ownerId && c.deletedAt == null)
                    // 🔴 与 SQL 侧 ORDER BY "pinnedAt" DESC NULLS LAST, "publishedAt" DESC 同一口径
                    .sorted(PinPolicy.ORDER)
                    .limit(Math.max(0, limit))
                    .toList();
        }
    }

    @Override
    public int countPinned(long ownerId) {
        synchronized (lock) {
            return (int) cards.values().stream()
                    .filter(c -> c.ownerId == ownerId && c.deletedAt == null && c.pinnedAt != null)
                    .count();
        }
    }

    @Override
    public List<Report> reports(String status, long cardId, long cursor, int limit) {
        synchronized (lock) {
            List<Report> out = new ArrayList<>();
            List<Report> sorted = new ArrayList<>(reports.values());
            sorted.sort(Comparator.comparingLong(r -> r.createdAt));
            for (Report r : sorted) {
                if (status != null && !status.isBlank() && !status.equals(r.status)) {
                    continue;
                }
                if (cardId > 0 && r.cardId != cardId) {
                    continue;
                }
                if (cursor > 0 && r.id <= cursor) {
                    continue;
                }
                out.add(r);
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    @Override
    public ModerationSetting setting() {
        synchronized (lock) {
            return setting;
        }
    }

    @Override
    public HandleResult handleAtomically(HandleCommand cmd) {
        synchronized (lock) {
            MemoryCard c = cards.get(cmd.cardId);
            ModerationTicket t = tickets.get(cmd.moderationId);
            if (c == null || t == null) {
                return null;
            }
            // CAS：当前状态必须仍是期望值，否则整体不生效（另一个审核员抢先处置了）
            if (!Objects.equals(c.status, cmd.expectedCardStatus)) {
                return null;
            }

            String fromStatus = c.status;

            // 🔴 只改这几个字段。originType / assistedByOps 一律不碰（MOD1 ②）
            c.status = cmd.toCardStatus;
            c.visibilityIntent = cmd.toVisibility;
            c.updatedAt = cmd.now;
            // 🔴 只在 reviewedAt IS NULL 时写：一张卡一生只有一个 7 天窗口
            if (cmd.writeReviewedAt && c.reviewedAt == null) {
                c.reviewedAt = cmd.now;
            }
            // 🔴 自动解除置顶：审核打回 / 下架 / 转非公开之后不许留悬空 pinnedAt，
            //    否则卡重新过审时会自己跳回置顶位，而作者并没有再做这个决定。
            if (c.pinnedAt != null && !(CardStatus.PUBLIC.equals(c.status)
                    && CardVisibility.PUBLIC.equals(c.visibilityIntent))) {
                c.pinnedAt = null;
            }

            t.state = cmd.toModerationState;
            t.handledAt = cmd.now;
            if (cmd.appealResult != null) {
                t.appealHandledBy = cmd.operatorId;
                t.appealHandledAt = cmd.now;
                t.appealResult = cmd.appealResult;
                // 🔴 appealAt 一动不动：overturn 不等于退还申诉机会
            } else {
                t.handledBy = cmd.operatorId;
            }
            if (cmd.reasonCode != null) {
                t.reasonCode = cmd.reasonCode;
            }
            if (cmd.note != null) {
                t.note = cmd.note;
            }
            if (cmd.snapshotJson != null) {
                t.snapshotJson = cmd.snapshotJson;
            }

            appendLogs(cmd, fromStatus);
            return result(c, t);
        }
    }

    @Override
    public HandleResult appealAtomically(HandleCommand cmd, String appealText) {
        synchronized (lock) {
            MemoryCard c = cards.get(cmd.cardId);
            ModerationTicket t = tickets.get(cmd.moderationId);
            if (c == null || t == null) {
                return null;
            }
            if (!Objects.equals(c.status, cmd.expectedCardStatus)) {
                return null;
            }
            // 🔴 唯一判据：appealAt 非空 = 机会已用掉（不设计数列）
            if (t.appealAt != null) {
                return null;
            }

            String fromStatus = c.status;
            c.status = cmd.toCardStatus;
            c.updatedAt = cmd.now;

            t.appealAt = cmd.now;
            t.appealText = appealText;
            t.state = cmd.toModerationState;

            appendLogs(cmd, fromStatus);
            return result(c, t);
        }
    }

    /** 双流水（§1.8.5 + §15.4）：与状态变更在同一临界区内追加，不允许只落一半。 */
    private void appendLogs(HandleCommand cmd, String fromStatus) {
        CardVisibilityLog vl = new CardVisibilityLog();
        vl.id = idGenerator.nextId();
        vl.cardId = cmd.cardId;
        vl.fromVisibility = cmd.fromVisibility;
        vl.toVisibility = cmd.toVisibility;
        vl.fromStatus = fromStatus;
        vl.toStatus = cmd.toCardStatus;
        vl.changedBy = cmd.operatorId;
        vl.changedRole = cmd.changedRole;
        vl.reasonCode = cmd.reasonCode;
        vl.changedAt = cmd.now;
        visibilityLogs.add(vl);

        AuditLog al = new AuditLog();
        al.id = idGenerator.nextId();
        al.actor = String.valueOf(cmd.operatorId);
        al.actorType = cmd.auditActorType;
        al.action = cmd.auditAction;
        al.targetType = "card";
        al.targetId = String.valueOf(cmd.cardId);
        al.scopeJson = cmd.auditScopeJson;
        al.ts = cmd.now;
        auditLogs.add(al);
    }

    private static HandleResult result(MemoryCard c, ModerationTicket t) {
        HandleResult r = new HandleResult();
        r.moderationId = t.id;
        r.cardId = c.id;
        r.state = t.state;
        r.cardStatus = c.status;
        r.reviewedAt = c.reviewedAt;
        r.handledAt = t.handledAt != null ? t.handledAt : c.updatedAt;
        return r;
    }

    @Override
    public ModerationSetting updateSetting(String mode, String scopeJson, long operatorId, long now) {
        synchronized (lock) {
            setting.mode = mode;
            setting.scopeJson = scopeJson;
            setting.updatedBy = operatorId;
            setting.updatedAt = now;

            AuditLog al = new AuditLog();
            al.id = idGenerator.nextId();
            al.actor = String.valueOf(operatorId);
            al.actorType = "staff";
            al.action = com.echo.http.model.ModerationModels.AuditAction.SETTINGS_UPDATE;
            al.targetType = "config";
            al.targetId = "moderation.settings";
            al.scopeJson = "{\"mode\":\"" + mode + "\"}";
            al.ts = now;
            auditLogs.add(al);
            return setting;
        }
    }

    @Override
    public void putCard(MemoryCard card) {
        synchronized (lock) {
            cards.put(card.id, card);
        }
    }

    @Override
    public boolean updateInteraction(long cardId, String interactionJson, long now) {
        synchronized (lock) {
            MemoryCard card = cards.get(cardId);
            if (card == null || card.deletedAt != null) {
                return false;
            }
            // 只动这两个字段，与 PG 实现的窄 UPDATE 对齐
            card.interactionJson = interactionJson;
            card.updatedAt = now;
            return true;
        }
    }

    @Override
    public boolean changeVisibilityAtomically(long cardId, long ownerId, String expectedVisibility,
                                              String toVisibility, boolean clearPin, long now) {
        synchronized (lock) {
            MemoryCard c = cards.get(cardId);
            if (c == null || c.deletedAt != null || c.ownerId != ownerId) {
                return false;
            }
            if (!Objects.equals(c.visibilityIntent, expectedVisibility)) {
                return false;   // CAS 未命中：另一个请求抢先改过
            }
            String from = c.visibilityIntent;
            c.visibilityIntent = toVisibility;
            c.updatedAt = now;
            if (clearPin) {
                // 🔴 与可见性同一临界区内清空，不留「已经不公开、但还置顶着」的中间态
                c.pinnedAt = null;
            }

            CardVisibilityLog vl = new CardVisibilityLog();
            vl.id = idGenerator.nextId();
            vl.cardId = cardId;
            vl.fromVisibility = from;
            vl.toVisibility = toVisibility;
            // 作者改可见性不改状态，两端同值（流水靠这个区分「作者收回」与「运营下架」）
            vl.fromStatus = c.status;
            vl.toStatus = c.status;
            vl.changedBy = ownerId;
            vl.changedRole = "author";
            vl.reasonCode = null;
            vl.changedAt = now;
            visibilityLogs.add(vl);
            return true;
        }
    }

    @Override
    public PinOutcome setPinnedAtomically(long cardId, long ownerId, boolean pin,
                                          int maxPinned, long now) {
        synchronized (lock) {
            MemoryCard c = cards.get(cardId);
            if (c == null || c.deletedAt != null || c.ownerId != ownerId) {
                return PinOutcome.NOT_FOUND;
            }
            if (!pin) {
                c.pinnedAt = null;
                c.updatedAt = now;
                return PinOutcome.OK;
            }
            // 🔴 仅 public 且已过审可置顶。reviewedAt 非空还不够——下架的卡它也非空
            if (!CardStatus.PUBLIC.equals(c.status) || c.reviewedAt == null
                    || !CardVisibility.PUBLIC.equals(c.visibilityIntent)) {
                return PinOutcome.NOT_FOUND;
            }
            if (c.pinnedAt != null) {
                return PinOutcome.OK;   // 幂等：已置顶再置一次不占额度
            }
            // 🔴 计数与写入在同一临界区，否则并发两次置顶都能通过上限校验
            long pinned = cards.values().stream()
                    .filter(x -> x.ownerId == ownerId && x.deletedAt == null && x.pinnedAt != null)
                    .count();
            if (pinned >= maxPinned) {
                return PinOutcome.AT_CAPACITY;
            }
            c.pinnedAt = now;
            c.updatedAt = now;
            return PinOutcome.OK;
        }
    }

    @Override
    public void putTicket(ModerationTicket ticket) {
        synchronized (lock) {
            tickets.put(ticket.id, ticket);
        }
    }

    @Override
    public void putReport(Report report) {
        synchronized (lock) {
            reports.put(report.id, report);
        }
    }

    @Override
    public List<AuditLog> auditLogs(String targetType, String targetId) {
        synchronized (lock) {
            return auditLogs.stream()
                    .filter(a -> targetType == null || targetType.equals(a.targetType))
                    .filter(a -> targetId == null || targetId.equals(a.targetId))
                    .toList();
        }
    }
}
