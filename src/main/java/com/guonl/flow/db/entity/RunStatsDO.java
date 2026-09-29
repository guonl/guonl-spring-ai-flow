package com.guonl.flow.db.entity;

import lombok.Data;

/**
 * 运行统计（flow_run 聚合查询结果）。
 */
@Data
public class RunStatsDO {

    /** 总次数 */
    private Integer total;

    /** 进行中 */
    private Integer running;

    /** 成功 */
    private Integer success;

    /** 失败 */
    private Integer failed;

    /** 平均耗时（毫秒） */
    private Long avgCostMillis;

    /** 累计token */
    private Long totalTokens;

    /** 模型调用总次数（成功执行的LLM节点数） */
    private Long llmCalls;
}
