package com.echo.infra.provenance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件元数据隐式标识的字段格式单测（{@code GB 45438—2025 附录 E}，规范性）。
 *
 * <p>本套用例的作用是<b>把标准原文钉住</b>：附录 E 是规范性附录，字段名、顺序、取值域都不是我们的自由，
 * 改动其中任何一处都应当先改标准解读、再改用例，而不是反过来。</p>
 */
class AigcLabelTest {

    private static final String CODE = "00ECHO0000000000000000100001";

    /** 27 位编码：前缀 00 + 20 位主体标识码 + 5 位服务扩展码。 */
    private static String code() {
        return ProviderCode.of("ECHO0000000000000000", "10001");
    }

    @Test
    void serializesExactlyAsAppendixE() {
        AigcLabel label = AigcLabel.firstWrite(code(), "8823393053208561601");

        assertThat(label.toMetadataValue()).isEqualTo(
                "{\"AIGC\": {\"Label\":\"1\","
                        + "\"ContentProducer\":\"00ECHO000000000000000010001\","
                        + "\"ProduceID\":\"8823393053208561601\","
                        + "\"ReservedCode1\":\"\","
                        + "\"ContentPropagator\":\"00ECHO000000000000000010001\","
                        + "\"PropagateID\":\"8823393053208561601\","
                        + "\"ReservedCode2\":\"\"}}");
    }

    /** 🔴 附录 E 注1：首次写入时，传播要素与制作要素一致。 */
    @Test
    void firstWriteMakesPropagationElementsMatchProduction() {
        AigcLabel label = AigcLabel.firstWrite(code(), "12345");

        assertThat(label.contentPropagator()).isEqualTo(label.contentProducer());
        assertThat(label.propagateId()).isEqualTo(label.produceId());
    }

    @Test
    void labelValueIsRestrictedToTheThreeDefinedCodes() {
        assertThat(AigcLabel.LABEL_GENERATED).isEqualTo("1");
        assertThat(AigcLabel.LABEL_POSSIBLY).isEqualTo("2");
        assertThat(AigcLabel.LABEL_SUSPECTED).isEqualTo("3");

        assertThatThrownBy(() -> new AigcLabel("0", "p", "i", "", "p", "i", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AigcLabel("true", "p", "i", "", "p", "i", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 🔴 附录 E j)：值只用码位 {@code 0x21}、{@code 0x23~0x5B}、{@code 0x5D~0x7E} 的字符。
     *
     * <p>被挡掉的三类正是能把这段 JSON 撑破或越出字符集的：空格、引号、反斜杠；中文也不在其中——
     * 这正是我们用「编码」而不是「名称」的原因。</p>
     */
    @Test
    void sanitizeKeepsOnlyTheCodePointsAppendixEAllows() {
        assertThat(AigcLabel.sanitize("ab cd")).isEqualTo("abcd");
        assertThat(AigcLabel.sanitize("a\"b")).isEqualTo("ab");
        assertThat(AigcLabel.sanitize("a\\b")).isEqualTo("ab");
        assertThat(AigcLabel.sanitize("回声Echo")).isEqualTo("Echo");
        assertThat(AigcLabel.sanitize("!#[]~")).isEqualTo("!#[]~");
    }

    /** 值被清洗成空 = 这个要素其实没写进去，比不写更糟：文件看着像已标识。 */
    @Test
    void requiredElementsCannotBeBlankAfterSanitizing() {
        assertThatThrownBy(() -> AigcLabel.firstWrite("回声", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ContentProducer");
        assertThatThrownBy(() -> AigcLabel.firstWrite(code(), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProduceID");
    }

    /** 附录 F.4：预留字段1 可放元数据的数字签名，用于保护标识完整性。 */
    @Test
    void reservedCode1CarriesTheIntegritySignature() {
        AigcLabel label = AigcLabel.firstWrite(code(), "12345")
                .withReservedCode1("e862483430d978cbf828b8b24296ef9328d843a0");

        assertThat(label.toMetadataValue())
                .contains("\"ReservedCode1\":\"e862483430d978cbf828b8b24296ef9328d843a0\"");
    }

    // ------------------------------------------------------ 服务提供者编码

    @Test
    void providerCodeMustBeTwentySevenCharsStartingWithZeroZero() {
        assertThat(ProviderCode.isValid(CODE.substring(0, 27))).isTrue();
        assertThat(ProviderCode.isValid("00ECHO000000000000000010001")).isTrue();

        assertThat(ProviderCode.isValid(null)).isFalse();
        assertThat(ProviderCode.isValid("")).isFalse();
        assertThat(ProviderCode.isValid("0" + "A".repeat(26))).as("前两位应为 00").isFalse();
        assertThat(ProviderCode.isValid("00" + "A".repeat(24))).as("长度不足 27").isFalse();
        assertThat(ProviderCode.isValid("00" + "a".repeat(25))).as("小写字母不合规").isFalse();
        assertThat(ProviderCode.isValid("00" + "-".repeat(25))).as("只允许数字与大写字母").isFalse();
    }

    @Test
    void providerCodeComposesFromSubjectAndServiceExtension() {
        assertThat(ProviderCode.of("ECHO0000000000000000", null))
                .isEqualTo("00ECHO000000000000000000000");
        assertThat(ProviderCode.of("ECHO0000000000000000", "10001"))
                .isEqualTo("00ECHO000000000000000010001");

        assertThatThrownBy(() -> ProviderCode.of("TOOSHORT", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 🔴 不合法就抛，不回退到占位编码——占位编码写进文件等于声明了一个不存在的主体。 */
    @Test
    void requireRefusesToInventACode() {
        assertThatThrownBy(() -> ProviderCode.require(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不得由代码自行编造");
    }

    @Test
    void configIsNotReadyUntilTheCodeIsConfigured() {
        assertThat(ProvenanceConfig.from(k -> null).ready()).isFalse();
        assertThat(ProvenanceConfig.from(k -> "not-a-code").ready()).isFalse();
        assertThat(ProvenanceConfig.from(k -> "00ECHO000000000000000010001").ready()).isTrue();
    }
}
