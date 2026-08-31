package com.aengine.template;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 */
public class UNIQUE extends Rule{

	private Set<Object> check = new HashSet<>();

    UNIQUE(String str) {}

    @Override
    protected void validate(Object value, ColumnMeta selfMeta, Template selfTemplate, Map<String, Template> templates) {
    	if (check.contains(value))
		    throw new RuntimeException("唯一索引"+selfMeta.name+":"+value+"重复");
    	check.add(value);
    }
}
