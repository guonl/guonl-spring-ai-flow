package com.guonl.flow.core.model;

import lombok.Data;

import java.util.List;

/**
 * 流程节点定义（一个可与大模型交互的业务场景）。
 * <p>prompt 中可使用变量协议引用上游数据：
 * {@code {{input}}} 流程输入文本、{@code {{input.rows}}} Excel解析结果、
 * {@code {{nodeId}}} / {@code {{nodeId.output}}} 上游节点原始输出、
 * {@code {{nodeId.json}}} 上游节点JSON、{@code {{nodeId.json.字段}}} 上游JSON字段值。</p>
 */
@Data
public class NodeDefinition {

    /** 节点唯一ID（前端生成，如 n_1712） */
    private String id;

    /** 节点名称 */
    private String name;

    /** 节点类型 */
    private NodeType type = NodeType.TEXT;

    /** 指令提示词（支持变量协议） */
    private String prompt;

    /** 系统提示词（可选，角色设定） */
    private String systemPrompt;

    /** 输出契约 */
    private OutputSpec outputSpec = new OutputSpec();

    /** 画布坐标X（UI布局） */
    private double x;

    /** 画布坐标Y（UI布局） */
    private double y;

    /** 节点级模型覆盖（可选，如视觉模型） */
    private String model;

    /** 温度覆盖（可选） */
    private Double temperature;

    /** 节点级图片URL（可选，图片类节点可直接绑定素材地址） */
    private String imageUrl;

    /** 条件路由分支定义（仅 ROUTER 节点使用；模型输出 {"route":"分支名"}） */
    private List<Route> routes;

    /** 会话记忆窗口轮数（携带最近N轮历史；null/0=不启用记忆） */
    private Integer memoryTurns;

    /** 绑定的知识库ID（仅 KNOWLEDGE 节点使用；向量检索该库命中片段注入提示词） */
    private Long knowledgeBaseId;

    /** 知识检索返回的命中片段数上限（仅 KNOWLEDGE 节点使用；空=系统默认） */
    private Integer topK;

    /** 引用的子流程ID（仅 SUBFLOW 节点使用；该流程作为「宏」同步执行） */
    private String subflowId;

    /** 聚合策略（仅 AGGREGATE 节点使用）：concat=按序拼接 / jsonMerge=JSON字段合并 / template=模板渲染；空默认 concat */
    private String aggregateStrategy;

    /** 聚合节点分隔符语义说明：concat 策略下 prompt 字段复用为上游输出间的分隔符（空则默认双换行） */
    public static final String AGGREGATE_CONCAT = "concat";

    /** 聚合节点 JSON 字段合并策略：每个上游输出一个字段（字段名取节点名，重名回退节点ID），值取其 JSON（无则取文本） */
    public static final String AGGREGATE_JSON_MERGE = "jsonMerge";

    /** 聚合节点模板渲染策略：prompt 复用为合并模板，支持 {@code {{节点ID}}} 变量协议引用各上游输出 */
    public static final String AGGREGATE_TEMPLATE = "template";

    /** 路由分支：标签即路由值，desc 提示模型选择依据 */
    @Data
    public static class Route {
        /** 分支名（模型输出 route 的取值，也是边条件的匹配值） */
        private String label;
        /** 分支说明（帮助模型判断走哪个分支） */
        private String desc;
    }
}
