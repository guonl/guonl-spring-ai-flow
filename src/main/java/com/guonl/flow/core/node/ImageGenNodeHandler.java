package com.guonl.flow.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guonl.flow.core.engine.NodeOutput;
import com.guonl.flow.core.engine.VarResolver;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 图片生成节点（文生图）：变量解析后的提示词送图片生成模型（Spring AI {@link ImageModel} SPI），
 * 产出图片写入节点输出text（data URL或http链接），同时写入 {@code json.url} 供下游
 * {@code {{nodeId.json.url}}} 引用；运行页对该类型节点渲染图片预览。
 * <p>不走会话大模型链路（无输出契约/自纠），实现 {@link NodeHandler} 而非继承 AbstractNodeHandler。</p>
 */
@Component
public class ImageGenNodeHandler implements NodeHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<ImageModel> imageModelProvider;

    public ImageGenNodeHandler(ObjectProvider<ImageModel> imageModelProvider) {
        this.imageModelProvider = imageModelProvider;
    }

    @Override
    public NodeType supports() {
        return NodeType.IMAGE_GEN;
    }

    @Override
    public NodeOutput execute(NodeDefinition node, NodeContext ctx) {
        if (node.getPrompt() == null || node.getPrompt().isBlank()) {
            throw new IllegalArgumentException("节点「" + node.getName() + "」未配置绘图提示词");
        }
        // 1. 变量表：流程输入 + 全部祖先产出；解析后的提示词即绘图指令
        Map<String, Object> vars = VarResolver.baseVars(ctx.getFlowInput());
        for (String ancestorId : ctx.getAncestorIds()) {
            VarResolver.registerNode(vars, ancestorId, ctx.getUpstreamOutputs().get(ancestorId));
        }
        String imagePrompt = VarResolver.resolve(node.getPrompt(), vars).resolved().trim();
        // 2. 调用图片生成模型
        ImageModel model = imageModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("图片生成模型未装配（ImageModel）。"
                + "mock模式自动可用；llm模式需启用 spring.ai.model.image=openai 并配置图像生成端点");
        }
        ImageResponse response = model.call(new ImagePrompt(imagePrompt));
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("图片生成模型未返回结果");
        }
        Image image = response.getResult().getOutput();
        String url = resolveImageUrl(image);
        if (url == null) {
            throw new IllegalStateException("图片生成模型未返回图片URL或base64数据");
        }
        // 3. 输出：text=图片URL（下游 {{nodeId.text}} 引用），json.url 同值（下游 {{nodeId.json.url}} 引用）
        ObjectNode json = MAPPER.createObjectNode();
        json.put("url", url);
        return new NodeOutput(url, json);
    }

    /** 优先取url；仅返回base64时拼装data URL */
    private String resolveImageUrl(Image image) {
        if (StringUtils.hasText(image.getUrl())) {
            return image.getUrl().trim();
        }
        if (StringUtils.hasText(image.getB64Json())) {
            return "data:image/png;base64," + image.getB64Json();
        }
        return null;
    }
}
