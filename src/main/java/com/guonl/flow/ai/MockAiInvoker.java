package com.guonl.flow.ai;

import com.guonl.flow.ai.AiInvoker.InvokeRequest;
import com.guonl.flow.ai.AiInvoker.InvokeResult;
import com.guonl.flow.ai.AiInvoker.ToolCallTrace;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.tool.FlowTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock实现：不调用真实大模型，按提示词中的「字段定义」契约生成模拟JSON，
 * 或回显指令生成模拟文本，用于无模型密钥环境完整体验产品交互。
 * <p>工具调用节点：真实执行无副作用的内置工具（时间/计算）演示调用轨迹。</p>
 */
@Slf4j
public class MockAiInvoker implements AiInvoker {

    private static final Pattern FIELD_LINE = Pattern.compile("^[-*]\\s*([A-Za-z0-9_\\u4e00-\\u9fa5]+)\\s*[:：]");

    private final AiProperties aiProperties;

    private final FlowTools flowTools;

    public MockAiInvoker(AiProperties aiProperties, FlowTools flowTools) {
        this.aiProperties = aiProperties;
        this.flowTools = flowTools;
    }

    @Override
    public InvokeResult invoke(InvokeRequest request) {
        log.info("[AI-MOCK] invoke, biz={}", request.getBizKey());
        sleep(aiProperties.getMockDelayMillis());
        String route = mockRoute(request.getUserPrompt());
        String content;
        List<ToolCallTrace> trace = null;
        if (route != null) {
            content = "{\"route\":\"" + route + "\"}";
        } else if (request.isUseTools()) {
            trace = demoTools();
            content = mockToolAnswer(trace);
        } else {
            boolean hasFields = request.getUserPrompt() != null && request.getUserPrompt().contains("字段定义如下");
            content = hasFields ? mockJson(request.getUserPrompt()) : mockText(request);
        }
        // 流式增量回调：打字机效果逐片段推送（SSE）
        if (request.getOnDelta() != null && content != null && !content.isEmpty()) {
            for (int i = 0; i < content.length(); i += 6) {
                request.getOnDelta().accept(content.substring(i, Math.min(i + 6, content.length())));
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return InvokeResult.builder()
            .content(content)
            .model(hasMedia(request) ? "mock-vision" : "mock-text")
            .promptTokens(estimate(request.getUserPrompt()))
            .completionTokens(estimate(content))
            .totalTokens(estimate(request.getUserPrompt()) + estimate(content))
            .toolCalls(trace)
            .build();
    }

    /** 真实执行内置工具（时间+计算），生成调用轨迹 */
    private List<ToolCallTrace> demoTools() {
        List<ToolCallTrace> trace = new ArrayList<>();
        ToolContext ctx = new ToolContext(Map.of(FlowTools.TRACE_KEY, trace));
        flowTools.now(ctx);
        flowTools.calc("1+2*3", ctx);
        return trace;
    }

    /** 工具调用演示输出：基于轨迹生成模拟回答 */
    private String mockToolAnswer(List<ToolCallTrace> trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("已调用 ").append(trace.size()).append(" 个工具完成处理：\n");
        for (ToolCallTrace t : trace) {
            String result = t.result();
            if (result.length() > 60) {
                result = result.substring(0, 60) + "…";
            }
            sb.append("- ").append(t.name()).append("(").append(t.arguments()).append(") → ").append(result).append('\n');
        }
        sb.append("\n基于以上工具结果：当前系统时间为")
            .append(trace.isEmpty() ? "（无）" : trace.get(0).result())
            .append("，表达式 1+2*3 的计算结果为")
            .append(trace.size() > 1 ? trace.get(1).result() : "（无）")
            .append("。以上为mock模式的工具调用演示。");
        return sb.toString();
    }

    /** 路由请求：从「候选分支如下」段提取第一个分支名，模拟模型路由到首个分支 */
    private String mockRoute(String prompt) {
        if (prompt == null || !prompt.contains("候选分支如下")) {
            return null;
        }
        for (String line : prompt.substring(prompt.indexOf("候选分支如下")).split("\n")) {
            String t = line.trim();
            if (t.startsWith("- ") && t.length() > 2) {
                String label = t.substring(2);
                int c = label.indexOf('：');
                if (c < 0) c = label.indexOf(':');
                return (c >= 0 ? label.substring(0, c) : label).trim();
            }
        }
        return null;
    }

    /** 流式：先模拟生成，再分块回调，模拟打字机效果 */
    @Override
    public void stream(InvokeRequest request, Consumer<String> onChunk) {
        InvokeResult result = invoke(request);
        String content = result.getContent() == null ? "" : result.getContent();
        for (int i = 0; i < content.length(); i += 6) {
            onChunk.accept(content.substring(i, Math.min(content.length(), i + 6)));
            sleep(25);
        }
    }

    private boolean hasMedia(InvokeRequest request) {
        return request.getMedias() != null && !request.getMedias().isEmpty();
    }

    private String mockJson(String prompt) {
        String section = prompt.substring(prompt.indexOf("字段定义如下") + "字段定义如下".length());
        List<String[]> fields = new ArrayList<>();
        for (String line : section.split("\n")) {
            Matcher m = FIELD_LINE.matcher(line.trim());
            if (m.find()) {
                fields.add(new String[]{m.group(1), line.trim()});
            }
        }
        if (fields.isEmpty()) {
            return "{\"result\":\"mock output\"}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i)[0];
            sb.append('"').append(name).append("\":").append(mockValue(name));
            if (i < fields.size() - 1) {
                sb.append(',');
            }
        }
        return sb.append('}').toString();
    }

    /** 按字段名关键词生成有业务感的模拟值 */
    private String mockValue(String field) {
        String f = field.toLowerCase();
        if (f.contains("name") || f.contains("title") || f.contains("company")) {
            return "\"上海示例科技有限公司\"";
        }
        if (f.contains("tax") || f.contains("code") || f.contains("no")) {
            return "\"91310000MA1FL08T2A\"";
        }
        if (f.contains("address") || f.contains("addr")) {
            return "\"上海市浦东新区示例路100号\"";
        }
        if (f.contains("phone") || f.contains("mobile")) {
            return "\"13800001234\"";
        }
        if (f.contains("date") || f.contains("time")) {
            return "\"2026-09-25\"";
        }
        if (f.contains("score") || f.contains("count") || f.contains("num") || f.contains("amount") || f.contains("price")) {
            return "86";
        }
        if (f.contains("dimension")) {
            return "\"准确性:86；完整性:82；格式规范性:90\"";
        }
        if (f.contains("pass") || f.contains("valid") || f.contains("ok") || f.contains("risk")) {
            return "true";
        }
        if (f.contains("summary") || f.contains("desc") || f.contains("remark") || f.contains("reason")) {
            return "\"模拟输出：该记录要素齐全，符合业务规则，可以正常流转。\"";
        }
        return "\"模拟-" + field + "\"";
    }

    private String mockText(InvokeRequest request) {
        String prompt = request.getUserPrompt() == null ? "" : request.getUserPrompt();
        int idx = prompt.indexOf("【处理指令】");
        String instruction = idx >= 0 ? prompt.substring(idx).split("\n")[0] : prompt;
        if (instruction.length() > 120) {
            instruction = instruction.substring(0, 120);
        }
        return "（Mock模拟输出）已理解指令「" + instruction.replace("【处理指令】", "").trim() + "」。"
            + "当前为 mock 模式，配置 flow.ai.provider=llm 并提供模型密钥后将返回真实模型结果。";
    }

    private int estimate(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 2);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
