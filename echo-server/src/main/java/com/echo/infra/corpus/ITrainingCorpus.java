package com.echo.infra.corpus;

import java.util.List;

/**
 * 训练语料写入抽象（AI-CAPABILITIES §7）。把「原始素材 ref + 生成结果 + 用户反馈」沉淀为
 * 自养模型（F/G 回声·基调、A 识别、B 定妆）的训练样本。
 *
 * <p>合规：调用方（EchoApi）负责 {@code trainConsent} 门控，本接口只接收<b>已同意</b>的样本；
 * 实现方仍应对 {@link TrainSample#consent} 做防御性校验。</p>
 *
 * <p>本期提供内存态实现 {@link InMemoryTrainingCorpus}；PG 落库（对齐 {@code t_train_sample}）为 TODO，
 * 与既有 HTTP 新域内存态一致（见 {@code com.echo.http.store.EchoStore}）。</p>
 */
public interface ITrainingCorpus {

    /**
     * 写入一条训练样本。实现方须对 {@link TrainSample#consent} 做防御性校验：
     * consent 非 true 一律拒写（返回 false），确保未同意的数据绝不进语料。
     *
     * @return 是否实际写入
     */
    boolean write(TrainSample sample);

    /** 当前语料总量。 */
    int size();

    /** 全量快照（只读副本，供导出/联调/单测）。 */
    List<TrainSample> snapshot();

    /** 某宠物的样本快照（只读副本）。 */
    List<TrainSample> byPet(String petId);
}
