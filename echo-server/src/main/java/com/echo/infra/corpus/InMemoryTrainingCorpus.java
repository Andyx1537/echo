package com.echo.infra.corpus;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ITrainingCorpus} 的内存态实现（本期）。线程安全用 {@link CopyOnWriteArrayList}
 * （写少读多、量级可控）。PG 落库（{@code t_train_sample}）为 TODO，切换时替换本类即可，上层不改动。
 *
 * <p>防御性门控：{@link TrainSample#consent} 非 true 直接拒写，双保险守住 PIPL「未同意不入语料」。</p>
 */
@Slf4j
public class InMemoryTrainingCorpus implements ITrainingCorpus {

    private final List<TrainSample> samples = new CopyOnWriteArrayList<>();

    @Override
    public boolean write(TrainSample sample) {
        if (sample == null || !sample.consent) {
            log.debug("TrainingCorpus 拒写：consent 非 true（PIPL 门控），petId={}",
                    sample == null ? null : sample.petId);
            return false;
        }
        samples.add(sample);
        log.debug("TrainingCorpus 写入训练样本 petId={}, total={}", sample.petId, samples.size());
        return true;
    }

    @Override
    public int size() {
        return samples.size();
    }

    @Override
    public List<TrainSample> snapshot() {
        return new ArrayList<>(samples);
    }

    @Override
    public List<TrainSample> byPet(String petId) {
        List<TrainSample> out = new ArrayList<>();
        for (TrainSample s : samples) {
            if (s.petId != null && s.petId.equals(petId)) {
                out.add(s);
            }
        }
        return out;
    }
}
