package com.guonl.flow.core.model;

/**
 * 节点类型：对应产品的五种输入场景。
 * <p>每种类型决定提示词组装方式与多模态素材的处理策略，
 * 由对应的 {@code NodeHandler} 实现具体执行逻辑。</p>
 */
public enum NodeType {

    /** 文本理解：纯文字输入，理解需求并按格式返回 */
    TEXT("文本理解", "#3b82f6", "text"),

    /** 图片识别：图片输入，识别内容并按格式返回 */
    IMAGE("图片识别", "#10b981", "image"),

    /** JSON处理：JSON数据输入，数据处理并按格式返回 */
    JSON("JSON处理", "#f59e0b", "json"),

    /** Excel处理：表格数据输入，数据处理并按格式返回 */
    EXCEL("Excel处理", "#8b5cf6", "table"),

    /** 组合处理：文本+图片+JSON等多源组合输入 */
    COMBINED("组合处理", "#ec4899", "layers"),

    /** 条件路由：根据上游内容从预定义分支中选路，只有命中分支的下游被调度 */
    ROUTER("条件路由", "#ef4444", "router"),

    /** 工具调用：挂载内置工具集（时间/计算/网页抓取/JSON提取），模型自主决定调用 */
    TOOL("工具调用", "#0ea5e9", "wrench"),

    /** 知识检索：向量检索知识库命中片段注入提示词，模型基于参考资料作答（RAG） */
    KNOWLEDGE("知识检索", "#14b8a6", "book"),

    /** 图片生成（文生图）：提示词经图片生成模型产出图片，输出图片URL（data URL或http链接）供下游引用与展示 */
    IMAGE_GEN("图片生成", "#f97316", "image-plus"),

    /** 内容审核：审核文本命中违规类别（ModerationModel），输出 {flagged, categories} 供条件路由分流降级 */
    MODERATION("内容审核", "#f43f5e", "shield"),

    /** 输出评估（LLM-as-judge）：按评估维度对上游输出打分，输出 {score, passed, reason, dimensions}，可接条件路由走重试或人工兜底 */
    EVALUATE("输出评估", "#84cc16", "scale"),

    /** 子流程：引用另一个流程定义作为「宏」同步执行，入参经 prompt 模板组装，出参取子流程末端节点输出，支持流程复用 */
    SUBFLOW("子流程", "#6366f1", "git-branch"),

    /** 聚合：不经模型的多上游输出汇合，支持拼接 / JSON字段合并 / 模板渲染三种策略，解决汇流点只能引用单个上游产出的痛点 */
    AGGREGATE("聚合", "#a855f7", "git-merge");

    /** 中文名称 */
    private final String label;

    /** 主题色（UI节点卡片使用） */
    private final String color;

    /** 图标标识（UI使用） */
    private final String icon;

    NodeType(String label, String color, String icon) {
        this.label = label;
        this.color = color;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    public String getIcon() {
        return icon;
    }

    /** 是否为多模态类型（可携带图片素材） */
    public boolean supportsMedia() {
        return this == IMAGE || this == COMBINED;
    }

    /**
     * 是否允许空处理指令（handler 内置默认行为，自动消费流程输入与上游输出）。
     * AGGREGATE 亦豁免：不经模型，prompt 语义随聚合策略变化（拼接策略下是分隔符、模板策略下是模板，
     * 校验由 FlowEngine 专项规则负责），故不参与通用「缺少处理指令」检查。
     */
    public boolean allowsBlankPrompt() {
        return this == MODERATION || this == EVALUATE || this == AGGREGATE;
    }
}
