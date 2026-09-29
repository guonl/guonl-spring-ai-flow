package com.guonl.flow.core.model;

import java.util.List;

/**
 * 通用分页结果（流程库检索等列表接口使用）。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {
}
