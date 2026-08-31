package com.aengine.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 */
public abstract class Rule {

    private static Pattern pk = Pattern.compile("PK\\(([\\w\\d_]+,?)+\\)");
	private static Pattern pk2 = Pattern.compile("PK");
    private static Pattern fk = Pattern.compile("FK\\([\\w\\d_]+\\.[\\w\\d_]+\\)");
    private static Pattern unique = Pattern.compile("UNIQUE");
    private static Pattern rv = Pattern.compile("RV\\(\\d+,\\d+\\)");
    private static Pattern _enum = Pattern.compile("ENUM\\((\\d+,?)+\\)");

    public static List<Rule> parse(String str){
        String copy = str;
        List<Rule> list = new ArrayList<>();
        {
            Matcher matcher = pk.matcher(str);
            if (matcher.find()) {
                String s = matcher.group();
                list.add(new PK(s));
                copy = copy.replace(s, "");
            } else {
            	Matcher m = pk2.matcher(str);
            	if (m.find()) {
		            String s = m.group();
		            list.add(new PK(s));
		            copy = copy.replace(s, "");
	            }
            }
        }
        {
            Matcher matcher = fk.matcher(str);
            while (matcher.find()) {
                String s = matcher.group();
                list.add(new FK(s));
                copy = copy.replace(s, "");
            }
        }
        {
            Matcher matcher = unique.matcher(str);
            while (matcher.find()) {
                String s = matcher.group();
                list.add(new UNIQUE(s));
                copy = copy.replace(s, "");
            }
        }
        {
            Matcher matcher = rv.matcher(str);
            while (matcher.find()) {
                String s = matcher.group();
                list.add(new RV(s));
                copy = copy.replace(s, "");
            }
        }
        {
            Matcher matcher = _enum.matcher(str);
            while (matcher.find()) {
                String s = matcher.group();
                list.add(new ENUM(s));
                copy = copy.replace(s, "");
            }
        }
        return list;
    }

    protected abstract void validate(Object value, ColumnMeta selfMeta, Template selfTemplate, Map<String, Template> templates);
}
