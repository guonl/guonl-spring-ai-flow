package com.guonl.flow.web;

import com.guonl.flow.core.engine.FlowEngine;
import com.guonl.flow.core.engine.FlowExecution;
import com.guonl.flow.core.engine.FlowRunService;
import com.guonl.flow.core.model.FlowDefinition;
import com.guonl.flow.core.model.PageResult;
import com.guonl.flow.core.store.FlowStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程编排 REST API：定义CRUD、静态校验、模板复制、提交运行。
 */
@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
@Tag(name = "流程定义管理", description = "流程定义 CRUD、静态校验、模板复制、提交运行")
public class FlowApiController {

    private final FlowStore flowStore;

    private final FlowEngine flowEngine;

    private final FlowRunService flowRunService;

    private final InputAssembler inputAssembler;

    @Operation(summary = "流程定义列表", description = "按最近更新时间倒序返回全部流程定义")
    @GetMapping
    public List<FlowDefinition> list() {
        return flowStore.list();
    }

    @Operation(summary = "流程定义分页检索", description = "按关键词（名称/描述模糊匹配）分页检索流程定义，按更新时间倒序；keyword 为空时返回全量分页")
    @GetMapping("/page")
    public PageResult<FlowDefinition> page(@RequestParam(value = "keyword", required = false) String keyword,
                                           @RequestParam(value = "page", defaultValue = "1") int page,
                                           @RequestParam(value = "size", defaultValue = "12") int size) {
        return flowStore.search(keyword, page, size);
    }

    @Operation(summary = "查询流程定义", description = "按 id 查询单个流程定义，不存在时返回 404 语义错误")
    @GetMapping("/{id}")
    public FlowDefinition get(@PathVariable String id) {
        return flowStore.require(id);
    }

    /** 新建或更新（id为空时自动生成） */
    @Operation(summary = "保存流程定义", description = "新建或更新流程定义，id 为空时自动生成（f_ 前缀随机串）")
    @PostMapping
    public FlowDefinition save(@RequestBody FlowDefinition flow) {
        if (flow.getNodes() == null) {
            flow.setNodes(new java.util.ArrayList<>());
        }
        if (flow.getEdges() == null) {
            flow.setEdges(new java.util.ArrayList<>());
        }
        return flowStore.save(flow);
    }

    @Operation(summary = "删除流程定义", description = "按 id 删除流程定义，返回 {deleted: 是否删除成功}")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean removed = flowStore.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", removed);
        return result;
    }

    /** 静态校验：返回 {valid, errors} */
    @Operation(summary = "校验已保存流程", description = "对已保存的流程定义执行静态校验，返回 {valid, errors}")
    @PostMapping("/{id}/validate")
    public Map<String, Object> validate(@PathVariable String id) {
        return validateResult(flowStore.require(id));
    }

    /** 校验未保存的定义（编辑器实时校验） */
    @Operation(summary = "校验草稿流程", description = "对未保存的流程定义（编辑器草稿）执行静态校验，返回 {valid, errors}")
    @PostMapping("/validate")
    public Map<String, Object> validateDraft(@RequestBody FlowDefinition flow) {
        return validateResult(flow);
    }

    /** 从模板/已有流程复制一份新流程 */
    @Operation(summary = "复制流程定义", description = "从模板/已有流程复制一份新流程，名称追加（副本）后缀")
    @PostMapping("/{id}/duplicate")
    public FlowDefinition duplicate(@PathVariable String id) {
        FlowDefinition source = flowStore.require(id);
        FlowDefinition copy = new FlowDefinition();
        copy.setName(source.getName() + "（副本）");
        copy.setDescription(source.getDescription());
        copy.setNodes(source.getNodes());
        copy.setEdges(source.getEdges());
        copy.setCreatedAt(LocalDateTime.now());
        return flowStore.save(copy);
    }

    /**
     * 提交运行：multipart 输入（文本 + 图片多选 + Excel），立即返回运行快照（RUNNING）。
     * 前端凭返回的 runId 轮询 /api/runs/{runId}。
     */
    @Operation(summary = "提交流程运行", description = "multipart 输入（文本+图片多选+Excel+音频），立即返回 RUNNING 快照，凭 runId 轮询 /api/runs/{runId}；音频经转录模型转为文字后并入text")
    @PostMapping("/{id}/run")
    public FlowExecution run(@PathVariable String id,
                             @RequestParam(value = "text", required = false) String text,
                             @RequestParam(value = "imageUrl", required = false) String imageUrl,
                             @RequestParam(value = "images", required = false) List<MultipartFile> images,
                             @RequestParam(value = "excel", required = false) MultipartFile excel,
                             @RequestParam(value = "audio", required = false) MultipartFile audio,
                             @RequestParam(value = "conversationId", required = false) String conversationId) throws IOException {
        FlowDefinition flow = flowStore.require(id);
        return flowRunService.start(flow, inputAssembler.assemble(text, imageUrl, images, excel, audio, conversationId));
    }

    private Map<String, Object> validateResult(FlowDefinition flow) {
        List<String> errors = flowEngine.validate(flow);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }
}
