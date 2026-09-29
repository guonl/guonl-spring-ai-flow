package com.guonl.flow.knowledge;

/**
 * 知识库检索单条结果（C1调试接口与C2检索增强节点共用）。
 */
public record RetrievedChunk(String text, double score, String docName) {
}
