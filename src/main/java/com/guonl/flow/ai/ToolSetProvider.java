package com.guonl.flow.ai;

/**
 * 工具集提供者SPI：按场景向大模型挂载不同的 @Tool 注解工具对象。
 * <p>调用方在 {@link AiInvoker.InvokeRequest#setToolSet(String)} 指定标识，
 * {@link SpringAiInvoker} 据此选择对应提供者的工具对象（未匹配到则回退流程引擎内置工具）。
 * 实现类为普通 @Component（如 assistant 包的助手工具集），与流程引擎工具互不干扰。</p>
 */
public interface ToolSetProvider {

    /** 工具集唯一标识（与 InvokeRequest.toolSet 对应，如 "assistant"） */
    String toolSet();

    /** 携带 @Tool 注解方法的工具对象（Spring AI 自动解析方法签名生成工具定义） */
    Object tools();
}
