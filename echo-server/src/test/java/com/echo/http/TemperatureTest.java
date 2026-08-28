package com.echo.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 温度规则单测（PRD §3.11 / 定案 #5）：地板 60、不惩罚衰减、只由主人回访回暖。
 */
class TemperatureTest {

    @Test
    void neverBelowFloor() {
        assertThat(Temperature.normalize(10.0)).isEqualTo(60.0);
        assertThat(Temperature.onOwnerVisit(10.0)).isGreaterThanOrEqualTo(60.0);
    }

    @Test
    void neverAboveCeiling() {
        assertThat(Temperature.onOwnerVisit(100.0)).isEqualTo(100.0);
        assertThat(Temperature.normalize(120.0)).isEqualTo(100.0);
    }

    @Test
    void ownerVisitWarmsUp() {
        double before = 72.0;
        double after = Temperature.onOwnerVisit(before);
        assertThat(after).isGreaterThan(before);
        assertThat(after).isLessThanOrEqualTo(100.0);
    }

    @Test
    void noVisitNoPenalty() {
        // 不主动回访 => 不调用回暖 => 温度不变（不惩罚式衰减：没有向下的调用路径）
        double t = 72.0;
        assertThat(Temperature.normalize(t)).isEqualTo(72.0);
    }
}
