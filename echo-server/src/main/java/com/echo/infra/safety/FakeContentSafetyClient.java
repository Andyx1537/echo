package com.echo.infra.safety;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 可注入的假实现：把第一关全链路（配额 / 熔断 / 失败方向 / 就绪判据）测通而不发网络请求。
 *
 * <p>也可用于开发环境联调：{@code ECHO_SAFETY_PROVIDER=fake} 时装配本实现，
 * ⚠️ 但它<b>不是</b>合规能力 —— 生产环境用 fake 等于没有第一关。
 * {@link ContentSafetyFactory} 会在装配 fake 时打 warn。</p>
 */
public final class FakeContentSafetyClient implements IContentSafetyClient {

    /** 默认命中词（仅用于测试，不是合规词表）。 */
    private static final List<String> DEFAULT_HITS = List.of("__blocked__", "违规样本");

    private final Function<String, ContentSafetyVerdict> behavior;
    private final AtomicInteger calls = new AtomicInteger();

    public FakeContentSafetyClient() {
        this(text -> DEFAULT_HITS.stream().anyMatch(text::contains)
                ? ContentSafetyVerdict.blocked("test_hit")
                : ContentSafetyVerdict.pass());
    }

    /** 自定义行为；behavior 抛 {@link RuntimeException} 可模拟客户端崩溃。 */
    public FakeContentSafetyClient(Function<String, ContentSafetyVerdict> behavior) {
        this.behavior = behavior;
    }

    /** 总是抛指定失败原因，用于验证「失败方向是拒绝」。 */
    public static FakeContentSafetyClient alwaysFailing(ContentSafetyException.Reason reason) {
        return new FakeContentSafetyClient(text -> {
            throw new WrappedFailure(new ContentSafetyException(reason, "模拟失败: " + reason));
        });
    }

    @Override
    public ContentSafetyVerdict inspectText(String text) throws ContentSafetyException {
        calls.incrementAndGet();
        try {
            return behavior.apply(text);
        } catch (WrappedFailure w) {
            throw w.cause;
        }
    }

    @Override
    public String provider() {
        return "fake";
    }

    public int callCount() {
        return calls.get();
    }

    /** {@link Function} 不能抛检查异常，借它把 {@link ContentSafetyException} 透出去。 */
    private static final class WrappedFailure extends RuntimeException {
        private final ContentSafetyException cause;

        WrappedFailure(ContentSafetyException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
