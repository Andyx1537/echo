package com.aengine.persistence;

import com.aengine.persistence.annotation.Index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 索引结构
 *
 */
public class IndexMeta {

    private String name;

    private final List<String> columns = new ArrayList<>();

    private Index.IndexType type;

    public IndexMeta() {
    }

    public IndexMeta(String name, Index.IndexType type, String... columns) {
        this.name = name;
        this.type = type;
        Collections.addAll(this.columns, columns);
    }

    public static IndexMeta parse(Index index) throws MetaException {
        if (index.columns().length == 0)
            throw new MetaException("columns of index:" + index.name() + " is empty");
        IndexMeta meta = new IndexMeta();
        if ("".equals(index.name().trim()))
            throw new MetaException("index name can not be empty");
        meta.name = index.name();
        meta.type = index.type();
        Collections.addAll(meta.columns, index.columns());
        return meta;
    }

    public String getName() {
        return name;
    }

    public List<String> getColumns() {
        return columns;
    }

    public Index.IndexType getType() {
        return type;
    }

    protected void setName(String name) {
        this.name = name;
    }

    protected void setType(Index.IndexType type) {
        this.type = type;
    }

    public String buildCreateSQL() {
        StringBuilder sb = new StringBuilder();
        switch (type) {
        case UNIQUE:
            sb.append("UNIQUE ");
            break;
        case FULLTEXT:
            sb.append("FULLTEXT ");
            break;
        default:
            break;
        }
        sb.append("INDEX `").append(name).append("` (");
        for (int j = 0; j < columns.size(); j++) {
            sb.append("`").append(columns.get(j)).append("` ASC");
            if (j < columns.size() - 1)
                sb.append(",");
        }
        sb.append(")");
        return sb.toString();
    }

    public boolean isSame(IndexMeta meta) {
        if (type != meta.type)
            return false;
        if (columns.size() != meta.columns.size())
            return false;
        for (int i = 0; i < columns.size(); i++) {
            if (!columns.get(i).equals(meta.columns.get(i)))
                return false;
        }
        return true;
    }
}
