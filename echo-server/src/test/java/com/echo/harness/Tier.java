package com.echo.harness;

/**
 * 订阅档位（PRD §7.1 三档订阅）。
 *
 * <p>每档携带月费与"每日附赠爱心"取值逻辑：免费档无附赠，基础/高级档在
 * {@link NumericConfig#basicGrant()} / {@link NumericConfig#premiumGrant()} 基础上按档取值。</p>
 */
public enum Tier {

    /** 免费白嫖：¥0，仅靠 capFree 白嫖路径挣暖意。 */
    NONE(0),

    /** 基础档：¥39/月，附赠 basicGrant。 */
    BASIC(39),

    /** 高级档：¥198/月，附赠 premiumGrant（≈自动满维系）。 */
    PREMIUM(198);

    private final int monthlyPrice;

    Tier(int monthlyPrice) {
        this.monthlyPrice = monthlyPrice;
    }

    /** 月费（元）。 */
    public int monthlyPrice() {
        return monthlyPrice;
    }

    /** 是否付费档。 */
    public boolean isPaid() {
        return this != NONE;
    }

    /**
     * 本档每日附赠的额外暖意（爱心）。
     *
     * @param cfg 数值配置（提供 basicGrant / premiumGrant）
     * @return 附赠量：NONE=0，BASIC=basicGrant，PREMIUM=premiumGrant
     */
    public double grant(NumericConfig cfg) {
        return switch (this) {
            case NONE -> 0.0;
            case BASIC -> cfg.basicGrant();
            case PREMIUM -> cfg.premiumGrant();
        };
    }
}
