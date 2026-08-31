package com.aengine.persistence;


import com.aengine.persistence.annotation.Column;
import com.aengine.persistence.annotation.Pk;
import com.aengine.util.GsonUtil;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 列结构
 *
 */
public class ColumnMeta {

    private String name;

    private String comment;

    private Field field;

    private boolean pk;

    private boolean auto;

    private boolean notNull;

    private boolean readOnly;

    private TypeEnum type;

    private int length;

    private String charset;

    private String defaultValue;

    public static ColumnMeta parse(Field field) throws MetaException {
        Column column = field.getAnnotation(Column.class);
        if (column == null)
            return null;
        TypeEnum type = TypeEnum.getTypeOfField(field);
        if (type == null)
            throw new MetaException(field.getType() + "is not supported as a column data type:" + field.getName());
        ColumnMeta meta = new ColumnMeta();
        meta.name = column.name();
        meta.field = field;
        meta.length = column.length();
        meta.comment = column.comment();
        meta.readOnly = column.readOnly();
        meta.type = type;
        if (column.notNull()) {
            if (TypeEnum.hasDefaultValue(type)) {
                meta.notNull = true;
            }
        }
        if ("____".equals(column.defaultValue()))
            meta.defaultValue = getDefaultValue(type);
        else
            meta.defaultValue = column.defaultValue();
        meta.charset = column.charset().trim();
        Pk pk = field.getAnnotation(Pk.class);
        if (pk != null) {
            meta.pk = true;
            meta.auto = pk.auto();
        }
        return meta;
    }

    private static Pattern pattern = Pattern.compile("\\(\\d*\\)");

    public static ColumnMeta parse(Map<String, Object> map) {
        ColumnMeta column = new ColumnMeta();
        column.pk = "PRI".equalsIgnoreCase(map.get("Key").toString());
        Object comment = map.get("Comment");
        column.comment = comment == null ? "NULL" : comment.toString();
        column.name = map.get("Field").toString();
        column.notNull = "NO".equalsIgnoreCase(map.get("Null").toString()) ? true : false;
        if (column.notNull) {
            if (map.get("Default") != null) {
                column.defaultValue = map.get("Default").toString();
            }
        }
        String type = map.get("Type").toString();
        column.type = TypeEnum.parseFromTypeString(type);
        if (column.type == TypeEnum.VARCHAR
                || column.type == TypeEnum.CHAR
                || column.type == TypeEnum.TEXT) {
            Matcher matcher = pattern.matcher(type);
            if (matcher.find()) {
                String l = matcher.group();
                column.length = Integer.parseInt(l.substring(1, l.length() - 1));
            }
        }
        return column;
    }

    public Field getField() {
        return field;
    }

    public String getName() {
        return name;
    }

    public boolean isPk() {
        return pk;
    }

    public boolean isAuto() {
        return auto;
    }

    public String getComment() {
        return comment;
    }

