package com.guonl.flow.core.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词变量解析器。
 * <p>变量协议（占位符 {@code {{path}}}）：
 * <pre>
 *   {{input}}              流程输入文本
 *   {{input.json}}         同 {{input}}（语义别名，输入常为JSON）
 *   {{input.rows}}         Excel解析数据
 *   {{nodeId}}             上游节点原始输出
 *   {{nodeId.output}}      同上
 *   {{nodeId.json}}        上游节点JSON（格式化）
 *   {{nodeId.json.字段.子字段}}  上游JSON字段值（解析时预展开全部叶子路径）
 * </pre>
 * 未在提示词中使用任何变量时，由处理器将上下文数据自动追加为参考信息，保证零模板成本。</p>
 */
public final class VarResolver {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\u4e00-\\u9fa5.]+)\\s*}}");

    private VarResolver() {
    }

    /** 构建根级变量表：流程输入 */
    public static Map<String, Object> baseVars(FlowInput input) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (input != null) {
            String text = input.getText() == null ? "" : input.getText().trim();
            vars.put("input", text);
            vars.put("input.json", text);
            if (input.hasExcel()) {
                vars.put("input.rows", input.getExcel().toJsonText());
            }
        }
        return vars;
    }

    /**
     * 注册上游节点输出变量：id、id.output、id.json 及 id.json 下全部叶子路径。
     */
    public static void registerNode(Map<String, Object> vars, String nodeId, NodeOutput output) {
        if (output == null) {
            return;
        }
        vars.put(nodeId, output.getText() == null ? "" : output.getText());
        vars.put(nodeId + ".output", output.getText() == null ? "" : output.getText());
        JsonNode json = output.getJson();
        if (json != null && !json.isMissingNode()) {
            vars.put(nodeId + ".json", json.toString());
            flattenJson(vars, nodeId + ".json", json);
        }
    }

    /** 递归展开JSON叶子路径 */
    private static void flattenJson(Map<String, Object> vars, String prefix, JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> flattenJson(vars, prefix + "." + e.getKey(), e.getValue()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenJson(vars, prefix + "." + (i + 1), node.get(i));
            }
        } else if (node.isValueNode()) {
            JsonNode value = node;
            vars.put(prefix, value.isTextual() ? value.asText() : value.toString());
        } else {
            vars.put(prefix, node.toString());
        }
    }

    /**
     * 解析模板中的所有占位符。
     */
    public static ResolveResult resolve(String template, Map<String, Object> vars) {
        if (template == null) {
            return new ResolveResult("", false, 0);
        }
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        boolean used = false;
        int missing = 0;
        while (m.find()) {
            String path = m.group(1);
            Object value = lookup(vars, path);
            used = true;
            String replacement = value != null ? value.toString() : "";
            if (value == null) {
                missing++;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return new ResolveResult(sb.toString(), used, missing);
    }

    /** 是否包含任何变量占位符 */
    public static boolean containsVar(String template) {
        return template != null && VAR.matcher(template).find();
    }

    /** 精确匹配优先，其次点路径下钻（Map/JsonNode） */
    private static Object lookup(Map<String, Object> vars, String path) {
        if (vars.containsKey(path)) {
            return vars.get(path);
        }
        String[] parts = path.split("\\.");
        Object current = vars.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else if (current instanceof JsonNode node) {
                current = node.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    /** 解析结果：resolved=解析后文本, usedVars=是否含变量, missingVars=未命中数 */
    public record ResolveResult(String resolved, boolean usedVars, int missingVars) {
    }
}
