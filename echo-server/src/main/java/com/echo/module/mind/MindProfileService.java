package com.echo.module.mind;

import com.aengine.util.GsonUtil;
import com.aengine.util.id.IDGenerator;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.vector.IVectorStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * 意识档案领域服务（TECH-P1 §4.1）：偏好 → LLM 补全 → 向量编码 → upsert → 落库。
 *
 * <p>纯领域逻辑，依赖以构造注入便于 mock 单测：</p>
 * <ul>
 *   <li>{@link MindProfileRepository} / {@link SelfVectorRepository} —— CachedPg 仓储</li>
 *   <li>{@link ILlmClient} —— LLM 补全（失败兜底用原始偏好，不阻塞主流程）</li>
 *   <li>{@link IVectorStore} —— 向量编码/写入（内存 mock 或 pgvector）</li>
 *   <li>{@link IDGenerator} —— 雪花 ID</li>
 * </ul>
 */
@Slf4j
public class MindProfileService {

    private final MindProfileRepository mindProfileRepository;

    private final SelfVectorRepository selfVectorRepository;

    private final ILlmClient llmClient;

    private final IVectorStore vectorStore;

    private final IDGenerator idGenerator;

    public MindProfileService(MindProfileRepository mindProfileRepository,
                              SelfVectorRepository selfVectorRepository,
                              ILlmClient llmClient,
                              IVectorStore vectorStore,
                              IDGenerator idGenerator) {
        this.mindProfileRepository = mindProfileRepository;
        this.selfVectorRepository = selfVectorRepository;
        this.llmClient = llmClient;
        this.vectorStore = vectorStore;
        this.idGenerator = idGenerator;
    }

    /**
     * 提交偏好，生成/更新意识档案与个人向量。
     *
     * @param accountId 已登录账号 ID
     * @param rawPrefs  用户自拟原始偏好（非空）
     * @return 处理结果
     */
    public Outcome submitPrefs(long accountId, List<String> rawPrefs) {
        if (rawPrefs == null || rawPrefs.isEmpty()) {
            return Outcome.invalid("rawPrefs 不能为空");
        }
        String rawJson = GsonUtil.beanToJson(rawPrefs);
        String enriched = safeEnrich(rawJson);

        float[] vector = vectorStore.encode(enriched);
        String normHash = Integer.toHexString(Arrays.hashCode(vector));

        // 1) 先落/更新 SelfVector 元数据行（行的创建归仓储；embedding 列归向量通道）
        SelfVector selfVector = selfVectorRepository.get("accountId", accountId);
        boolean newVector = selfVector == null;
        if (newVector) {
            selfVector = new SelfVector();
            selfVector.setId(idGenerator.nextId());
            selfVector.setAccountId(accountId);
        }
        selfVector.setDim(IVectorStore.DIM);
        selfVector.setVectorRef("self:" + accountId);
        selfVector.setNormHash(normHash);
        if (newVector) {
            selfVectorRepository.add(selfVector);
        } else {
            selfVectorRepository.save(selfVector);
        }

        // 2) 写入 embedding 向量列（行已存在）
        vectorStore.upsert(accountId, vector);

        // 3) 落/更新 MindProfile
        MindProfile profile = mindProfileRepository.get("accountId", accountId);
        boolean newProfile = profile == null;
        if (newProfile) {
            profile = new MindProfile();
            profile.setId(idGenerator.nextId());
            profile.setAccountId(accountId);
            profile.setVersion(1);
        } else {
            profile.setVersion(profile.getVersion() + 1);
        }
        profile.setRawPrefs(rawJson);
        profile.setEnrichedPrefs(enriched);
        profile.setVectorId(selfVector.getId());
        if (newProfile) {
            mindProfileRepository.add(profile);
        } else {
            mindProfileRepository.save(profile);
        }

        log.info("意识档案落库 accountId={}, profileId={}, vectorId={}, version={}",
                accountId, profile.getId(), selfVector.getId(), profile.getVersion());
        return Outcome.ok(profile.getId(), selfVector.getId(), enriched);
    }

    /** LLM 补全：异常/超时兜底回退到原始偏好，不阻塞主流程（§4.1）。 */
    private String safeEnrich(String rawJson) {
        try {
            String enriched = llmClient.enrich(rawJson);
            return (enriched == null || enriched.isBlank()) ? rawJson : enriched;
        } catch (Exception e) {
            log.warn("LLM 补全失败，兜底使用原始偏好", e);
            return rawJson;
        }
    }

    /** 处理结果。{@code code} 0=成功，1=参数错误。 */
    public record Outcome(int code, long profileId, long vectorId, String enrichedPrefs, String message) {

        static Outcome ok(long profileId, long vectorId, String enrichedPrefs) {
            return new Outcome(0, profileId, vectorId, enrichedPrefs, "ok");
        }

        static Outcome invalid(String message) {
            return new Outcome(1, 0L, 0L, "", message);
        }

        public boolean success() {
            return code == 0;
        }
    }
}
