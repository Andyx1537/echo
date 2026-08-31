package com.aengine.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

/**
 */
public class ExcelReader implements TemplateReader {

    private static final Logger log = LoggerFactory.getLogger(ExcelReader.class);

    DecimalFormat df4Num = new DecimalFormat("0");
    DecimalFormat df4Str = new DecimalFormat("#");

    private TemplateMeta parseToTemplateMeta(Sheet sheet, int metaFlag) {
        int startRow = sheet.getFirstRowNum();
        Row row = sheet.getRow(startRow + NAME_ROW - 1);
        Cell cell = row.getCell(0);
        if (cell == null) {
            throw new RuntimeException("配置表名字不能为空" + sheet.getSheetName() + "行：" + cell.getRow() + "列：" + cell.getColumnIndex());
        }
        String name = cell.getStringCellValue();
        if (name == null) {
            throw new RuntimeException("配置表名字不能为空" + sheet.getSheetName());
        }
        TemplateMeta templateMeta = new TemplateMeta(name);

        Row nameRow = sheet.getRow(startRow + FIELD_NAME_ROW - 1);
        if (nameRow == null) {
            throw new RuntimeException("配置表表头格式不完整，缺少列名定义行" + sheet.getSheetName());
        }
        Row typeRow = sheet.getRow(startRow + FIELD_TYPE_ROW - 1);
        if (typeRow == null) {
            throw new RuntimeException("配置表表头格式不完整，缺少列类型定义行" + sheet.getSheetName());
        }
        Row ruleRow = sheet.getRow(startRow + RULE_ROW - 1);
        if (ruleRow == null) {
            throw new RuntimeException("配置表表头格式不完整，缺少规则定义行" + sheet.getSheetName());
        }
        Row flagRow = sheet.getRow(startRow + FLAG_ROW - 1);
        if (flagRow == null) {
            throw new RuntimeException("配置表表头格式不完整，缺少标记定义行" + sheet.getSheetName());
        }
        Row descRow = sheet.getRow(startRow + DESC_ROW - 1);
        if (descRow == null) {
            throw new RuntimeException("配置表表头格式不完整，缺少描述定义行" + sheet.getSheetName());
        }
        for (int i = nameRow.getFirstCellNum(); i < nameRow.getLastCellNum(); i++) {
            Cell flagCell = flagRow.getCell(i);
            if (flagCell == null) {
                throw new RuntimeException("列标记不能为空，第" + (i + 1) + "列" + sheet.getSheetName());
            }
            int flag = -1;
            try {

                flag = (Double.valueOf(flagCell.toString())).intValue();
                if ((flag & metaFlag) <= 0) {
                    continue;
                }
            } catch (Exception ex) {
                System.err.println("flagCell.toString() " + flagCell.toString());
                ex.printStackTrace();
                throw new RuntimeException("列标记数据格式错误，第" + (i + 1) + "列" + sheet.getSheetName());
            }

            ColumnMeta meta = new ColumnMeta(i);
            meta.parseFlag(flag);
            Cell nameCell = nameRow.getCell(i);
            if (nameCell == null) {
                throw new RuntimeException("列名不能为空，第" + (i + 1) + "列" + sheet.getSheetName());
            }
            String fieldName = nameCell.getStringCellValue();
            if (fieldName == null) {
                throw new RuntimeException("列名不能为空，第" + (i + 1) + "列" + sheet.getSheetName());
            }
            meta.parseName(fieldName);
            Cell typeCell = typeRow.getCell(i);
            if (typeCell == null) {
                throw new RuntimeException("列类型不能为空，第" + (i + 1) + "列" + sheet.getSheetName());
            }
            String type = typeCell.getStringCellValue();
            meta.parseType(type);
            Cell ruleCell = ruleRow.getCell(i);
            if (ruleCell != null) {
                String rule = ruleCell.getStringCellValue();
                meta.parseRule(rule);
            }
            Cell descCell = descRow.getCell(i);
            if (descCell == null) {
                throw new RuntimeException("列名不能为空，第" + (i + 1) + "列" + sheet.getSheetName());
            }
            meta.parseDesc(descCell.getStringCellValue());
            templateMeta.addColumnMeta(meta);
        }
        return templateMeta;
    }

