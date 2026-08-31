package com.aengine.template;


import com.aengine.util.GsonUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 导出配置表
 * 
 */
public class ExportTemplate {

	private final static String HELP_TIP = "java -jar xxx.jar -i [input directory] -o [output directory]";
	private final static String CONFIG_BASE_NAME = "ConfigBase.ts";

	public static void main(String[] args) {
		if (args.length != 4 && args.length != 6) {
			System.out.println(HELP_TIP);
			return;
		}
		Map<String, String> params = new HashMap<>();
		for (int i = 0; i < args.length; i = i + 2) {
			params.put(args[i], args[i + 1]);
		}
		if (!params.containsKey("-i") || !params.containsKey("-o")) {
			System.out.println(HELP_TIP);
			return;
		}

		// 读取配置表
		System.out.println("start export template");
		Map<String, Template> templates = TemplateUtil.parse(new File(params.get("-i")), "utf-8",
				ColumnMeta.FLAG_CLIENT);
		if (templates == null)
			return;

		// 生成配置表文件
		File outDir = new File(params.get("-o"));
		exportTemplate(outDir, templates);

		// 导出数据表格式
		exportHeads(outDir, templates);

		// 压缩配置表
		Set<String> set = new HashSet<>();
		set.add("LanguageCfg.txt");
		ZipFileUtil.filePathToZip(outDir.getPath(), outDir.getPath(), "config", set);
		ZipFileUtil.filesToZip(outDir.getPath(), outDir.getPath(), "language", set);
		System.out.println("end export template");

		if (!params.containsKey("-s")) {
			return;
		}
		System.out.println("start export server template");
		Map<String, Template> serverTemplates = TemplateUtil.parse(new File(params.get("-i")), "utf-8", ColumnMeta.FLAG_SERVER);
		if (serverTemplates == null)
			return;
		System.out.println("start check server template");
		checkTemplates(templates);
		System.out.println("end check server template");
		// 生成配置表文件
		//这里生成的是 .cfg 文件，单独用于某类型配置用的配置结构。暂时不放开
//		File serOutDir = new File(params.get("-s"));
//		Map<String, CFGTemplate> cfgTemplates = ExcelConver.parse(new File(params.get("-i")), "utf-8", ColumnMeta.FLAG_SERVER);
//		ExcelConver.exportTemplate(serOutDir, cfgTemplates);
		
		System.out.println("end export server template");
	}
	
	/**
	 * 验证数据的合法性
	 * @param templates
	 */
	public static void checkTemplates(Map<String, Template> map) {
		for (Template template : map.values()) {
			try {
				template.validate(map);
			} catch (Exception e) {
				System.err.println("校验" + template.getName() + "失败");
				e.printStackTrace();
				return;
			}
		}
	}

	/**
	 * 导出配置文件
	 * 
	 * @param outDir 导出目录
	 * @param templates 配置文件
	 */
	private static void exportTemplate(File outDir, Map<String, Template> templates) {
		for (Template template : templates.values()) {
			String name = template.getName().replace("Config", "Cfg");
			System.out.println("template: " + name + ".txt");
			PrintWriter pw = null;
			try {
				File outFile = new File(outDir, name + ".txt");
				if (!outFile.exists())
					outFile.createNewFile();
				pw = new PrintWriter(outFile, "utf-8");
				pw.print("{\n");
				pw.print("\t\"name\":" + "\"" + name + "\",\n");
				pw.print("\t\"data\":[\n");
				boolean flag = false;
				for (Map<String, Object> datas : template.data) {
					StringBuilder sb = new StringBuilder();
					for (ColumnMeta meta : template.meta.columns) {
						if (sb.length() > 0)
							sb.append(',');
						else
							sb.append(flag ? ",\n\t\t[" : "\t\t[");
						sb.append(exportData(meta, datas.get(meta.name)));
						flag = true;
					}
					sb.append("]");
					pw.print(sb.toString());
				}
				pw.print("\n\t]\n");
				pw.print("}\n");
			} catch (IOException e) {
				System.err.println("error template:" + GsonUtil.beanToJson(template));
				e.printStackTrace();
			} finally {
				if (pw != null)
					pw.close();
			}
		}

	}

	/**
	 * 导出文件头
	 * 
	 * @param outDir 输出目录
	 * @param templates 配置表
	 * @return
	 */
	private static void exportHeads(File outDir, Map<String, Template> templates) {
		PrintWriter pw = null;
		try {
			File outFile = new File(outDir, CONFIG_BASE_NAME);
			if (!outFile.exists())
				outFile.createNewFile();
			pw = new PrintWriter(outFile, "utf-8");
			pw.println("module com_main {\n");
			for (Template template : templates.values()) {
				pw.print("\texport class " + template.getName().replace("Config", "Cfg") + " extends  ConfigInfo{\n");
				StringBuilder sb = new StringBuilder();
				for (ColumnMeta meta : template.meta.columns) {
					String name = meta.name.replace('.', '_');
					pw.print("\t\tpublic " + name + ":" + exportType(meta, meta.type) + ";\n");
					if (sb.length() > 0)
						sb.append(",");
					sb.append("'");
					sb.append(name);
					sb.append("'");
				}
				pw.print("\n\t\tpublic attrs(){\n");
				pw.print("\t\t\treturn [" + sb.toString() + "];\n");
				pw.print("\t\t}\n");
				pw.print("\t}\n\n");
			}
			pw.print("}");
		} catch (IOException e) {
			System.err.println("export heads error!");
			e.printStackTrace();
		} finally {
			if (pw != null)
				pw.close();
		}
	}

	/**
	 * 导出数据
	 * 
	 * @param meta 格式
	 * @param object 数据
	 * @return
	 */
	private static String exportData(ColumnMeta meta, Object value) {
		switch (meta.type) {
		case LONG:
		case INT:
		case DOUBLE:
			return value == null || "".equals(value) ? "0" : value.toString();
		case BOOLEAN:
			return value == null || "".equals(value) ? "false" : value.toString();
		case JSON:
			return value == null || "".equals(value) ? "{}" : value.toString();
		case DATE:
		case TABLE:
		case STRING:
			return value == null || "".equals(value) ? "\"\"" : '"' + value.toString() + '"';
		case MAP:
			return value == null || "".equals(value) ? "{}" : GsonUtil.beanToJson(value);
		case LIST:
			return value == null || "".equals(value) ? "[]" : GsonUtil.beanToJson(value);
		default:
			return null;
		}
	}

	/**
	 * 导出数据类型
	 * 
	 * @param meta 格式
	 * @return
	 */
	private static String exportType(ColumnMeta meta, TypeEnum type) {
		switch (type) {
		case LONG:
		case INT:
		case DOUBLE:
			return "number";
		case TABLE:
		case DATE:
			return "string";
		case BOOLEAN:
			return "boolean";
		case JSON:
			return "any";
		case STRING:
			return "string";
		case MAP:
			return "{}";
		case LIST:
			return "Array<" + exportType(meta, meta.subType.get("")) + ">";
		default:
			return null;
		}
	}

}
