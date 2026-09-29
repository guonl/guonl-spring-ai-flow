package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.guonl.flow.core.model.OutputSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词组装公共工具：输出契约文本在节点处理器与 Playground 即席执行之间复用。
 */
public final class PromptKit {

    /** 默认系统提示词（节点未配置时的兜底角色设定） */
    public static final String DEFAULT_SYSTEM_PROMPT =
        "你是企业业务流程中的数据处理引擎，严谨、准确、只输出被要求的内容，不输出任何多余解释。";

    private PromptKit() {
    }

    /** 组装输出要求段落（JSON字段契约 / 纯文本） */
    public static String buildOutputRequirement(OutputSpec spec) {
        if (spec == null || !spec.isJson()) {
            return "【输出要求】直接输出结果内容，不要附加解释、前缀或代码块标记。";
        }
        StringBuilder sb = new StringBuilder("【输出要求】仅输出一个JSON对象，不要输出任何其他文字、解释或代码块标记。字段定义如下：\n");
        if (spec.getFields() == null || spec.getFields().isEmpty()) {
            sb.append("- 根据处理指令自行设计合理的JSON结构\n");
        } else {
            for (OutputSpec.Field field : spec.getFields()) {
                if (field.getName() == null || field.getName().isBlank()) {
                    continue;
                }
                sb.append("- ").append(field.getName().trim()).append("：")
                    .append(field.getDesc() == null ? "" : field.getDesc().trim())
                    .append(field.isRequired() ? "（必填）" : "")
                    .append("\n");
            }
            // JSON骨架示例：帮助模型对齐输出结构（不以"- "开头，不影响mock的字段行解析）
            sb.append("按如下JSON结构输出：").append(buildSkeleton(spec)).append("\n");
        }
        return sb.toString();
    }

    /** 根据字段定义构建 JSON 骨架示例，帮助模型对齐输出结构 */
    public static String buildSkeleton(OutputSpec spec) {
        StringBuilder sb = new StringBuilder("{");
        if (spec.getFields() != null) {
            for (OutputSpec.Field field : spec.getFields()) {
                if (field.getName() == null || field.getName().isBlank()) {
                    continue;
                }
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append("\"").append(field.getName().trim()).append("\": ").append(sampleValue(field));
            }
        }
        return sb.append("}").toString();
    }

    /** 按字段名/描述启发式推断示例占位值 */
    private static String sampleValue(OutputSpec.Field field) {
        String name = field.getName() == null ? "" : field.getName().toLowerCase();
        String desc = field.getDesc() == null ? "" : field.getDesc().toLowerCase();
        String hint = name + " " + desc;
        if (hint.contains("数组") || hint.contains("列表") || hint.contains("array") || hint.contains("list")) {
            return "[...]";
        }
        if (hint.contains("数值") || hint.contains("数字") || hint.contains("金额") || hint.contains("数量")
            || hint.contains("score") || hint.contains("count") || hint.contains("number")) {
            return "0";
        }
        if (hint.contains("布尔") || hint.contains("是否") || hint.contains("boolean")) {
            return "true";
        }
        return "\"...\"";
    }

    /** 校验 JSON 输出是否满足必填字段，返回缺失或为空的必填字段名列表 */
    public static List<String> validateRequired(OutputSpec spec, JsonNode json) {
        List<String> missing = new ArrayList<>();
        if (spec == null || !spec.isJson() || spec.getFields() == null || json == null) {
            return missing;
        }
        for (OutputSpec.Field field : spec.getFields()) {
            if (field.getName() == null || field.getName().isBlank() || !field.isRequired()) {
                continue;
            }
            JsonNode v = json.get(field.getName().trim());
            if (v == null || v.isNull()
                || (v.isTextual() && v.asText().isBlank())
                || (v.isArray() && v.isEmpty())
                || (v.isObject() && v.isEmpty())) {
                missing.add(field.getName().trim());
            }
        }
        return missing;
    }

    /** 构建纠错重试提示词：附上一次的错误输出与失败原因，要求模型自纠 */
    public static String buildCorrectionPrompt(String lastOutput, String error) {
        return "【上一次输出不符合要求，请修正后重新输出】\n"
            + "失败原因：" + (error == null ? "未知" : error) + "\n"
            + "上一次输出：\n"
            + truncate(lastOutput, 2000) + "\n"
            + "请严格按照输出要求重新输出，不要重复之前的错误。";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…（已截断，共" + text.length() + "字符）";
    }
}
