package com.aengine.template;

/**
 */
public enum TypeEnum {
    INT("int", "Integer"),
    LONG("long", "Long"),
    BOOLEAN("boolean", "Boolean"),
    DOUBLE("double", "Double"),
    STRING("String", "String"),
    LIST("List", "List"),
    MAP("Map", "Map"),
    JSON("Json", "Json"),
    DATE("Date", "String"),
	TABLE("table","String"),
    BYTE("byte","Byte");
	
	private final String typeStr;
	
	private final String objTypeStr;
	
	private TypeEnum(String value, String objTypeStr) {
		this.typeStr = value;
		this.objTypeStr = objTypeStr;
	}

    public static TypeEnum parse(String type) {
        if ("int".equals(type))
            return INT;
        if ("long".equals(type))
            return LONG;
        if ("bool".equals(type))
            return BOOLEAN;
        if ("double".equals(type))
            return DOUBLE;
        if ("string".equals(type))
            return STRING;
        if ("json".equals(type) || "data".equals(type))
            return JSON;
        if ("date".equals(type))
            return DATE;
        if (type.startsWith("list"))
            return LIST;
        if (type.startsWith("map"))
            return MAP;
        if (type.startsWith("table"))
            return TABLE;
        if(type.startsWith("byte"))
            return BYTE;

        return null;
    }

    public static TypeEnum parseListSubType(String type) {
        int start = type.indexOf('<');
        int end = type.indexOf('>');
        return parse(type.substring(start + 1, end));
    }

    public static TypeEnum[] parseMapSubType(String type) {
        int start = type.indexOf('<');
        int end = type.indexOf('>');
        type = type.substring(start + 1, end);
        String[] temp = type.split(",");
        TypeEnum[] tp = new TypeEnum[2];
        tp[0] = parse(temp[0]);
        tp[1] = parse(temp[1]);
        return tp;
    }

	public String getTypeStr() {
		return typeStr;
	}

	public String getObjTypeStr() {
		return objTypeStr;
	}
    
}
