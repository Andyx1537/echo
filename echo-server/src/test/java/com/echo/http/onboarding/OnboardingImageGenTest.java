package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.infra.imagegen.GeneratedImage;
import com.echo.infra.imagegen.IImageGenClient;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.provenance.AigcMetadataWriter;
import com.echo.infra.provenance.GeneratedMediaPublisher;
import com.echo.infra.provenance.ProvenanceConfig;
import com.echo.infra.storage.IStorage;
import com.echo.infra.storage.LocalDiskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
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
    void liveGenerationWithoutPublisherFailsVisibly() {
        IImageGenClient imageGen = urlOnlyGen();
        OnboardingAggregate.Anchor anchor = portraitAnchor();
        ExecutorOnboardingGenerationPort port = new ExecutorOnboardingGenerationPort(
                new MockLlmClient(), new IDGenerator(63), Runnable::run,
                imageGen, resourceId -> "data:image/jpeg;base64,abc", null, null);
        assertThatThrownBy(() -> port.buildCandidates(anchor, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("服务提供者编码");
    }

    @Test
    void liveGenerationPersistsToOwnStorage(@TempDir Path dir) throws IOException {
        byte[] png = pngBytes();
        IStorage storage = new LocalDiskStorage(dir.toString(), "");
        GeneratedMediaPublisher publisher = new GeneratedMediaPublisher(
                storage, ProvenanceConfig.from(k -> "00ECHO000000000000000010001"));
        IImageGenClient imageGen = new IImageGenClient() {
            @Override
            public boolean isLive() {
                return true;
            }

            @Override
            public List<GeneratedImage> stylize(String baseImageRef) {
                assertThat(baseImageRef).isEqualTo("data:image/jpeg;base64,abc");
                return List.of(
                        new GeneratedImage(null, png, "image/png"),
                        new GeneratedImage(null, png, "image/png"),
                        new GeneratedImage(null, png, "image/png"));
            }
        };
        ExecutorOnboardingGenerationPort port = new ExecutorOnboardingGenerationPort(
                new MockLlmClient(), new IDGenerator(63), Runnable::run,
                imageGen, resourceId -> "data:image/jpeg;base64,abc", publisher,
                ProvenanceConfig.from(k -> "00ECHO000000000000000010001"));
        List<OnboardingAggregate.Candidate> candidates = port.buildCandidates(portraitAnchor(), null);
        assertThat(candidates).hasSize(3).allSatisfy(candidate -> {
            assertThat(candidate.imageUrl).startsWith("/api/v1/files/");
            assertThat(candidate.signature).contains("开发流程预览");
            String key = candidate.imageUrl.substring("/api/v1/files/".length());
            IStorage.Loaded loaded = storage.load(key);
            assertThat(loaded).isNotNull();
            assertThat(AigcMetadataWriter.read(loaded.data())).isNotBlank();
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

    @Test
    void englishJsonDumpFallsBackToChineseSignature() {
        OnboardingAggregate.Anchor anchor = new OnboardingAggregate.Anchor();
        anchor.answerSnapshot = "[{\"questionId\":\"Q1\",\"answerCodes\":[\"home\"]},"
                + "{\"questionId\":\"Q2\",\"answerCodes\":[\"tiny\"]}]";
        ILlmClient noisy = new ILlmClient() {
            @Override
            public String enrich(String rawPrefs) {
                return rawPrefs;
            }

            @Override
            public String complete(String prompt) {
                assertThat(prompt).startsWith("private-pet-onboarding\n");
                assertThat(prompt).contains("facts=home,tiny");
                assertThat(prompt).doesNotContain("questionId");
                return "It looks like you've shared a JSON array representing answers from a pet onboarding flow.";
            }
        };
        List<OnboardingAggregate.Candidate> candidates = new ExecutorOnboardingGenerationPort(
                noisy, new IDGenerator(63), Runnable::run).buildCandidates(anchor, null);
        assertThat(candidates).hasSize(3).allSatisfy(candidate ->
                assertThat(candidate.signature).isEqualTo("从熟悉的日常，慢慢认出它"));
    }

    @Test
    void chineseSignatureIsKept() {
        assertThat(ExecutorOnboardingGenerationPort.candidateSignature("门口那一小团还在等"))
                .isEqualTo("门口那一小团还在等");
    }

    private static OnboardingAggregate.Anchor portraitAnchor() {
        OnboardingAggregate.Anchor anchor = new OnboardingAggregate.Anchor();
        anchor.assetSnapshot = "[{\"assetId\":\"a1\",\"mediaType\":\"image\",\"resourceId\":\"pic-1\"}]";
        return anchor;
    }

    private static IImageGenClient urlOnlyGen() {
        return new IImageGenClient() {
            @Override
            public boolean isLive() {
                return true;
            }

            @Override
            public List<GeneratedImage> stylize(String baseImageRef) {
                return List.of(
                        new GeneratedImage("https://cdn.example/1.png", null, "image/png"),
                        new GeneratedImage("https://cdn.example/2.png", null, "image/png"),
                        new GeneratedImage("https://cdn.example/3.png", null, "image/png"));
            }
        };
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(180, 120, 90));
        g.fillRect(0, 0, 32, 24);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
