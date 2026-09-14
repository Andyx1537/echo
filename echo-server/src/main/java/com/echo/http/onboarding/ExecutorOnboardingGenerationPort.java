package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.http.CopyGuardFilter;
import com.echo.infra.imagegen.GeneratedImage;
import com.echo.infra.imagegen.IImageGenClient;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.provenance.GeneratedMediaPublisher;
import com.echo.infra.provenance.ProvenanceConfig;
import com.echo.infra.storage.IStorage;
import com.echo.infra.vision.IImageRefResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Small provider adapter. It deliberately owns no product state. */
@Slf4j
public final class ExecutorOnboardingGenerationPort implements OnboardingGenerationPort {
    private static final String[] GRADIENTS = {
            "linear-gradient(145deg,#efc4a8,#f5e6ca)",
            "linear-gradient(145deg,#b8cdb2,#e9eed7)",
            "linear-gradient(145deg,#a9c9da,#e1edf2)"
    };
    private static final String[] EMOJIS = {"🐾", "🌤️", "✨"};

    private final ILlmClient llm;
    private final IDGenerator ids;
    private final Executor executor;
    private final IImageGenClient imageGen;
    private final IImageRefResolver imageRefs;
    private final GeneratedMediaPublisher mediaPublisher;
    private final ProvenanceConfig provenance;
    private final HttpClient downloadHttp;

    public ExecutorOnboardingGenerationPort(ILlmClient llm, IDGenerator ids, Executor executor) {
        this(llm, ids, executor, null, null, null, null);
    }

    public ExecutorOnboardingGenerationPort(ILlmClient llm, IDGenerator ids, Executor executor,
                                            IImageGenClient imageGen, IImageRefResolver imageRefs,
                                            GeneratedMediaPublisher mediaPublisher, ProvenanceConfig provenance) {
        this.llm = llm;
        this.ids = ids;
        this.executor = executor;
        this.imageGen = imageGen;
        this.imageRefs = imageRefs;
        this.mediaPublisher = mediaPublisher;
        this.provenance = provenance;
        this.downloadHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void submit(String jobId, OnboardingAggregate.Anchor anchor, String adjustmentCode,
                       BiConsumer<List<OnboardingAggregate.Candidate>, Throwable> completion) {
        executor.execute(() -> {
            try {
                completion.accept(buildCandidates(anchor, adjustmentCode), null);
            } catch (Throwable error) {
                completion.accept(List.of(), error);
            }
        });
    }

    List<OnboardingAggregate.Candidate> buildCandidates(OnboardingAggregate.Anchor anchor, String adjustmentCode) {
        String raw = llm.complete("private-pet-onboarding\n" + (anchor == null ? "" : anchor.answerSnapshot)
                + "\nadjustment=" + (adjustmentCode == null ? "" : adjustmentCode));
        List<String> imageUrls = List.of();
        if (imageGen != null && imageGen.isLive()) {
            imageUrls = liveImageUrls(anchor);
        }
        List<OnboardingAggregate.Candidate> out = new ArrayList<>();
        int n = Math.max(3, imageUrls.size());
        for (int i = 0; i < n && i < 3; i++) {
            OnboardingAggregate.Candidate c = new OnboardingAggregate.Candidate();
            c.candidateId = String.valueOf(ids.nextId());
            c.gradient = GRADIENTS[i];
            c.emoji = EMOJIS[i];
            c.signature = CopyGuardFilter.sanitize(raw == null || raw.isBlank() || "{}".equals(raw)
                    ? "从熟悉的日常，慢慢认出它" : raw);
            if (i < imageUrls.size()) {
                c.imageUrl = imageUrls.get(i);
            }
            out.add(c);
        }
        return out;
    }

    private List<String> liveImageUrls(OnboardingAggregate.Anchor anchor) {
        String resourceId = portraitResourceId(anchor);
        if (resourceId == null || imageRefs == null) {
            throw new IllegalStateException("真出图需要已选定的肖像");
        }
        String ref = imageRefs.resolve(resourceId);
        if (ref == null) {
            throw new IllegalStateException("肖像读不出来，无法出定妆图");
        }
        List<GeneratedImage> images = imageGen.stylize(ref);
        if (images == null || images.size() < 3) {
            throw new IllegalStateException("定妆图不足三张");
        }
        List<String> urls = new ArrayList<>();
        for (GeneratedImage image : images.subList(0, 3)) {
            urls.add(persist(image));
        }
        return urls;
    }

    private String persist(GeneratedImage image) {
        byte[] data = image.data();
        if (data == null && image.url() != null) {
            data = download(image.url());
        }
        if (data != null && mediaPublisher != null && provenance != null && provenance.ready()) {
            IStorage.Stored stored = mediaPublisher.publish(String.valueOf(ids.nextId()), data,
                    image.contentType() == null ? "image/png" : image.contentType(), "candidate.png");
            return stored.url();
        }
        if (image.url() != null && !image.url().isBlank()) {
            if (provenance == null || !provenance.ready()) {
                log.warn("[imggen] 未配置服务提供者编码，定妆图暂用供应商临时地址，不落盘");
            }
            return image.url();
        }
        throw new IllegalStateException("定妆图没有可展示的地址");
    }

    private byte[] download(String url) {
        try {
            HttpResponse<byte[]> response = downloadHttp.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2 || response.body() == null || response.body().length == 0) {
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("[imggen] 定妆图下载失败 urlHost={}", URI.create(url).getHost());
            return null;
        }
    }

    static String portraitResourceId(OnboardingAggregate.Anchor anchor) {
        if (anchor == null) {
            return null;
        }
        String selectedAssetId = selectedAssetId(anchor.subjectSnapshot);
        String fromSelected = resourceIdForAsset(anchor.assetSnapshot, selectedAssetId, true);
        if (fromSelected != null) {
            return fromSelected;
        }
        return resourceIdForAsset(anchor.assetSnapshot, null, true);
    }

    private static String selectedAssetId(String subjectSnapshot) {
        JsonArray subjects = array(subjectSnapshot);
        for (JsonElement element : subjects) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject subject = element.getAsJsonObject();
            if (subject.has("userSelected") && subject.get("userSelected").getAsBoolean()
                    && subject.has("assetId")) {
                return subject.get("assetId").getAsString();
            }
        }
        if (!subjects.isEmpty() && subjects.get(0).isJsonObject() && subjects.get(0).getAsJsonObject().has("assetId")) {
            return subjects.get(0).getAsJsonObject().get("assetId").getAsString();
        }
        return null;
    }

    private static String resourceIdForAsset(String assetSnapshot, String assetId, boolean imageOnly) {
        for (JsonElement element : array(assetSnapshot)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            if (imageOnly && asset.has("mediaType") && !"image".equals(asset.get("mediaType").getAsString())) {
                continue;
            }
            if (assetId != null && (!asset.has("assetId") || !assetId.equals(asset.get("assetId").getAsString()))) {
                continue;
            }
            if (asset.has("resourceId") && !asset.get("resourceId").getAsString().isBlank()) {
                return asset.get("resourceId").getAsString();
            }
        }
        return null;
    }

    private static JsonArray array(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonArray();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
    }
}
