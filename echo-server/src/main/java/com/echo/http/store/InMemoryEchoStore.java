package com.echo.http.store;

import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.FlowerLog;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EchoStore} 的内存态实现（六项定案的服务端状态载体）。
 *
 * <p><b>关键技术选择</b>：新域（往宠档案/献花/记得/明信片/记录/消息/亲友/光谱）采用<em>内存实现</em>，
 * 使 HTTP 网关在<b>无 DB</b> 时也能完整跑通核心闭环（符合任务"无 DB 退化启动、返回内存实现保证前端联调"），
 * 且便于单测（外部服务全 mock、无需起库）。接 DB 时改用 {@link PgEchoStore} 落库，装配见
 * {@code EchoHttpBootstrap}。</p>
 *
 * <p>线程安全：全部用 {@link ConcurrentHashMap} + 同步块保护复合操作（如献花额度校验+写入），
 * 满足 HttpServer 多线程 dispatch 下的一致性。</p>
 */
public class InMemoryEchoStore implements EchoStore {

    private final Map<String, Long> tokenToAccount = new ConcurrentHashMap<>();
    private final Map<String, Long> deviceToAccount = new ConcurrentHashMap<>();
    private final Map<Long, AccountProfile> profiles = new ConcurrentHashMap<>();

    private final Map<Long, PetProfile> petByOwner = new ConcurrentHashMap<>();
    private final Map<String, PetProfile> petById = new ConcurrentHashMap<>();

    private final Map<String, List<PetEcho>> echoesByPet = new ConcurrentHashMap<>();
    private final Map<String, PetEcho> echoById = new ConcurrentHashMap<>();

    private final List<FlowerLog> flowerLogs = new ArrayList<>();
    /**
     * petId -> (记得它的 accountId -> 置为记得的时刻)。状态开关，不可叠加计数（定案 #4）。
     *
     * <p>装时刻是为了让「被接住」能把记得与献花排到同一条时间线上（{@code t_remember.createdAt}
     * 本来就有这一列，内存态此前没跟上）。🔴 <b>时刻不外露</b>：面孔墙只用它定顺序。</p>
     */
    private final Map<String, Map<Long, Long>> rememberByPet = new ConcurrentHashMap<>();

    /** ownerAccountId -> (windowId -> 回应已看到哪一刻)。水位线，单调不回退。 */
    private final Map<Long, Map<String, Long>> reactionSeen = new ConcurrentHashMap<>();

    private final Map<String, List<Postcard>> postcardsByPet = new ConcurrentHashMap<>();
    private final Map<Long, List<RecordEntry>> recordsByAccount = new ConcurrentHashMap<>();
    private final Map<Long, List<MessageEntry>> messagesByAccount = new ConcurrentHashMap<>();
    private final Map<Long, List<RelationEntry>> relationsByAccount = new ConcurrentHashMap<>();

    private final Map<Long, List<SpectrumNode>> spectrumNodes = new ConcurrentHashMap<>();
    private final Map<Long, List<ShadowArea>> spectrumShadows = new ConcurrentHashMap<>();

    private final Map<String, Onboarding> onboardings = new ConcurrentHashMap<>();

    // ------------------------------------------------------------- token/account
    @Override
    public void bindToken(String token, long accountId) {
        tokenToAccount.put(token, accountId);
    }

    @Override
    public Long resolveToken(String token) {
        return token == null ? null : tokenToAccount.get(token);
    }

    @Override
    public Long accountByDevice(String deviceId) {
        return deviceId == null ? null : deviceToAccount.get(deviceId);
    }

    @Override
    public void putProfile(AccountProfile profile) {
        profiles.put(profile.accountId, profile);
        if (profile.deviceId != null) {
            deviceToAccount.put(profile.deviceId, profile.accountId);
        }
    }

    @Override
    public AccountProfile profile(long accountId) {
        return profiles.get(accountId);
    }

    // ------------------------------------------------------------- pet
    @Override
    public void putPet(PetProfile pet) {
        petByOwner.put(pet.ownerAccountId, pet);
        petById.put(pet.petId, pet);
    }

    @Override
    public PetProfile petOfOwner(long accountId) {
        return petByOwner.get(accountId);
    }

    @Override
    public PetProfile petById(String petId) {
        return petById.get(petId);
    }

    @Override
    public List<PetProfile> allPets() {
        return new ArrayList<>(petById.values());
    }

