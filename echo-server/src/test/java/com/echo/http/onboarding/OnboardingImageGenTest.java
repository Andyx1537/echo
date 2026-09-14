package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.infra.imagegen.GeneratedImage;
import com.echo.infra.imagegen.IImageGenClient;
import com.echo.infra.llm.MockLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingImageGenTest {
    @Test
    void portraitComesFromSelectedImageAsset() {
        OnboardingAggregate.Anchor anchor = new OnboardingAggregate.Anchor();
        anchor.subjectSnapshot = "[{\"assetId\":\"asset-2\",\"userSelected\":true}]";
        anchor.assetSnapshot = "["
                + "{\"assetId\":\"asset-1\",\"mediaType\":\"video\",\"resourceId\":\"vid-1\"},"
                + "{\"assetId\":\"asset-2\",\"mediaType\":\"image\",\"resourceId\":\"pic-9\"}]";
        assertThat(ExecutorOnboardingGenerationPort.portraitResourceId(anchor)).isEqualTo("pic-9");
    }

    @Test
    void liveGenerationWritesImageUrlsAndKeepsPreviewCopy() {
        IImageGenClient imageGen = new IImageGenClient() {
            @Override
            public boolean isLive() {
                return true;
            }

            @Override
            public List<GeneratedImage> stylize(String baseImageRef) {
                assertThat(baseImageRef).isEqualTo("data:image/jpeg;base64,abc");
                return List.of(
                        new GeneratedImage("https://cdn.example/1.png", null, "image/png"),
                        new GeneratedImage("https://cdn.example/2.png", null, "image/png"),
                        new GeneratedImage("https://cdn.example/3.png", null, "image/png"));
            }
        };
        OnboardingAggregate.Anchor anchor = new OnboardingAggregate.Anchor();
        anchor.assetSnapshot = "[{\"assetId\":\"a1\",\"mediaType\":\"image\",\"resourceId\":\"pic-1\"}]";
        ExecutorOnboardingGenerationPort port = new ExecutorOnboardingGenerationPort(
                new MockLlmClient(), new IDGenerator(63), Runnable::run,
                imageGen, resourceId -> "data:image/jpeg;base64,abc", null, null);
        List<OnboardingAggregate.Candidate> candidates = port.buildCandidates(anchor, null);
        assertThat(candidates).hasSize(3).allSatisfy(candidate -> {
            assertThat(candidate.imageUrl).startsWith("https://cdn.example/");
            assertThat(candidate.signature).contains("开发流程预览");
        });
    }

    @Test
    void liveGenerationWithoutPortraitFailsVisibly() {
        IImageGenClient imageGen = new IImageGenClient() {
            @Override
            public boolean isLive() {
                return true;
            }

            @Override
            public List<GeneratedImage> stylize(String baseImageRef) {
                throw new AssertionError("should not call vendor");
            }
        };
        ExecutorOnboardingGenerationPort port = new ExecutorOnboardingGenerationPort(
                new MockLlmClient(), new IDGenerator(63), Runnable::run,
                imageGen, resourceId -> "data:image/jpeg;base64,abc", null, null);
        assertThatThrownBy(() -> port.buildCandidates(new OnboardingAggregate.Anchor(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("肖像");
    }
}
