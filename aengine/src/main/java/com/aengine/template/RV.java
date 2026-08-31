package com.aengine.template;

import java.util.Map;

/**
 */
public class RV extends Rule{

    final int min;
    final int max;

    RV(String str) {
        int begin = str.indexOf('(');
        int end = str.indexOf(')');
        str = str.substring(begin + 1, end);
        String[] temp = str.split(",");
        min = Integer.parseInt(temp[0]);
        max = Integer.parseInt(temp[1]);
    }

    @Override
    protected void validate(Object value, ColumnMeta selfMeta, Template selfTemplate, Map<String, Template> templates){
        if (value == null)
            throw new RuntimeException("待检查的范围约束值不能为空");
        if (!(value instanceof Integer))
            throw new RuntimeException("待检查的范围约束值必须是int");
        int v = (int)value;
        if (v > max || v < min)
            throw new RuntimeException("待检查的范围约束值"+v+"不在范围（"+min+","+max+"）内");

    }
}
