package com.aengine.template;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 */
public class ENUM extends Rule{

    private Set<Integer> values = new HashSet<>();

    ENUM(String str) {
        int begin = str.indexOf('(');
        int end = str.indexOf(')');
        str = str.substring(begin + 1, end);
        String[] temp = str.split(",");
        for (String s : temp) {
            if (s.length() > 0)
                values.add(Integer.parseInt(s));
        }
    }

    @Override
    protected void validate(Object value, ColumnMeta selfMeta, Template selfTemplate, Map<String, Template> templates){
        if (value == null)
            throw new RuntimeException("待检查的枚举约束值不能为空");
        if (!(value instanceof Integer))
            throw new RuntimeException("待检查的枚举约束值必须是int");
        int v = (int)value;
        if (!values.contains(v))
            throw new RuntimeException("待检查的枚举约束值"+v+"不在范围内");
    }
}