    @Override
    public void deletePet(String petId, long ownerAccountId) {
        petById.remove(petId);
        petByOwner.remove(ownerAccountId);
        List<PetEcho> echoes = echoesByPet.remove(petId);
        if (echoes != null) {
            for (PetEcho e : echoes) {
                echoById.remove(e.echoId);
            }
        }
        postcardsByPet.remove(petId);
        rememberByPet.remove(petId);
    }

    // ------------------------------------------------------------- echoes
    @Override
    public void addEcho(PetEcho echo) {
        // 幂等：同 echoId 视为更新（回复回写场景），避免列表重复
        PetEcho prev = echoById.put(echo.echoId, echo);
        List<PetEcho> list = echoesByPet.computeIfAbsent(echo.petId, k -> new ArrayList<>());
        if (prev != null) {
            list.remove(prev);
        }
        list.add(echo);
    }

    @Override
    public List<PetEcho> echoesOfPet(String petId) {
        return new ArrayList<>(echoesByPet.getOrDefault(petId, List.of()));
    }

    @Override
    public PetEcho echoById(String echoId) {
        return echoById.get(echoId);
    }

    // ------------------------------------------------------------- flowers
    @Override
    public synchronized void addFlowerLog(FlowerLog log) {
        flowerLogs.add(log);
    }

    @Override
    public synchronized int flowersUsedToday(long accountId, int day) {
        int used = 0;
        for (FlowerLog l : flowerLogs) {
            if (l.fromAccountId == accountId && l.day == day && "daily".equals(l.type)) {
                used += l.count;
            }
        }
        return used;
    }

    @Override
    public synchronized int flowersFromTo(long fromAccountId, long ownerAccountId) {
        int total = 0;
        for (FlowerLog l : flowerLogs) {
            if (l.fromAccountId == fromAccountId && l.toOwnerAccountId == ownerAccountId) {
                total += l.count;
            }
        }
        return total;
    }

    @Override
    public Object flowerLock() {
        return this;
    }

    // ------------------------------------------------------------- remember
    @Override
    public boolean setRemember(String petId, long accountId, boolean remembered) {
        Map<Long, Long> marks = rememberByPet.computeIfAbsent(petId, k -> new LinkedHashMap<>());
        synchronized (marks) {
            if (remembered) {
                // putIfAbsent 而非 put：重复置"记得"不刷新时刻，否则同一次记得会在到达里反复浮上来
                return marks.putIfAbsent(accountId, System.currentTimeMillis()) == null;
            }
            return marks.remove(accountId) != null;
        }
    }

    @Override
    public boolean isRemembered(String petId, long accountId) {
        Map<Long, Long> marks = rememberByPet.get(petId);
        if (marks == null) {
            return false;
        }
        synchronized (marks) {
            return marks.containsKey(accountId);
        }
    }

    @Override
    public List<Long> rememberFaces(String petId) {
        Map<Long, Long> marks = rememberByPet.get(petId);
        if (marks == null) {
            return List.of();
        }
        synchronized (marks) {
            return new ArrayList<>(marks.keySet());
        }
    }

