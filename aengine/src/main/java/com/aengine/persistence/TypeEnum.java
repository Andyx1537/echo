package com.aengine.persistence;


import com.aengine.persistence.annotation.Column;

import java.lang.reflect.Field;
import java.util.Date;

/**
 * 数据类型
 * 考虑到上线项目出现的代码回滚，导致数据库添加的字段在旧代码中无法赋值，
 * 如果字段设置为NOT NULL，会导致数据插入失败，因此所有NOT NULL的字段都必须设置默认值，没有设置的将会填充缺省默认值
 * TEXT，BLOB无法设置NOT NULL，这两个类型无法设置默认值。
 * 
 */
public enum TypeEnum {
    TINYINT,
    SMALLINT,
    INT,
    BIGINT,
    DOUBLE,
    CHAR,
    VARCHAR,
    TEXT,
    DATETIME,
    OBJ,
	BLOB,
    ;

    public static TypeEnum parseFromTypeString(String type) {
        type = type.toUpperCase();
        for (TypeEnum tp : values()) {
            if (type.startsWith(tp.name())) {
                return tp;
            }
        }
        return null;
    }

    public static TypeEnum getTypeOfField(Field field) {
        Class<?> type = field.getType();
        if (type == Long.TYPE || type == Long.class)
            return BIGINT;
        if (type == Integer.TYPE || type == Integer.class)
            return INT;
        if (type == Short.TYPE || type == Short.class)
            return SMALLINT;
        if (type == Byte.TYPE || type == Byte.class)
            return TINYINT;
        if (type == Boolean.TYPE || type == Boolean.class)
            return TINYINT;
        if (type == Double.TYPE || type == Double.class || type == Float.TYPE || type == Float.class)
            return DOUBLE;
        if (type == String.class) {
        	Column column = field.getAnnotation(Column.class);
        	if (column.length() < 255) {
        		if (column.immutable())
        			return CHAR;
        		else
        			return VARCHAR;
        	} else 
        		return TEXT;
        }
        if(type == Character.class){
            return CHAR;
        }
        if (type == Date.class)
            return DATETIME;
        if (type.isEnum())
            return TINYINT;
        else if (type.isArray()) {
            if (type.getComponentType() == byte.class) {
                return BLOB;
            } else {
            	return OBJ;
            }
        } else
            return OBJ;
    }

    public static boolean hasDefaultValue(TypeEnum type) {
    	switch (type) {
		    case TEXT:
		    case BLOB:
		    case OBJ:
		    	return false;
	    }
	    return true;
    }
}
