package com.guonl.flow.core.engine;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.excel.ExcelParser;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次流程运行的业务输入。
 * 文本/JSON走 text，图片走 images（服务端已持有字节），Excel走 excel（已解析）。
 */
@Data
@Builder
public class FlowInput {

    /** 文本或JSON字符串输入 */
    private String text;

    /** 图片素材（上传或URL） */
    @Builder.Default
    private List<AiInvoker.MediaItem> images = new ArrayList<>();

    /** Excel解析结果（可选） */
    private ExcelParser.ExcelData excel;

    /** 会话ID（多轮记忆隔离键，可选；同ID多次运行共享历史） */
    private String conversationId;

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    public boolean hasExcel() {
        return excel != null && excel.rows() != null && !excel.rows().isEmpty();
    }
}
