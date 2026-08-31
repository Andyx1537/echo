package com.aengine.persistence.dialect;

import com.aengine.persistence.annotation.Index;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引描述，方言据此产出 {@code CREATE INDEX}。
 *
 * <p>比 {@code IndexMeta} 多两样：<b>局部条件</b>（{@code WHERE ...}）与
 * <b>列级排序</b>（{@code ("publishedAt" DESC)}）。这两样是 PG 独有，
 * 现有 {@code schema.sql} 里 76 个索引有 23 个用了局部条件、9 处用了 DESC。</p>
 *
 * <p>做成独立的描述对象而不是直接扩 {@code IndexMeta}，是为了让方言层
 * <b>不依赖注解元数据</b>——方言只管把描述翻译成 SQL，可以单独测。</p>
 */
public final class IndexSpec {

    /** 列 + 排序方向。方向为空表示按方言默认（升序）。 */
    public record Column(String name, Order order) {
        public Column(String name) {
            this(name, Order.NONE);
        }
    }

    public enum Order {
        NONE, ASC, DESC
    }

    private final String name;
    private final Index.IndexType type;
    private final List<Column> columns = new ArrayList<>();
    /** 局部索引条件，原样拼进 {@code WHERE}；为空表示全表索引。 */
    private String where = "";
    /** 索引方法（PG 的 {@code USING gin} / {@code ivfflat} 等）；为空用默认。 */
    private String using = "";

    public IndexSpec(String name, Index.IndexType type) {
        this.name = name;
        this.type = type;
    }

    public IndexSpec column(String col) {
        columns.add(new Column(col));
        return this;
    }

    public IndexSpec column(String col, Order order) {
        columns.add(new Column(col, order));
        return this;
    }

    public IndexSpec where(String condition) {
        this.where = condition == null ? "" : condition.trim();
        return this;
    }

    public IndexSpec using(String method) {
        this.using = method == null ? "" : method.trim();
        return this;
    }

    public String getName() {
        return name;
    }

    public Index.IndexType getType() {
        return type;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public String getWhere() {
        return where;
    }

    public String getUsing() {
        return using;
    }

    public boolean isPartial() {
        return !where.isEmpty();
    }

    public boolean hasOrder() {
        return columns.stream().anyMatch(c -> c.order() != Order.NONE);
    }
}
