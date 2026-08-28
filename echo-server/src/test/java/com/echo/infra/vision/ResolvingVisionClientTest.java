package com.echo.infra.vision;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResolvingVisionClient} 单测：验证 detect 前确实做了 {@code resourceId → 图片引用} 的解析，
 * 以及解析失败时不发起网络调用、如实标 {@code fallback}。不发起任何真实网络调用。
 */
class ResolvingVisionClientTest {

    /** 记录真实客户端收到的引用；断言它拿到的绝不是原始 resourceId。 */
    private static final class CapturingClient implements IVisionClient {
        private final AtomicReference<String> seen = new AtomicReference<>();

        @Override
        public List<DetectSubject> detect(String ref) {
            return detectWithSource(ref).subjects();
        }

        @Override
        public DetectResult detectWithSource(String ref) {
            seen.set(ref);
            return DetectResult.model(List.of(DetectSubject.of(DetectSubject.SubjectType.ANIMAL, "猫", 0.98)));
        }
    }

    @Test
    void resolvedRefIsHandedToDelegateInsteadOfRawResourceId() {
        CapturingClient delegate = new CapturingClient();
        IImageRefResolver resolver = id -> "data:image/jpeg;base64,AAAA";
        ResolvingVisionClient client = new ResolvingVisionClient(resolver, delegate, new StubVisionClient(false));

        DetectResult result = client.detectWithSource("8823393053208561601");

        assertThat(delegate.seen.get()).isEqualTo("data:image/jpeg;base64,AAAA");
        assertThat(result.source()).isEqualTo(DetectResult.Source.MODEL);
        assertThat(result.subjects().get(0).species()).isEqualTo("猫");
    }

    /** 解析不出引用时直接兜底：不打网络（注定 400/超时），并标 fallback。 */
    @Test
    void unresolvableResourceSkipsNetworkAndMarksFallback() {
        CapturingClient delegate = new CapturingClient();
        ResolvingVisionClient client = new ResolvingVisionClient(id -> null, delegate, new StubVisionClient(false));

        DetectResult result = client.detectWithSource("missing");

        assertThat(delegate.seen.get()).isNull();
        assertThat(result.source()).isEqualTo(DetectResult.Source.FALLBACK);
        assertThat(result.subjects().get(0).species()).isEqualTo("狗");
    }

    /** 解析器意外抛异常也不能把建档流程冲塌。 */
    @Test
    void resolverExceptionDegradesGracefully() {
        ResolvingVisionClient client = new ResolvingVisionClient(id -> {
            throw new IllegalStateException("boom");
        }, new CapturingClient(), new StubVisionClient(false));

        DetectResult result = client.detectWithSource("res-1");

        assertThat(result.source()).isEqualTo(DetectResult.Source.FALLBACK);
        assertThat(result.subjects()).hasSize(1);
    }
}
