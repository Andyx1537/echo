package com.aengine.template;


import com.aengine.util.GsonUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
class Template {

	final TemplateMeta meta;

	final List<Map<String, Object>> data = new ArrayList<>();

	Template(TemplateMeta meta) {
		this.meta = meta;
	}

	void readRow(Row row) {
		int size = meta.columns.size();
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < size; i++) {
			ColumnMeta columnMeta = meta.columns.get(i);
			Cell cell = row.getCell(columnMeta.cols);
			if (cell == null) {
				map.put(columnMeta.name, null);
			} else {
				Object obj = columnMeta.parseValue(cell);
				if (obj == null)
					throw new RuntimeException(
							meta.name + "," + row.getRowNum() + "行," + cell.getColumnIndex() + "列格式错误:" + cell);
				map.put(columnMeta.name, obj);
			}
		}
		data.add(map);
	}

	String getName() {
		return meta.name;
	}

	void validate(Map<String, Template> templates) {
		for (ColumnMeta columnMeta : meta.columns) {
			for (Map<String, Object> map : data) {
				Object value = map.get(columnMeta.name);
				if (columnMeta.type == TypeEnum.MAP) {
					Map<?, ?> m = (Map<?, ?>) value;
					List<Rule> keyRule = columnMeta.getKeyRule();
					List<Rule> valueRule = columnMeta.getValueRule();
					if (keyRule != null && m != null && m.size() > 0)
						for (Rule rule : keyRule)
							for (Object k : m.keySet())
								rule.validate(k, columnMeta, this, templates);
					if (valueRule != null && m != null && m.size() > 0)
						for (Rule rule : valueRule)
							for (Object v : m.values())
								rule.validate(v, columnMeta, this, templates);
				} else if (columnMeta.type == TypeEnum.LIST) {
					List<?> list = (List<?>) value;
					List<Rule> defaultRule = columnMeta.getDefaultRule();
					if (defaultRule != null && list != null && list.size() > 0)
						for (Rule rule : defaultRule)
							for (Object v : list)
								rule.validate(v, columnMeta, this, templates);
				} else {
					List<Rule> defaultRule = columnMeta.getDefaultRule();
					if (defaultRule != null)
						for (Rule rule : defaultRule)
							rule.validate(value, columnMeta, this, templates);
				}
			}
		}
	}

	/**
	 * 以配置为基准转换配置表
	 * @param clazz 转换类
	 * @return
	 * @throws Exception
	 */
	<T> List<T> transformByTemplate(Class<T> clazz) throws Exception {
		List<T> list = new ArrayList<>();
		for (Map<String, Object> map : data) {
			T obj = clazz.newInstance();
			for (String name : map.keySet()) {
				ColumnMeta columnMeta = meta.ref.get(name);
				int index = name.indexOf('.');
				if (index < 0) {
					Field field = clazz.getDeclaredField(name);
					field.setAccessible(true);
					if (columnMeta.type == TypeEnum.JSON)
						field.set(obj, GsonUtil.jsonToBean((String) map.get(name), field.getGenericType()));
					else
						field.set(obj, map.get(name));
				} else {
					String suffix = name.substring(index + 1);
					String prefix = name.substring(0, index);
					Field field = clazz.getDeclaredField(prefix);
					field.setAccessible(true);
					Class<?> cl = field.getType();
					Object so = field.get(obj);
					if (so == null) {
						so = cl.newInstance();
						field.set(obj, so);
					}
					Field sf = cl.getDeclaredField(suffix);
					sf.setAccessible(true);
					if (columnMeta.type == TypeEnum.JSON)
						sf.set(so, GsonUtil.jsonToBean((String) map.get(name), sf.getGenericType()));
					else
						sf.set(so, map.get(name));
				}
			}
			list.add(obj);
		}
		return list;
	}

	/**
	 * 以类为基准转换配置表
	 * @param clazz 转换类
	 * @return
	 * @throws Exception
	 */
	<T> List<T> transformByClass(Class<T> clazz) throws Exception {
		List<T> list = new ArrayList<>();
		for (Map<String, Object> map : data) {
			T obj = clazz.newInstance();
			for (Field field : clazz.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()))
					continue;
				if(field.getAnnotation(NotConfigure.class) != null)
					continue;
				field.setAccessible(true);
				String name = field.getName();
				if (field.getType().getClassLoader() == null || map.containsKey(name)) {
					ColumnMeta columnMeta = meta.ref.get(name);
					Object dataObj = map.get(name);
					if (columnMeta == null)
						throw new RuntimeException(
								"not find [ " + name + " ] column of the [ " + clazz.getSimpleName() + " ] table");
					if(dataObj == null)
						continue;
					if (columnMeta.type == TypeEnum.JSON)
						field.set(obj, GsonUtil.jsonToBean((String) dataObj, field.getGenericType()));
					else
						field.set(obj, dataObj);
				} else {
					Class<?> cl = field.getType();
					Object so = field.get(obj);
					if (so == null) {
						so = cl.newInstance();
						field.set(obj, so);
					}
					for (Field sf : cl.getDeclaredFields()) {
						String sname = name + "." + sf.getName();
						ColumnMeta columnMeta = meta.ref.get(sname);
						Object dataObj = map.get(sname);
						if (columnMeta == null)
							throw new RuntimeException(
									"not find [ " + sname + " ]column of the [ " + clazz.getSimpleName() + " ] table");
						if(dataObj == null)
							continue;
						sf.setAccessible(true);
						if (columnMeta.type == TypeEnum.JSON)
							sf.set(so, GsonUtil.jsonToBean((String) dataObj, sf.getGenericType()));
						else
							sf.set(so, dataObj);
					}
				}
			}
			list.add(obj);
		}
		return list;
	}
}
