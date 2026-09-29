package com.guonl.flow.web;

import com.guonl.flow.config.AiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端全局配置查询：AI provider 模式与默认模型（侧栏状态胶囊、页面占位符使用）。
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(name = "系统配置查询", description = "AI provider 模式与默认模型（侧栏状态胶囊、页面占位符使用）")
public class ConfigApiController {

    private final AiProperties aiProperties;

    @Operation(summary = "查询全局配置", description = "返回 {provider, textModel, visionModel}")
    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", aiProperties.getProvider());
        result.put("textModel", aiProperties.getTextModel());
        result.put("visionModel", aiProperties.getVisionModel());
        return result;
    }
}