    // ------------------------------------------------------------- 被接住（B20）
    @Override
    public List<ReactionMark> reactionsReceived(long ownerAccountId, int limit) {
        List<ReactionMark> out = new ArrayList<>();
        synchronized (this) {
            for (FlowerLog l : flowerLogs) {
                if (l.toOwnerAccountId != ownerAccountId || l.fromAccountId == ownerAccountId) {
                    continue;
                }
                out.add(mark("flower:" + l.id, "flower", l.windowId, l.fromAccountId, l.createdAt));
            }
        }
        for (Map.Entry<String, Map<Long, Long>> e : rememberByPet.entrySet()) {
            String petId = e.getKey();
            PetProfile pet = petById.get(petId);
            if (pet == null || pet.ownerAccountId != ownerAccountId) {
                continue;
            }
            Map<Long, Long> marks = e.getValue();
            synchronized (marks) {
                for (Map.Entry<Long, Long> m : marks.entrySet()) {
                    long actor = m.getKey();
                    if (actor == ownerAccountId) {
                        continue;
                    }
                    out.add(mark("remember:" + petId + ":" + actor, "remember", petId, actor, m.getValue()));
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    @Override
    public void markReactionsSeen(long ownerAccountId, String windowId, long upToCreatedAt) {
        reactionSeen
                .computeIfAbsent(ownerAccountId, k -> new ConcurrentHashMap<>())
                .merge(windowId, upToCreatedAt, Math::max);
    }

    @Override
    public long reactionsSeenAt(long ownerAccountId, String windowId) {
        Map<String, Long> byWindow = reactionSeen.get(ownerAccountId);
        return byWindow == null ? 0L : byWindow.getOrDefault(windowId, 0L);
    }

    private static ReactionMark mark(String id, String kind, String windowId, long actor, long createdAt) {
        ReactionMark r = new ReactionMark();
        r.id = id;
        r.kind = kind;
        r.windowId = windowId;
        r.actorAccountId = actor;
        r.createdAt = createdAt;
        return r;
    }

    // ------------------------------------------------------------- postcards
    @Override
    public void putPostcards(String petId, List<Postcard> cards) {
        postcardsByPet.put(petId, new ArrayList<>(cards));
    }

    /**
     * 明信片墙，<b>按 {@code createdAt} 升序</b>——最早的一张在最上面。
     *
     * <p>🔴 <b>升序是产品有意为之，不是与 records/messages 不一致的疏漏。</b>
     * 明信片墙是一条时间线叙事，要从头看到尾才成立；{@code /records}、{@code /messages}
     * 是「最新的先看」，方向相反是<b>两种东西本来就不一样</b>，
     * 🔴 <b>不要"顺手统一"把它改成降序。</b></p>
     *
     * <p>同毫秒用 {@code id} 兜底，凑出一个全序：两条同刻明信片的先后不能随
     * {@code HashMap} 的插入顺序变。{@code PgEchoStore} 那边是同一个 {@code ORDER BY}，
     * 两端必须给出同一页——它们是同一个接口的两个实现，客户端看不出自己连的是哪个。</p>
     */
    @Override
    public List<Postcard> postcards(String petId) {
        List<Postcard> all = postcardsByPet.get(petId);
        if (all == null) {
            return new ArrayList<>();
        }
        List<Postcard> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator
                .comparingLong((Postcard c) -> c.createdAt)
                .thenComparing(c -> c.id, java.util.Comparator.nullsLast(String::compareTo)));
        return sorted;
    }

    @Override
    public Postcard postcard(String petId, String id) {
        for (Postcard c : postcards(petId)) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- records
    @Override
    public void addRecord(RecordEntry r) {
        recordsByAccount.computeIfAbsent(r.accountId, k -> new ArrayList<>()).add(r);
    }

    @Override
    public List<RecordEntry> records(long accountId) {
        return new ArrayList<>(recordsByAccount.getOrDefault(accountId, List.of()));
    }

    // ------------------------------------------------------------- messages
    @Override
    public void addMessage(MessageEntry m) {
        messagesByAccount.computeIfAbsent(m.accountId, k -> new ArrayList<>()).add(m);
    }

    @Override
    public List<MessageEntry> messages(long accountId) {
        return new ArrayList<>(messagesByAccount.getOrDefault(accountId, List.of()));
    }

    @Override
    public void updateMessage(MessageEntry m) {
        // 内存态：messages() 返回的是同一批对象引用，就地修改已生效；此处为契约对齐的空实现。
    }

    // ------------------------------------------------------------- relations
    @Override
    public void addRelation(RelationEntry r) {
        relationsByAccount.computeIfAbsent(r.accountId, k -> new ArrayList<>()).add(r);
    }

    @Override
    public List<RelationEntry> relations(long accountId) {
        return new ArrayList<>(relationsByAccount.getOrDefault(accountId, List.of()));
    }

    @Override
    public RelationEntry relation(long accountId, String relationId) {
        for (RelationEntry r : relations(accountId)) {
            if (r.id.equals(relationId)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public void updateRelation(RelationEntry r) {
        // 内存态：relation() 返回的是列表内同一对象引用，就地修改已生效；此处为契约对齐的空实现。
    }

    // ------------------------------------------------------------- spectrum
    @Override
    public List<SpectrumNode> spectrumNodes(long accountId) {
        return spectrumNodes.computeIfAbsent(accountId, k -> new ArrayList<>());
    }

    @Override
    public List<ShadowArea> spectrumShadows(long accountId) {
        return spectrumShadows.computeIfAbsent(accountId, k -> new ArrayList<>());
    }

    // ------------------------------------------------------------- onboarding
    @Override
    public void putOnboarding(Onboarding o) {
        onboardings.put(o.onboardingId, o);
    }

    @Override
    public Onboarding onboarding(String id) {
        return onboardings.get(id);
    }
}
