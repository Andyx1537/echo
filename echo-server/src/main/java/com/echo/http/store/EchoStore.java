package com.echo.http.store;

import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.FlowerLog;
import com.echo.http.model.Models.MessageEntry;
import com.echo.http.model.Models.Onboarding;
import com.echo.http.model.Models.PetEcho;
import com.echo.http.model.Models.ReactionMark;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.model.Models.Postcard;
import com.echo.http.model.Models.RecordEntry;
import com.echo.http.model.Models.RelationEntry;
import com.echo.http.model.Models.ShadowArea;
import com.echo.http.model.Models.SpectrumNode;

import java.util.List;

/**
 * HTTP 网关新增业务域的存储抽象（六项定案的服务端状态载体）。
 *
 * <p>两套实现：</p>
 * <ul>
 *   <li>{@link InMemoryEchoStore} —— 无 DB 内存态，保证"无库也能跑通核心闭环"、单测无需起库；</li>
 *   <li>{@link PgEchoStore} —— PostgreSQL 落库（{@code echo.db.enabled=true} 时装配），
 *       持久化耐久域（往宠/近况/献花/记得/明信片/记录/消息/亲友/账号概要）。</li>
 * </ul>
 *
 * <p><b>持久化语义约定</b>：调用方（{@code EchoApi}）在<em>修改领域对象字段后必须调用对应的
 * put/update 方法回写</em>（如 {@code putPet}/{@code updateRelation}/{@code updateMessage}）。
 * 内存实现依赖对象引用、回写为幂等空操作；PG 实现据此 UPSERT 落库。两套实现行为一致。</p>
 *
 * <p>与 {@code src/main/resources/sql/schema.sql} 中的新域表一一对应，字段形状见
 * {@link com.echo.http.model.Models}。</p>
 */
public interface EchoStore {

    // ------------------------------------------------------------- token/account
    /** 绑定会话 token → accountId（会话态，不落库）。 */
    void bindToken(String token, long accountId);

    /** @return token 对应的 accountId，无效返回 null。 */
    Long resolveToken(String token);

    /** 按设备指纹取账号（游客幂等；PG 实现查 t_account_profile.deviceId）。 */
    Long accountByDevice(String deviceId);

    /** UPSERT 账号 HTTP 概要（t_account_profile）。 */
    void putProfile(AccountProfile profile);

    AccountProfile profile(long accountId);

    // ------------------------------------------------------------- pet
    /** UPSERT 往宠档案（t_pet）；修改温度/可见性/收花计数等字段后须调用回写。 */
    void putPet(PetProfile pet);

    PetProfile petOfOwner(long accountId);

    PetProfile petById(String petId);

    /** 所有宠物窗口（广场用）。 */
    List<PetProfile> allPets();

    /** dev-only（mi-2 resetPet）：删除某宠物（含 owner 映射），回到未建档态。 */
    void deletePet(String petId, long ownerAccountId);

    // ------------------------------------------------------------- echoes
    /** UPSERT 一条近况/来信（t_pet_echo）；同 echoId 视为更新（回复回写）。 */
    void addEcho(PetEcho echo);

    List<PetEcho> echoesOfPet(String petId);

    PetEcho echoById(String echoId);

    // ------------------------------------------------------------- flowers
    /** 记录一次献花（t_flower_log）；额度校验在 service 层 {@link #flowerLock()} 同步块内配合完成。 */
    void addFlowerLog(FlowerLog log);

    /** 某账号在某自然日已用的免费额度朵数（type=daily）。 */
    int flowersUsedToday(long accountId, int day);

    /** owner 视角：某人给它献过的总花数（私密羁绊名单；不排名，仅供 owner 内部）。 */
    int flowersFromTo(long fromAccountId, long ownerAccountId);

    /** 献花额度校验+写入的进程内互斥锁对象（单实例 MVP 足够；多实例需 DB 级约束，为 TODO）。 */
    Object flowerLock();

    // ------------------------------------------------------------- remember
    /** 置/取消记得（幂等状态开关，t_remember 唯一约束）。@return 是否本次发生变化。 */
    boolean setRemember(String petId, long accountId, boolean remembered);

    boolean isRemembered(String petId, long accountId);

    /** 记得它的账号列表（面孔墙用；精确总数不对外，仅 owner insights 内部可读长度）。 */
    List<Long> rememberFaces(String petId);

    // ------------------------------------------------------------- 被接住（B20）
    /**
     * 别人对<b>我的</b>窗做出的回应，时间倒序（{@code B20} 到达的源数据）。
     *
     * <p>把献花与记得归一成一种形状。🔴 <b>排除自己对自己窗的回应</b>——
     * 「被接住」说的是别人接住了你，自己给自己献一束花不是那件事。</p>
     */
    List<ReactionMark> reactionsReceived(long ownerAccountId, int limit);

    /**
     * 把某扇窗上截至 {@code upToCreatedAt} 的回应记为「已看过」。
     *
     * <p>🔴 <b>存的是一条水位线，不是每行一个已读位。</b>「看过即散」（{@code B23}）散的是整张卡的暖点，
     * 从来不存在「读了一半」这种状态；水位是这件事最小的表示，也天然<b>单调不回退</b>——
     * 逐行已读位则允许被改回未读，等于给红点轰炸留了一条路。</p>
     */
    void markReactionsSeen(long ownerAccountId, String windowId, long upToCreatedAt);

    /** 某扇窗的回应「已看到哪一刻」；从没看过返回 0。 */
    long reactionsSeenAt(long ownerAccountId, String windowId);

    // ------------------------------------------------------------- postcards
    /** 覆盖写某宠物的明信片列表（t_postcard）。 */
    void putPostcards(String petId, List<Postcard> cards);

    List<Postcard> postcards(String petId);

    Postcard postcard(String petId, String id);

    // ------------------------------------------------------------- records
    void addRecord(RecordEntry r);

    List<RecordEntry> records(long accountId);

    // ------------------------------------------------------------- messages
    void addMessage(MessageEntry m);

    List<MessageEntry> messages(long accountId);

    /** 回写单条消息（如已读状态）；内存态为空操作，PG 落库据此 UPDATE。 */
    void updateMessage(MessageEntry m);

    // ------------------------------------------------------------- relations
    void addRelation(RelationEntry r);

    List<RelationEntry> relations(long accountId);

    RelationEntry relation(long accountId, String relationId);

    /** 回写单条亲友关系（如优先级/静音/reel 已看）；内存态为空操作，PG 落库据此 UPDATE。 */
    void updateRelation(RelationEntry r);

    // ------------------------------------------------------------- spectrum
    /** 光谱暖光节点（无独立表，进程内维护；返回可变列表，调用方可直接增补）。 */
    List<SpectrumNode> spectrumNodes(long accountId);

    /** 光谱暗面（无独立表，进程内维护；返回可变列表，调用方可直接增补）。 */
    List<ShadowArea> spectrumShadows(long accountId);

    // ------------------------------------------------------------- onboarding
    /** 建档流程态（短生命周期，进程内维护，不落库）。 */
    void putOnboarding(Onboarding o);

    Onboarding onboarding(String id);
}
