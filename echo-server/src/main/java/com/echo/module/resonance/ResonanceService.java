package com.echo.module.resonance;

import com.echo.infra.vector.IVectorStore;
import com.echo.infra.vector.ScoredId;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 共鸣匹配领域服务（TECH-P1 §4.3）：取我的向量 → topN → 过滤(自己/黑名单) → 回候选。
 *
 * <p><b>不再落 {@code t_resonance_record}</b>（TECH-DESIGN-feed-recall-and-exposure §1.1/§2.5.3）：
 * 原先每次查询都给每个候选写一行，而该表<em>没有任何读取路径</em>——全仓检索确认无 SELECT、
 * 无 {@code list}/{@code get} 调用，且表上唯一索引是单列 {@code idx(accountId)}；若真用于历史留痕，
 * 索引形态应是 {@code (accountId, createTime DESC)}，只有单列意味着这张表从未被设计过读取场景。
 * 每请求 N 行的纯写入既是 IO 负担，也让 {@code @Cache(columns="accountId")} 的 per-account 索引
 * 随翻页单调增长。需要这类数据时，走 {@code SPEC-recommendation-ranking §5.4} 的精排影子日志，
 * 它比这张表完整得多。</p>
 */
@Slf4j
public class ResonanceService {

    /** 默认返回数量上限。 */
    public static final int DEFAULT_TOP_N = 10;

    /** 默认余弦距离阈值上限（约最大距离，等价"不过滤"）。 */
    public static final double DEFAULT_THRESHOLD = 2.0d;

    private final IVectorStore vectorStore;

    public ResonanceService(IVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 查询共鸣候选。
     *
     * @param accountId 已登录账号 ID
     * @param topN      返回上限（&lt;=0 用默认）
     * @param threshold 余弦距离阈值上限（&lt;=0 用默认）
     * @return 候选项（已过滤自己；按距离升序）
     */
    public List<ScoredId> queryResonance(long accountId, int topN, double threshold) {
        float[] myVector = vectorStore.get(accountId);
        if (myVector == null) {
            log.debug("共鸣查询：accountId={} 尚无个人向量，返回空", accountId);
            return List.of();
        }
        int k = topN > 0 ? topN : DEFAULT_TOP_N;
        double th = threshold > 0 ? threshold : DEFAULT_THRESHOLD;

        // 多取一个名额，过滤掉自己后仍能凑足 k 个
        List<ScoredId> hits = vectorStore.topN(myVector, k + 1, th);
        List<ScoredId> candidates = new ArrayList<>(k);
        for (ScoredId hit : hits) {
            if (hit.accountId() == accountId) {
                continue; // 过滤自己
            }
            if (isBlocked(accountId, hit.accountId())) {
                continue; // 预留黑名单钩子
            }
            candidates.add(hit);
            if (candidates.size() >= k) {
                break;
            }
        }

        return candidates;
    }

    /** 黑名单判断（P1 预留，恒 false）。 */
    private boolean isBlocked(long accountId, long peerId) {
        return false;
    }
}