    public boolean isNotNull() {
        return notNull;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public TypeEnum getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public String getCharset() {
        return charset;
    }


    /**
     * 为实体中对应的列赋值
     *
     * @param t   实体
     * @param obj 值
     */
    public void cast(AbstractEntity t, Object obj) {
        Class<?> type = field.getType();
        try {
            field.setAccessible(true);
            if (type == long.class || type == Long.class) {
                Number num = (Number) obj;
                field.setLong(t, num.longValue());
            } else if (type == int.class || type == Integer.class) {
                Number num = (Number) obj;
                field.setInt(t, num.intValue());
            } else if (type == short.class || type == Short.class) {
                Number num = (Number) obj;
                field.setShort(t, num.shortValue());
            } else if (type == byte.class || type == Byte.class) {
                Number num = (Number) obj;
                field.setByte(t, num.byteValue());
            } else if (type == boolean.class || type == Boolean.class) {
                if (obj.getClass() == Integer.class || obj.getClass() == int.class) {
                    int v = (int) obj;
                    field.setBoolean(t, v == 1);
                } else if (obj.getClass() == Boolean.class) {
                    field.setBoolean(t, (boolean) obj);
                } else {
                    throw new RuntimeException();
                }
            } else if (type == double.class || type == Double.class) {
                Number num = (Number) obj;
                field.setDouble(t, num.doubleValue());
            } else if (type == float.class || type == Float.class) {
                Number num = (Number) obj;
                field.setFloat(t, num.floatValue());
            } else if (type == String.class) {
                field.set(t, obj);
            } else if (type == Date.class) {
                field.set(t, obj);
            } else if (type.isArray() && type.getComponentType() == byte.class) {
                field.set(t, obj);
            } else {
                String json = obj == null ? null : obj.toString();
                Object target = GsonUtil.jsonToBean(json, field.getGenericType());
                if (target == null && field.getAnnotation(Column.class).notNull()) {
                    if (field.getType().isArray() && field.getType().getComponentType() == byte.class) {
                        json = "[]";
                    } else {
                        json = Set.class.isAssignableFrom(type) || List.class.isAssignableFrom(type) ? "[]" : "{}";
                    }
                    target = GsonUtil.jsonToBean(json, field.getGenericType());
                }
                field.set(t, target);
            }
        } catch (Exception e) {
            throw new RuntimeException("field :" + field.getName() + ", obj = " + obj, e);
        }
    }

    public String getValue(Object obj) {
        if (field.getType() == String.class) {
            return "\"" + obj.toString() + "\"";
        } else if (field.getType() == Date.class) {
            SimpleDateFormat sd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return "\"" + sd.format((Date) obj) + "\"";
        } else if (field.getType() == Boolean.class || field.getType() == boolean.class) {
            return String.valueOf((Boolean) obj ? 1 : 0);
        } else {
            return obj.toString();
        }
    }

    public Object getFieldValue(AbstractEntity entity) {
        try {
            return field.get(entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getTypeString() {
        switch (type) {
            case CHAR:
            case VARCHAR:
                if (length >= 255) {
                    return "TEXT";
                }
                return type.toString() + "(" + length + ")";
            case TEXT://不知道这个规模是否合适.尽量不要存太多就好
            case OBJ:
                return "TEXT";
            default:
                return type.toString();
        }
    }

    private static String getDefaultValue(TypeEnum type) {
        switch (type) {
            case TINYINT:
            case SMALLINT:
            case INT:
            case BIGINT:
            case DOUBLE:
                return "0";
            case CHAR:
            case VARCHAR:
            case OBJ:
                return "";
            case DATETIME:
                return "1970-01-01 08:00:00";
            default:
                return "";
        }
    }

    public String buildCreateSQL() {
        StringBuilder sb = new StringBuilder();
        sb.append("`").append(name).append("`");
        sb.append(" ").append(getTypeString());
        if (!"".equals(charset)) {
            sb.append(" CHARACTER SET '").append(charset).append("'");
        }
        if (notNull) {
            sb.append(" NOT NULL");
            if (!pk) {
                sb.append(" DEFAULT '").append(defaultValue).append("'");
            }
        }
        if (pk && auto) {
            sb.append(" AUTO_INCREMENT");
        }
        sb.append(" COMMENT '").append(comment).append("'");
        return sb.toString();
    }

    public boolean isSame(ColumnMeta meta) {
        if (meta.notNull != notNull) {
            return false;
        }
        if (comment == null && meta.comment != null) {
            return false;
        }
        if (!comment.equals(meta.comment)) {
            return false;
        }
        if (!pk && notNull && !meta.defaultValue.equals(defaultValue)) {
            return false;
        }
        if (type == meta.type) {
            if (type == TypeEnum.CHAR || type == TypeEnum.VARCHAR) {
                if (length != meta.length) {
                    return false;
                }
            }
        } else {
            if (!(type == TypeEnum.TEXT && meta.type == TypeEnum.OBJ))
                return false;
        }
        return true;
    }
}
