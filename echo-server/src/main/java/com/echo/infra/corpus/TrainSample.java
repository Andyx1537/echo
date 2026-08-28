package com.echo.infra.corpus;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化训练样本（AI-CAPABILITIES §7 训练语料回流）。对齐 PG 训练语料表 {@code t_train_sample}。
 *
 * <p>合规红线（PIPL）：{@code accountId} 为<b>去标识后的 hash</b>（不落真实账号身份）；
 * 仅 {@code consent==true} 的样本才写入语料（门控见 {@link ITrainingCorpus} 调用方 EchoApi）。</p>
 *
 * <p>字段用 public 便于内存态直接读写，与 {@code com.echo.http.model.Models} 风格一致。</p>
 */
public final class TrainSample {

    /** 去标识后的账号标识（hash，不可还原为真实 accountId）。 */
    public String accountId;
    /** 宠物档案 ID。 */
    public String petId;
    /** 输入素材 resourceId 列表（原件在私有对象存储，语料只留引用）。 */
    public List<String> inputRefs = new ArrayList<>();
    /** 识别得到的种类（视觉模型输出）。 */
    public String detectedSpecies;
    /** 用户纠正后的种类（若与 detected 不同即为高质量纠偏信号）。 */
    public String correctedSpecies;
    /** 用户最终选定的定妆候选 ID。 */
    public String chosenCandidateId;
    /** 定妆重做次数（越高说明候选越不满意，负向信号）。 */
    public int redoCount;
    /** 性情词。 */
    public List<String> traits = new ArrayList<>();
    /** 回声/近况生成文本。 */
    public String echoText;
    /** 用户反馈信号：记得 / 献花 / 停留（remember|flower|stay），可空。 */
    public String feedback;
    /** 采集时间。 */
    public long createdAt;
    /** 训练用途同意标记（写入语料的前置门控，恒为 true）。 */
    public boolean consent;

    public TrainSample() {
    }
}
