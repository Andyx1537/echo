package com.echo.http.model;

import com.echo.http.model.Models.SubjectFields;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主体类型四字段的兜底方向。
 *
 * <h2>为什么这个文件必须存在</h2>
 *
 * <p>这两个兜底值<b>看起来都只是"随便挑一个默认值"</b>，实际上各自堵着一个
 * 「照着写不会挂、只会悄悄判错」的口子（{@code MOD5} 点名过的那一类）。
 * 🔴 <b>把它们改回去不会有任何测试变红、不会报错、不会有日志</b> —— 除了这个文件。</p>
 *
 * <p>🔴 <b>本测试断言的是<u>方向</u>而不是<u>字面值</u>。</b>如果哪天取值域改名，
 * 改的应当是常量，而「兜底不能落到能力最宽的那一类」「兜底不能落成 user」这两条
 * 判断本身不随之改变。</p>
 */
class SubjectFieldsTest {

    /**
     * 🔴 {@code subjectType} 缺失 / 不认识时不得落 {@code animal}。
     *
     * <p>{@code animal} 是三类里能力最宽的一类。落它等于「认不出来就按最宽的放行」，
     * 与保守兜底的方向正好相反 —— 而<b>「字段缺失」正是老客户端与第三方调用的常态</b>。</p>
     */
    @Test
    void unknownSubjectTypeFallsBackToOtherNeverToAnimal() {
        assertThat(SubjectFields.DEFAULT_SUBJECT_TYPE)
                .as("兜底值不得是能力最宽的那一类")
                .isNotEqualTo("animal")
                .isEqualTo("other");

        for (String bad : new String[]{null, "", "animal ", "ANIMAL", "pet", "人", "unknown"}) {
            assertThat(SubjectFields.normalizeType(bad))
                    .as("不认识的取值「%s」必须落 other，绝不能落 animal", bad)
                    .isEqualTo("other");
        }
    }

    /** 认得的三个值必须原样保留，否则用户的主动选择会被兜底吃掉。 */
    @Test
    void knownSubjectTypesArePreserved() {
        for (String ok : new String[]{"animal", "person", "other"}) {
            assertThat(SubjectFields.normalizeType(ok)).isEqualTo(ok);
        }
    }

    /**
     * 🔴🔴 本文件里最要紧的一条：{@code subjectSource} 的兜底<b>绝不能是 {@code user}</b>。
     *
     * <p>{@code SR-D1} 的触发条件是「素材置信度低 <b>且</b> {@code subjectSource != "user"}」。
     * 兜底成 {@code user} 会让后半句<b>永远为假</b>，于是整条兜底分支被<b>静默关掉</b>——
     * 🔴 <b>不报错，只是低置信度素材从此再也进不了 {@code L1}</b>，
     * 而那正是它们唯一的归宿。</p>
     */
    @Test
    void unknownSubjectSourceNeverFallsBackToUser() {
        assertThat(SubjectFields.DEFAULT_SUBJECT_SOURCE)
                .as("兜底成 user 会静默关掉 SR-D1 整条兜底分支")
                .isNotEqualTo("user")
                .isEqualTo("default");

        for (String bad : new String[]{null, "", "USER", "user ", "machine ", "manual"}) {
            assertThat(SubjectFields.normalizeSource(bad))
                    .as("不认识的取值「%s」必须落 default，绝不能落 user", bad)
                    .isEqualTo("default");
        }
    }

    /** 归一化之后 {@code != "user"} 这个判断必须仍然为真——这才是兜底能触发的前提。 */
    @Test
    void fallbackSourceStillSatisfiesTheSrD1Condition() {
        assertThat(SubjectFields.normalizeSource(null))
                .as("SR-D1 的后半句 subjectSource != 'user' 在兜底路径上必须成立")
                .isNotEqualTo("user");
    }

    /** 用户真的填过时不能被改写成 default，否则「用户答了」这次表态就丢了。 */
    @Test
    void explicitUserSourceIsPreserved() {
        assertThat(SubjectFields.normalizeSource("user")).isEqualTo("user");
        assertThat(SubjectFields.normalizeSource("machine")).isEqualTo("machine");
        assertThat(SubjectFields.normalizeSource("default")).isEqualTo("default");
    }

    /**
     * 🔴 可空的两个字段：{@code null} 必须<b>保持 {@code null}</b>，不得塌成空串或兜底值。
     *
     * <p>{@code null} 在这两列上是<b>有意义的状态</b>：机器没给出可用判定 / 用户没动过预填值。
     * 把它补齐成某个具体类型，等于让「用户没答」这个状态消失 ——
     * 🔴 <b>而那正是 {@code SR-D1} 的触发条件。</b></p>
     */
    @Test
    void nullMachineOrUserTypeStaysNull() {
        assertThat(SubjectFields.normalizeNullableType(null))
                .as("null = 机器没判 / 用户没答，是有意义的状态，不能被补齐")
                .isNull();
    }

    /** 可空字段拿到不认识的值时，仍然收到最保守的那一类（而不是放行）。 */
    @Test
    void unknownNullableTypeIsStillNarrowedToOther() {
        assertThat(SubjectFields.normalizeNullableType("pet")).isEqualTo("other");
        assertThat(SubjectFields.normalizeNullableType("person")).isEqualTo("person");
    }
}
