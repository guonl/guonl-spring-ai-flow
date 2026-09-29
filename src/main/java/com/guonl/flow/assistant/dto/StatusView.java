package com.guonl.flow.assistant.dto;

/**
 * 助手状态视图（悬浮窗角标/独立页横幅用）。
 */
public record StatusView(boolean enabled, String provider, String model, boolean toolsEnabled) {
}
