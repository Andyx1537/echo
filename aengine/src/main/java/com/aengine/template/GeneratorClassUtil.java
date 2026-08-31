package com.aengine.template;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 	生成class类
 */
public class GeneratorClassUtil {

	private final static String HELP_TIP = "java -cp xxx.jar com.aengine.template.GeneratorClassUtil -i [input directory] -p [package directory] -o [output directory]";

	/**
	 * 生成class类
	 * 
	 * @param configPath 配置路径
	 * @param packagePath 生成包路径
	 * @param outDir 输出目录
	 * @throws Exception 
	 */
	public static void generatorClass(String configPath, String packagePath, String outDir) throws Exception {
		File configFile = new File(configPath);
		File outFile = new File(outDir);
		if (!configFile.exists())
			throw new FileNotFoundException("configDir");
		if (!outFile.exists())
			outFile.mkdirs();
		if (configFile.isFile()) {
			genFileToMeta(configFile, packagePath, outFile);
		} else if (configFile.isDirectory()) {
			for (File f : configFile.listFiles()) {
				if (f.isFile())
					genFileToMeta(f, packagePath, outFile);
			}
		}
	}

	/**
	 * file生成meta
	 * @param configFile 配置文件
	 * @param packagePath 包路径
	 * @param outFile 输出目录
	 */
	private static void genFileToMeta(File configFile, String packagePath, File outFile) {
//		TemplateReader csvReader = new CSVReader();
		TemplateReader excelReader = new ExcelReader();
		if (configFile.getName().startsWith("~$"))
			return;
		String fileName = configFile.getName();
		try {
			Map<String, TemplateMeta> templateMetas = null;
			if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
				templateMetas = excelReader.parseToTemplateMeta(configFile, null, ColumnMeta.FLAG_SERVER);
			}
//			else if (fileName.endsWith(".csv")) {
//				templateMetas = csvReader.parseToTemplateMeta(configFile, null, ColumnMeta.FLAG_SERVER);
//			}
			if (templateMetas == null)
				return;
			for (TemplateMeta templateMeta : templateMetas.values()) {
				genMetaToClass(templateMeta, packagePath, outFile);
			}
		} catch (Exception e) {
			System.out.println("generator class error:" + fileName);
			e.printStackTrace();
		}
	}

	/**
	 * 通过meta生成class
	 * 
	 * @param meta
	 * @param packagePath
	 * @param outFile
	 * @throws Exception
	 */
	private static void genMetaToClass(TemplateMeta meta, String packagePath, File outFile) throws Exception {
		PrintWriter pw = null;
		try {
			System.out.println("generator class: " + meta.name + ".java");
			pw = new PrintWriter(outFile.getPath() + "/" + meta.name + ".java", "utf-8");
			StringBuffer fieldSb = new StringBuffer();
			StringBuffer methodSb = new StringBuffer();
			Set<TypeEnum> typeSet = new HashSet<>();
			Set<String> objFields = new HashSet<>();
			for (ColumnMeta columnMeta : meta.columns) {
				int index = columnMeta.name.indexOf(".");
				if (index > 0) {
					objFields.add(columnMeta.name.substring(0, index));
					continue;
				}
				String name = columnMeta.name;
				TypeEnum type = columnMeta.type;
				String typeStr = type.getTypeStr();
				if (type == TypeEnum.LIST)
					typeStr += "<" + columnMeta.subType.get("").getObjTypeStr() + ">";
				if (type == TypeEnum.MAP)
					typeStr += "<" + columnMeta.subType.get("key").getObjTypeStr() + ","
							+ columnMeta.subType.get("value").getObjTypeStr() + ">";
				String desc = columnMeta.desc.replaceAll("\n|\r", " ");
				fieldSb.append("\tprivate " + typeStr + " " + name + "; // " + desc + "\n");
				methodSb.append("\n\tpublic " + typeStr + " get");
				methodSb.append(Character.toUpperCase(name.charAt(0)) + name.substring(1) + "() {\n\t\treturn ");
				methodSb.append(name + ";\n\t}\n");
				typeSet.add(type);
			}
			for (String field : objFields) {
				String type = Character.toUpperCase(field.charAt(0)) + field.substring(1);
				fieldSb.append("\tprivate " + type + " " + field + ";\n");
				methodSb.append("\n\tpublic " + type + " get");
				methodSb.append(type + "() {\n\t\treturn ");
				methodSb.append(field + ";\n\t}\n");
			}
			ColumnMeta pk = meta.columns.get(0);
			pw.println("package " + packagePath + ";\n");
			pw.println("import java.util.Map;");
			pw.println("import java.util.HashMap;");
			pw.println("import java.util.List;");
			if (typeSet.contains(TypeEnum.DATE))
				pw.println("import java.util.Date;");
			pw.println();
			pw.println("import com.aengine.template.LoadTemplate;");
			pw.println("\npublic class " + meta.name + "{\n");
			pw.print(fieldSb.toString());
			pw.print(methodSb.toString());
			pw.println("\n\tprivate static Map<" + pk.type.getObjTypeStr() + ", " + meta.name + "> cache;");
			pw.println("\n\tpublic static " + meta.name + " getConfig(" + pk.type.getTypeStr() + " " + pk.name + "){");
			pw.println("\t\treturn cache.get(" + pk.name + ");\n\t}\n");
			pw.println("\t@LoadTemplate\n\tpublic static void init(List<" + meta.name + "> list) {");
			pw.println("\t\tMap<" + pk.type.getObjTypeStr() + ", " + meta.name + "> m1 = new HashMap<>();");
			pw.println("\t\tfor (" + meta.name + " config : list) {");
			pw.println("\t\t\tm1.put(config." + pk.name + ", config);");
			pw.println("\t\t}");
			pw.println("\t\tcache = m1;");
			pw.println("\t}\n");
			pw.println("}\n");
		} finally {
			if (pw != null)
				pw.close();
		}
	}

	public static void main(String[] args) {
		if (args.length != 6) {
			System.out.println(HELP_TIP);
			return;
		}
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < args.length; i += 2) {
			map.put(args[i], args[i + 1]);
		}
		try {
			generatorClass(map.get("-i"), map.get("-p"), map.get("-o"));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