    @Override
    public Map<String, Template> parse(File file, Charset charset, int flag) throws Exception {
        Workbook book = null;
        try {
            book = WorkbookFactory.create(file, null, true);
            int sheets = book.getNumberOfSheets();
            Map<String, Template> map = new HashMap<>();
            for (int i = 0; i < sheets; i++) {

                String sheetName = book.getSheetName(i).trim();
                if (sheetName.indexOf("#") != 0) {
                    continue;
                }

                Sheet sheet = book.getSheetAt(i);
                TemplateMeta meta = parseToTemplateMeta(sheet, flag);
                if (meta.columns.isEmpty()) {
                    continue;
                }
                Template template = map.containsKey(meta.name) ? map.get(meta.name) : new Template(meta);

                int startRow = sheet.getFirstRowNum();
                for (int j = startRow + FIELD_TYPE_ROW; j <= sheet.getLastRowNum(); j++) {
                    Row row = sheet.getRow(j);
                    if (row == null || row.getCell(0) == null || row.getCell(0).getCellType() == CellType.BLANK) {
                        log.warn("table:" + template.getName() + " sheet name:" + sheetName + " 第" + (j + 1) + "行为空数据");
                        continue;
                    }

                    Map<String, Object> data = new HashMap<>();
                    for (ColumnMeta columnMeta : meta.columns) {
                        Cell cell = row.getCell(columnMeta.cols);
                        if (cell == null) {
                            data.put(columnMeta.name, null);
                        } else {
                            Object temp = null;
                            switch (cell.getCellType()) {
                                case NUMERIC:
                                    switch (columnMeta.type) {

                                        case INT:
                                        case LONG:
                                            temp = df4Num.format(cell.getNumericCellValue());
                                            break;
                                        case LIST:
                                        case STRING:
                                            Double num = cell.getNumericCellValue();
                                            if (num.longValue() == num.doubleValue())
                                                temp = num.longValue();
                                            else
                                                temp = num;
                                            break;
                                        default:
                                            temp = cell.getNumericCellValue();
                                            break;
                                    }
                                    break;
                                case STRING:
                                    temp = cell.getStringCellValue();
                                    break;
                                case BOOLEAN:
                                    temp = cell.getBooleanCellValue();
                                    break;
                                case FORMULA:
                                    try {
                                        temp = cell.getRichStringCellValue().getString();
                                    } catch (Exception e) {
                                        try {
                                            temp = cell.getNumericCellValue();
                                            if (columnMeta.type.equals(TypeEnum.STRING)
                                                    || columnMeta.type.equals(TypeEnum.LIST)) {
                                                Double num = cell.getNumericCellValue();
                                                if (num.longValue() == num.doubleValue())
                                                    temp = num.longValue();
                                                else
                                                    temp = num;
                                            }
                                        } catch (Exception e1) {
                                            throw new RuntimeException(meta.name + "," + (row.getRowNum() + 1) + "行,"
                                                    + (cell.getColumnIndex() + 1) + "列格式错误");
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                            if (temp == null) {
                                data.put(columnMeta.name, null);
                            } else {
                                Object obj = columnMeta.parseValue(temp);
                                if (obj == null) {
                                    throw new RuntimeException(meta.name + "," + (row.getRowNum() + 1) + "行,"
                                            + (cell.getColumnIndex() + 1) + "列格式错误");
                                }
                                data.put(columnMeta.name, obj);
                            }
                        }
                    }
                    template.data.add(data);
                }
                if (template != null) {
                    map.put(template.getName(), template);
                }
            }
            return map;
        } finally {
            if (book != null)
                book.close();
        }
    }

    @Override
    public Map<String, TemplateMeta> parseToTemplateMeta(File file, Charset charset, int flag) throws Exception {
        Workbook book = null;
        try {
            book = WorkbookFactory.create(file, null, true);
            int sheets = book.getNumberOfSheets();
            Map<String, TemplateMeta> map = new HashMap<>();
            for (int i = 0; i < sheets; i++) {
                String sheetName = book.getSheetName(i).trim();
                if (sheetName.indexOf("#") != 0) {
                    continue;
                }
                Sheet sheet = book.getSheetAt(i);
                TemplateMeta meta = parseToTemplateMeta(sheet, flag);
                if (meta.columns.isEmpty()) {
                    continue;
                }
                map.put(meta.name, meta);
            }
            return map;
        } finally {
            if (book != null)
                book.close();
        }
    }

}
