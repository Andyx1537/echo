package com.echo.http.ranking;

import java.util.List;
import java.util.Set;

/**
 * 「独立互动者」的口径：<b>哪些回声类型算一次互动</b>（制作人裁定）。
 *
 * <p>这个集合喂两处，两处必须共用同一个口径：</p>
 *
 * <ul>
 *   <li>{@code SURGE} 的 {@code base}（近 14 天日均）与 {@code surge}（近 24h）</li>
 *   <li>{@code S4 自然流掉} 的「近 14 天独立互动者数 = 0」判定</li>
 * </ul>
 *
 * <h2>🔴 关注（R6 / R7）不计入</h2>
 *
 * <p>关注是<b>对作者或题材</b>的持续订阅，不是对<b>这一张卡</b>的表达。若计入，
 * 一个关注者就能让一张卡永远不满足 S4 的「近 14 天独立互动者 = 0」，于是<b>再也流不掉</b>——
 * 而「零响应内容满一周退出主动分发」正是被裁定要保住的那条。</p>
 *
 * <p>这一条与 schema 里 {@code t_resonance_type.countsToAcceptance} 的取值一致
 * （R1–R5 为 {@code true}，R6/R7 为 {@code false}），schema 原注释写的是
 * 「R6/R7 是「持续关注」，不是「对某一条发布的表达」→ 不计入被接住」。</p>
 *
 * <h2>🔴 唯一真源在数据库，不在这里</h2>
 *
 * <p>{@code t_resonance_type.countsToAcceptance} 那一列被数据库触发器保护着，
 * 改不动、删不掉、动了会抛异常——因为它直接决定北极星分子。
 * 本类的常量是<b>那一列的镜像</b>，不是第二份真源。</p>
 *
 * <p>🔴 <b>接上 DB 之后，读表而不是读这里。</b>两份清单一定会分叉，
 * 而分叉的那天不会有人发现：北极星和 SURGE 会开始用不同的口径数同一批人，
 * 两个数都还是数得出来的。见 {@link #assertMatchesDictionary}。</p>
 */
public final class InteractorScope {

    /** R1 记得。 */
    public static final String R1_REMEMBER = "R1";
    /** R2 留脚印。 */
    public static final String R2_FOOTPRINT = "R2";
    /** R3 留一句话。 */
    public static final String R3_LEAVE_WORDS = "R3";
    /** R4 我也想起一件事。 */
    public static final String R4_ME_TOO = "R4";
    /** R5 共同留一束心意（即献花）。 */
    public static final String R5_SHARED_FLOWER = "R5";
    /** R6 关注题材。🔴 不计入。 */
    public static final String R6_FOLLOW_TOPIC = "R6";
    /** R7 关注 ta。🔴 不计入。 */
    public static final String R7_FOLLOW_AUTHOR = "R7";

    /**
     * 计入独立互动者的类型：<b>R1–R5</b>。
     *
     * <p>🔴 <b>R5（共同留一束心意 / 献花）计入，尽管它是付费类。</b>
     * 这与「付费不影响分发」不冲突，理由见 {@link #WHY_PAID_FLOWERS_STILL_COUNT}。</p>
     */
    public static final Set<String> COUNTED = Set.of(
            R1_REMEMBER, R2_FOOTPRINT, R3_LEAVE_WORDS, R4_ME_TOO, R5_SHARED_FLOWER);

    /** 🔴 不计入的类型：持续关注类。 */
    public static final Set<String> NOT_COUNTED = Set.of(R6_FOLLOW_TOPIC, R7_FOLLOW_AUTHOR);

    /**
     * 为什么付费的献花仍然计入，而这不构成「付费影响分发」。
     *
     * <p><b>因为这个度量数的是「人」，不是「量」。</b>一个人买一百束心意，
     * 在独立互动者里仍然只是 <b>1</b>。钱能买到的是心意的<b>数量</b>，
     * 而数量恰好是这个指标唯一不看的东西。</p>
     *
     * <p>再者每天有免费额度，任何人不花钱也能成为那个「1」——
     * 付费买的是「多送几束」，不是「被算作一个互动者」的资格。</p>
     *
     * <p>⚠️ 真正需要防的是<b>作者出钱找人来献花</b>，但那是刷量，
     * 由 {@code SurgeModel} 的关注关系占比与互刷环检测挡，
     * 与「献花算不算互动」是两个问题。</p>
     */
    public static final String WHY_PAID_FLOWERS_STILL_COUNT =
            "独立互动者数的是人不是量：买一百束心意仍然只算一个人，而数量正是这个指标不看的东西；"
                    + "且每日有免费额度，不花钱也能成为那个 1。";

    private InteractorScope() {
    }

    public static boolean counts(String resonanceType) {
        return COUNTED.contains(resonanceType);
    }

    /**
     * 用数据库字典校对本类的镜像是否还准。
     *
     * <p>🔴 接上 {@code t_resonance_type} 之后<b>在启动时调一次</b>：
     * 两份清单分叉的那天不会有人发现，因为北极星和 SURGE 都还能算出数来，只是不再是同一批人。</p>
     *
     * @param typesWithCountsToAcceptanceTrue 从 {@code t_resonance_type} 读出的、
     *                                        {@code countsToAcceptance = true} 的 code 集合
     */
    public static void assertMatchesDictionary(Set<String> typesWithCountsToAcceptanceTrue) {
        if (!COUNTED.equals(typesWithCountsToAcceptanceTrue)) {
            List<String> onlyInCode = COUNTED.stream()
                    .filter(t -> !typesWithCountsToAcceptanceTrue.contains(t)).sorted().toList();
            List<String> onlyInDb = typesWithCountsToAcceptanceTrue.stream()
                    .filter(t -> !COUNTED.contains(t)).sorted().toList();
            throw new IllegalStateException(
                    "InteractorScope.COUNTED 与 t_resonance_type.countsToAcceptance 已分叉——"
                            + "北极星与 SURGE 会开始用不同口径数同一批人，而两个数都还算得出来。"
                            + "只在代码里：" + onlyInCode + "；只在字典里：" + onlyInDb);
        }
    }
}
