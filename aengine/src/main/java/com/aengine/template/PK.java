package com.aengine.template;

import java.util.*;

/**
 */
public class PK extends Rule{

    private boolean checked = false;

    private List<String> union = new ArrayList<>();
    PK(String str) {
        int begin = str.indexOf('(');
        int end = str.indexOf(')');
        if (begin < 0 || end < 0)
            return;
        str = str.substring(begin + 1, end);
        String[] temp = str.split(",");
        for (String s : temp)
            if (!"".equals(s))
                union.add(s);
    }

    @Override
    protected void validate(Object value, ColumnMeta selfMeta, Template selfTemplate, Map<String, Template> templates){
        if (checked)
            return;
        if (union.size() == 0) {
            Set<Object> set = new HashSet<>();
            for (Map<String, Object> map : selfTemplate.data) {
                Object v = map.get(selfMeta.name);
                if (v == null)
                    throw new RuntimeException("主键不能为空");
                if (!set.contains(v))
                    set.add(v);
                else
                    throw new RuntimeException("主键\"" + v + "\"重复");
            }
        } else {
            Set<String> set = new HashSet<>();
            for (Map<String, Object> map : selfTemplate.data) {
                Object v = map.get(selfMeta.name);
                StringBuilder sb = new StringBuilder();
                sb.append(v).append("_");
                for (String name : union) {
                    v = map.get(name);
                    sb.append(v).append("_");
                }
                if (!set.add(sb.toString()))
                    throw new RuntimeException("主键" + sb.toString() + "重复");
            }
        }
        checked = true;
    }
}
