package com.guonl.flow.excel;

import com.guonl.flow.config.FlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel解析器：将xlsx/xls首个Sheet解析为「表头 + 行数据」的JSON结构，
 * 作为 {{input.rows}} 变量送入大模型，超限行数与单元格内容截断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelParser {

    private final FlowProperties flowProperties;

    /**
     * @param data    Excel文件字节
     * @param maxRows 最大送入行数
     * @return 解析结果
     */
    public ExcelData parse(byte[] data, int maxRows) {
        DataFormatter formatter = new DataFormatter();
        int maxCellChars = flowProperties.getExcel().getMaxCellChars();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();
            int truncated = 0;
            for (Row row : sheet) {
                if (row == null || row.getLastCellNum() <= 0) {
                    continue;
                }
                if (row.getRowNum() == 0) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        String h = formatter.formatCellValue(row.getCell(c)).trim();
                        headers.add(h.isBlank() ? "列" + (c + 1) : h);
                    }
                    continue;
                }
                if (rows.size() >= maxRows) {
                    truncated++;
                    continue;
                }
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    if (cell != null && cell.getCellType() == CellType.STRING && value.length() > maxCellChars) {
                        value = value.substring(0, maxCellChars) + "…";
                    }
                    rowMap.put(headers.get(c), value);
                }
                rows.add(rowMap);
            }
            log.info("[Excel] parsed, sheet={}, headers={}, rows={}, truncated={}",
                sheet.getSheetName(), headers.size(), rows.size(), truncated);
            return new ExcelData(sheet.getSheetName(), headers, rows, truncated);
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel解析失败: " + e.getMessage(), e);
        }
    }

    /** 解析结果 */
    public record ExcelData(String sheetName, List<String> headers, List<Map<String, String>> rows, int truncated) {

        /**
         * 转为紧凑JSON文本（供提示词引用）
         */
        public String toJsonText() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"sheet\":\"").append(sheetName).append("\",\"totalRows\":").append(rows.size() + truncated);
            if (truncated > 0) {
                sb.append(",\"note\":\"仅展示前").append(rows.size()).append("行\"");
            }
            sb.append(",\"headers\":[");
            for (int i = 0; i < headers.size(); i++) {
                sb.append('"').append(escape(headers.get(i))).append(i < headers.size() - 1 ? "\"," : "\"");
            }
            sb.append("],\"rows\":[");
            for (int i = 0; i < rows.size(); i++) {
                sb.append('{');
                int c = 0;
                for (Map.Entry<String, String> e : rows.get(i).entrySet()) {
                    if (c++ > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
                }
                sb.append('}').append(i < rows.size() - 1 ? ',' : "");
            }
            return sb.append("]}").toString();
        }

        private String escape(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", " ");
        }
    }
}
