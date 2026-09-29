package com.guonl.flow.core.node;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.AiProperties;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 图片识别节点：图片输入（运行时上传或节点绑定URL），识别内容并按指定格式返回。
 */
@Component
public class ImageNodeHandler extends AbstractNodeHandler {

    public ImageNodeHandler(AiInvoker aiInvoker, AiProperties aiProperties) {
        super(aiInvoker, aiProperties);
    }

    @Override
    public NodeType supports() {
        return NodeType.IMAGE;
    }

    @Override
    protected String resolveSystemPrompt(NodeDefinition node) {
        if (node.getSystemPrompt() != null && !node.getSystemPrompt().isBlank()) {
            return node.getSystemPrompt();
        }
        return "你是专业的图像内容识别助手，仔细观察图片中的全部细节，"
            + "只依据图片真实内容作答，无法识别的字段返回null，严格按照要求输出。";
    }

    @Override
    protected List<AiInvoker.MediaItem> collectMedias(NodeDefinition node, FlowInput input) {
        List<AiInvoker.MediaItem> medias = super.collectMedias(node, input);
        if (medias.isEmpty() && !StringUtils.hasText(node.getImageUrl())) {
            throw new IllegalArgumentException("图片识别节点「" + node.getName() + "」缺少图片素材，请在运行时上传图片或为节点绑定图片URL");
        }
        return medias;
    }
}
