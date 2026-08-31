package com.aengine.template;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * template 模块单元测试：TypeEnum 类型解析、ExcelReader 用 POI 生成的 xlsx 读回校验。
 */
class TemplateTest {

    @Test
    void typeEnumParsing() {
        assertThat(TypeEnum.parse("int")).isEqualTo(TypeEnum.INT);
        assertThat(TypeEnum.parse("long")).isEqualTo(TypeEnum.LONG);
        assertThat(TypeEnum.parse("string")).isEqualTo(TypeEnum.STRING);
        assertThat(TypeEnum.parse("json")).isEqualTo(TypeEnum.JSON);
        assertThat(TypeEnum.parse("list<int>")).isEqualTo(TypeEnum.LIST);
        assertThat(TypeEnum.parse("map<int,string>")).isEqualTo(TypeEnum.MAP);
        assertThat(TypeEnum.parse("unknown")).isNull();
    }

    @Test
    void typeEnumSubTypeParsing() {
        assertThat(TypeEnum.parseListSubType("list<int>")).isEqualTo(TypeEnum.INT);
        TypeEnum[] kv = TypeEnum.parseMapSubType("map<int,string>");
        assertThat(kv).containsExactly(TypeEnum.INT, TypeEnum.STRING);
    }

    @Test
    void excelReaderRoundTrip(@TempDir Path dir) throws Exception {
        File file = dir.resolve("hero.xlsx").toFile();
        writeWorkbook(file);

        ExcelReader reader = new ExcelReader();
        Map<String, Template> templates = reader.parse(file, StandardCharsets.UTF_8, ColumnMeta.FLAG_SERVER);

        assertThat(templates).containsKey("HeroConfig");
        Template template = templates.get("HeroConfig");
        assertThat(template.data).hasSize(2);
        assertThat(template.data.get(0).get("id")).isEqualTo(1);
        assertThat(template.data.get(0).get("name")).isEqualTo("Alice");
        assertThat(template.data.get(1).get("id")).isEqualTo(2);
        assertThat(template.data.get(1).get("name")).isEqualTo("Bob");
    }

    private void writeWorkbook(File file) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("#hero");

            // NAME_ROW (idx 0): 配置表名
            sheet.createRow(0).createCell(0).setCellValue("HeroConfig");
            // RULE_ROW (idx 1): 留空
            sheet.createRow(1);
            // DESC_ROW (idx 2): 描述
            Row desc = sheet.createRow(2);
            desc.createCell(0).setCellValue("编号");
            desc.createCell(1).setCellValue("名字");
            // FLAG_ROW (idx 3): 标记（3 = client|server）
            Row flag = sheet.createRow(3);
            flag.createCell(0).setCellValue(3);
            flag.createCell(1).setCellValue(3);
            // FIELD_NAME_ROW (idx 4): 字段名
            Row name = sheet.createRow(4);
            name.createCell(0).setCellValue("id");
            name.createCell(1).setCellValue("name");
            // FIELD_TYPE_ROW (idx 5): 字段类型
            Row type = sheet.createRow(5);
            type.createCell(0).setCellValue("int");
            type.createCell(1).setCellValue("string");
            // 数据行
            Row d1 = sheet.createRow(6);
            d1.createCell(0).setCellValue(1);
            d1.createCell(1).setCellValue("Alice");
            Row d2 = sheet.createRow(7);
            d2.createCell(0).setCellValue(2);
            d2.createCell(1).setCellValue("Bob");

            try (FileOutputStream out = new FileOutputStream(file)) {
                book.write(out);
            }
        }
    }
}
