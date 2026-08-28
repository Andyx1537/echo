package com.echo.infra.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第一关（合规词表 · 第三方内容安全服务）的单测。
 *
 * <p>这套用例盯的不是「命中的能拦住」——那是最容易写对的部分。盯的是三件写错了
 * <b>从调用方完全看不出来</b>的事：</p>
 *
 * <ul>
 *   <li>🔴 <b>失败方向</b>：超时 / 限流 / 鉴权失败 / 格式异常 / 熔断，一律「未通过」。
 *       写反了整关等于不存在，而且日志安静、指标漂亮。</li>
 *   <li>🔴 <b>降级只能更严</b>：配额用尽要拒绝。放行的话，把请求打到超过配额
 *       就是一条可主动触发的绕过路径。</li>
 *   <li>🔴 <b>就绪判据是真的调通过一次</b>，不是「配置填了」。一份填错的 endpoint
 *       能骗过配置检查，但骗不过这个判据 —— 而 {@code S13} 开关就挂在它上面。</li>
 * </ul>
 */
class ContentSafetyGateTest {

    private static ContentSafetyGate gate(IContentSafetyClient client) {
        return new ContentSafetyGate(ContentSafetyConfig.fake(), client);
    }

    // ------------------------------------------------------ 未配置 ≠ 通过

    @Test
    void unconfiguredSkipsInsteadOfPassing() {
        ContentSafetyGate g = new ContentSafetyGate(ContentSafetyConfig.unconfigured(),
                new FakeContentSafetyClient());

        ContentSafetyVerdict v = g.inspect("随便一句话");
        assertThat(v.outcome()).isEqualTo(ContentSafetyVerdict.Outcome.SKIPPED_UNCONFIGURED);
        assertThat(v.actuallyInspected())
                .as("🔴 跳过不算「检查过了」，否则报表上这一关是 100% 通过率").isFalse();
        assertThat(g.isOperational())
                .as("🔴 未配置就必须未就绪 —— S13 开关据此打不开").isFalse();
        assertThat(g.skippedCount()).isEqualTo(1);
    }

    @Test
    void unconfiguredProbeDoesNotFireRequest() {
        FakeContentSafetyClient client = new FakeContentSafetyClient();
        ContentSafetyGate g = new ContentSafetyGate(ContentSafetyConfig.unconfigured(), client);

        assertThat(g.probe()).isFalse();
        assertThat(client.callCount()).as("未配置时不该发请求").isZero();
    }

    // ------------------------------------------------------ 正常判定

    @Test
    void blockedVerdictIsRecordedAsBlockedNotAsFailure() {
        ContentSafetyGate g = gate(new FakeContentSafetyClient(
                text -> ContentSafetyVerdict.blocked("涉政")));

        ContentSafetyVerdict v = g.inspect("一段违规内容");
        assertThat(v.passed()).isFalse();
        assertThat(v.label()).isEqualTo("涉政");
        assertThat(g.blockedCount()).isEqualTo(1);
        assertThat(g.failedClosedCount())
                .as("🔴 真有人发违规内容 与 服务在出问题，是两个必须分开的数字").isZero();
        assertThat(g.isOperational()).as("调通了就算就绪").isTrue();
    }

    @Test
    void blankTextPassesWithoutCallingService() {
        FakeContentSafetyClient client = new FakeContentSafetyClient();
        ContentSafetyGate g = gate(client);

        assertThat(g.inspect("   ").passed()).isTrue();
        assertThat(client.callCount()).isZero();
    }

    // ------------------------------------------------------ 🔴 失败方向

    @Test
    void everyFailureReasonFailsClosed() {
        for (ContentSafetyException.Reason reason : ContentSafetyException.Reason.values()) {
            ContentSafetyGate g = gate(FakeContentSafetyClient.alwaysFailing(reason));
            ContentSafetyVerdict v = g.inspect("今天的阳光很好。");
            assertThat(v.passed())
                    .as("🔴 %s 必须按未通过处理", reason).isFalse();
            assertThat(v.outcome()).isEqualTo(ContentSafetyVerdict.Outcome.FAILED_CLOSED);
        }
    }

