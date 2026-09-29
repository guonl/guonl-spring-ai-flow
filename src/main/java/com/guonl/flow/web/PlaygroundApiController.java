package com.guonl.flow.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.core.engine.FlowEngine;
import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.engine.NodeExecution;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import com.guonl.flow.core.model.OutputSpec;
import com.guonl.flow.core.node.PromptKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playground 即席体验 API：
 * <ul>
 *   <li>POST /api/playground —— 单节点流程完整执行（复用节点处理器流水线），返回结构化结果</li>
 *   <li>POST /api/playground/stream —— SSE 流式输出（打字机效果），mock/llm 双模式可用</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/playground")
@RequiredArgsConstructor
@Tag(name = "Playground 即席体验", description = "单节点即席执行（阻塞）与 SSE 流式输出，mock/llm 双模式可用")
public class PlaygroundApiController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowEngine flowEngine;

    private final AiInvoker aiInvoker;

    private final InputAssembler inputAssembler;

    /** 即席执行（阻塞）：完整复用节点处理器能力（变量/契约/多模态/JSON提取） */
    @Operation(summary = "Playground 即席执行", description = "阻塞式单节点完整执行（复用节点处理器：变量/契约/多模态/JSON提取），返回结构化结果")
    @PostMapping
    public Map<String, Object> run(@ModelAttribute PlaygroundForm form) throws IOException {
        NodeDefinition node = toNode(form);
        FlowDefinition flow = new FlowDefinition();
        flow.setId("playground");
        flow.setName("Playground · " + node.getName());
        flow.setNodes(new ArrayList<>(List.of(node)));
        flow.setEdges(new ArrayList<>());
        FlowInput input = inputAssembler.assemble(form.getText(), form.getImageUrl(),
            form.getImage() != null ? List.of(form.getImage()) : null, form.getExcel(), null, null);
        FlowExecution execution = flowEngine.execute(flow, input);
        NodeExecution ne = execution.getNodes().values().iterator().next();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", execution.getRunId());
        result.put("status", execution.getStatus());
        result.put("output", ne.getOutput());
        result.put("parsedJson", ne.getParsedJson());
        result.put("error", ne.getError());
        result.put("model", ne.getModel());
        result.put("costMillis", ne.getCostMillis());
        result.put("totalTokens", ne.getTotalTokens());
        return result;
    }

    /** 流式执行：SSE格式逐块输出，终止发 [DONE]，异常发 error 事件 */
    @Operation(summary = "Playground 流式执行", description = "SSE 流式输出（打字机效果）：逐块 data 事件，终止发 [DONE]，异常发 error 事件")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@ModelAttribute PlaygroundForm form) throws IOException {
        AiInvoker.InvokeRequest request = toStreamRequest(form);
        StreamingResponseBody body = out -> {
            try {
                aiInvoker.stream(request, chunk -> writeSse(out, "data:{\"chunk\":\"" + escape(chunk) + "\"}"));
                writeSse(out, "data:[DONE]");
            } catch (Exception e) {
                log.error("[Playground] stream failed", e);
                writeSse(out, "data:{\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}");
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("X-Accel-Buffering", "no")
            .body(body);
    }

    // ---------- 表单与构建 ----------

    @Data
    public static class PlaygroundForm {
        /** 场景类型：text/image/json/excel/combined */
        private String scene = "text";
        /** 处理指令 */
        private String prompt;
        /** 系统提示词（可选） */
        private String systemPrompt;
        /** 输出类型：json|text */
        private String outputType = "json";
        /** JSON字段契约（JSON数组字符串，outputType=json 时生效） */
        private String fields;
        /** 文本输入 */
        private String text;
        /** 图片URL（可选） */
        private String imageUrl;
        /** 模型覆盖（可选） */
        private String model;
        /** 温度覆盖（可选） */
        private Double temperature;
        /** 上传图片（可选） */
        private MultipartFile image;
        /** 上传Excel（可选） */
        private MultipartFile excel;
    }

    private NodeDefinition toNode(PlaygroundForm form) {
        NodeType type;
        try {
            type = NodeType.valueOf(StringUtils.hasText(form.getScene()) ? form.getScene().toUpperCase() : "TEXT");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知场景类型: " + form.getScene());
        }
        if (!StringUtils.hasText(form.getPrompt())) {
            throw new IllegalArgumentException("处理指令不能为空");
        }
        NodeDefinition node = new NodeDefinition();
        node.setId("pg");
        node.setName(type.getLabel());
        node.setType(type);
        node.setPrompt(form.getPrompt());
        node.setSystemPrompt(form.getSystemPrompt());
        node.setModel(form.getModel());
        node.setTemperature(form.getTemperature());
        node.setImageUrl(form.getImageUrl());
        OutputSpec spec = new OutputSpec();
        spec.setType(StringUtils.hasText(form.getOutputType()) ? form.getOutputType() : "json");
        if (spec.isJson() && StringUtils.hasText(form.getFields())) {
            try {
                List<OutputSpec.Field> fields = MAPPER.readValue(form.getFields(), new TypeReference<List<OutputSpec.Field>>() {
                });
                spec.setFields(new ArrayList<>(fields));
            } catch (Exception e) {
                throw new IllegalArgumentException("字段契约格式不正确: " + e.getMessage());
            }
        }
        node.setOutputSpec(spec);
        return node;
    }

    /** 流式请求组装：指令 + 输入数据 + 输出契约 */
    private AiInvoker.InvokeRequest toStreamRequest(PlaygroundForm form) throws IOException {
        NodeDefinition node = toNode(form);
        NodeType type = node.getType();
        StringBuilder user = new StringBuilder();
        user.append("【处理指令】\n").append(node.getPrompt().trim());
        List<AiInvoker.MediaItem> medias = new ArrayList<>();
        if (StringUtils.hasText(form.getText())) {
            user.append("\n\n【输入数据】\n").append(truncate(form.getText().trim(), 6000));
        }
        if (form.getExcel() != null && !form.getExcel().isEmpty()) {
            user.append("\n\n【表格数据】\n").append(truncate(inputAssembler.parseExcel(form.getExcel()).toJsonText(), 12000));
        }
        if (type.supportsMedia()) {
            if (StringUtils.hasText(form.getImageUrl())) {
                medias.add(AiInvoker.MediaItem.ofUrl("playground-url", "image/png", form.getImageUrl().trim()));
            }
            if (form.getImage() != null && !form.getImage().isEmpty()) {
                try {
                    medias.add(AiInvoker.MediaItem.ofBytes(form.getImage().getOriginalFilename(),
                        form.getImage().getContentType(), form.getImage().getBytes()));
                } catch (IOException e) {
                    throw new IllegalArgumentException("图片读取失败: " + e.getMessage());
                }
            }
        }
        user.append("\n\n").append(PromptKit.buildOutputRequirement(node.getOutputSpec()));
        String system = StringUtils.hasText(form.getSystemPrompt())
            ? form.getSystemPrompt()
            : PromptKit.DEFAULT_SYSTEM_PROMPT + (type.supportsMedia()
                ? " 图像相关任务只依据图片真实内容作答，无法识别的字段返回null。" : "");
        return AiInvoker.InvokeRequest.builder()
            .bizKey("playground:" + form.getScene())
            .systemPrompt(system)
            .userPrompt(user.toString())
            .medias(medias)
            .model(form.getModel())
            .temperature(form.getTemperature())
            .build();
    }

    private void writeSse(OutputStream out, String payload) {
        try {
            out.write((payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // 客户端断开
        }
    }

    private String escape(String text) {
        return text == null ? "" : text
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", " ");
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…（已截断，共" + text.length() + "字符）";
    }
}
