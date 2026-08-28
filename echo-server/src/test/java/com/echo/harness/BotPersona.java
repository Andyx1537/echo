package com.echo.harness;

/**
 * 体验 Bot 画像库项（EXP-BOTS §1 画像三轴 × §2.1 扩展画像库）。
 *
 * <p>一个画像 = 消费观(A) × 操作习惯(B) × 情感画像(C) 的一次组合，再赋予行为参数。
 * 由 {@code resources/harness/populations/default.json} 加载。</p>
 *
 * @param id           稳定标识（如 "s1".."s12"）
 * @param name         中文名（A×B×C）
 * @param weight       人口权重（启用项归一化后合计 1.0）
 * @param enabled      是否计入大盘（未启用为预备群体）
 * @param pOpen        每日打开概率（期望投入折算）
 * @param pets         宠物数（多宠 → 每宠暖意摊薄）
 * @param tier         订阅档位（决定附赠爱心与月费）
 * @param sensitivity  低温敏感度（越高越易被"低温/消极暗示"伤到）
 * @param novelty      新鲜需求（疲劳期后越高越易因单调流失）
 * @param baseChurn    基础日流失率（与温度无关的自然流失）
 * @param lowTemp      低温阈：温度低于此值开始触发低温流失 hazard（想你/地板区上界）
 * @param grief        是否深度哀伤·强依赖群体（红线重点保护对象）
 * @param voiceProfile §7 画像嗓音/在意点（定性层评审视角）
 */
public record BotPersona(
        String id,
        String name,
        double weight,
        boolean enabled,
        double pOpen,
        int pets,
        Tier tier,
        double sensitivity,
        double novelty,
        double baseChurn,
        double lowTemp,
        boolean grief,
        String voiceProfile
) {

    /** 本画像每日"期望白嫖 + 订阅附赠"的暖意来源上限（capFree + 档位附赠）。 */
    public double dailyWarmthCap(NumericConfig cfg) {
        return cfg.capFree() + tier.grant(cfg);
    }

    /** 月费（元），转发自 {@link Tier#monthlyPrice()}。 */
    public int monthlyPrice() {
        return tier.monthlyPrice();
    }
}
