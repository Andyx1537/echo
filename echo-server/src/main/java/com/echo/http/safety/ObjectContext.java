package com.echo.http.safety;

import lombok.extern.slf4j.Slf4j;

/**
 * 第四、五关共用的对象状态入参（{@code SPEC-security §4.3.1}）。
 *
 * <p>两关都是<b>状态相关</b>的检查——同一句话在不同对象状态下，一句是事故、一句是正常承接。
 * 因此两关不各自判断状态，共用这一个由调用方传入的字段组。</p>
 *
 * <p>🔴 三条不可放宽的约束：</p>
 * <ul>
 *   <li>必须由 context 传入，默认 {@link Status#UNKNOWN}；
 *       <b>不允许模型自行推断</b>，也<b>不允许安全闸从文本反推</b>。</li>
 *   <li>字段缺失 / 非法值 → 一律按 {@code unknown} 走最保守分支。</li>
 *   <li><b>通用位置不得传 {@code deceased}</b>。通用位置（引导语、空状态、入口文案、对外描述）
 *       面向所有人，不存在一个确定的对象状态可传。调用方若在通用位置携带
 *       {@code objectStatus != unknown}，安全闸降级按 {@code unknown} 处理并告警——
 *       🔴 按<b>调用方违规</b>记，不按内容违规记。</li>
 * </ul>
 */
@Slf4j
public record ObjectContext(Kind kind, Status status, Position position) {

    /** 对象品类。第五关只对 {@link Kind#PERSON} 生效。 */
    public enum Kind {
        PERSON,
        /** 🟢 宠物无人格权，第五关对宠物不适用。 */
        PET;

        /**
         * 缺失 / 非法值 → {@link #PERSON}（最保守分支）。
         *
         * <p>🔴 方向是刻意选的：默认成 {@code person} 时，漏传 {@code objectKind} 的新调用方会
         * <b>直接被第五关拦住</b>——立刻可见、立刻能修。默认成 {@code pet} 则相反：漏传的
         * 在世自然人对象会一路放行到拟真生成，而这是无授权可依的那一类，事后无法补救。</p>
         *
         * <p>因此 P0 的宠物调用方<b>必须显式传 {@code pet}</b>（见 {@link ObjectContext#pet}），
         * 不能依赖默认值。默认值在这里是安全网，不是缺省用法。</p>
         */
        public static Kind parse(String raw) {
            if (raw == null) {
                return PERSON;
            }
            return switch (raw.trim().toLowerCase()) {
                case "pet" -> PET;
                case "person" -> PERSON;
                default -> {
                    log.warn("非法 objectKind({})，按最保守分支 person 处理", raw);
                    yield PERSON;
                }
            };
        }
    }

    /** 对象状态。 */
    public enum Status {
        /** 默认。🔴 无法排除对象在世，因此第五关按最保守分支处理。 */
        UNKNOWN,
        DECEASED,
        LIVING;

        /** 缺失 / 非法值 → 一律 {@link #UNKNOWN}（最保守分支）。 */
        public static Status parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            return switch (raw.trim().toLowerCase()) {
                case "deceased" -> DECEASED;
                case "living" -> LIVING;
                case "unknown" -> UNKNOWN;
                default -> {
                    log.warn("非法 objectStatus({})，按 unknown 处理", raw);
                    yield UNKNOWN;
                }
            };
        }
    }

    /** 文案位置。区分它是为了执行「通用位置不得传 deceased」。 */
    public enum Position {
        /** 引导语、空状态、入口文案、对外描述——面向所有人。 */
        GENERIC,
        /** 已经绑定到某个具体对象的位置（近况、来信、窗口页）。 */
        OBJECT_BOUND
    }

    /**
     * 规范化：执行「通用位置不得传 deceased」的降级。
     *
     * <p>🔴 降级时的告警必须记成<b>调用方违规</b>，而不是内容违规——把它记进内容侧，
     * 拦截率报表就会出现一批并不存在的"风险内容"，而真正的问题（某个入口传错了字段）
     * 反而被埋掉了。</p>
     */
    public ObjectContext normalized() {
        if (position == Position.GENERIC && status != Status.UNKNOWN) {
            // 埋点应落 caller_violation{field=objectStatus}，不落内容侧拦截
            log.warn("调用方违规：通用位置携带 objectStatus={}，已降级按 unknown 处理", status);
            return new ObjectContext(kind, Status.UNKNOWN, position);
        }
        return this;
    }

    /** 对象绑定位置的宠物场景（P0 唯一实际产生的组合）。 */
    public static ObjectContext pet(Status status) {
        return new ObjectContext(Kind.PET, status, Position.OBJECT_BOUND);
    }

    /** 通用位置：🔴 恒为 unknown。 */
    public static ObjectContext generic() {
        return new ObjectContext(Kind.PET, Status.UNKNOWN, Position.GENERIC);
    }
}
