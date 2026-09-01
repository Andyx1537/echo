package com.echo.module.mind;

import com.aengine.util.id.IDGenerator;
import com.echo.infra.embedding.EmbeddingDescriptor;
import com.echo.infra.vector.IVectorStore;

import java.util.Arrays;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;

/**
 * 向量模型切换后的显式回填入口。调用方分批传入账号，服务按当前模型重新编码并更新元数据。
 * 不扫描全表、不在应用启动时自动回填，避免升级时形成不可控的长事务和供应商费用。
 */
@Slf4j
public final class VectorRebuildService {
    private final MindProfileRepository profiles;
    private final SelfVectorRepository vectors;
    private final IVectorStore vectorStore;
    private final IDGenerator idGenerator;

    public VectorRebuildService(MindProfileRepository profiles, SelfVectorRepository vectors,
                                IVectorStore vectorStore, IDGenerator idGenerator) {
        this.profiles = profiles;
        this.vectors = vectors;
        this.vectorStore = vectorStore;
        this.idGenerator = idGenerator;
    }

    public Report rebuild(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) return new Report(0, 0, 0);
        int rebuilt = 0;
        int skipped = 0;
        int failed = 0;
        for (Long accountId : accountIds) {
            if (accountId == null || accountId <= 0) { skipped++; continue; }
            try {
                MindProfile profile = profiles.get("accountId", accountId);
                if (profile == null || profile.getEnrichedPrefs() == null) { skipped++; continue; }
                rebuildOne(accountId, profile.getEnrichedPrefs());
                rebuilt++;
            } catch (RuntimeException e) {
                failed++;
                log.error("向量回填失败 accountId={}", accountId, e);
            }
        }
        return new Report(rebuilt, skipped, failed);
    }

    private void rebuildOne(long accountId, String text) {
        float[] embedding = vectorStore.encode(text);
        IVectorStore.requireDimension(embedding);
        EmbeddingDescriptor descriptor = vectorStore.descriptor();
        SelfVector vector = vectors.get("accountId", accountId);
        boolean created = vector == null;
        if (created) {
            vector = new SelfVector();
            vector.setId(idGenerator.nextId());
            vector.setAccountId(accountId);
        }
        vector.setDim(descriptor.dimensions());
        vector.setVectorRef("self:" + accountId);
        vector.setNormHash(Integer.toHexString(Arrays.hashCode(embedding)));
        vector.setEmbedProvider(descriptor.provider());
        vector.setEmbedModel(descriptor.model());
        vector.setEmbedVersion(descriptor.version());
        vector.setEmbeddedAt(System.currentTimeMillis());
        if (created) vectors.add(vector); else vectors.save(vector);
        vectorStore.upsert(accountId, embedding);
    }

    public record Report(int rebuilt, int skipped, int failed) { }
}
