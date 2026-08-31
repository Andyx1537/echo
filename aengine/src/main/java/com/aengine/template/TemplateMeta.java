package com.aengine.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
public class TemplateMeta {

    final List<ColumnMeta> columns = new ArrayList<>();

    final Map<String, ColumnMeta> ref = new HashMap<>();

    final String name;

    TemplateMeta(String name) {
        this.name = name;
    }

    void addColumnMeta(ColumnMeta meta) {
        columns.add(meta);
        ref.put(meta.name, meta);
    }
}
