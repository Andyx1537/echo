package com.aengine.template;

import java.util.Map;

/**
 */
public class FK extends Rule{

    final String tName;

    final String cName;

    FK(String str) {
        int begin = str.indexOf('(');
        int end = str.indexOf(')');
        int split = str.indexOf(".", begin + 1);
        tName = str.substring(begin + 1, split);
        cName = str.substring(split + 1, end);
    }

    @Override
    protected void validate(Object value, ColumnMeta selfMeta, Template selfTemplate, Map<String, Template> templates){
    	if (value instanceof Number && ((Number)value).doubleValue() == 0)
    		return;
        if (value == null)
            throw new RuntimeException("待检查的外键关联"+selfTemplate.getName()+"."+selfMeta.name+"不能为空值");
        Template fTable = templates.get(tName);
        if (fTable == null)
            throw new RuntimeException(tName + "不存在，无法检查外键");
        for (Map<String, Object> map : fTable.data) {
            if (!map.containsKey(cName))
                throw new RuntimeException(cName + "在" + tName + "中不存在，无法检查外键");
            Object obj = map.get(cName);
            if (value.equals(obj))
                return;
        }
        throw new RuntimeException("在"+tName+"."+cName+"上检查外键"+value.toString()+"失败");
    }
}
