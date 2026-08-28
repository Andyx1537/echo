package com.echo.http.safety;

import com.echo.http.ApiException;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成入口闸（{@code SPEC-security §4.1} 前置闸 + {@code §4.3.3} 处置段 + {@code DECISIONS A7}）。
 *
 * <p>🔴 <b>拟真类生成必须在入口拒绝，不能只落在输出侧。</b>图像与音频的越界无法靠词表判定，
 * 等生成完再拦，一份没有合法授权基础的拟真产物<b>已经存在了</b>——哪怕不投递，它也已经落盘、
 * 已经花过钱。所以判定点前移到任务创建之前。</p>
 *
 * <h2>A7：不予呈现（隐藏），不是报错</h2>
 *
 * <p>{@code DECISIONS A7} 定死了产品形态：{@code person + living/unknown} 时生成类入口
 * <b>不予呈现</b>，🔴 <b>不得报错</b>。两条理由：报错的原因在产品层无法解释（要解释就得跟用户讲
 * 人格权与授权基础）；且冷硬报错与「界面与信息设计要宽泛、易于接受」的产品原则直接相悖。</p>
 *
 * <p>这对后端的要求是：<b>接口语义必须支持「隐藏」，而不是只会返回一个错误码</b>。
 * 因此本类提供两个方法，前端应当用第一个：</p>
 *
 * <ul>
 *   <li>{@link #capabilities} —— 能力查询。前端据此<b>直接不渲染</b>入口。这是主路径。</li>
 *   <li>{@link #requireAllowed} —— 兜底。用户绕过 UI 直接打接口时才会走到，
 *       返回温柔说明而非冷硬报错。</li>
 * </ul>
 *
 * <p>🔴 <b>顺序不能倒过来</b>：只有 {@code requireAllowed} 而没有 {@code capabilities}，
 * 前端就只能「先渲染入口、等用户点了再报错」——那正是 {@code A7} 否定的形态。</p>
 */
@Slf4j
public final class GenerationEntryGate {

    /** 拟真类生成任务（{@code §4.1} 前置闸列举的五种）。 */
    public enum TaskKind {
        /** 定妆。 */
        PORTRAIT_STYLING("styling", true),
        /** 换装。 */
        OUTFIT_CHANGE("outfit", true),
        /** 动态化。 */
        ANIMATION("animation", true),
        /** 唤醒视频。 */
        AWAKEN_VIDEO("awaken_video", true),
        /** 声音克隆。 */
        VOICE_CLONE("voice_clone", true),
        /** 近况（文本类，以对象口吻）。 */
        ECHO_TEXT("echo", false),
        /** 来信（文本类，以对象口吻）。 */
        LETTER("letter", false);

        private final String wireName;
        /** 是否属"拟真类"——拟真类必须在入口拦，文本类可在输出侧拦。 */
        private final boolean simulation;

        TaskKind(String wireName, boolean simulation) {
            this.wireName = wireName;
            this.simulation = simulation;
        }

        public String wireName() {
            return wireName;
        }

        public boolean simulation() {
            return simulation;
        }
    }

    private final SafetyMetrics metrics;

    public GenerationEntryGate(SafetyMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 该对象是否允许创建该类生成任务。
     *
     * <p>判据（{@code §4.3.1} 联动矩阵）：{@code objectKind=person} 且
     * {@code objectStatus ∈ {living, unknown}} → 拟真类与"ta 口吻"文本类<b>一律不允许</b>。
     * 🟢 宠物不受此限。</p>
     */
    public boolean isAllowed(TaskKind kind, ObjectContext ctx) {
        ObjectContext c = ctx == null ? ObjectContext.generic() : ctx.normalized();
        if (c.kind() != ObjectContext.Kind.PERSON) {
            return true; // 🟢 宠物无人格权，不存在授权主体问题
        }
        // 逝者场景的授权来自近亲属（民法典第 994 条），是一条可走通的授权路径
        if (c.status() == ObjectContext.Status.DECEASED) {
            return true;
        }
        // person + living/unknown：无合法授权基础。
        // 🔴 这不是"风险偏高"——风险偏高可以靠加水印、加标识、加免责声明来降；
        //    无授权可依加什么都不成立。因此它不是可用工程手段换取的权衡项。
        return false;
    }

    /**
     * 能力查询（{@code A7} 的后端支撑）。
     *
     * <p>返回每一类生成任务在该对象上是否可用。前端据此<b>隐藏</b>不可用的入口，
     * 而不是渲染出来等用户点了再报错。</p>
     *
     * @return {@code {taskKind -> available}}，顺序稳定
     */
    public Map<String, Boolean> capabilities(ObjectContext ctx) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (TaskKind k : TaskKind.values()) {
            out.put(k.wireName(), isAllowed(k, ctx));
        }
        return out;
    }

    /**
     * 兜底校验：不允许时抛温柔说明。
     *
     * <p>🔴 调用点必须在<b>任务创建之前</b>——{@code §8 TC-SEC-12} 第 ② 项判的是任务表里
     * 有没有这条记录，若实现成先建任务再拒，用例必然失败。</p>
     *
     * <p>⚠️ 这是<b>兜底而非主路径</b>：正常情况下前端已按 {@link #capabilities} 隐藏了入口，
     * 走到这里说明用户绕过了 UI。所以文案是温柔说明，不是冷硬报错。</p>
     */
    public void requireAllowed(TaskKind kind, ObjectContext ctx) {
        if (isAllowed(kind, ctx)) {
            return;
        }
        // 🔴 先记数再抛：这一笔计入"被拒绝创建的任务数"，**不进输出侧拦截率**
        metrics.recordEntryRejection(kind.wireName(), "person_living_or_unknown");
        throw new ApiException(ApiException.RULE_FORBIDDEN,
                "这一类生成我们暂时还没有准备好，先看看别的方式吧。",
                "generation not available for objectKind=person with living/unknown status");
    }
}
