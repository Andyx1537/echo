package com.aengine.template;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.*;

/**
 */
public class TemplateUtil {

	private static final Logger log = LoggerFactory.getLogger(TemplateUtil.class);

	private static String getExtension(File file) {
		String name = file.getName();
		int index = name.lastIndexOf(".");
		if (index <= 0) {
			return "";
		}
		return name.substring(index + 1).toLowerCase().trim();
	}

	public static Map<String, Template> parse(List<File> files, String charset, int flag) {
//		TemplateReader csvReader = new CSVReader();
		TemplateReader excelReader = new ExcelReader();
		Map<String, Template> map = new HashMap<>();
		for (File file : files) {
			try {
				if (file.isDirectory() || file.isHidden() || !file.canRead()) {
					continue;
				}
				// temp file
				if (file.getName().startsWith("~$")) {
					continue;
				}
				String extension = getExtension(file);
				Map<String, Template> readTemplates = null;
				if ("xlsx".equals(extension) || "xls".equals(extension)) {
					readTemplates = excelReader.parse(file, null, flag);
				}
//				else if ("csv".equals(extension)) {
//					readTemplates = csvReader.parse(file, Charset.forName(charset), flag);
//				} else if ("cfg".equals(extension)) {
//					CFGReader cfgReader = new CFGReader();
//					readTemplates = cfgReader.parse(file, Charset.forName(charset), flag);
//				}
				if (readTemplates != null) {
					readTemplates.forEach((k, v) -> {
						if (map.containsKey(k)) {
							Template template = map.get(k);
							template.data.addAll(v.data);
						} else {
							map.put(k, v);
						}
					});
				}
			} catch (Exception e) {
				log.error("文件:" + file.getName(), e);
				return null;
			}
		}

		for (Template template : map.values()) {
			try {
				template.validate(map);
			} catch (Exception e) {
				log.error("校验" + template.getName() + "失败", e);
				return null;
			}
		}
		return map;
	}

	public static Map<String, Template> parse(File directory, String charset, int flag) {
		Collection<File> files = FileUtils.listFiles(directory, new String[] { "xls", "xlsx", "csv", "cfg"}, true);
		return parse(new ArrayList<>(files), charset, flag);
	}

	/**
	 * 针对确实的信息加载确认的配置
	 *
	 * @param file
	 * @param clazz
	 * @return
	 * @throws Exception
	 */
	public static List<?> loadTemplateByFixedInfo(File file, Class<?> clazz) throws Exception {
		TemplateReader excelReader = new ExcelReader();
		Map<String, Template> templates = excelReader.parse(file, Charset.forName("utf-8"), ColumnMeta.FLAG_SERVER);
		if (templates == null) {
			throw new InstantiationException("读取配置文件失败");
		}
		List<?> list = null;
		for (Map.Entry<String, Template> entry : templates.entrySet()) {
			Template template = entry.getValue();
			if (!template.getName().equals(clazz.getSimpleName())) {
				continue;
			}

			try {
				list = template.transformByClass(clazz);
			} catch (Exception e) {
				log.error("", e);

			}
		}
		return list;

	}

	public static void loadTemplate(List<File> files, String charset, String packageURI) throws InstantiationException {
		Map<String, Template> templates = parse(files, charset, ColumnMeta.FLAG_SERVER);
		if (templates == null) {
			throw new InstantiationException("读取配置文件失败");
		}
		loadTemplate(templates, packageURI);
	}

	private static void loadTemplate(Map<String, Template> templates, String packageURI) {
		 Map<Method,List<?>> delayMethod = new HashMap<>(); 
		for (Template template : templates.values()) {
			String className = packageURI + "." + template.getName();
			Class<?> clazz;
			try {
				clazz = Class.forName(className);
			} catch (ClassNotFoundException e) {
				log.error(className, e);
				continue;
			}
			List<?> list;
			try {
				list = template.transformByClass(clazz);
			} catch (Exception e) {
				log.error(className, e);
				continue;
			}
			for (Method method : clazz.getDeclaredMethods()) {
				if (!Modifier.isStatic(method.getModifiers())) {
					continue;
				}
				//延迟加载模板的定义存在歧义。会出现嵌套配置及对于大文件处理的实现
				//暂时不开放此实现方案
//				if(method.getAnnotation(DelayLoadTemplate.class)!=null) {
//					delayMethod.put(method, list);
//					continue;
//				}
				if (method.getAnnotation(LoadTemplate.class) == null) {
					continue;
				}
				if (method.getParameterCount() != 1) {
					continue;
				}
				if (method.getParameterTypes()[0] != List.class) {
					continue;
				}
				boolean accessible = method.isAccessible();
				if (!accessible) {
					method.setAccessible(true);
				}
				try {
					method.invoke(null, list);
				} catch (Exception e) {
					log.error("Can't access method " + method.getName() + " of class "
							+ method.getDeclaringClass().getName(), e);
				}
				if (!accessible) {
					method.setAccessible(false);
				}
			}
		}
		
//		delayMethod.forEach((k,v) ->{
//			try {
//				k.invoke(null, v);
//			} catch (Exception e) {
//				log.error("Can't access method " + k.getName() + " of class "
//						+ k.getDeclaringClass().getName(), e);
//			}
//		});
		
		
	}

	public static void loadTemplate(File dir, String charset, String packageURI) throws InstantiationException {
		Map<String, Template> templates = parse(dir, charset, ColumnMeta.FLAG_SERVER);
		if (templates == null) {
			throw new InstantiationException("读取配置文件失败");
		}
		loadTemplate(templates, packageURI);
	}

	public static Map<Class<?>, List<?>> loadAllTemplate(File dir, String charset, String packageURI) throws InstantiationException{
		Map<String, Template> templates = parse(dir, charset, ColumnMeta.FLAG_SERVER);
		if (templates == null) {
			throw new InstantiationException("读取配置文件失败");
		}
		Map<Class<?>, List<?>> total = new HashMap<>();
		for (Template template : templates.values()) {
			String className = packageURI + "." + template.getName();
			Class<?> clazz;
			try {
				clazz = Class.forName(className);
			} catch (ClassNotFoundException e) {
				log.error(className, e);
				continue;
			}
			List<?> list;
			try {
				list = template.transformByClass(clazz);
				total.put(clazz, list);
			} catch (Exception e) {
				log.error(className, e);
			}
		}
		return total;
	}
}
