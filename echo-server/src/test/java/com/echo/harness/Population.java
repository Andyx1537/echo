package com.echo.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 画像库（EXP-BOTS §2.1）：从 JSON 资源加载全量画像，提供启用项与归一化权重。
 *
 * <p>默认从 classpath 资源 {@code harness/populations/default.json} 加载；也可从外部文件路径加载
 * （供 {@link ExpBotsHarness} main 传参覆盖）。仅用 gson，无第三方依赖。</p>
 */
public final class Population {

    /** 内置默认画像库资源路径（classpath）。 */
    public static final String DEFAULT_RESOURCE = "harness/populations/default.json";

    private static final Gson GSON = new GsonBuilder().create();

    private final String version;
    private final List<BotPersona> personas;

    private Population(String version, List<BotPersona> personas) {
        this.version = version;
        this.personas = List.copyOf(personas);
    }

    /** 从内置 classpath 资源加载默认画像库。 */
    public static Population loadDefault() {
        try (InputStream in = Population.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到画像库资源: " + DEFAULT_RESOURCE);
            }
            return fromReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("加载默认画像库失败", e);
        }
    }

    /** 从外部文件路径加载画像库（结构与 default.json 一致）。 */
    public static Population loadFromFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return fromReader(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("加载画像库文件失败: " + path, e);
        }
    }

    static Population fromReader(Reader reader) {
        Root root = GSON.fromJson(reader, Root.class);
        Objects.requireNonNull(root, "画像库 JSON 解析为空");
        List<BotPersona> list = root.personas == null ? List.of() : root.personas;
        return new Population(root.version, list);
    }

    /** 画像库版本。 */
    public String version() {
        return version;
    }

    /** 全量画像（含未启用预备项），只读。 */
    public List<BotPersona> all() {
        return personas;
    }

    /** 仅启用（计入大盘）的画像。 */
    public List<BotPersona> enabled() {
        List<BotPersona> out = new ArrayList<>();
        for (BotPersona p : personas) {
            if (p.enabled()) {
                out.add(p);
            }
        }
        return out;
    }

    /** 启用项原始权重之和。 */
    public double enabledWeightSum() {
        double sum = 0.0;
        for (BotPersona p : enabled()) {
            sum += p.weight();
        }
        return sum;
    }

    /**
     * 返回启用项，权重归一化到合计 1.0（保持其它字段不变）。
     *
     * <p>若启用项权重和为 0（异常配置），退化为等权。</p>
     */
    public List<BotPersona> enabledNormalized() {
        List<BotPersona> enabled = enabled();
        double sum = enabledWeightSum();
        List<BotPersona> out = new ArrayList<>(enabled.size());
        if (enabled.isEmpty()) {
            return out;
        }
        boolean uniform = sum <= 0.0;
        double uniformW = 1.0 / enabled.size();
        for (BotPersona p : enabled) {
            double w = uniform ? uniformW : p.weight() / sum;
            out.add(new BotPersona(p.id(), p.name(), w, p.enabled(), p.pOpen(), p.pets(),
                    p.tier(), p.sensitivity(), p.novelty(), p.baseChurn(), p.lowTemp(), p.grief(),
                    p.voiceProfile()));
        }
        return out;
    }

    /** gson 反序列化根对象。 */
    private static final class Root {
        @SerializedName("version")
        String version;
        @SerializedName("personas")
        List<BotPersona> personas;
    }
}
