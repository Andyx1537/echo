package com.aengine.template;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map;

public interface TemplateReader {
	int NAME_ROW = 1;
	int RULE_ROW = 2;
	int DESC_ROW = 3;
	int FLAG_ROW = 4;
	int FIELD_NAME_ROW = 5;
	int FIELD_TYPE_ROW = 6;

	Map<String, Template> parse(File file, Charset charset, int flag) throws Exception;
	
	public Map<String, TemplateMeta> parseToTemplateMeta(File file, Charset charset, int flag) throws Exception;
}
