package com.guonl.flow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型输出JSON提取器。
 * 兼容模型输出携带 Markdown 代码块、前后说明文字等情况，
 * 按括号配对定位最外层JSON对象后解析。
 */
@Slf4j
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonExtractor() {
    }

    /**
     * 从模型输出中提取JSON对象，解析失败返回null
     */
    public static JsonNode extract(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return null;
        }
        String text = modelOutput.trim();
        // 剥离markdown代码块
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd > 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("[JsonExtractor] parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取并格式化为标准JSON字符串（失败时返回null）
     */
    public static String extractPretty(String modelOutput) {
        JsonNode node = extract(modelOutput);
        if (node == null) {
            return null;
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
