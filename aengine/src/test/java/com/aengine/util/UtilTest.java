package com.aengine.util;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * util 通用工具单元测试：StringUtils / GsonUtil / DateUtil / NetworkUtil。
 */
class UtilTest {

    // ---------- StringUtils ----------

    @Test
    void stringUtilsBasics() {
        assertThat(StringUtils.isEmpty("")).isTrue();
        assertThat(StringUtils.isEmpty(null)).isTrue();
        assertThat(StringUtils.isEmpty("x")).isFalse();
        assertThat(StringUtils.hasText("  ")).isFalse();
        assertThat(StringUtils.hasText(" a ")).isTrue();
        assertThat(StringUtils.capitalize("abc")).isEqualTo("Abc");
        assertThat(StringUtils.trimAllWhitespace(" a b c ")).isEqualTo("abc");
        assertThat(StringUtils.countOccurrencesOf("aXaXa", "X")).isEqualTo(2);
        assertThat(StringUtils.replace("a-b-c", "-", "_")).isEqualTo("a_b_c");
    }

    @Test
    void stringUtilsMd5IsStable() {
        assertThat(StringUtils.md5("abc")).isEqualTo(StringUtils.md5("abc"));
        assertThat(StringUtils.md5("abc")).hasSize(32);
    }

    @Test
    void stringUtilsCommaDelimitedToArray() {
        assertThat(StringUtils.commaDelimitedListToStringArray("a,b,c"))
                .containsExactly("a", "b", "c");
    }

    // ---------- GsonUtil ----------

    @Test
    void gsonRoundTrip() {
        Bean bean = new Bean();
        bean.id = 5;
        bean.name = "hero";
        String json = GsonUtil.beanToJson(bean);
        Bean back = GsonUtil.jsonToBean(json, Bean.class);
        assertThat(back).isNotNull();
        assertThat(back.id).isEqualTo(5);
        assertThat(back.name).isEqualTo("hero");
    }

    @Test
    void gsonInvalidJsonReturnsNull() {
        assertThat(GsonUtil.jsonToBean("not-json{", Bean.class)).isNull();
    }

    @Test
    void gsonGetParamByName() {
        assertThat(GsonUtil.getParamByName("{\"name\":\"abc\",\"id\":1}", "name"))
                .isEqualTo("abc");
    }

    // ---------- DateUtil ----------

    @Test
    void dateUtilParsingAndFields() {
        Date date = DateUtil.fromDateStr("2020-01-02");
        assertThat(date).isNotNull();
        assertThat(DateUtil.getYear(date)).isEqualTo(2020);
        assertThat(DateUtil.getMonth(date)).isEqualTo(0); // 0-based (January)
        assertThat(DateUtil.getDay(date)).isEqualTo(2);
        assertThat(DateUtil.toDateInt(date)).isEqualTo(20200102);
        assertThat(DateUtil.isSameDate(date, DateUtil.fromDateStr("2020-01-02"))).isTrue();
        assertThat(DateUtil.isSameDate(date, DateUtil.fromDateStr("2020-01-03"))).isFalse();
    }

    @Test
    void dateUtilDaysBetween() {
        Date begin = DateUtil.fromDateStr("2020-01-01");
        Date end = DateUtil.fromDateStr("2020-01-04");
        assertThat(DateUtil.getDaysBetween(begin, end)).isEqualTo(3);
        assertThat(DateUtil.isBetween(begin, end, DateUtil.fromDateStr("2020-01-02"))).isTrue();
        assertThat(DateUtil.isBetween(begin, end, DateUtil.fromDateStr("2020-02-02"))).isFalse();
    }

    // ---------- NetworkUtil ----------

    @Test
    void networkUtilMessageId() {
        assertThat(NetworkUtil.getMessageID(Login_1001.class)).isEqualTo(1001);
    }

    @Test
    void networkUtilIpMatchingAndFormat() {
        assertThat(NetworkUtil.checkIPMatching("*", "10.2.88.12")).isTrue();
        assertThat(NetworkUtil.checkIPMatching("*.*.*.*", "10.2.88.12")).isTrue();
        assertThat(NetworkUtil.checkIPMatching("10.2.88.12", "10.2.88.12")).isTrue();
        assertThat(NetworkUtil.checkIPMatching("10.2.88.13", "10.2.88.12")).isFalse();
        assertThat(NetworkUtil.validateIPFormat("192.168.1.1")).isTrue();
        assertThat(NetworkUtil.validateIPFormat("999.1.1.1")).isFalse();
    }

    static class Bean {
        int id;
        String name;
    }

    // 类名以 _<id> 结尾，用于 getMessageID
    static class Login_1001 {
    }
}
