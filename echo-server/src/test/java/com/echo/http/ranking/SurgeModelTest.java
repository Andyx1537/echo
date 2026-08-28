package com.echo.http.ranking;

import com.echo.http.ranking.SurgeModel.Candidate;
import com.echo.http.ranking.SurgeModel.Decision;
import com.echo.http.ranking.SurgeModel.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code SURGE} 准入判定（{@code SPEC §3.9} / {@code TECH §8.6}）。
 *
 * <p>除了门槛数值，这里重点钉两件事：<b>反马太</b>（越热越难拿）与<b>防刷的结构性上限</b>
 * （即便刷成功了，代价上限也是封死的）。</p>
 */
class SurgeModelTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_800_000_000_000L;

    private final SurgeModel model = new SurgeModel(SurgeConfig.DEFAULTS);

    /** 一个刚好够格的候选，各用例只改自己关心的那一项。 */
    private static Candidate candidate() {
        return new Candidate("card-1", 42L, SurgeModel.POOL_DRAINED,
                6, 1.0, 0, false, 0, 0L, 0L);
    }

    private Decision evaluate(Candidate c) {
        return model.evaluate(c, NOW);
    }

    @Test
    void aGenuinelySurgingDrainedCardIsAdmitted() {
        Decision d = evaluate(candidate());
        assertThat(d.admitted()).isTrue();
        assertThat(d.reason()).isEqualTo(Reason.ADMITTED);
        assertThat(d.ratio()).isEqualTo(6.0);
    }

    // ------------------------------------------------------ 资格与门槛

    /** 🔴 只有自然流掉的内容才谈得上"拉回"。 */
    @Test
    void onlyDrainedContentIsEligible() {
        for (String pool : new String[]{"FRESH", "STEADY", "RESTRICTED"}) {
            Candidate c = new Candidate("c", 1L, pool, 50, 0.1, 0, false, 0, 0L, 0L);
            assertThat(evaluate(c).reason()).isEqualTo(Reason.NOT_DRAINED);
        }
    }

    /** 绝对地板：近 24h 至少 5 个独立互动者。 */
    @Test
    void absoluteFloorIsFiveInteractors() {
        Candidate below = new Candidate("c", 1L, SurgeModel.POOL_DRAINED, 4, 0.0, 0, false, 0, 0L, 0L);
        assertThat(evaluate(below).reason()).isEqualTo(Reason.BELOW_ABS_FLOOR);

        Candidate atFloor = new Candidate("c", 1L, SurgeModel.POOL_DRAINED, 5, 0.0, 0, false, 0, 0L, 0L);
        assertThat(evaluate(atFloor).admitted()).isTrue();
    }

    /**
     * 🔴 <b>反马太</b>：基线 0 的冷内容 5 个人就够，基线 10/日的热内容要 30 个。
     *
     * <p>越是已经有热度的内容，想再拿一次拉回越难。这是 {@code SURGE} 与「热榜」的分界线。</p>
     */
    @Test
    void theHotterItAlreadyIsTheHarderItIsToSurgeAgain() {
        Candidate cold = new Candidate("c", 1L, SurgeModel.POOL_DRAINED, 5, 0.0, 0, false, 0, 0L, 0L);
        assertThat(evaluate(cold).admitted()).as("基线 0，5 个人就够").isTrue();

        Candidate warmNotEnough = new Candidate("c", 1L, SurgeModel.POOL_DRAINED, 29, 10.0, 0, false, 0, 0L, 0L);
        assertThat(evaluate(warmNotEnough).reason())
                .as("基线 10/日，29 个人还不够——虽然人数是冷内容的 6 倍")
                .isEqualTo(Reason.BELOW_RATIO);

        Candidate warmEnough = new Candidate("c", 1L, SurgeModel.POOL_DRAINED, 30, 10.0, 0, false, 0, 0L, 0L);
        assertThat(evaluate(warmEnough).admitted()).as("到 30 才够").isTrue();
    }

    // ---------------------------------------------------------- 防刷

    /** 热度主要来自作者自己的关注者 = 熟人捧场，不是被更多人发现。 */
    @Test
    void surgeDrivenMostlyByTheAuthorsOwnFollowersIsVoided() {
        Candidate mostlyFriends = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                10, 0.5, 6, false, 0, 0L, 0L);
        assertThat(evaluate(mostlyFriends).reason()).isEqualTo(Reason.FOLLOW_RATIO);

        Candidate halfFriends = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                10, 0.5, 5, false, 0, 0L, 0L);
        assertThat(evaluate(halfFriends).admitted()).as("正好一半不算超过").isTrue();
    }

    @Test
    void mutualBoostRingIsRejected() {
        Candidate ring = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                20, 0.5, 0, true, 0, 0L, 0L);
        assertThat(evaluate(ring).reason()).isEqualTo(Reason.MUTUAL_BOOST_RING);
    }

    /** 🔴 终身 2 次封顶：即便每次都刷成功，一张卡这辈子也只能被拉回两次。 */
    @Test
    void lifetimeTriggersAreCappedAtTwo() {
        Candidate used2 = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                100, 0.1, 0, false, 2, 0L, 0L);
        assertThat(evaluate(used2).reason()).isEqualTo(Reason.LIFETIME_EXHAUSTED);
    }

    /** 同一张卡两次触发之间要隔 30 天。 */
    @Test
    void aCardMustWaitThirtyDaysBetweenTriggers() {
        Candidate day29 = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                100, 0.1, 0, false, 1, NOW - 29 * DAY, 0L);
        assertThat(evaluate(day29).reason()).isEqualTo(Reason.IN_COOLDOWN);

        Candidate day31 = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                100, 0.1, 0, false, 1, NOW - 31 * DAY, 0L);
        assertThat(evaluate(day31).admitted()).isTrue();
    }

    /** 同一作者名下的内容 7 天最多一次——挡住「换一张卡接着刷」。 */
    @Test
    void oneAuthorCanOnlySurgeOncePerWeekAcrossAllTheirCards() {
        Candidate day6 = new Candidate("other-card", 1L, SurgeModel.POOL_DRAINED,
                100, 0.1, 0, false, 0, 0L, NOW - 6 * DAY);
        assertThat(evaluate(day6).reason()).isEqualTo(Reason.AUTHOR_WEEKLY_CAP);

        Candidate day8 = new Candidate("other-card", 1L, SurgeModel.POOL_DRAINED,
                100, 0.1, 0, false, 0, 0L, NOW - 8 * DAY);
        assertThat(evaluate(day8).admitted()).isTrue();
    }

    /**
     * 防刷类的拒绝只落在「本来够格」的候选上。
     *
     * <p>否则 {@code rank_surge_reject} 里的 {@code follow_ratio} 计数会被大量根本不够热的
     * 候选淹掉，那个数字就不再能回答「有多少人在刷」。</p>
     */
    @Test
    void antiAbuseReasonsAreOnlyReportedForOtherwiseQualifyingCandidates() {
        Candidate coldAndFriendly = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                2, 0.0, 2, true, 0, 0L, 0L);
        assertThat(evaluate(coldAndFriendly).reason())
                .as("不够热就报不够热，不要报成刷量")
                .isEqualTo(Reason.BELOW_ABS_FLOOR);
    }

    // ------------------------------------------------------- 通道行为

    @Test
    void windowLastsTwentyFourHours() {
        assertThat(model.expiresAt(NOW)).isEqualTo(NOW + 24 * 3600_000L);
    }

    /** 通道内按增速降序，不按绝对热度——否则通道内部会重新变成一个小热榜。 */
    @Test
    void channelOrdersBySurgeRatioNotByRawHeat() {
        Decision fastButSmall = new Decision(true, Reason.ADMITTED, 12.0);
        Decision slowButBig = new Decision(true, Reason.ADMITTED, 3.5);

        assertThat(java.util.stream.Stream.of(slowButBig, fastButSmall)
                .sorted(SurgeModel.channelOrder()).toList())
                .containsExactly(fastButSmall, slowButBig);
    }

    /** 🔴 REVIVE 已整体移除：零响应内容拿不到任何"救济"。 */
    @Test
    void aSilentCardGetsNoReliefWhatsoever() {
        Candidate silent = new Candidate("c", 1L, SurgeModel.POOL_DRAINED,
                0, 0.0, 0, false, 0, 0L, 0L);
        assertThat(evaluate(silent).admitted())
                .as("零响应满一周即吸引力不足，不设救济通道")
                .isFalse();
    }

    // ---------------------------------------------------------- 配置

    @Test
    void configRejectsARatioThatWouldTurnSurgeIntoUniversalRelief() {
        assertThatThrownBy(() -> new SurgeConfig(5, 1.0, 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须大于 1");
    }

    @Test
    void configReadsThresholdsFromTheEnvironment() {
        SurgeConfig c = SurgeConfig.from(java.util.Map.of(
                "ECHO_SURGE_ABS_FLOOR", "8",
                "ECHO_SURGE_RATIO", "4.0")::get);
        assertThat(c.absFloor()).isEqualTo(8);
        assertThat(c.ratio()).isEqualTo(4.0);
        assertThat(c.ttlHours()).isEqualTo(24);
    }

    /** 每个拒绝原因都要有埋点字面量，否则 rank_surge_reject 报不出分布。 */
    @Test
    void everyReasonCarriesAnEventCode() {
        for (Reason r : Reason.values()) {
            assertThat(r.code()).isNotBlank();
        }
    }
}
