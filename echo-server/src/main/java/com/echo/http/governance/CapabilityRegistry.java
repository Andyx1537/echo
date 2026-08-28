package com.echo.http.governance;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 治理能力的<b>运行时探测</b>登记处（{@code S13} 开启前置的机器可判实现）。
 *
 * <p>每一项能力登记一个探针（{@link BooleanSupplier}），由装配层在挂载真实实现时注册。
 * 探针回答的是「这个能力现在到底在不在」——比如举报能力的探针检查 C 端提交路由是否真的挂上了，
 * 而不是检查某个配置项是否被勾选。</p>
 *
 * <p>🔴 <b>默认全部未就绪。</b>没有注册探针的能力一律视为未就绪，而不是「没说就算有」。
 * 这个方向是刻意的：漏注册一个探针的后果是开关打不开（可发现、可修复），
 * 反过来则是开关能在能力缺失的情况下被打开（不可发现，正是 {@code S13} 要防的）。</p>
 */
@Slf4j
public final class CapabilityRegistry {

    private final Map<GovernanceCapability, BooleanSupplier> probes =
            new EnumMap<>(GovernanceCapability.class);

    /**
     * 注册一项能力的就绪探针。
     *
     * @param probe 运行时探测；🔴 必须探测真实能力（路由/实现是否在位），不得只读配置
     */
    public synchronized void register(GovernanceCapability capability, BooleanSupplier probe) {
        probes.put(capability, probe);
    }

    /** 该项能力当前是否就绪。未注册探针 → false（默认未就绪）。 */
    public boolean isReady(GovernanceCapability capability) {
        BooleanSupplier probe;
        synchronized (this) {
            probe = probes.get(capability);
        }
        if (probe == null) {
            return false;
        }
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException e) {
            // 探针自己炸了 → 按未就绪处理。宁可开关打不开，不可在状态不明时放开
            log.warn("治理能力探针异常，按未就绪处理: {}", capability, e);
            return false;
        }
    }

    /** 全部五项是否都就绪。 */
    public boolean allReady() {
        return notReady().isEmpty();
    }

    /** @return 当前未就绪的能力清单（顺序同枚举声明，便于稳定展示）。 */
    public List<GovernanceCapability> notReady() {
        List<GovernanceCapability> missing = new ArrayList<>();
        for (GovernanceCapability c : GovernanceCapability.values()) {
            if (!isReady(c)) {
                missing.add(c);
            }
        }
        return missing;
    }

    /** 逐项就绪状态快照（供后台展示「还差哪几项」）。 */
    public Map<GovernanceCapability, Boolean> snapshot() {
        Map<GovernanceCapability, Boolean> out = new EnumMap<>(GovernanceCapability.class);
        for (GovernanceCapability c : GovernanceCapability.values()) {
            out.put(c, isReady(c));
        }
        return out;
    }
}