    /** 🔴 实现里漏出来的任意 RuntimeException 也必须被收成「未通过」，不能沿栈上抛。 */
    @Test
    void unexpectedRuntimeExceptionFailsClosed() {
        ContentSafetyGate g = gate(new FakeContentSafetyClient(text -> {
            throw new IllegalStateException("客户端内部炸了");
        }));

        assertThat(g.inspect("今天的阳光很好。").outcome())
                .isEqualTo(ContentSafetyVerdict.Outcome.FAILED_CLOSED);
    }

    /** 客户端返回 null 也算格式异常 → 未通过（不能当成「没问题」）。 */
    @Test
    void nullVerdictFailsClosed() {
        ContentSafetyGate g = gate(new FakeContentSafetyClient(text -> null));

        assertThat(g.inspect("今天的阳光很好。").passed()).isFalse();
    }

    @Test
    void failureMarksCapabilityNotReady() {
        ContentSafetyGate g = gate(new FakeContentSafetyClient(text -> {
            throw new IllegalStateException("挂了");
        }));
        // 先调通一次是不可能的（一直抛），所以就绪状态从头到尾都是 false
        g.inspect("一句话");
        assertThat(g.isOperational())
                .as("🔴 能力状态必须反映「现在其实没有保护」").isFalse();
    }

    // ------------------------------------------------------ 🔴 降级只能更严

    @Test
    void exhaustedLocalQuotaRejectsRatherThanPasses() {
        ContentSafetyConfig onePerSecond = new ContentSafetyConfig(
                ContentSafetyConfig.PROVIDER_FAKE, null, null, null, null,
                800, 1, 5, 30_000);
        ContentSafetyGate g = new ContentSafetyGate(onePerSecond, new FakeContentSafetyClient());

        assertThat(g.inspect("第一句").passed()).isTrue();
        // 🔴 第二句在同一秒内超配额 —— 必须拒绝。放行等于给出一条可主动触发的绕过路径
        ContentSafetyVerdict second = g.inspect("第二句");
        assertThat(second.passed()).isFalse();
        assertThat(second.outcome()).isEqualTo(ContentSafetyVerdict.Outcome.FAILED_CLOSED);
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresAndKeepsRejecting() {
        ContentSafetyConfig breakAtTwo = new ContentSafetyConfig(
                ContentSafetyConfig.PROVIDER_FAKE, null, null, null, null,
                800, 0, 2, 60_000);
        FakeContentSafetyClient client = FakeContentSafetyClient.alwaysFailing(
                ContentSafetyException.Reason.TIMEOUT);
        ContentSafetyGate g = new ContentSafetyGate(breakAtTwo, client);

        g.inspect("一");
        g.inspect("二");
        int callsBeforeCircuit = client.callCount();

        // 熔断期内：🔴 一律拒绝，且不再打服务
        ContentSafetyVerdict v = g.inspect("三");
        assertThat(v.passed()).as("🔴 熔断期内是一律拒绝，不是一律放行").isFalse();
        assertThat(client.callCount()).isEqualTo(callsBeforeCircuit);
    }

    // ------------------------------------------------------ 🔴 就绪判据

    @Test
    void operationalOnlyAfterARealSuccessfulCall() {
        ContentSafetyGate g = gate(new FakeContentSafetyClient());

        assertThat(g.isOperational())
                .as("🔴 配置齐了不算就绪 —— 一份填错的 endpoint 能过配置检查").isFalse();
        assertThat(g.probe()).isTrue();
        assertThat(g.isOperational()).isTrue();
    }

    @Test
    void metricsSeparateSkippedFromPassed() {
        ContentSafetyGate configured = gate(new FakeContentSafetyClient());
        configured.inspect("一句话");
        assertThat(configured.skippedCount()).isZero();

        ContentSafetyGate unconfigured = new ContentSafetyGate(
                ContentSafetyConfig.unconfigured(), new FakeContentSafetyClient());
        unconfigured.inspect("一句话");
        assertThat(unconfigured.skippedCount()).isEqualTo(1);
        assertThat(unconfigured.blockedCount()).isZero();
    }
}
